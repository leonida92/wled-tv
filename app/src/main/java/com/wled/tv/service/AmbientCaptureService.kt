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
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.wled.tv.R
import com.wled.tv.data.PreferencesRepository
import com.wled.tv.model.DeviceThroughputStats
import com.wled.tv.model.DeviceType
import com.wled.tv.model.ThroughputHistoryPoint
import com.wled.tv.model.ThroughputStats
import com.wled.tv.model.WledConfig
import com.wled.tv.model.WledDevice
import com.wled.tv.network.WledHttpClient
import com.wled.tv.network.WledUdpSender
import com.wled.tv.processing.ScreenColorProcessor
import com.wled.tv.ui.MainActivity
import com.wled.tv.ui.views.DeviceOverlayData
import com.wled.tv.ui.views.SamplingOverlayView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class AmbientCaptureService : Service() {

    private lateinit var prefsRepo: PreferencesRepository
    private var config: WledConfig = WledConfig()

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

    private val colorProcessor = ScreenColorProcessor()
    private val udpSender = WledUdpSender()
    private val httpClient = WledHttpClient()
    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val rawTargetBuffers = HashMap<String, ByteArray>()
    private val deviceBuffers = HashMap<String, ByteArray>()
    private var isCapturing = AtomicBoolean(false)
    private var isScreenOff = AtomicBoolean(false)
    private var lastFrameTime = 0L
    private var lastSendTime = 0L
    private var lastLivePreviewTime = 0L
    private var lastOverlayUpdateTime = 0L
    private var lastOverlayTopCrop = -1f
    private var lastOverlayLeftCrop = -1f
    private var blackWithAudioStartTime = 0L
    private var lastAudioCheckTime = 0L
    private var cachedIsAudioPlaying = false
    private var captureWidth = 320
    private var captureHeight = 180

    private var windowManager: WindowManager? = null
    private var overlayView: SamplingOverlayView? = null
    @Volatile
    private var isOverlayVisible: Boolean = false

    private var statsFramesInCurrentSecond = 0
    private var statsPacketsInCurrentSecond = 0
    private var statsTotalPackets = 0L
    private val statsPerDevicePacketsInSecond = HashMap<String, Int>()
    private val statsPerDeviceTotalPackets = HashMap<String, Long>()
    private var lastStatsCalculationTime = 0L

    private fun recordPacketSent(deviceId: String) {
        statsPacketsInCurrentSecond++
        statsTotalPackets++
        statsPerDevicePacketsInSecond[deviceId] = (statsPerDevicePacketsInSecond[deviceId] ?: 0) + 1
        statsPerDeviceTotalPackets[deviceId] = (statsPerDeviceTotalPackets[deviceId] ?: 0L) + 1L
    }

    private fun checkAndEmitThroughputStats(now: Long) {
        if (lastStatsCalculationTime == 0L) {
            lastStatsCalculationTime = now
            return
        }
        val elapsed = now - lastStatsCalculationTime
        if (elapsed >= 1000L) {
            val actualFps = ((statsFramesInCurrentSecond * 1000L) / elapsed).toInt()
            val actualPps = ((statsPacketsInCurrentSecond * 1000L) / elapsed).toInt()
            val targetFps = config.calibration.fps.coerceIn(15, 60)

            val deviceStatsList = config.enabledDevices.map { dev ->
                val devPps = (((statsPerDevicePacketsInSecond[dev.id] ?: 0) * 1000L) / elapsed).toInt()
                val devTotal = statsPerDeviceTotalPackets[dev.id] ?: 0L
                DeviceThroughputStats(
                    deviceId = dev.id,
                    deviceName = dev.name,
                    ip = dev.ip,
                    pps = devPps,
                    totalPackets = devTotal
                )
            }

            val stats = ThroughputStats(
                fps = actualFps,
                targetFps = targetFps,
                pps = actualPps,
                totalPackets = statsTotalPackets,
                deviceStats = deviceStatsList
            )
            latestStats = stats

            recordHistoryPoint(ThroughputHistoryPoint(timestamp = now, fps = actualFps, pps = actualPps))

            if (throughputListener != null) {
                mainHandler.post {
                    throughputListener?.onThroughputStatsUpdated(stats)
                }
            }

            statsFramesInCurrentSecond = 0
            statsPacketsInCurrentSecond = 0
            statsPerDevicePacketsInSecond.clear()
            lastStatsCalculationTime = now
        }
    }

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

            val targetFps = config.calibration.fps.coerceIn(15, 60)
            val tickIntervalMs = (1000L / targetFps).coerceAtLeast(10L)
            val startUptime = SystemClock.uptimeMillis()

            if (!isTestingOverride) {
                val now = System.currentTimeMillis()
                val devices = config.enabledDevices
                val shouldSendPreview = liveFrameListener != null && (now - lastLivePreviewTime >= 50L)

                for (device in devices) {
                    val leds = device.totalLeds
                    if (leds <= 0) continue

                    val requiredSize = leds * 3
                    val rawBuf = rawTargetBuffers[device.id] ?: continue
                    if (rawBuf.size < requiredSize) continue

                    var smoothBuf = deviceBuffers[device.id]
                    if (smoothBuf == null || smoothBuf.size != requiredSize) {
                        smoothBuf = ByteArray(requiredSize)
                        deviceBuffers[device.id] = smoothBuf
                    }

                    // Step temporal smoothing filter towards target colors
                    colorProcessor.applySmoothing(
                        deviceId = device.id,
                        rawRgb = rawBuf,
                        ledCount = leds,
                        smoothingFactor = device.calibration.smoothingFactor,
                        outputRgb = smoothBuf
                    )

                    // Transmit smoothed frame to WLED via configured protocol (DDP / DRGB / WARLS)
                    udpSender.sendFrame(
                        ip = device.ip,
                        port = device.port,
                        protocol = device.protocol,
                        rgb = smoothBuf,
                        ledCount = leds,
                        colorOrder = device.calibration.colorOrder,
                        isRgbw = device.calibration.isRgbw,
                        rgbwMode = device.calibration.rgbwMode,
                        timeoutSeconds = 5
                    )
                    recordPacketSent(device.id)

                    if (shouldSendPreview) {
                        val previewCopy = smoothBuf.copyOf(leds * 3)
                        liveFrameListener?.onDeviceFrameProcessed(device.id, previewCopy, leds)
                    }
                }
                if (shouldSendPreview) {
                    lastLivePreviewTime = now
                }
                lastSendTime = now
            }

            val now = System.currentTimeMillis()
            checkAndEmitThroughputStats(now)

            val currentCrop = colorProcessor.currentTopCrop
            val currentLeft = colorProcessor.currentLeftCrop
            if (isOverlayVisible && overlayView != null && (currentCrop != lastOverlayTopCrop || currentLeft != lastOverlayLeftCrop || now - lastOverlayUpdateTime >= 250L)) {
                lastOverlayUpdateTime = now
                updateOverlayData()
            }

            val elapsed = SystemClock.uptimeMillis() - startUptime
            val delayMs = (tickIntervalMs - elapsed).coerceAtLeast(1L)
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
        currentServiceInstance = this
        prefsRepo = PreferencesRepository(this)
        config = prefsRepo.loadConfig()
        createNotificationChannel()
        startForegroundServiceWithNotification()

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

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
                registerReceiver(screenReceiver, filter, Context.RECEIVER_EXPORTED)
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
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode != 0 && resultData != null && !isCapturing.get()) {
            config = prefsRepo.loadConfig()
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
        val runnable = Runnable {
            val oldWidth = captureWidth
            val oldHeight = captureHeight
            config = prefsRepo.loadConfig()
            colorProcessor.reset()
            rawTargetBuffers.clear()
            ensureWledAwake()
            if (isCapturing.get() && (config.calibration.captureWidth != oldWidth || config.calibration.captureHeight != oldHeight)) {
                reinitCaptureResolution(config.calibration.captureWidth, config.calibration.captureHeight)
            }
            Log.i(TAG, "Config reloaded: ${config.enabledDevices.size} enabled devices, Saturation=${config.calibration.saturation}, Resolution=${config.calibration.captureWidth}x${config.calibration.captureHeight}")
        }
        val handler = backgroundHandler
        if (handler != null && handler.looper.thread.isAlive) {
            handler.post(runnable)
        } else {
            runnable.run()
        }
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
        stateListener?.onStateChanged(true)

        // Wake WLED controllers and set target brightness via HTTP
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

            colorProcessor.reset()

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

            statsFramesInCurrentSecond = 0
            statsPacketsInCurrentSecond = 0
            statsTotalPackets = 0L
            statsPerDevicePacketsInSecond.clear()
            statsPerDeviceTotalPackets.clear()
            lastStatsCalculationTime = System.currentTimeMillis()
            latestStats = null

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

            if (!isCapturing.get()) {
                image.close()
                return@setOnImageAvailableListener
            }

            if (isScreenOff.get() || powerManager?.isInteractive == false) {
                if (!isScreenOff.get()) {
                    handleScreenOff()
                }
                image.close()
                return@setOnImageAvailableListener
            }

            if (isTestingOverride) {
                image.close()
                return@setOnImageAvailableListener
            }

            // Cap video frame acquisition to at most the target FPS with a small 3ms jitter tolerance
            val targetFps = config.calibration.fps.coerceIn(15, 60)
            val minFrameInterval = (1000L / targetFps) - 3L
            val now = System.currentTimeMillis()
            if (now - lastFrameTime < minFrameInterval) {
                image.close()
                return@setOnImageAvailableListener
            }
            lastFrameTime = now
            statsFramesInCurrentSecond++

            try {
                val planes = image.planes
                if (planes.isEmpty()) return@setOnImageAvailableListener
                val plane = planes[0]
                val buffer = plane.buffer ?: return@setOnImageAvailableListener

                val width = image.width
                val height = image.height
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride

                // Widevine L1 DRM Blackout Detection:
                // When commercial streaming services (Netflix, Disney+, Prime) enforce hardware DRM,
                // MediaProjection captures solid black while audio streams normally.
                // 1. Check if frame is 100% black first (99.9% of video frames will return false immediately)
                val isFrameBlack = colorProcessor.isFrameCompletelyBlack(buffer, width, height, rowStride, pixelStride)

                if (isFrameBlack) {
                    // Only perform audioManager Binder IPC if frame is black, throttled to 2 Hz (every 500ms)
                    val isAudioPlaying = if (now - lastAudioCheckTime >= 500L) {
                        lastAudioCheckTime = now
                        val active = audioManager?.isMusicActive == true
                        cachedIsAudioPlaying = active
                        active
                    } else {
                        cachedIsAudioPlaying
                    }

                    if (isAudioPlaying) {
                        if (blackWithAudioStartTime == 0L) {
                            blackWithAudioStartTime = now
                        } else if (now - blackWithAudioStartTime >= 5000L) {
                            if (!isDrmActive) {
                                isDrmActive = true
                                Log.w(TAG, "Widevine L1 DRM blackout detected (100% black frame for >5s with active audio)")
                            }
                        }
                    } else {
                        blackWithAudioStartTime = 0L
                        if (isDrmActive) isDrmActive = false
                    }
                } else {
                    blackWithAudioStartTime = 0L
                    if (isDrmActive) isDrmActive = false
                }

                colorProcessor.isOverlayActive = isOverlayVisible
                val anyAutoLetterbox = config.enabledDevices.any { it.perimeter.autoLetterbox }
                if (anyAutoLetterbox) {
                    colorProcessor.scanLetterbox(buffer, width, height, rowStride, pixelStride)
                }

                val devices = config.enabledDevices
                for (device in devices) {
                    val leds = device.totalLeds
                    if (leds <= 0) continue

                    val requiredSize = leds * 3
                    var rawBuf = rawTargetBuffers[device.id]
                    if (rawBuf == null || rawBuf.size != requiredSize) {
                        rawBuf = ByteArray(requiredSize)
                        rawTargetBuffers[device.id] = rawBuf
                    }

                    colorProcessor.extractRawColors(
                        buffer = buffer,
                        width = width,
                        height = height,
                        rowStride = rowStride,
                        pixelStride = pixelStride,
                        device = device,
                        calibration = device.calibration,
                        outputRawRgb = rawBuf
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error acquiring frame colors", e)
            } finally {
                // Free the hardware graphic buffer IMMEDIATELY back to display server
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
            blackoutAndPowerOffLeds()
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
            lastSendTime = 0L
            lastFrameTime = 0L
            backgroundHandler?.removeCallbacks(streamingTickerRunnable)
            backgroundHandler?.post(streamingTickerRunnable)
        }
    }

    private fun blackoutAndPowerOffLeds() {
        serviceScope.launch(Dispatchers.IO) {
            for (device in config.enabledDevices) {
                val leds = device.totalLeds
                if (leds > 0) {
                    val blackFrame = ByteArray(leds * 3)
                    // Send blackout frames to immediately turn off LEDs before HTTP takes effect
                    for (i in 0..2) {
                        udpSender.sendFrame(
                            ip = device.ip,
                            port = device.port,
                            protocol = device.protocol,
                            rgb = blackFrame,
                            ledCount = leds,
                            colorOrder = device.calibration.colorOrder,
                            isRgbw = device.calibration.isRgbw,
                            rgbwMode = device.calibration.rgbwMode,
                            timeoutSeconds = 1
                        )
                        delay(40L)
                    }
                }
                // Clear frame buffer so stale frames are never re-transmitted
                deviceBuffers[device.id]?.fill(0)
                rawTargetBuffers[device.id]?.fill(0)
                // Hardware turn-off command via HTTP JSON API
                httpClient.turnOff(device.ip)
            }
        }
    }

    private fun ensureWledAwake() {
        serviceScope.launch {
            for (device in config.enabledDevices) {
                launch {
                    for (attempt in 1..3) {
                        val success = httpClient.wakeAndSetBrightness(device.ip, device.calibration.maxBrightness)
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
                wm.addView(view, params)
                overlayView = view
                isOverlayVisible = true
                colorProcessor.isOverlayActive = true
                Log.i(TAG, "Sampling overlay attached to WindowManager")
                backgroundHandler?.post {
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
                colorProcessor.isOverlayActive = false
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
        val devices = config.enabledDevices
        val overlayList = ArrayList<DeviceOverlayData>(devices.size)
        for (device in devices) {
            val leds = device.totalLeds
            val buf = deviceBuffers[device.id]
            if (leds > 0 && buf != null) {
                val zones = colorProcessor.getActiveZones(device)
                val rgbCopy = buf.copyOf(leds * 3)
                overlayList.add(
                    DeviceOverlayData(
                        deviceId = device.id,
                        deviceName = device.name,
                        zones = zones,
                        rgb = rgbCopy,
                        ledCount = leds
                    )
                )
            }
        }
        val fps = latestStats?.fps?.toFloat() ?: statsFramesInCurrentSecond.toFloat()
        val pps = latestStats?.pps ?: statsPacketsInCurrentSecond
        lastOverlayTopCrop = colorProcessor.currentTopCrop
        lastOverlayLeftCrop = colorProcessor.currentLeftCrop
        view.updateTelemetry(
            devices = overlayList,
            currentTopCrop = colorProcessor.currentTopCrop,
            currentBottomCrop = colorProcessor.currentBottomCrop,
            currentLeftCrop = colorProcessor.currentLeftCrop,
            currentRightCrop = colorProcessor.currentRightCrop,
            fps = fps,
            pps = pps,
            isDrmActive = isDrmActive
        )
    }

    private fun stopCapture() {
        if (!isCapturing.getAndSet(false)) return

        hideSamplingOverlay()
        isRunning = false
        isDrmActive = false
        colorProcessor.isOverlayActive = false
        blackWithAudioStartTime = 0L
        lastAudioCheckTime = 0L
        cachedIsAudioPlaying = false
        stateListener?.onStateChanged(false)

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

        blackoutAndPowerOffLeds()
        statsFramesInCurrentSecond = 0
        statsPacketsInCurrentSecond = 0
        statsPerDevicePacketsInSecond.clear()
        latestStats = null
        rawTargetBuffers.clear()
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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WLED TV Ambient Active")
            .setContentText("Streaming ambient bias lighting to ${config.enabledDevices.size} lights")
            .setSmallIcon(R.drawable.ic_tv)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_crop, "Overlay", toggleOverlayPendingIntent)
            .addAction(R.drawable.ic_power, "Stop", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
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
        stopCapture()
        try {
            udpSender.close()
        } catch (_: Exception) {}
        try {
            serviceScope.cancel()
        } catch (_: Exception) {}
        if (currentServiceInstance == this) {
            currentServiceInstance = null
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    interface LiveFrameListener {
        fun onDeviceFrameProcessed(deviceId: String, rgb: ByteArray, count: Int)
    }

    interface ServiceStateListener {
        fun onStateChanged(running: Boolean)
    }

    interface ThroughputListener {
        fun onThroughputStatsUpdated(stats: ThroughputStats)
    }

    companion object {
        private const val TAG = "AmbientCaptureService"
        const val CHANNEL_ID = "wled_ambient_capture_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_STOP = "com.wled.tv.ACTION_STOP"
        const val ACTION_RELOAD_CONFIG = "com.wled.tv.ACTION_RELOAD_CONFIG"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        @Volatile
        var isRunning: Boolean = false

        @Volatile
        var isDrmActive: Boolean = false

        @Volatile
        var isTestingOverride: Boolean = false

        var currentServiceInstance: AmbientCaptureService? = null
        var liveFrameListener: LiveFrameListener? = null
        var stateListener: ServiceStateListener? = null
        var throughputListener: ThroughputListener? = null

        @Volatile
        var latestStats: ThroughputStats? = null

        private const val MAX_HISTORY_SECONDS = 10800
        private val historyLock = Any()
        private val historyBuffer = ArrayDeque<ThroughputHistoryPoint>(MAX_HISTORY_SECONDS)

        fun recordHistoryPoint(point: ThroughputHistoryPoint) {
            synchronized(historyLock) {
                if (historyBuffer.size >= MAX_HISTORY_SECONDS) {
                    historyBuffer.removeFirst()
                }
                historyBuffer.addLast(point)
            }
        }

        fun getHistorySnapshot(windowSeconds: Int = MAX_HISTORY_SECONDS): List<ThroughputHistoryPoint> {
            val cutoff = System.currentTimeMillis() - (windowSeconds * 1000L)
            return synchronized(historyLock) {
                historyBuffer.filter { it.timestamp >= cutoff }
            }
        }

        fun clearHistory() {
            synchronized(historyLock) {
                historyBuffer.clear()
            }
        }
    }
}
