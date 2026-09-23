package com.velocimetro.scooterlab

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ScooterSecurityTest {
    @Test
    fun hkdfMatchesRfc5869CaseOne() {
        val actual = ScooterSecurity.hkdfSha256(
            ikm = ByteArray(22) { 0x0b },
            salt = hex("000102030405060708090a0b0c"),
            info = hex("f0f1f2f3f4f5f6f7f8f9"),
            length = 42,
        )
        assertArrayEquals(
            hex("3cb25f25faacd57a90434f64d0362f2a" + "2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"),
            actual,
        )
    }

    @Test
    fun crc32UsesLittleEndianSerialization() {
        assertArrayEquals(hex("2639f4cb"), ScooterSecurity.crc32LittleEndian("123456789".encodeToByteArray()))
    }

    @Test
    fun aesCcmMatchesSyntheticReference() {
        val key = ByteArray(16) { it.toByte() }
        val nonce = ByteArray(12) { (it + 16).toByte() }
        val plaintext = hex("2639f4cb")
        val encrypted = AesCcm.encrypt(key, nonce, plaintext, tagLength = 4)
        assertArrayEquals(hex("058c4d6be1293916"), encrypted)
        assertArrayEquals(plaintext, AesCcm.decrypt(key, nonce, encrypted, tagLength = 4))
    }

    @Test
    fun aesCcmEncryptsEveryCounterBlockForLongTelemetryPayloads() {
        val key = ByteArray(16) { (it + 9).toByte() }
        val nonce = ByteArray(12) { (it + 41).toByte() }
        val plaintext = ByteArray(240) { (it * 7).toByte() }

        val encrypted = AesCcm.encrypt(key, nonce, plaintext, tagLength = 4)

        assertNotEquals(
            plaintext.copyOfRange(16, 32).toList(),
            encrypted.copyOfRange(16, 32).toList(),
        )
        assertArrayEquals(plaintext, AesCcm.decrypt(key, nonce, encrypted, tagLength = 4))
    }

    @Test
    fun p256PeersDeriveTheSameSessionKey() {
        val first = ScooterSecurity.generateEphemeralKeyPair()
        val second = ScooterSecurity.generateEphemeralKeyPair()
        val ltmk = ByteArray(32) { (it * 3).toByte() }
        val firstSession = ScooterSecurity.deriveSessionKey(first, second.public, ltmk)
        val secondSession = ScooterSecurity.deriveSessionKey(second, first.public, ltmk)
        assertEquals(64, firstSession.size)
        assertArrayEquals(firstSession, secondSession)
    }

    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
