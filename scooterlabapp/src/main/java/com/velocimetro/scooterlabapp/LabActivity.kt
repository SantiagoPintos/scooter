package com.velocimetro.scooterlabapp

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.velocimetro.scooterlab.MiotBleApplicationCipher
import com.velocimetro.scooterlab.MiotBleSpecV2Codec
import com.velocimetro.scooterlab.MiotBleSpecRequestCounter
import com.velocimetro.scooterlab.MiotScooterApplicationInitializer
import com.velocimetro.scooterlab.MiotScooterBatteryReader
import com.velocimetro.scooterlab.MiotScooterBatteryReadState
import com.velocimetro.scooterlab.MiotScooterPowerModeReader
import com.velocimetro.scooterlab.MiotScooterPowerModeReadState
import com.velocimetro.scooterlab.MiotScooterInboundResponseConsumer
import com.velocimetro.scooterlab.MiotScooterInitializationState
import com.velocimetro.scooterlab.ScooterApplicationChannel
import com.velocimetro.scooterlab.ScooterApplicationChannelState
import com.velocimetro.scooterlab.ScooterChannelPacket
import com.velocimetro.scooterlab.MiotScooterCommandComposer
import com.velocimetro.scooterlab.MiotScooterCommandState
import java.io.File
import java.util.TimeZone

/**
 * Debug-only launcher for a Bluetooth authentication check and explicit laboratory lock tests.
 * A physical action is possible only after the scooter accepts a fresh session, the response
 * channel is ready, and the user confirms a specific on-screen action. The target is chosen
 * locally and the laboratory credential is provisioned once by the lab runner.
 */
