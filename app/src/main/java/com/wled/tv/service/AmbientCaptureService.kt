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
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.wled.tv.R
import com.wled.tv.data.PreferencesRepository
import com.wled.tv.model.DeviceType
import com.wled.tv.model.WledConfig
import com.wled.tv.model.WledDevice
import com.wled.tv.network.WledHttpClient
import com.wled.tv.network.WledUdpSender
import com.wled.tv.processing.ScreenColorProcessor
import com.wled.tv.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    private var powerManager: PowerManager? = null
    private var displayManager: DisplayManager? = null
    private var connectivityManager: ConnectivityManager? = null

    private val colorProcessor = ScreenColorProcessor()
    private val udpSender = WledUdpSender()
    private val httpClient = WledHttpClient()
    private val serviceScope = CoroutineScope(Dispatchers.IO)

    private val deviceBuffers = HashMap<String, ByteArray>()
    private var isCapturing = AtomicBoolean(false)
    private var isScreenOff = AtomicBoolean(false)
    private var lastFrameTime = 0L
    private var lastSendTime = 0L

    private val keepaliveRunnable = object : Runnable {
        override fun run() {
            if (!isCapturing.get()) return

            if (isScreenOff.get() || powerManager?.isInteractive == false) {
                if (!isScreenOff.get()) {
                    handleScreenOff()
                }
                backgroundHandler?.postDelayed(this, 1000L)
                return
            }

            val now = System.currentTimeMillis()
            // Re-send current frame every 500ms to maintain WLED realtime connection across all devices
            if (now - lastSendTime >= 500L && !isTestingOverride) {
                for (device in config.enabledDevices) {
                    val leds = device.totalLeds
                    val buf = deviceBuffers[device.id]
                    if (buf != null && buf.size >= leds * 3 && leds > 0) {
                        udpSender.sendDrgbFrame(
                            ip = device.ip,
                            port = device.port,
                            timeoutSeconds = 5,
                            rgb = buf,
                            ledCount = leds,
                            colorOrder = device.calibration.colorOrder
                        )
                    }
                }
                lastSendTime = now
            }

            backgroundHandler?.postDelayed(this, 500L)
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

        try {
            powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WLED_TV:AmbientWakeLock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WakeLock", e)
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
        config = prefsRepo.loadConfig()
        colorProcessor.reset()
        ensureWledAwake()
        Log.i(TAG, "Config reloaded: ${config.enabledDevices.size} enabled devices, Saturation=${config.calibration.saturation}")
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
            backgroundThread = HandlerThread("WledCaptureThread").apply { start() }
            backgroundHandler = Handler(backgroundThread!!.looper)

            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)

            // Scaled buffer for ultra-low latency edge color extraction (320x180)
            val captureWidth = 320
            val captureHeight = 180
            val densityDpi = metrics.densityDpi

            imageReader = ImageReader.newInstance(
                captureWidth,
                captureHeight,
                PixelFormat.RGBA_8888,
                2
            )

            colorProcessor.reset()

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "WledAmbientDisplay",
                captureWidth,
                captureHeight,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                backgroundHandler
            )

            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener

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

                val targetInterval = 1000L / config.calibration.fps.coerceIn(15, 60)
                val now = System.currentTimeMillis()
                if (now - lastFrameTime < targetInterval) {
                    image.close()
                    return@setOnImageAvailableListener
                }
                lastFrameTime = now

                try {
                    val devices = config.enabledDevices
                    for (device in devices) {
                        val leds = device.totalLeds
                        if (leds <= 0) continue

                        val requiredSize = leds * 3
                        var buf = deviceBuffers[device.id]
                        if (buf == null || buf.size != requiredSize) {
                            buf = ByteArray(requiredSize)
                            deviceBuffers[device.id] = buf
                        }

                        if (colorProcessor.processDevice(image, device, device.calibration, buf)) {
                            udpSender.sendDrgbFrame(
                                ip = device.ip,
                                port = device.port,
                                timeoutSeconds = 5,
                                rgb = buf,
                                ledCount = leds,
                                colorOrder = device.calibration.colorOrder
                            )

                            liveFrameListener?.onDeviceFrameProcessed(device.id, buf, leds)
                        }
                    }
                    lastSendTime = now
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing frame", e)
                } finally {
                    image.close()
                }
            }, backgroundHandler)

            // Start heartbeat keepalive to maintain ambient lighting on static/paused screens
            backgroundHandler?.postDelayed(keepaliveRunnable, 500L)

            Log.i(TAG, "Ambient multi-device capture started (${config.enabledDevices.size} devices @ ${config.calibration.fps} FPS)")
        } catch (e: Exception) {
            Log.e(TAG, "Exception in initCapture", e)
            stopCapture()
            stopSelf()
        }
    }

    private fun handleScreenOff() {
        if (isScreenOff.compareAndSet(false, true)) {
            Log.i(TAG, "Screen sleep / standby / daydream detected - turning off WLED lights")
            blackoutAndPowerOffLeds()
        }
    }

    private fun handleScreenOn() {
        if (isScreenOff.compareAndSet(true, false)) {
            Log.i(TAG, "Screen wake / active detected - resuming ambient lighting")
            ensureWledAwake()
            lastSendTime = 0L
            lastFrameTime = 0L
            backgroundHandler?.removeCallbacks(keepaliveRunnable)
            backgroundHandler?.post(keepaliveRunnable)
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
                        udpSender.sendDrgbFrame(
                            ip = device.ip,
                            port = device.port,
                            timeoutSeconds = 1,
                            rgb = blackFrame,
                            ledCount = leds,
                            colorOrder = device.calibration.colorOrder
                        )
                        delay(40L)
                    }
                }
                // Clear frame buffer so stale frames are never re-transmitted
                deviceBuffers[device.id]?.fill(0)
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

    private fun stopCapture() {
        if (!isCapturing.getAndSet(false)) return

        isRunning = false
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
        udpSender.close()
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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WLED TV Ambient Active")
            .setContentText("Streaming ambient bias lighting to ${config.enabledDevices.size} lights")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pendingIntent)
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
        stopCapture()
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
        var isTestingOverride: Boolean = false

        var currentServiceInstance: AmbientCaptureService? = null
        var liveFrameListener: LiveFrameListener? = null
        var stateListener: ServiceStateListener? = null
    }
}
