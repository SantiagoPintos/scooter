package com.velocimetro.scooterlab

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.zip.CRC32

/** Pure cryptographic operations used by the security-chip login protocol. */
object ScooterSecurity {
    private val hkdfSalt = "smartcfg-login-salt".encodeToByteArray()
    private val hkdfInfo = "smartcfg-login-info".encodeToByteArray()
    private val confirmationNonce = ByteArray(12) { (it + 16).toByte() }

    fun generateEphemeralKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    /** Xiaomi Home sends X || Y, each exactly 32 bytes, without the SEC1 0x04 prefix. */
    fun encodePublicKey(publicKey: PublicKey): ByteArray {
        val point = (publicKey as? ECPublicKey)?.w
            ?: throw IllegalArgumentException("Expected an EC public key")
        return point.affineX.unsignedFixed(32) + point.affineY.unsignedFixed(32)
    }

    fun decodePeerPublicKey(peerKey: ByteArray, localPublicKey: PublicKey): PublicKey {
        require(peerKey.size == 64) { "The peer public key must contain 64 bytes" }
        val local = localPublicKey as? ECPublicKey
            ?: throw IllegalArgumentException("Expected an EC public key")
        val x = BigInteger(1, peerKey.copyOfRange(0, 32))
        val y = BigInteger(1, peerKey.copyOfRange(32, 64))
        return KeyFactory.getInstance("EC").generatePublic(
            ECPublicKeySpec(ECPoint(x, y), local.params)
        )
    }

    fun deriveSessionKey(localKeyPair: KeyPair, peerPublicKey: PublicKey, effectiveLtmk: ByteArray): ByteArray {
        require(effectiveLtmk.size == 32) { "The effective LTMK must contain 32 bytes" }
        val sharedSecret = KeyAgreement.getInstance("ECDH").run {
            init(localKeyPair.private)
            doPhase(peerPublicKey, true)
            generateSecret()
        }
        require(sharedSecret.size >= 32) { "Unexpected ECDH secret length" }
        val ikm = sharedSecret.copyOfRange(0, 32) + effectiveLtmk.copyOf()
        return hkdfSha256(ikm, hkdfSalt, hkdfInfo, 64)
    }

    /**
     * Encrypts CRC32(peerPublicKey) with AES-CCM-128, a fixed 12-byte nonce and a 4-byte tag.
     * The returned value is ciphertext followed by tag (8 bytes total).
     */
    fun buildConfirmation(sessionKey: ByteArray, peerKey: ByteArray): ByteArray {
        require(sessionKey.size == 64) { "The session key must contain 64 bytes" }
        require(peerKey.size == 64) { "The peer public key must contain 64 bytes" }
        return AesCcm.encrypt(
            key = sessionKey.copyOfRange(16, 32),
            nonce = confirmationNonce,
            plaintext = crc32LittleEndian(peerKey),
            tagLength = 4,
        )
    }

    fun crc32LittleEndian(input: ByteArray): ByteArray {
        val value = CRC32().apply { update(input) }.value
        return ByteArray(4) { index -> (value shr (index * 8)).toByte() }
    }

    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..(255 * 32)) { "Invalid HKDF output length" }
        val prk = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(salt, "HmacSHA256"))
            doFinal(ikm)
        }
        val output = ByteArray(length)
        var previous = ByteArray(0)
        var written = 0
        var counter = 1
        while (written < length) {
            previous = Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(prk, "HmacSHA256"))
                update(previous)
                update(info)
                update(counter.toByte())
                doFinal()
            }
            val copied = minOf(previous.size, length - written)
            previous.copyInto(output, written, 0, copied)
            written += copied
            counter += 1
        }
        return output
    }

    private fun BigInteger.unsignedFixed(size: Int): ByteArray {
        val source = toByteArray()
        val withoutSign = if (source.size > 1 && source[0] == 0.toByte()) source.copyOfRange(1, source.size) else source
        require(withoutSign.size <= size) { "Coordinate does not fit P-256" }
        return ByteArray(size).also { destination ->
            withoutSign.copyInto(destination, size - withoutSign.size)
        }
    }
}

