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

import android.graphics.PixelFormat

import android.hardware.display.DisplayManager

import android.hardware.display.VirtualDisplay

import android.media.AudioManager

import android.media.ImageReader

import android.media.projection.MediaProjection

import android.media.projection.MediaProjectionManager

import android.net.ConnectivityManager

import android.net.Network

import android.net.NetworkRequest

import android.net.wifi.WifiManager

import android.os.Build

import android.os.Handler

import android.os.HandlerThread

import android.os.IBinder

import android.os.Looper

import android.os.PowerManager

import android.provider.Settings

import android.util.DisplayMetrics

import android.util.Log

import android.view.Display

import android.view.WindowManager

import android.widget.Toast

import androidx.core.app.NotificationCompat

import com.wled.tv.R

import com.wled.tv.data.ConfigRepository

import com.wled.tv.engine.AmbientStreamingEngine

import com.wled.tv.model.WledConfig

import com.wled.tv.network.WledHttpClient

import com.wled.tv.ui.MainActivity

import com.wled.tv.ui.views.SamplingOverlayView

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

 * VirtualDisplay, ImageReader, NotificationManager, Wake/Wifi locks) to the

 * in-process [AmbientStreamingEngine].

 */

class AmbientCaptureService : Service() {



    private lateinit var configRepo: ConfigRepository

    private var config: WledConfig = WledConfig()

    private var engine: AmbientStreamingEngine? = null



    private var mediaProjection: MediaProjection? = null

    private var virtualDisplay: VirtualDisplay? = null

    private var imageReader: ImageReader? = null

    private var backgroundThread: HandlerThread? = null

    private var backgroundHandler: Handler? = null

    private var wakeLock: PowerManager.WakeLock? = null

    private var wifiLock: WifiManager.WifiLock? = null

    private var powerManager: PowerManager? = null

    private var displayManager: DisplayManager? = null

    private var connectivityManager: ConnectivityManager? = null

    private var audioManager: AudioManager? = null



    private val fallbackHttpClient = WledHttpClient()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val mainHandler = Handler(Looper.getMainLooper())



    private val isCapturing = AtomicBoolean(false)

    private val isScreenOff = AtomicBoolean(false)

    private var captureWidth = 320

    private var captureHeight = 180



    private var windowManager: WindowManager? = null

    private var overlayView: SamplingOverlayView? = null

    @Volatile

    private var isOverlayVisible: Boolean = false

    private val systemStatsCollector by lazy { SystemStatsCollector(applicationContext) }

    private var lastSystemStatsSampleTime = 0L

    private var latestSystemStatsString = ""

    private var lastOverlayUpdateTime = 0L

    private var lastOverlayTopCrop = -1f

    private var lastOverlayLeftCrop = -1f



    private val streamingTickerRunnable = object : Runnable {

        override fun run() {

            if (!isCapturing.get()) return



            if (isScreenOff.get() || powerManager?.isInteractive == false) {

                if (!isScreenOff.get()) {

                    handleScreenOff()

                }

                backgroundHandler?.postDelayed(this, 1000L)

                return

            }



            val currentEngine = engine

            if (currentEngine == null) {

                backgroundHandler?.postDelayed(this, 16L)

                return

            }



            val delayMs = currentEngine.tickStreaming()



            val now = System.currentTimeMillis()

            if (isOverlayVisible && overlayView != null && now - lastSystemStatsSampleTime >= 1000L) {

                lastSystemStatsSampleTime = now

                latestSystemStatsString = systemStatsCollector.sample()

            }



            val currentCrop = currentEngine.colorProcessor.currentTopCrop

            val currentLeft = currentEngine.colorProcessor.currentLeftCrop

            if (isOverlayVisible && overlayView != null && (currentCrop != lastOverlayTopCrop || currentLeft != lastOverlayLeftCrop || now - lastOverlayUpdateTime >= 250L)) {

                lastOverlayUpdateTime = now

                updateOverlayData()

            }



            backgroundHandler?.postDelayed(this, delayMs)

        }

    }



    private val displayListener = object : DisplayManager.DisplayListener {

        override fun onDisplayAdded(displayId: Int) {}

        override fun onDisplayRemoved(displayId: Int) {}

        override fun onDisplayChanged(displayId: Int) {

            if (displayId == Display.DEFAULT_DISPLAY) {

                val display = displayManager?.getDisplay(displayId)

                val state = display?.state

                if (state == Display.STATE_OFF || state == Display.STATE_DOZE || state == Display.STATE_DOZE_SUSPEND) {

                    handleScreenOff()

                } else if (state == Display.STATE_ON) {

                    handleScreenOn()

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

                    val isInteractive = powerManager?.isInteractive == true

                    if (intent.action == Intent.ACTION_SCREEN_OFF ||

                        intent.action == Intent.ACTION_SHUTDOWN ||

                        intent.action == Intent.ACTION_DREAMING_STARTED ||

                        isIdle || !isInteractive) {

                        handleScreenOff()

                    }

                }

                Intent.ACTION_SCREEN_ON,

                Intent.ACTION_DREAMING_STOPPED -> {

                    handleScreenOn()

                }

            }

        }

    }