class LabActivity : Activity() {
    private lateinit var statusLabel: TextView
    private lateinit var statusDetail: TextView
    private lateinit var bluetoothValue: TextView
    private lateinit var targetValue: TextView
    private lateinit var selectedTargetDetail: TextView
    private lateinit var credentialValue: TextView
    private lateinit var startButton: Button
    private lateinit var targetActionButton: Button
    private lateinit var targetHint: TextView
    private lateinit var candidateList: LinearLayout
    private lateinit var controlsCard: LinearLayout
    private lateinit var controlDetail: TextView
    private lateinit var lockToggleButton: Button
    private lateinit var dashboardContent: LinearLayout
    private lateinit var settingsContent: LinearLayout
    private lateinit var dashboardTabButton: Button
    private lateinit var settingsTabButton: Button
    private lateinit var scooterNameValue: TextView
    private lateinit var batteryValue: TextView
    private lateinit var setupButton: Button
    private var session: ScooterSessionConnection? = null
    private var commandComposer: MiotScooterCommandComposer? = null
    private var batteryReader: MiotScooterBatteryReader? = null
    private var powerModeReader: MiotScooterPowerModeReader? = null
    private var applicationInitializer: MiotScooterApplicationInitializer? = null
    private var inboundResponseConsumer: MiotScooterInboundResponseConsumer? = null
    private var applicationChannel: ScooterApplicationChannel? = null
    private val pendingApplicationFrames = ArrayDeque<ByteArray>()
    private var testStarted = false
    private var automaticConnectionAttempted = false
    private var pendingLockState: Boolean? = null
    private var lastKnownLockState: Boolean? = null
    private var lastKnownBatteryPercentage: Int? = null
    private var lastKnownPowerMode: Int? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanning = false
    private val scanHandler = Handler(Looper.getMainLooper())
    private val scanCandidates = linkedMapOf<String, ScanResult>()
    private val stopScanRunnable = Runnable { stopScan(showResult = true) }
    private val commandTimeoutHandler = Handler(Looper.getMainLooper())
    private val applicationInitializationHandler = Handler(Looper.getMainLooper())
    private val batteryReadHandler = Handler(Looper.getMainLooper())
    private val powerModeReadHandler = Handler(Looper.getMainLooper())
    private val automaticConnectionHandler = Handler(Looper.getMainLooper())
    private val batteryReadTimeoutRunnable = Runnable {
        val reader = batteryReader ?: return@Runnable
        if (reader.state == MiotScooterBatteryReadState.WAITING_FLOW_ACK ||
            reader.state == MiotScooterBatteryReadState.WAITING_RESPONSE
        ) {
            reader.cancel()
            Log.i(logTag, "Battery read did not return a matching MiOT response; leaving dashboard value unavailable.")
        }
    }
    private val powerModeReadTimeoutRunnable = Runnable {
        val reader = powerModeReader ?: return@Runnable
        if (reader.state == MiotScooterPowerModeReadState.WAITING_FLOW_ACK ||
            reader.state == MiotScooterPowerModeReadState.WAITING_RESPONSE
        ) {
            reader.cancel()
            Log.i(logTag, "Power-mode read did not return a matching MiOT response; leaving dashboard mode unavailable.")
        }
    }
    private val commandTimeoutRunnable = Runnable {
        val composer = commandComposer ?: return@Runnable
        if (composer.state == MiotScooterCommandState.WAITING_FLOW_ACK ||
            composer.state == MiotScooterCommandState.WAITING_DATA_ACK ||
            composer.state == MiotScooterCommandState.WAITING_RESPONSE
        ) {
            composer.abort()
            pendingLockState = null
            controlDetail.text = "El scooter no confirmó la orden dentro del tiempo esperado."
            setControlsEnabled(applicationChannel?.state == ScooterApplicationChannelState.READY)
            showFailure("The scooter did not confirm the setting. Check its current state before retrying.")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = recordCandidate(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach(::recordCandidate)
        }

        override fun onScanFailed(errorCode: Int) {
            runOnUiThread {
                scanning = false
                targetActionButton.isEnabled = true
                targetActionButton.text = "Buscar scooter"
                showFailure("No se pudo iniciar la búsqueda Bluetooth (código $errorCode).")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createContent())
        refreshReadiness()
        if (intent.getBooleanExtra(startExtra, false)) {
            startAuthentication()
        } else {
            scheduleAutomaticConnection()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!testStarted) {
            refreshReadiness()
            scheduleAutomaticConnection()
        }
    }

    override fun onDestroy() {
        stopScan(showResult = false)
        commandTimeoutHandler.removeCallbacks(commandTimeoutRunnable)
        applicationInitializationHandler.removeCallbacksAndMessages(null)
        batteryReadHandler.removeCallbacksAndMessages(null)
        powerModeReadHandler.removeCallbacksAndMessages(null)
        automaticConnectionHandler.removeCallbacksAndMessages(null)
        commandComposer?.clear()
        batteryReader?.cancel()
        powerModeReader?.cancel()
        applicationInitializer = null
        batteryReader = null
        powerModeReader = null
        inboundResponseConsumer = null
        applicationChannel?.clear()
        clearPendingApplicationFrames()
        session?.close()
        session = null
        super.onDestroy()
    }

    private fun createContent(): View = ScrollView(this).apply {
        setBackgroundColor(backgroundColor)
        isFillViewport = true
        addView(LinearLayout(this@LabActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(28))

            addView(heroCard())
            addView(spacer(18))
            dashboardContent = LinearLayout(this@LabActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(scooterOverviewCard())
                addView(spacer(14))
                addView(statusCard())
                controlsCard = controlCard()
                addView(controlsCard)
            }
            addView(dashboardContent)
            settingsContent = LinearLayout(this@LabActivity).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                addView(readinessCard())
                addView(spacer(16))
                startButton = Button(this@LabActivity).apply {
                    text = "Reconnect scooter"
                    textSize = 16f
                    isAllCaps = false
                    setTextColor(Color.WHITE)
                    typeface = Typeface.DEFAULT_BOLD
                    setBackgroundDrawable(roundBackground(primaryActionColor, 16))
                    setOnClickListener { startAuthentication() }
                }
                addView(startButton, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(52),
                ))
            }
            addView(settingsContent)
            addView(spacer(18))
            addView(bottomNavigation())
        })
    }

    private fun heroCard(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(20), dp(22), dp(20))
        setBackgroundDrawable(roundBackground(heroCardColor, 24))
        elevation = dp(6).toFloat()
        addView(TextView(this@LabActivity).apply {
            text = "YOUR SCOOTER"
            setTextColor(accentColor)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.12f
        })
        addView(TextView(this@LabActivity).apply {
            text = "Xiaomi Scooter 6 Max   •   Bluetooth"
            setTextColor(primaryTextColor)
            textSize = 25f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(8), 0, 0)
        })
        addView(TextView(this@LabActivity).apply {
            text = "Direct control from this phone"
            setTextColor(secondaryTextColor)
            textSize = 15f
            setPadding(0, dp(5), 0, 0)
        })
    }

    private fun scooterOverviewCard(): View = card().apply {
        addView(TextView(this@LabActivity).apply {
            text = "DASHBOARD"
            setTextColor(mutedTextColor)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.1f
        })
        scooterNameValue = TextView(this@LabActivity).apply {
            text = "Scooter not selected"
            setTextColor(primaryTextColor)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(10), 0, 0)
        }
        addView(scooterNameValue)
        batteryValue = TextView(this@LabActivity).apply {
            text = "Battery  —"
            setTextColor(secondaryTextColor)
            textSize = 17f
            setPadding(0, dp(6), 0, 0)
        }
        addView(batteryValue)
        setupButton = Button(this@LabActivity).apply {
            text = "Choose scooter in Settings"
            isAllCaps = false
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(primaryTextColor)
            setBackgroundDrawable(roundBackground(secondaryActionColor, 14))
            setOnClickListener { showSettings() }
        }
        addView(setupButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(48),
        ).apply { topMargin = dp(16) })
    }

    private fun bottomNavigation(): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER
        setPadding(dp(6), dp(6), dp(6), dp(6))
        setBackgroundDrawable(roundBackground(navigationColor, 22))
        dashboardTabButton = navigationButton("Dashboard") { showDashboard() }
        settingsTabButton = navigationButton("Settings") { showSettings() }
        addView(dashboardTabButton, LinearLayout.LayoutParams(0, dp(46), 1f))
        addView(settingsTabButton, LinearLayout.LayoutParams(0, dp(46), 1f))
        updateNavigation()
    }

    private fun navigationButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setOnClickListener { action() }
    }

    private fun statusCard(): View = card().apply {
        addView(TextView(this@LabActivity).apply {
            text = "ESTADO"
            setTextColor(mutedTextColor)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.1f
        })
        statusLabel = TextView(this@LabActivity).apply {
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(9), 0, 0)
        }
        addView(statusLabel)
        statusDetail = TextView(this@LabActivity).apply {
            setTextColor(secondaryTextColor)
            textSize = 15f
            setPadding(0, dp(6), 0, 0)
        }
        addView(statusDetail)
    }

    private fun readinessCard(): View = card().apply {
        addView(TextView(this@LabActivity).apply {
            text = "CONEXIÓN"
            setTextColor(mutedTextColor)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.1f
            setPadding(0, 0, 0, dp(10))
        })
        bluetoothValue = readinessRow("Bluetooth")
        targetValue = readinessRow("Scooter")
        credentialValue = readinessRow("Acceso local")
        targetActionButton = Button(this@LabActivity).apply {
            text = "Buscar scooter cercano"
            isAllCaps = false
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(primaryTextColor)
            setBackgroundDrawable(roundBackground(secondaryActionColor, 14))
            setOnClickListener { beginScan() }
        }
        addView(targetActionButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(48),
        ).apply { topMargin = dp(10) })
        selectedTargetDetail = TextView(this@LabActivity).apply {
            setTextColor(secondaryTextColor)
            textSize = 14f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundDrawable(roundBackground(selectedCandidateColor, 14))
        }
        addView(selectedTargetDetail, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) })
        targetHint = TextView(this@LabActivity).apply {
            setTextColor(mutedTextColor)
            textSize = 13f
            text = "Seleccioná el scooter detectado para conectarlo desde esta app."
            setPadding(0, dp(8), 0, 0)
        }
        addView(targetHint)
        candidateList = LinearLayout(this@LabActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        addView(candidateList)
    }

    private fun LinearLayout.readinessRow(label: String): TextView {
        val row = LinearLayout(this@LabActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        row.addView(TextView(this@LabActivity).apply {
            text = label
            setTextColor(primaryTextColor)
            textSize = 16f
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return TextView(this@LabActivity).also { value ->
            value.textSize = 14f
            value.gravity = Gravity.END
            row.addView(value)
            addView(row)
        }
    }

    private fun controlCard(): LinearLayout = card().apply {
        visibility = View.GONE
        setPadding(dp(20), dp(18), dp(20), dp(18))
        addView(TextView(this@LabActivity).apply {
            text = "LOCK"
            setTextColor(mutedTextColor)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.1f
        })
        controlDetail = TextView(this@LabActivity).apply {
            text = "Choose an action. Every change requires confirmation."
            setTextColor(secondaryTextColor)
            textSize = 15f
            setPadding(0, dp(8), 0, dp(12))
        }
        addView(controlDetail)
        lockToggleButton = Button(this@LabActivity).apply {
            text = "Lock or unlock scooter"
            isAllCaps = false
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setBackgroundDrawable(roundBackground(primaryActionColor, 14))
            setOnClickListener { requestLockToggle() }
        }
        addView(lockToggleButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(52),
        ))
    }

    private fun showDashboard() {
        dashboardContent.visibility = View.VISIBLE
        settingsContent.visibility = View.GONE
        updateNavigation()
    }

    private fun showSettings() {
        dashboardContent.visibility = View.GONE
        settingsContent.visibility = View.VISIBLE
        updateNavigation()
    }

    private fun updateNavigation() {
        if (!::dashboardTabButton.isInitialized || !::settingsTabButton.isInitialized) return
        val dashboardVisible = dashboardContent.visibility == View.VISIBLE
        dashboardTabButton.setTextColor(if (dashboardVisible) Color.WHITE else mutedTextColor)
        dashboardTabButton.setBackgroundDrawable(roundBackground(if (dashboardVisible) primaryActionColor else Color.TRANSPARENT, 16))
        settingsTabButton.setTextColor(if (dashboardVisible) mutedTextColor else Color.WHITE)
        settingsTabButton.setBackgroundDrawable(roundBackground(if (dashboardVisible) Color.TRANSPARENT else primaryActionColor, 16))
    }

    private fun refreshReadiness() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        val bluetoothReady = adapter != null && adapter.isEnabled && hasBluetoothPermissions()
        val addressReady = !selectedTargetAddress().isNullOrBlank()
        val secretReady = File(filesDir, laboratoryCredentialFileName).let {
            it.isFile && it.length() == secretSize.toLong()
        }

        bluetoothValue.setReadiness(
            ready = bluetoothReady,
            readyText = "Disponible",
            unavailableText = if (adapter?.isEnabled == false) "Desactivado" else "Falta permiso",
        )
        targetValue.setReadiness(addressReady, "Seleccionado", "Pendiente")
        selectedTargetDetail.text = if (addressReady) {
            "Selected scooter: ${selectedTargetName()}\nChange it here only if needed."
        } else {
            "Choose your scooter once to enable automatic connection."
        }
        credentialValue.setReadiness(secretReady, "Preparada", "Pendiente")
        if (!scanning) {
            targetActionButton.text = if (addressReady) "Change scooter" else "Find scooter"
            targetActionButton.isEnabled = bluetoothReady
        }

        val ready = bluetoothReady && addressReady && secretReady
        startButton.isEnabled = ready && !testStarted
        scooterNameValue.text = if (addressReady) selectedTargetName() else "Scooter not selected"
        batteryValue.text = "Battery  —  ·  Range  —"
        setupButton.visibility = if (addressReady) View.GONE else View.VISIBLE
        if (!ready) {
            showStatus(
                title = "Setup needed",
                detail = "Open Settings to finish the one-time setup.",
                color = warningColor,
            )
        } else if (!testStarted) {
            showStatus(
                title = "Ready",
                detail = "Connecting automatically…",
                color = readyColor,
            )
        }
    }

    private fun scheduleAutomaticConnection() {
        if (automaticConnectionAttempted || testStarted || !hasSavedScooterSetup()) return
        automaticConnectionAttempted = true
        automaticConnectionHandler.postDelayed({
            if (!testStarted && hasSavedScooterSetup()) startAuthentication()
        }, automaticConnectionDelayMillis)
    }

    private fun hasSavedScooterSetup(): Boolean {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        return adapter != null && adapter.isEnabled && hasBluetoothPermissions() &&
            !selectedTargetAddress().isNullOrBlank() &&
            File(filesDir, laboratoryCredentialFileName).let { it.isFile && it.length() == secretSize.toLong() }
    }

    @Suppress("DEPRECATION")
    private fun startAuthentication() {
        if (testStarted) return
        if (!hasBluetoothPermissions()) {
            requestBluetoothPermissions()
            return
        }
        val address = selectedTargetAddress()
        if (address.isNullOrBlank()) {
            refreshReadiness()
            return
        }
        val secretFile = File(filesDir, laboratoryCredentialFileName)
        val effectiveLtmk = try {
            secretFile.readBytes().also { require(it.size == secretSize) }
        } catch (error: Exception) {
            refreshReadiness()
            return
        }
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            refreshReadiness()
            return
        }
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (error: IllegalArgumentException) {
            showFailure("La preparación del scooter no es válida.")
            return
        }

        testStarted = true
        startButton.isEnabled = false
        clearPendingApplicationFrames()
        applicationInitializationHandler.removeCallbacksAndMessages(null)
        session?.close()
        session = ScooterSessionConnection(this, device, effectiveLtmk, object : ScooterSessionConnection.Listener {
            override fun onConnecting(detail: String) {
                showProgress("Conectando", detail)
            }

            override fun onAuthenticated(sessionKey: ByteArray) {
                prepareLaboratoryControls(sessionKey)
                applicationInitializationHandler.postDelayed(
                    ::beginApplicationInitializationAfterChannelReceipt,
                    initialApplicationDeliveryWindowMillis,
                )
            }

            override fun onApplicationFrame(frame: ByteArray) = processApplicationFrame(frame)

            override fun onFailure(reason: String) {
                testStarted = false
                session?.close()
                session = null
                showFailure("No se pudo preparar la conexión: $reason")
            }
        })
        showProgress("Conectando", "Abriendo únicamente el canal de autenticación Bluetooth…")
        try {
            session?.connect()
        } catch (_: Exception) {
            testStarted = false
            session?.close()
            session = null
            showFailure("No se pudo abrir la conexión Bluetooth con el scooter.")
        } finally {
            effectiveLtmk.fill(0)
        }
    }

    private fun prepareLaboratoryControls(sessionKey: ByteArray) {
        try {
            commandComposer?.clear()
            applicationChannel?.clear()
            lastKnownBatteryPercentage = null
            lastKnownPowerMode = null
            renderDashboardTelemetry()
            // Xiaomi Home restarts this counter on every authenticated BLE session. Its first
            // outbound opening request is 2, while next() increments before returning a value.
            // The scooter's independently-originated initial update can carry another request
            // id and must not seed this outbound counter.
            val requestIds = MiotBleSpecRequestCounter(observedMiotInitialCounter)
            val cipher = MiotBleApplicationCipher(sessionKey)
            commandComposer = MiotScooterCommandComposer.forInitializedSession(requestIds, cipher)
            batteryReader = MiotScooterBatteryReader(requestIds, cipher)
            powerModeReader = MiotScooterPowerModeReader(requestIds, cipher)
            applicationInitializer = MiotScooterApplicationInitializer(requestIds, cipher)
            inboundResponseConsumer = MiotScooterInboundResponseConsumer(cipher)
            applicationChannel = ScooterApplicationChannel()
        } finally {
            sessionKey.fill(0)
        }
        controlsCard.visibility = View.GONE
        setControlsEnabled(false)
        while (pendingApplicationFrames.isNotEmpty()) {
            val frame = pendingApplicationFrames.removeFirst()
            try {
                processApplicationFrame(frame)
            } finally {
                frame.fill(0)
            }
        }
        // The scooter may send a single-control payload at the start of this channel. Xiaomi
        // Home acknowledges it before consuming it, but does not wait for it before starting
        // the outbound MiOT startup sequence.
    }

    /** Starts MiOT only once Xiaomi Home's initial channel receipt has been accepted for write. */
    private fun beginApplicationInitializationAfterChannelReceipt() {
        if (applicationInitializer?.state != MiotScooterInitializationState.IDLE) return
        beginApplicationInitialization()
    }

    private fun beginApplicationInitialization() {
        val initializer = applicationInitializer ?: return
        val currentSession = session ?: run {
            showFailure("La conexión Bluetooth ya no está disponible.")
            return
        }
        try {
            showProgress(
                "Inicializando canal de aplicación",
                "Sincronizando el canal MiOT antes de habilitar acciones. No se envía bloqueo ni desbloqueo.",
            )
            val nowMillis = System.currentTimeMillis()
            val phoneTime = MiotBleSpecV2Codec.phoneTimeSeconds(
                utcSeconds = nowMillis / 1_000L,
                utcOffsetSeconds = TimeZone.getDefault().getOffset(nowMillis) / 1_000,
            )
            currentSession.writeApplicationFrames(initializer.begin(phoneTime))
            scheduleStartupFlowFallback(initializer)
        } catch (error: Exception) {
            showFailure("No se pudo iniciar la sincronización del canal de aplicación.")
        }
    }

    private fun finishApplicationInitialization() {
        applicationChannel?.markReady()
        controlsCard.visibility = View.VISIBLE
        setControlsEnabled(true)
        updateLockToggleLabel()
        controlDetail.text = "Connected. Choose an action and confirm it before sending."
        showStatus(
            title = "Connected",
            detail = "",
            color = readyColor,
        )
        requestBatteryPercentage()
    }

    /** Reads Xiaomi Home's documented UINT8 battery property without changing scooter state. */
    private fun requestBatteryPercentage() {
        val reader = batteryReader ?: return
        val currentSession = session ?: return
        try {
            currentSession.writeApplicationFrames(reader.begin())
            batteryReadHandler.removeCallbacksAndMessages(null)
            batteryReadHandler.postDelayed({
                if (batteryReader !== reader || reader.state != MiotScooterBatteryReadState.WAITING_FLOW_ACK) {
                    return@postDelayed
                }
                try {
                    reader.advanceWithoutFlowAcknowledgement().takeIf { it.isNotEmpty() }?.let {
                        currentSession.writeApplicationFrames(it)
                    }
                } catch (_: Exception) {
                    reader.cancel()
                }
            }, observedFlowWindowMillis)
            batteryReadHandler.postDelayed(batteryReadTimeoutRunnable, batteryReadTimeoutMillis)
        } catch (_: Exception) {
            reader.cancel()
            Log.i(logTag, "Battery read could not start; leaving dashboard value unavailable.")
        }
    }

    private fun consumeBatteryResponse(metadata: com.velocimetro.scooterlab.MiotInboundPayloadMetadata) {
        val percentage = batteryReader?.onInboundResponse(metadata) ?: return
        batteryReadHandler.removeCallbacksAndMessages(null)
        lastKnownBatteryPercentage = percentage
        renderDashboardTelemetry()
        Log.i(logTag, "Battery read completed for the requested MiOT property.")
        powerModeReadHandler.postDelayed(::requestPowerMode, observedFlowWindowMillis)
    }

    /** Reads Xiaomi Home's current riding mode after battery telemetry has completed. */
    private fun requestPowerMode() {
        val reader = powerModeReader ?: return
        val currentSession = session ?: return
        try {
            currentSession.writeApplicationFrames(reader.begin())
            powerModeReadHandler.removeCallbacksAndMessages(null)
            powerModeReadHandler.postDelayed({
                if (powerModeReader !== reader || reader.state != MiotScooterPowerModeReadState.WAITING_FLOW_ACK) {
                    return@postDelayed
                }
                try {
                    reader.advanceWithoutFlowAcknowledgement().takeIf { it.isNotEmpty() }?.let {
                        currentSession.writeApplicationFrames(it)
                    }
                } catch (_: Exception) {
                    reader.cancel()
                }
            }, observedFlowWindowMillis)
            powerModeReadHandler.postDelayed(powerModeReadTimeoutRunnable, batteryReadTimeoutMillis)
        } catch (_: Exception) {
            reader.cancel()
            Log.i(logTag, "Power-mode read could not start; leaving dashboard mode unavailable.")
        }
    }

    private fun consumePowerModeResponse(metadata: com.velocimetro.scooterlab.MiotInboundPayloadMetadata) {
        val mode = powerModeReader?.onInboundResponse(metadata) ?: return
        powerModeReadHandler.removeCallbacksAndMessages(null)
        lastKnownPowerMode = mode
        renderDashboardTelemetry()
        Log.i(logTag, "Power-mode read completed for the requested MiOT property.")
    }

    private fun renderDashboardTelemetry() {
        val battery = lastKnownBatteryPercentage?.let { "$it%" } ?: "—"
        val mode = when (lastKnownPowerMode) {
            1 -> "Walk"
            2 -> "Drive"
            3 -> "Sport"
            4 -> "Boost"
            else -> "—"
        }
        batteryValue.text = "Battery  $battery  ·  $mode"
    }

    /** This scooter exposes the observed startup window without a separate flow ACK. */
    private fun scheduleStartupFlowFallback(initializer: MiotScooterApplicationInitializer) {
        applicationInitializationHandler.postDelayed({
            if (applicationInitializer !== initializer ||
                initializer.state != MiotScooterInitializationState.WAITING_FLOW_ACK
            ) return@postDelayed
            try {
                val fallbackFrames = initializer.advanceWithoutFlowAcknowledgement()
                if (fallbackFrames.isNotEmpty()) {
                    session?.writeApplicationFrames(fallbackFrames)
                        ?: throw IllegalStateException("La conexión Bluetooth ya no está disponible")
                    scheduleStartupDataFallback(initializer)
                }
            } catch (_: Exception) {
                showFailure("No se pudo continuar la sincronización del canal de aplicación.")
            }
        }, observedFlowWindowMillis)
    }

    private fun scheduleStartupDataFallback(initializer: MiotScooterApplicationInitializer) {
        applicationInitializationHandler.postDelayed({
            if (applicationInitializer !== initializer ||
                initializer.state != MiotScooterInitializationState.WAITING_DATA_ACK
            ) return@postDelayed
            try {
                val nextFlow = initializer.advanceWithoutDataAcknowledgement()
                if (nextFlow.isNotEmpty()) {
                    session?.writeApplicationFrames(nextFlow)
                        ?: throw IllegalStateException("La conexión Bluetooth ya no está disponible")
                    scheduleStartupFlowFallback(initializer)
                } else if (initializer.state == MiotScooterInitializationState.COMPLETED) {
                    applicationInitializer = null
                    finishApplicationInitialization()
                }
            } catch (_: Exception) {
                showFailure("No se pudo completar la sincronización del canal de aplicación.")
            }
        }, observedStartupDataWindowMillis)
    }

    private fun requestLockChange(locked: Boolean) {
        val action = if (locked) "bloquear" else "desbloquear"
        val composer = commandComposer
        if (applicationChannel?.state != ScooterApplicationChannelState.READY) {
            showFailure("El canal de aplicación aún está terminando su inicialización.")
            return
        }
        if (composer == null || composer.state == MiotScooterCommandState.WAITING_FLOW_ACK ||
            composer.state == MiotScooterCommandState.WAITING_DATA_ACK ||
            composer.state == MiotScooterCommandState.WAITING_RESPONSE
        ) {
            showFailure("Todavía hay una operación en curso.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Confirmar: $action")
            .setMessage(
                "Esta acción se enviará ahora al scooter por Bluetooth. " +
                    "Verificá visualmente el resultado antes de continuar.",
            )
            .setNegativeButton("Cancelar", null)
            .setPositiveButton(if (locked) "Bloquear" else "Desbloquear") { _, _ ->
                beginLockChange(locked)
            }
            .show()
    }

    private fun requestLockToggle() {
        when (lastKnownLockState) {
            true -> requestLockChange(locked = false)
            false -> requestLockChange(locked = true)
            null -> AlertDialog.Builder(this)
                .setTitle("Choose lock action")
                .setItems(arrayOf("Lock scooter", "Unlock scooter")) { _, selected ->
                    requestLockChange(locked = selected == 0)
                }
                .show()
        }
    }

    private fun beginLockChange(locked: Boolean) {
        val composer = commandComposer ?: run {
            showFailure("El canal de control no está preparado.")
            return
        }
        val currentSession = session ?: run {
            showFailure("La conexión Bluetooth ya no está disponible.")
            return
        }
        val action = if (locked) "bloqueo" else "desbloqueo"
        try {
            pendingLockState = locked
            val commandFrames = composer.beginLock(locked)
            setControlsEnabled(false)
            controlDetail.text = "Solicitud de $action iniciada. Esperando el acuse del scooter…"
            showProgress("Orden en curso", "Esperando el acuse del canal de aplicación…")
            currentSession.writeApplicationFrames(commandFrames)
            commandTimeoutHandler.removeCallbacks(commandTimeoutRunnable)
            commandTimeoutHandler.postDelayed({
                if (composer.state != MiotScooterCommandState.WAITING_FLOW_ACK) return@postDelayed
                try {
                    val fallbackFrames = composer.advanceWithoutFlowAcknowledgement()
                    if (fallbackFrames.isNotEmpty()) currentSession.writeApplicationFrames(fallbackFrames)
                } catch (_: Exception) {
                    composer.abort()
                }
            }, observedFlowWindowMillis)
            commandTimeoutHandler.postDelayed(commandTimeoutRunnable, commandTimeoutMillis)
        } catch (error: Exception) {
            commandTimeoutHandler.removeCallbacks(commandTimeoutRunnable)
            composer.abort()
            setControlsEnabled(true)
            showFailure("No se pudo iniciar la solicitud de $action.")
        }
    }

    private fun processApplicationFrame(frame: ByteArray) {
        val channel = applicationChannel
        val composer = commandComposer
        if (channel == null || composer == null) {
            pendingApplicationFrames.addLast(frame.copyOf())
            return
        }
        try {
            val channelResult = channel.onInboundFrame(frame)
            val receivedPacket = channelResult.packet
            val initialPayload = (receivedPacket as? ScooterChannelPacket.SingleControl)?.payload
            if (channelResult.responseFrames.isNotEmpty()) {
                session?.writeApplicationResponseFrames(channelResult.responseFrames) {
                    if (initialPayload != null) {
                        consumeInitialSingleControlPayload(initialPayload)?.let { metadata ->
                            composer.onInboundResponse(metadata)
                            consumeBatteryResponse(metadata)
                            consumePowerModeResponse(metadata)
                        }
                        renderCommandState(composer)
                        beginApplicationInitializationAfterChannelReceipt()
                    }
                } ?: throw IllegalStateException("La conexión Bluetooth ya no está disponible")
            }
            if (receivedPacket is ScooterChannelPacket.SingleControl) {
                // The payload is deliberately consumed by the write-accepted callback above.
                // Xiaomi Home's c02 handler uses the same ordering.
            }
            channelResult.completedDataPayload?.let { payload ->
                val envelopeLength = payload.size
                val envelopeSequence = payload.takeIf { it.size >= 2 }?.let {
                    (it[0].toInt() and 0xff) or ((it[1].toInt() and 0xff) shl 8)
                }
                val responseMetadata = try {
                    inboundResponseConsumer?.consume(payload)
                } finally {
                    payload.fill(0)
                }
                if (responseMetadata != null) {
                    Log.i(
                        logTag,
                        "Respuesta MiOT validada: estructura de ${responseMetadata.plaintextLength} bytes, " +
                        "operación ${responseMetadata.operation ?: "ausente"}; contenido descartado.",
                    )
                    composer.onInboundResponse(responseMetadata)
                    consumeBatteryResponse(responseMetadata)
                    consumePowerModeResponse(responseMetadata)
                    renderCommandState(composer)
                } else {
                    Log.i(
                        logTag,
                        "Respuesta MiOT recibida y acusada; envoltura no validada " +
                            "(secuencia ${envelopeSequence ?: "ausente"}, $envelopeLength bytes).",
                    )
                }
            }
            if (channelResult.becameReady) {
                controlDetail.text = "El scooter confirmó el canal. Finalizando su sincronización antes de mostrar acciones."
            }
            val initializer = applicationInitializer
            if (initializer != null) {
                val startupFrames = initializer.onApplicationFrame(frame)
                if (startupFrames.isNotEmpty()) {
                    session?.writeApplicationFrames(startupFrames)
                        ?: throw IllegalStateException("La conexión Bluetooth ya no está disponible")
                }
                if (initializer.state == MiotScooterInitializationState.WAITING_FLOW_ACK && startupFrames.isNotEmpty()) {
                    scheduleStartupFlowFallback(initializer)
                }
                if (initializer.state == MiotScooterInitializationState.WAITING_DATA_ACK && startupFrames.isNotEmpty()) {
                    scheduleStartupDataFallback(initializer)
                }
                when (initializer.state) {
                    MiotScooterInitializationState.COMPLETED -> {
                        applicationInitializer = null
                        applicationInitializationHandler.postDelayed(
                            ::finishApplicationInitialization,
                            applicationStartupFinalSettleMillis,
                        )
                    }
                    MiotScooterInitializationState.FAILED -> {
                        throw IllegalStateException("El scooter rechazó la sincronización MiOT")
                    }
                    else -> Unit
                }
            }
            val nextFrames = composer.onApplicationFrame(frame)
            if (nextFrames.isNotEmpty()) {
                controlDetail.text = "Acuse recibido. Enviando el dato protegido…"
                session?.writeApplicationFrames(nextFrames)
                    ?: throw IllegalStateException("La conexión Bluetooth ya no está disponible")
            }
            val batteryFrames = batteryReader?.onApplicationFrame(frame).orEmpty()
            if (batteryFrames.isNotEmpty()) {
                session?.writeApplicationFrames(batteryFrames)
                    ?: throw IllegalStateException("La conexión Bluetooth ya no está disponible")
            }
            val powerModeFrames = powerModeReader?.onApplicationFrame(frame).orEmpty()
            if (powerModeFrames.isNotEmpty()) {
                session?.writeApplicationFrames(powerModeFrames)
                    ?: throw IllegalStateException("La conexión Bluetooth ya no está disponible")
            }
            renderCommandState(composer)
        } catch (error: Exception) {
            commandTimeoutHandler.removeCallbacks(commandTimeoutRunnable)
            composer.abort()
            controlDetail.text = "La conexión no pudo completar la operación."
            setControlsEnabled(true)
            showFailure("No se pudo continuar la operación de aplicación.")
        }
    }

    private fun renderCommandState(composer: MiotScooterCommandComposer) {
        when (composer.state) {
                MiotScooterCommandState.COMPLETED -> {
                    commandTimeoutHandler.removeCallbacks(commandTimeoutRunnable)
                    pendingLockState?.let { locked ->
                        lastKnownLockState = locked
                        pendingLockState = null
                        updateLockToggleLabel()
                    }
                    controlDetail.text = "El scooter confirmó la recepción. Comprobá visualmente el estado físico."
                    setControlsEnabled(true)
                    showStatus(
                        title = "Orden confirmada por el canal",
                        detail = "El scooter acusó la orden. Verificá el bloqueo o desbloqueo físicamente.",
                        color = readyColor,
                    )
                }
                MiotScooterCommandState.FAILED -> {
                    commandTimeoutHandler.removeCallbacks(commandTimeoutRunnable)
                    pendingLockState = null
                    controlDetail.text = "El canal rechazó la orden o recibió una respuesta inválida."
                    setControlsEnabled(true)
                    showFailure("La orden no fue confirmada por el canal de aplicación.")
                }
                else -> Unit
            }
    }

    private fun consumeInitialSingleControlPayload(payload: ByteArray): com.velocimetro.scooterlab.MiotInboundPayloadMetadata? {
        val initialPayloadMetadata = try {
            inboundResponseConsumer?.consume(payload)
        } finally {
            payload.fill(0)
        }
        if (initialPayloadMetadata != null) {
            Log.i(
                logTag,
                "Mensaje inicial MiOT validado tras el acuse: estructura de ${initialPayloadMetadata.plaintextLength} bytes " +
                    "(declarados ${initialPayloadMetadata.declaredLength ?: "ausente"}), solicitud " +
                    "${initialPayloadMetadata.requestId ?: "ausente"}, operación " +
                    "${initialPayloadMetadata.operation ?: "ausente"}, propiedad " +
                    "${initialPayloadMetadata.serviceId ?: "ausente"}." +
                    "${initialPayloadMetadata.propertyId ?: "ausente"}, formato " +
                    "${initialPayloadMetadata.valueTypeAndLength ?: "ausente"}; contenido descartado.",
            )
        } else {
            Log.i(logTag, "El mensaje inicial MiOT no pudo validarse tras el acuse de canal.")
        }
        return initialPayloadMetadata
    }

    private fun setControlsEnabled(enabled: Boolean) {
        if (::lockToggleButton.isInitialized) lockToggleButton.isEnabled = enabled
    }

    private fun updateLockToggleLabel() {
        if (!::lockToggleButton.isInitialized) return
        lockToggleButton.text = when (lastKnownLockState) {
            true -> "Unlock scooter"
            false -> "Lock scooter"
            null -> "Lock or unlock scooter"
        }
    }

    private fun clearPendingApplicationFrames() {
        while (pendingApplicationFrames.isNotEmpty()) pendingApplicationFrames.removeFirst().fill(0)
    }

    private fun showProgress(title: String, detail: String) = showStatus(title, detail, progressColor)

    private fun showFailure(detail: String) = showStatus("No se completó", detail, failureColor)

    private fun showStatus(title: String, detail: String, color: Int) {
        // High-level diagnostic status only; keys, addresses, and BLE payloads are never logged.
        Log.i(logTag, "$title: $detail")
        val compactTitle = when {
            color == failureColor -> "Connection issue"
            title == "Connected" || title.contains("confirmada", ignoreCase = true) ||
                title.contains("inicializado", ignoreCase = true) -> "Connected"
            title.contains("orden", ignoreCase = true) -> "Updating lock"
            title == "Ready" -> "Ready"
            else -> "Connecting"
        }
        val compactDetail = if (color == failureColor) detail else ""
        runOnUiThread {
            statusLabel.text = compactTitle
            statusLabel.setTextColor(color)
            statusDetail.text = compactDetail
            statusDetail.visibility = if (compactDetail.isBlank()) View.GONE else View.VISIBLE
        }
    }

    private fun TextView.setReadiness(ready: Boolean, readyText: String, unavailableText: String) {
        text = if (ready) readyText else unavailableText
        setTextColor(if (ready) readyColor else warningColor)
    }

    private fun beginScan() {
        if (testStarted || scanning) return
        if (!hasBluetoothPermissions()) {
            requestBluetoothPermissions()
            return
        }
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            refreshReadiness()
            return
        }
        val currentScanner = try {
            adapter.bluetoothLeScanner
        } catch (error: SecurityException) {
            null
        }
        if (currentScanner == null) {
            showFailure("La búsqueda Bluetooth no está disponible en este teléfono.")
            return
        }

        scanCandidates.clear()
        candidateList.removeAllViews()
        scanner = currentScanner
        scanning = true
        targetActionButton.isEnabled = false
        targetActionButton.text = "Buscando…"
        targetHint.text = "Buscando durante ${scanDurationMillis / 1000} segundos. " +
            "No se establecerá una conexión con ningún dispositivo."
        showProgress("Buscando scooter", "Escaneando dispositivos Bluetooth cercanos…")
        try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            // The scooter's FE95 GATT service is not necessarily advertised. Filtering on it
            // hides an otherwise visible scooter, so discovery intentionally stays local and
            // unfiltered; the later authentication step validates the selected target.
            currentScanner.startScan(null, settings, scanCallback)
            scanHandler.postDelayed(stopScanRunnable, scanDurationMillis)
        } catch (error: SecurityException) {
            scanning = false
            targetActionButton.isEnabled = true
            showFailure("Falta permiso para buscar dispositivos Bluetooth.")
        }
    }

    private fun recordCandidate(result: ScanResult) {
        val address = try {
            result.device.address
        } catch (error: SecurityException) {
            return
        }
        if (address.isBlank()) return
        val isNew = synchronized(scanCandidates) { scanCandidates.put(address, result) == null }
        if (!isNew) return
        runOnUiThread {
            if (candidateName(result).equals(targetScooterAdvertisedName, ignoreCase = true)) {
                selectTarget(result)
            } else {
                renderCandidates()
            }
        }
    }

    private fun renderCandidates() {
        candidateList.removeAllViews()
        val results = synchronized(scanCandidates) { scanCandidates.values.toList() }
        results.forEach { result ->
            val candidate = TextView(this).apply {
                val alreadySelected = result.device.address == selectedTargetAddress()
                text = candidateName(result) + if (alreadySelected) {
                    "\n✓ Seleccionado actualmente"
                } else {
                    "\nTocar para elegir este scooter"
                }
                textSize = 15f
                setTextColor(primaryTextColor)
                setPadding(dp(16), dp(12), dp(16), dp(12))
                setBackgroundDrawable(roundBackground(if (alreadySelected) selectedCandidateColor else candidateColor))
                isClickable = true
                isFocusable = true
                contentDescription = "Seleccionar ${candidateName(result)} como scooter"
                setOnClickListener { selectTarget(result) }
            }
            candidateList.addView(candidate, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) })
        }
        if (results.isNotEmpty()) {
            targetHint.text = "Se encontraron ${results.size} dispositivo(s) Bluetooth. " +
                "Tocá tu scooter para guardarlo como destino local."
        }
    }

    private fun selectTarget(result: ScanResult) {
        val address = try {
            result.device.address
        } catch (error: SecurityException) {
            showFailure("No se pudo leer el dispositivo seleccionado.")
            return
        }
        getSharedPreferences(preferencesName, MODE_PRIVATE)
            .edit()
            .putString(savedTargetAddressKey, address)
            .putString(savedTargetNameKey, candidateName(result))
            .apply()
        stopScan(showResult = false)
        candidateList.removeAllViews()
        targetHint.text = "Scooter selected. It will reconnect automatically when the app opens."
        automaticConnectionAttempted = false
        refreshReadiness()
        showDashboard()
        scheduleAutomaticConnection()
    }

    private fun stopScan(showResult: Boolean) {
        scanHandler.removeCallbacks(stopScanRunnable)
        if (!scanning) return
        scanning = false
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
            // Permission state can change while the activity is in the foreground.
        } finally {
            scanner = null
        }
        runOnUiThread {
            targetActionButton.isEnabled = true
            targetActionButton.text = "Buscar de nuevo"
            if (showResult) {
                val count = synchronized(scanCandidates) { scanCandidates.size }
                targetHint.text = if (count == 0) {
                    "No se encontraron dispositivos Bluetooth. Acercate al scooter y probá de nuevo."
                } else {
                    "Se encontraron $count dispositivo(s). Tocá tu scooter para guardarlo como destino local."
                }
                if (count == 0) showFailure("No se encontró ningún dispositivo Bluetooth.")
            }
        }
    }

    private fun candidateName(result: ScanResult): String {
        val advertisedName = result.scanRecord?.deviceName?.trim().orEmpty()
        if (advertisedName.isNotEmpty()) return advertisedName
        return "Dispositivo Bluetooth cercano"
    }

    private fun selectedTargetAddress(): String? = intent.getStringExtra(deviceAddressExtra)
        ?.takeIf(String::isNotBlank)
        ?: getSharedPreferences(preferencesName, MODE_PRIVATE).getString(savedTargetAddressKey, null)

    private fun selectedTargetName(): String =
        getSharedPreferences(preferencesName, MODE_PRIVATE).getString(savedTargetNameKey, null)
            ?.takeIf(String::isNotBlank)
            ?: "Scooter guardado"

    private fun hasBluetoothPermissions(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    } else {
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestBluetoothPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        requestPermissions(permissions, bluetoothPermissionRequestCode)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == bluetoothPermissionRequestCode) {
            automaticConnectionAttempted = false
            refreshReadiness()
            scheduleAutomaticConnection()
        }
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(18), dp(20), dp(18))
        setBackgroundDrawable(roundBackground(cardColor, 20))
        elevation = dp(3).toFloat()
    }

    private fun spacer(height: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }

    private fun roundBackground(color: Int, radius: Int = 18) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(color)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val logTag = "ScooterLab"
        const val deviceAddressExtra = "device_address"
        const val startExtra = "start_authentication"
        const val laboratoryCredentialFileName = "lab_credential.bin"
        const val secretSize = 32
        const val commandTimeoutMillis = 8_000L
        const val batteryReadTimeoutMillis = 3_000L
        const val applicationStartupFinalSettleMillis = 300L
        // Xiaomi Home's first data frame follows the application flow by roughly 80 ms. The
        // The GATT writer schedules the flow about 20 ms after it is queued.
        const val observedFlowWindowMillis = 60L
        const val observedStartupDataWindowMillis = 400L
        const val initialApplicationDeliveryWindowMillis = 350L
        const val bluetoothPermissionRequestCode = 10
        const val preferencesName = "scooter_lab"
        const val savedTargetAddressKey = "selected_target_address"
        const val savedTargetNameKey = "selected_target_name"
        const val targetScooterAdvertisedName = "xioami.scooter.6max"
        const val observedMiotInitialCounter = 1
        const val scanDurationMillis = 12_000L
        const val automaticConnectionDelayMillis = 450L

        val backgroundColor = Color.rgb(8, 15, 29)
        val heroCardColor = Color.rgb(18, 35, 57)
        val cardColor = Color.rgb(17, 29, 48)
        val navigationColor = Color.rgb(20, 33, 54)
        val candidateColor = Color.rgb(27, 45, 70)
        val selectedCandidateColor = Color.rgb(16, 73, 81)
        val primaryActionColor = Color.rgb(14, 116, 144)
        val secondaryActionColor = Color.rgb(31, 52, 79)
        val lockActionColor = Color.rgb(53, 65, 84)
        val accentColor = Color.rgb(34, 211, 238)
        val primaryTextColor = Color.rgb(248, 250, 252)
        val secondaryTextColor = Color.rgb(203, 213, 225)
        val mutedTextColor = Color.rgb(148, 163, 184)
        val readyColor = Color.rgb(52, 211, 153)
        val warningColor = Color.rgb(251, 191, 36)
        val progressColor = Color.rgb(56, 189, 248)
        val failureColor = Color.rgb(251, 113, 133)
    }
}