/** Minimal AES-CCM implementation for the protocol's no-AAD, 4-byte-tag use case. */
internal object AesCcm {
    fun encrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, tagLength: Int): ByteArray {
        validate(key, nonce, plaintext.size, tagLength)
        val mac = mac(key, nonce, plaintext, tagLength)
        val s0 = counterBlock(key, nonce, 0)
        val ciphertext = xor(plaintext, counterStream(key, nonce, plaintext.size))
        val tag = xor(mac.copyOf(tagLength), s0.copyOf(tagLength))
        return ciphertext + tag
    }

    fun decrypt(key: ByteArray, nonce: ByteArray, ciphertextAndTag: ByteArray, tagLength: Int): ByteArray {
        require(ciphertextAndTag.size >= tagLength) { "Ciphertext is shorter than its tag" }
        val ciphertext = ciphertextAndTag.copyOfRange(0, ciphertextAndTag.size - tagLength)
        validate(key, nonce, ciphertext.size, tagLength)
        val plaintext = xor(ciphertext, counterStream(key, nonce, ciphertext.size))
        val expectedTag = xor(mac(key, nonce, plaintext, tagLength).copyOf(tagLength), counterBlock(key, nonce, 0).copyOf(tagLength))
        val receivedTag = ciphertextAndTag.copyOfRange(ciphertext.size, ciphertextAndTag.size)
        require(MessageDigest.isEqual(expectedTag, receivedTag)) { "CCM authentication failed" }
        return plaintext
    }

    private fun mac(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, tagLength: Int): ByteArray {
        val lengthBytes = encodeLength(plaintext.size, 15 - nonce.size)
        val flags = (((tagLength - 2) / 2) shl 3) or ((15 - nonce.size) - 1)
        var state = aesBlock(key, byteArrayOf(flags.toByte()) + nonce + lengthBytes)
        plaintext.asBlocksOf16().forEach { block -> state = aesBlock(key, xor(state, block)) }
        return state
    }

    private fun counterBlock(key: ByteArray, nonce: ByteArray, counter: Int): ByteArray {
        val lengthBytes = encodeLength(counter, 15 - nonce.size)
        val flags = ((15 - nonce.size) - 1).toByte()
        return aesBlock(key, byteArrayOf(flags) + nonce + lengthBytes)
    }

    /** CCM's message stream uses a distinct AES counter block for every 16-byte chunk. */
    private fun counterStream(key: ByteArray, nonce: ByteArray, length: Int): ByteArray {
        val stream = ByteArray(length)
        var offset = 0
        var counter = 1
        while (offset < length) {
            val block = counterBlock(key, nonce, counter)
            val copied = minOf(block.size, length - offset)
            block.copyInto(stream, offset, endIndex = copied)
            offset += copied
            counter += 1
        }
        return stream
    }

    private fun aesBlock(key: ByteArray, block: ByteArray): ByteArray = Cipher.getInstance("AES/ECB/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        doFinal(block)
    }

    private fun validate(key: ByteArray, nonce: ByteArray, messageLength: Int, tagLength: Int) {
        require(key.size == 16) { "AES-CCM-128 requires a 16-byte key" }
        require(nonce.size in 7..13) { "CCM nonce must contain 7 to 13 bytes" }
        require(tagLength in 4..16 && tagLength % 2 == 0) { "Invalid CCM tag length" }
        val lengthBytes = 15 - nonce.size
        require(messageLength < (1 shl (lengthBytes * 8))) { "Message is too long for the nonce" }
    }

    private fun encodeLength(value: Int, width: Int): ByteArray = ByteArray(width) { index ->
        (value ushr ((width - 1 - index) * 8)).toByte()
    }

    private fun ByteArray.asBlocksOf16(): List<ByteArray> {
        if (isEmpty()) return emptyList()
        return buildList {
            var offset = 0
            while (offset < this@asBlocksOf16.size) {
                val block = ByteArray(16)
                this@asBlocksOf16.copyInto(block, 0, offset, minOf(this@asBlocksOf16.size, offset + 16))
                add(block)
                offset += 16
            }
        }
    }

    private fun xor(left: ByteArray, right: ByteArray): ByteArray {
        require(left.size == right.size) { "XOR operands must have the same length" }
        return ByteArray(left.size) { index -> (left[index].toInt() xor right[index].toInt()).toByte() }
    }
}
