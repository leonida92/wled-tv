package com.wled.tv.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.view.Display
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import com.wled.tv.R
import com.wled.tv.data.ConfigRepository
import com.wled.tv.engine.AmbientStreamingEngine
import com.wled.tv.model.WledConfig
import com.wled.tv.network.DefaultWledControllerClient
import com.wled.tv.network.WledControllerClient
import com.wled.tv.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Operating system adapter connecting Android hardware APIs (MediaProjection,
 * VirtualDisplay, ImageReader, NotificationManager, Wake/Wifi locks, Display power,
 * and floating sampling overlay) to the in-process [AmbientStreamingEngine].
 */
class AmbientCaptureService : Service() {

    private lateinit var configRepo: ConfigRepository

    @VisibleForTesting
    internal var config: WledConfig = WledConfig()

    @VisibleForTesting
    internal var engine: AmbientStreamingEngine? = null

    @VisibleForTesting
    internal var fallbackControllerClient: WledControllerClient = DefaultWledControllerClient.getInstance()

    @VisibleForTesting
    internal var audioManager: AudioManager? = null

    internal var samplingOverlayManager: SamplingOverlayManager? = null
    internal var captureSession: ScreenCaptureSession? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // System locks and connectivity state
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var connectivityManager: ConnectivityManager? = null
    private var connectivityRegistered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.i(TAG, "Network interface connected / available")
            if (isRunning.value && isDisplayInteractive && !isScreenOff.get()) {
                wakeFixtures()
            }
        }
    }

    // Display power state
    private var displayManager: DisplayManager? = null
    private var powerManager: PowerManager? = null
    private val isScreenOff = AtomicBoolean(false)
    private var displayListenersRegistered = false

    internal val isScreenOffState: Boolean
        get() = isScreenOff.get()

    private val isDisplayInteractive: Boolean
        get() = try {
            powerManager?.isInteractive != false
        } catch (_: Exception) {
            true
        }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                val state = displayManager?.getDisplay(displayId)?.state
                if (state == Display.STATE_OFF || state == Display.STATE_DOZE || state == Display.STATE_DOZE_SUSPEND) {
                    onScreenOffDetected()
                } else if (state == Display.STATE_ON) {
                    onScreenOnDetected()
                }
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF,
                Intent.ACTION_SHUTDOWN,
                Intent.ACTION_DREAMING_STARTED,
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                    val isIdle = powerManager?.isDeviceIdleMode == true
                    val interactive = powerManager?.isInteractive == true
                    if (intent.action == Intent.ACTION_SCREEN_OFF ||
                        intent.action == Intent.ACTION_SHUTDOWN ||
                        intent.action == Intent.ACTION_DREAMING_STARTED ||
                        isIdle || !interactive
                    ) {
                        onScreenOffDetected()
                    }
                }
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_DREAMING_STOPPED -> {
                    onScreenOnDetected()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        configRepo = ConfigRepository.getInstance(applicationContext)
        config = configRepo.currentConfig
        createNotificationChannel()
        startForegroundServiceWithNotification()

        samplingOverlayManager = SamplingOverlayManager(
            context = this,
            activeEngineFlow = activeEngine,
            onVisibilityChanged = { isShowing ->
                _isOverlayShowing.value = isShowing
                engine?.setOverlayActive(isShowing)
            }
        )

        registerSystemMonitors()
        initAudioManager()

        serviceScope.launch {
            configRepo.configFlow.collect { newConfig ->
                handleConfigChanged(newConfig)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundServiceWithNotification()

        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "Stop command received")
                stopCapture()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RELOAD_CONFIG -> { reloadConfig(); return START_STICKY }
            ACTION_TOGGLE_OVERLAY -> { toggleSamplingOverlay(); return START_STICKY }
            ACTION_SHOW_OVERLAY -> { showSamplingOverlay(); return START_STICKY }
            ACTION_HIDE_OVERLAY -> { hideSamplingOverlay(); return START_STICKY }
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultData != null && shouldStartCapture(resultCode, resultData)) {
            config = configRepo.currentConfig
            startCapture(resultCode, resultData)
        }

        return START_STICKY
    }

    @VisibleForTesting
    internal fun shouldStartCapture(resultCode: Int, resultData: Intent?): Boolean {
        val isAlreadyCapturing = captureSession?.isCapturing == true
        return resultCode != 0 && resultData != null && !isAlreadyCapturing
    }

    private fun startForegroundServiceWithNotification() {
        val notification = createNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to startForeground", e)
        }
    }

    fun reloadConfig() {
        handleConfigChanged(configRepo.currentConfig)
    }

    @VisibleForTesting
    internal fun fleetParametersChanged(oldConfig: WledConfig, newConfig: WledConfig): Boolean {
        if (oldConfig.devices.size != newConfig.devices.size) return true
        val oldFleet = oldConfig.devices.map { Triple(it.id, "${it.ip}:${it.port}", it.enabled) }
        val newFleet = newConfig.devices.map { Triple(it.id, "${it.ip}:${it.port}", it.enabled) }
        return oldFleet != newFleet
    }

    private fun handleConfigChanged(newConfig: WledConfig) {
        val oldConfig = config
        val oldWidth = oldConfig.capture.captureWidth
        val oldHeight = oldConfig.capture.captureHeight
        config = newConfig
        engine?.updateConfig(newConfig)

        if (fleetParametersChanged(oldConfig, newConfig)) {
            wakeFixtures()
        }

        val newWidth = newConfig.capture.captureWidth
        val newHeight = newConfig.capture.captureHeight
        if (captureSession?.isCapturing == true && (newWidth != oldWidth || newHeight != oldHeight)) {
            captureSession?.reinitResolution(newWidth, newHeight)
        }
        updateNotification()
        Log.i(TAG, "Config updated: ${newConfig.enabledDevices.size} enabled devices, Resolution=${newWidth}x${newHeight}")
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        val projection = projectionManager?.getMediaProjection(resultCode, resultData)

        if (projection == null) {
            Log.e(TAG, "Failed to obtain MediaProjection")
            _isRunning.value = false
            _activeEngine.value = null
            stopSelf()
            return
        }

        if (captureSession != null || engine != null) {
            captureSession?.release()
            captureSession = null
            engine?.close()
            engine = null
        }

        _isRunning.value = true

        val newEngine = AmbientStreamingEngine(
            initialConfig = config,
            isAudioActive = { isAudioActive() }
        ).apply {
            setOverlayActive(samplingOverlayManager?.isOverlayVisible == true)
        }
        engine = newEngine
        _activeEngine.value = newEngine

        wakeFixtures()

        val session = ScreenCaptureSession(
            context = this,
            mediaProjection = projection,
            engineProvider = { engine },
            isScreenActive = { isDisplayInteractive && !isScreenOff.get() },
            onScreenStandbyDetected = {
                if (!isDisplayInteractive && !isScreenOff.get()) {
                    onScreenOffDetected()
                }
            },
            onStopCallback = {
                stopCapture()
                stopSelf()
            },
            onTick = { activeEngine ->
                samplingOverlayManager?.update(activeEngine)
            }
        )
        captureSession = session
        session.start(config.capture.captureWidth, config.capture.captureHeight)
    }

    private fun stopCapture() {
        if (!_isRunning.value && captureSession == null && engine == null) return

        _isRunning.value = false
        _isOverlayShowing.value = false
        samplingOverlayManager?.hide()

        captureSession?.release()
        captureSession = null

        val currentEngine = engine
        engine = null
        _activeEngine.value = null

        blackoutCurrentFleet(currentEngine, closeEngineAfter = true)
        Log.i(TAG, "Ambient screen capture stopped")
    }

    private fun blackoutCurrentFleet(
        targetEngine: AmbientStreamingEngine? = engine,
        closeEngineAfter: Boolean = false
    ) {
        serviceScope.launch {
            try {
                if (targetEngine != null) {
                    targetEngine.blackout(sendPowerOff = true)
                } else {
                    fallbackControllerClient.blackoutFleet(config.enabledDevices, sendPowerOff = true)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error during fleet blackout: ${e.message}", e)
            } finally {
                if (closeEngineAfter) {
                    targetEngine?.close()
                }
            }
        }
    }

    @VisibleForTesting
    internal fun onScreenOffDetected(): Boolean {
        if (!isScreenOff.compareAndSet(false, true)) return false
        Log.i(TAG, "Screen sleep / standby / daydream detected - turning off WLED lights")
        releaseWifiLock()
        captureSession?.pauseStreaming()
        blackoutCurrentFleet(engine, closeEngineAfter = false)
        return true
    }

    @VisibleForTesting
    internal fun onScreenOnDetected(): Boolean {
        if (!isScreenOff.compareAndSet(true, false)) return false
        Log.i(TAG, "Screen wake / active detected - resuming ambient lighting")
        acquireWifiLock()
        wakeFixtures()
        captureSession?.resumeStreaming()
        return true
    }

    internal fun isAudioActive(): Boolean = audioManager?.isMusicActive == true

    internal fun wakeFixtures() {
        val client = engine?.controllerClient ?: fallbackControllerClient
        val devices = (engine?.config ?: config).enabledDevices
        serviceScope.launch {
            for (device in devices) {
                launch {
                    for (attempt in 1..3) {
                        val success = client.wakeAndSetBrightness(device)
                        if (success) break
                        delay(1000L)
                    }
                }
            }
        }
    }

    fun showSamplingOverlay() { samplingOverlayManager?.show() }
    fun hideSamplingOverlay() { samplingOverlayManager?.hide() }
    fun toggleSamplingOverlay() { samplingOverlayManager?.toggle() }
    fun isSamplingOverlayShowing(): Boolean = samplingOverlayManager?.isOverlayVisible ?: isOverlayShowing.value

    private fun registerSystemMonitors() {
        acquireWakeLock()
        acquireWifiLock()

        try {
            connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            connectivityManager?.registerNetworkCallback(NetworkRequest.Builder().build(), networkCallback)
            connectivityRegistered = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register networkCallback", e)
        }

        try {
            displayManager = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            displayManager?.registerDisplayListener(displayListener, null)

            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SHUTDOWN)
                addAction(Intent.ACTION_DREAMING_STARTED)
                addAction(Intent.ACTION_DREAMING_STOPPED)
                addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(screenReceiver, filter)
            }
            displayListenersRegistered = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register display power monitors", e)
        }
    }

    private fun unregisterSystemMonitors() {
        if (displayListenersRegistered) {
            try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
            try { displayManager?.unregisterDisplayListener(displayListener) } catch (_: Exception) {}
            displayListenersRegistered = false
        }
        if (connectivityRegistered) {
            try { connectivityManager?.unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
            connectivityRegistered = false
        }
        connectivityManager = null
        releaseWakeLock()
        releaseWifiLock()
    }

    private fun acquireWifiLock() {
        try {
            if (wifiLock == null) {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wifiManager?.createWifiLock(lockMode, "WLED_TV:AmbientWifiLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
                Log.i(TAG, "Wi-Fi Low-Latency Lock acquired")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WifiLock", e)
        }
    }

    private fun releaseWifiLock() {
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {}
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WLED_TV:AmbientWakeLock")?.apply {
                setReferenceCounted(false)
                acquire(6 * 60 * 60 * 1000L)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WakeLock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        wakeLock = null
    }

    private fun createNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP }
        val pendingIntent = PendingIntent.getActivity(this, 0, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val stopIntent = Intent(this, AmbientCaptureService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val toggleOverlayIntent = Intent(this, OverlayControlReceiver::class.java).apply { action = OverlayControlReceiver.ACTION_TOGGLE_OVERLAY }
        val toggleOverlayPendingIntent = PendingIntent.getBroadcast(this, 1, toggleOverlayIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val deviceCount = config.enabledDevices.size
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WLED TV Ambient Active")
            .setContentText("Streaming ambient bias lighting to $deviceCount lights")
            .setSmallIcon(R.drawable.ic_tv)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_crop, "Overlay", toggleOverlayPendingIntent)
            .addAction(R.drawable.ic_power, "Stop", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(NOTIFICATION_ID, createNotification())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update notification", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Ambient Lighting Service", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Running real-time screen ambient capture"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun initAudioManager() {
        try {
            audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get AudioManager", e)
        }
    }

    override fun onDestroy() {
        unregisterSystemMonitors()
        audioManager = null

        samplingOverlayManager?.release()
        samplingOverlayManager = null

        stopCapture()

        try {
            serviceScope.cancel()
        } catch (_: Exception) {}

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "AmbientCaptureService"
        const val CHANNEL_ID = "wled_ambient_capture_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_STOP = "com.wled.tv.ACTION_STOP"
        const val ACTION_RELOAD_CONFIG = "com.wled.tv.ACTION_RELOAD_CONFIG"
        const val ACTION_TOGGLE_OVERLAY = "com.wled.tv.ACTION_TOGGLE_OVERLAY"
        const val ACTION_SHOW_OVERLAY = "com.wled.tv.ACTION_SHOW_OVERLAY"
        const val ACTION_HIDE_OVERLAY = "com.wled.tv.ACTION_HIDE_OVERLAY"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _activeEngine = MutableStateFlow<AmbientStreamingEngine?>(null)
        val activeEngine: StateFlow<AmbientStreamingEngine?> = _activeEngine.asStateFlow()

        private val _isOverlayShowing = MutableStateFlow(false)
        val isOverlayShowing: StateFlow<Boolean> = _isOverlayShowing.asStateFlow()

        @VisibleForTesting
        internal fun setRunningForTesting(running: Boolean) {
            _isRunning.value = running
        }

        @VisibleForTesting
        internal fun setActiveEngineForTesting(engine: AmbientStreamingEngine?) {
            _activeEngine.value = engine
        }
    }
}