    private val networkCallback = object : ConnectivityManager.NetworkCallback() {

        override fun onAvailable(network: Network) {

            Log.i(TAG, "Network interface connected / available")

            if (isCapturing.get() && !isScreenOff.get()) {

                ensureWledAwake()

            }

        }

    }



    override fun onCreate() {

        super.onCreate()

        configRepo = ConfigRepository.getInstance(applicationContext)

        config = configRepo.currentConfig

        createNotificationChannel()

        startForegroundServiceWithNotification()



        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager



        serviceScope.launch {

            configRepo.configFlow.collect { newConfig ->

                handleConfigChanged(newConfig)

            }

        }



        try {

            powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

            wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WLED_TV:AmbientWakeLock")?.apply {

                setReferenceCounted(false)

                acquire(6 * 60 * 60 * 1000L) // 6-hour maximum session guard timeout

            }

        } catch (e: Exception) {

            Log.e(TAG, "Failed to acquire WakeLock", e)

        }



        try {

            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

            val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

                WifiManager.WIFI_MODE_FULL_LOW_LATENCY

            } else {

                @Suppress("DEPRECATION")

                WifiManager.WIFI_MODE_FULL_HIGH_PERF

            }

            wifiLock = wifiManager?.createWifiLock(lockMode, "WLED_TV:AmbientWifiLock")?.apply {

                setReferenceCounted(false)

                acquire()

            }

            Log.i(TAG, "Wi-Fi Low-Latency Lock acquired (mode: $lockMode)")

        } catch (e: Exception) {

            Log.e(TAG, "Failed to acquire WifiLock", e)

        }



        try {

            displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

            displayManager?.registerDisplayListener(displayListener, null)

        } catch (e: Exception) {

            Log.e(TAG, "Failed to register displayListener", e)

        }



        try {

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

        } catch (e: Exception) {

            Log.e(TAG, "Failed to register screenReceiver", e)

        }



        try {

            connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            val request = NetworkRequest.Builder().build()

            connectivityManager?.registerNetworkCallback(request, networkCallback)

        } catch (e: Exception) {

            Log.e(TAG, "Failed to register networkCallback", e)

        }



        try {

            audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        } catch (e: Exception) {

            Log.e(TAG, "Failed to get AudioManager", e)

        }

    }



    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        // ALWAYS ensure foreground status is updated immediately

        startForegroundServiceWithNotification()



        when (intent?.action) {

            ACTION_STOP -> {

                Log.i(TAG, "Stop command received")

                stopCapture()

                stopSelf()

                return START_NOT_STICKY

            }

            ACTION_RELOAD_CONFIG -> {

                reloadConfig()

                return START_STICKY

            }

            ACTION_TOGGLE_OVERLAY -> {

                toggleSamplingOverlay()

                return START_STICKY

            }

            ACTION_SHOW_OVERLAY -> {

                showSamplingOverlay()

                return START_STICKY

            }

            ACTION_HIDE_OVERLAY -> {

                hideSamplingOverlay()

                return START_STICKY

            }

        }



        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0

        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)

        } else {

            @Suppress("DEPRECATION")

            intent?.getParcelableExtra(EXTRA_RESULT_DATA)

        }



        if (resultCode != 0 && resultData != null && !isCapturing.get()) {

            config = configRepo.currentConfig

            startCapture(resultCode, resultData)

        }



        return START_STICKY

    }



    private fun startForegroundServiceWithNotification() {

        val notification = createNotification()

        try {

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {

                startForeground(

                    NOTIFICATION_ID,

                    notification,

                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION

                )

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



    @androidx.annotation.VisibleForTesting
    internal fun fleetParametersChanged(oldConfig: WledConfig, newConfig: WledConfig): Boolean {

        if (oldConfig.devices.size != newConfig.devices.size) return true

        val oldFleet = oldConfig.devices.map { Triple(it.id, "${it.ip}:${it.port}", it.enabled) }

        val newFleet = newConfig.devices.map { Triple(it.id, "${it.ip}:${it.port}", it.enabled) }

        return oldFleet != newFleet

    }



    private fun handleConfigChanged(newConfig: WledConfig) {

        val oldConfig = config

        val oldWidth = captureWidth

        val oldHeight = captureHeight

        config = newConfig

        engine?.updateConfig(newConfig)



        if (fleetParametersChanged(oldConfig, newConfig)) {

            ensureWledAwake()

        }



        val newWidth = newConfig.calibration.captureWidth

        val newHeight = newConfig.calibration.captureHeight

        if (isCapturing.get() && (newWidth != oldWidth || newHeight != oldHeight)) {

            reinitCaptureResolution(newWidth, newHeight)

        }

        updateNotification()

        Log.i(TAG, "Config updated: ${newConfig.enabledDevices.size} enabled devices, Saturation=${newConfig.calibration.saturation}, Resolution=${newWidth}x${newHeight}")

    }



    private fun startCapture(resultCode: Int, resultData: Intent) {

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)



        if (mediaProjection == null) {

            Log.e(TAG, "Failed to obtain MediaProjection")

            stopSelf()

            return

        }



        isCapturing.set(true)

        isRunning = true



        val newEngine = AmbientStreamingEngine(

            initialConfig = config,

            isAudioActive = { audioManager?.isMusicActive == true }

        ).apply {

            colorProcessor.isOverlayActive = isOverlayVisible

        }

        engine = newEngine

        _activeEngine.value = newEngine



        ensureWledAwake()



        mediaProjection?.registerCallback(object : MediaProjection.Callback() {

            override fun onStop() {

                Log.w(TAG, "MediaProjection stopped by system")

                stopCapture()

                stopSelf()

            }

        }, null)



        initCapture()

    }



    private fun initCapture() {

        try {

            backgroundThread = HandlerThread(

                "WledCaptureThread",

                android.os.Process.THREAD_PRIORITY_BACKGROUND

            ).apply { start() }

            backgroundHandler = Handler(backgroundThread!!.looper)



            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val metrics = DisplayMetrics()

            @Suppress("DEPRECATION")

            windowManager.defaultDisplay.getRealMetrics(metrics)



            captureWidth = config.calibration.captureWidth

            captureHeight = config.calibration.captureHeight

            val densityDpi = metrics.densityDpi



            val reader = ImageReader.newInstance(

                captureWidth,

                captureHeight,

                PixelFormat.RGBA_8888,

                3

            )

            imageReader = reader

            setupImageAvailableListener(reader)



            virtualDisplay = mediaProjection?.createVirtualDisplay(

                "WledAmbientDisplay",

                captureWidth,

                captureHeight,

                densityDpi,

                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,

                reader.surface,

                null,

                backgroundHandler

            )



            // Start steady 60Hz streaming ticker

            backgroundHandler?.removeCallbacks(streamingTickerRunnable)

            backgroundHandler?.post(streamingTickerRunnable)



            Log.i(TAG, "Ambient multi-device capture started (${config.enabledDevices.size} devices @ ${config.calibration.fps} FPS, ${captureWidth}x${captureHeight})")

        } catch (e: Exception) {

            Log.e(TAG, "Exception in initCapture", e)

            stopCapture()

            stopSelf()

        }

    }



    private fun setupImageAvailableListener(reader: ImageReader) {

        reader.setOnImageAvailableListener({ r ->

            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener



            try {

                if (!isCapturing.get() || isScreenOff.get() || powerManager?.isInteractive == false) {

                    if (!isScreenOff.get() && powerManager?.isInteractive == false) {

                        handleScreenOff()

                    }

                    return@setOnImageAvailableListener

                }



                val planes = image.planes

                if (planes.isNotEmpty()) {

                    val plane = planes[0]

                    val buffer = plane.buffer

                    if (buffer != null) {

                        engine?.onFrameAvailable(

                            buffer = buffer,

                            width = image.width,

                            height = image.height,

                            rowStride = plane.rowStride,

                            pixelStride = plane.pixelStride

                        )

                    }

                }

            } catch (e: Exception) {

                Log.e(TAG, "Error acquiring frame colors", e)

            } finally {

                image.close()

            }

        }, backgroundHandler)

    }



    private fun reinitCaptureResolution(newWidth: Int, newHeight: Int) {

        backgroundHandler?.post {

            if (!isCapturing.get() || mediaProjection == null) return@post

            try {

                captureWidth = newWidth

                captureHeight = newHeight

                val oldReader = imageReader



                val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

                val metrics = DisplayMetrics()

                @Suppress("DEPRECATION")

                windowManager.defaultDisplay.getRealMetrics(metrics)



                val newReader = ImageReader.newInstance(

                    newWidth,

                    newHeight,

                    PixelFormat.RGBA_8888,

                    3

                )

                setupImageAvailableListener(newReader)

                imageReader = newReader



                virtualDisplay?.resize(newWidth, newHeight, metrics.densityDpi)

                virtualDisplay?.setSurface(newReader.surface)



                oldReader?.close()

                Log.i(TAG, "Capture resolution updated to ${newWidth}x${newHeight}")

            } catch (e: Exception) {

                Log.e(TAG, "Failed to reinit capture resolution", e)

            }

        }

    }



    private fun handleScreenOff() {

        if (isScreenOff.compareAndSet(false, true)) {

            Log.i(TAG, "Screen sleep / standby / daydream detected - turning off WLED lights")

            try {

                if (wifiLock?.isHeld == true) {

                    wifiLock?.release()

                }

            } catch (_: Exception) {}

            backgroundHandler?.removeCallbacks(streamingTickerRunnable)

            serviceScope.launch {

                try {

                    engine?.blackout(sendPowerOff = true)

                } catch (e: Exception) {

                    Log.w(TAG, "Error during blackout on screen off: ${e.message}")

                }

            }

        }

    }



    private fun handleScreenOn() {

        if (isScreenOff.compareAndSet(true, false)) {

            Log.i(TAG, "Screen wake / active detected - resuming ambient lighting")

            try {

                if (wifiLock?.isHeld == false) {

                    wifiLock?.acquire()

                }

            } catch (_: Exception) {}

            ensureWledAwake()

            backgroundHandler?.removeCallbacks(streamingTickerRunnable)

            backgroundHandler?.post(streamingTickerRunnable)

        }

    }



    private fun ensureWledAwake() {

        val client = engine?.httpClient ?: fallbackHttpClient

        val devices = (engine?.config ?: config).enabledDevices

        serviceScope.launch {

            for (device in devices) {

                launch {

                    for (attempt in 1..3) {

                        val success = client.wakeAndSetBrightness(device.ip, device.calibration.maxBrightness)

                        if (success) break

                        delay(1000L)

                    }

                }

            }

        }

    }



    fun showSamplingOverlay() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {

            Log.w(TAG, "Cannot show overlay: SYSTEM_ALERT_WINDOW permission not granted")

            mainHandler.post {

                Toast.makeText(

                    this,

                    "WLED TV: 'Display over other apps' permission required. Enable in Settings or via ADB.",

                    Toast.LENGTH_LONG

                ).show()

            }

            return

        }



        mainHandler.post {

            if (isOverlayVisible || overlayView != null) return@post

            val wm = windowManager ?: (getSystemService(Context.WINDOW_SERVICE) as WindowManager).also {

                windowManager = it

            }



            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

            } else {

                @Suppress("DEPRECATION")

                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

            }



            val params = WindowManager.LayoutParams(

                WindowManager.LayoutParams.MATCH_PARENT,

                WindowManager.LayoutParams.MATCH_PARENT,

                layoutType,

                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or

                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or

                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or

                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,

                PixelFormat.TRANSLUCENT

            )



            try {

                val view = SamplingOverlayView(this)

                view.bindEngine(activeEngine)

                wm.addView(view, params)

                overlayView = view

                isOverlayVisible = true

                _isOverlayShowing.value = true

                engine?.colorProcessor?.isOverlayActive = true

                Log.i(TAG, "Sampling overlay attached to WindowManager")

                backgroundHandler?.post {

                    lastSystemStatsSampleTime = System.currentTimeMillis()

                    latestSystemStatsString = systemStatsCollector.sample()

                    updateOverlayData()

                } ?: updateOverlayData()

            } catch (e: Exception) {

                Log.e(TAG, "Failed to add overlay view to WindowManager", e)

            }

        }

    }



    fun hideSamplingOverlay() {

        mainHandler.post {

            val view = overlayView ?: return@post

            val wm = windowManager ?: (getSystemService(Context.WINDOW_SERVICE) as WindowManager)

            try {

                wm.removeView(view)

                Log.i(TAG, "Sampling overlay removed from WindowManager")

            } catch (e: Exception) {

                Log.e(TAG, "Failed to remove overlay view", e)

            } finally {

                overlayView = null

                isOverlayVisible = false

                _isOverlayShowing.value = false

                engine?.colorProcessor?.isOverlayActive = false

                backgroundHandler?.post {

                    systemStatsCollector.reset()

                    lastSystemStatsSampleTime = 0L

                    latestSystemStatsString = ""

                }

            }

        }

    }



    fun toggleSamplingOverlay() {

        if (isOverlayVisible) {

            hideSamplingOverlay()

        } else {

            showSamplingOverlay()

        }

    }



    fun isSamplingOverlayShowing(): Boolean = isOverlayVisible



    private fun updateOverlayData() {

        val view = overlayView ?: return

        val currentEngine = engine ?: return

        lastOverlayTopCrop = currentEngine.colorProcessor.currentTopCrop

        lastOverlayLeftCrop = currentEngine.colorProcessor.currentLeftCrop

        view.updateFromEngine(currentEngine, latestSystemStatsString)

    }



    private fun stopCapture() {

        if (!isCapturing.getAndSet(false)) return



        hideSamplingOverlay()

        isRunning = false

        _isOverlayShowing.value = false

        latestSystemStatsString = ""

        systemStatsCollector.reset()



        try {

            backgroundHandler?.removeCallbacksAndMessages(null)

        } catch (_: Exception) {}



        try {

            virtualDisplay?.release()

            virtualDisplay = null

        } catch (e: Exception) {

            Log.e(TAG, "Error releasing virtual display", e)

        }



        try {

            imageReader?.close()

            imageReader = null

        } catch (e: Exception) {

            Log.e(TAG, "Error closing ImageReader", e)

        }



        try {

            backgroundThread?.quitSafely()

            backgroundThread = null

            backgroundHandler = null

        } catch (e: Exception) {

            Log.e(TAG, "Error stopping background thread", e)

        }



        try {

            mediaProjection?.stop()

            mediaProjection = null

        } catch (e: Exception) {

            Log.e(TAG, "Error stopping MediaProjection", e)

        }



        val currentEngine = engine

        engine = null

        _activeEngine.value = null



        serviceScope.launch {

            try {

                currentEngine?.blackout(sendPowerOff = true)

            } catch (e: Exception) {

                Log.w(TAG, "Error during engine blackout on stop", e)

            } finally {

                currentEngine?.close()

            }

        }



        Log.i(TAG, "Ambient screen capture stopped")

    }



    private fun createNotification(): Notification {

        val launchIntent = Intent(this, MainActivity::class.java).apply {

            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP

        }

        val pendingIntent = PendingIntent.getActivity(

            this,

            0,

            launchIntent,

            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        )



        val stopIntent = Intent(this, AmbientCaptureService::class.java).apply {

            action = ACTION_STOP

        }

        val stopPendingIntent = PendingIntent.getService(

            this,

            0,

            stopIntent,

            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        )



        val toggleOverlayIntent = Intent(this, OverlayControlReceiver::class.java).apply {

            action = OverlayControlReceiver.ACTION_TOGGLE_OVERLAY

        }

        val toggleOverlayPendingIntent = PendingIntent.getBroadcast(

            this,

            1,

            toggleOverlayIntent,

            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        )



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

            val channel = NotificationChannel(

                CHANNEL_ID,

                "Ambient Lighting Service",

                NotificationManager.IMPORTANCE_LOW

            ).apply {

                description = "Running real-time screen ambient capture"

                setShowBadge(false)

            }

            val manager = getSystemService(NotificationManager::class.java)

            manager?.createNotificationChannel(channel)

        }

    }



    override fun onDestroy() {

        try {

            unregisterReceiver(screenReceiver)

        } catch (_: Exception) {}

        try {

            displayManager?.unregisterDisplayListener(displayListener)

            displayManager = null

        } catch (_: Exception) {}

        try {

            connectivityManager?.unregisterNetworkCallback(networkCallback)

            connectivityManager = null

        } catch (_: Exception) {}

        try {

            if (wakeLock?.isHeld == true) {

                wakeLock?.release()

            }

            wakeLock = null

        } catch (_: Exception) {}

        try {

            if (wifiLock?.isHeld == true) {

                wifiLock?.release()

            }

            wifiLock = null

        } catch (_: Exception) {}

        audioManager = null

        hideSamplingOverlay()

        if (isCapturing.get()) {

            stopCapture()

        }

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



        @Volatile

        var isRunning: Boolean = false



        private val _activeEngine = MutableStateFlow<AmbientStreamingEngine?>(null)

        val activeEngine: StateFlow<AmbientStreamingEngine?> = _activeEngine.asStateFlow()



        private val _isOverlayShowing = MutableStateFlow(false)

        val isOverlayShowing: StateFlow<Boolean> = _isOverlayShowing.asStateFlow()



        @androidx.annotation.VisibleForTesting

        internal fun setActiveEngineForTesting(engine: AmbientStreamingEngine?) {

            _activeEngine.value = engine

        }

    }

}

