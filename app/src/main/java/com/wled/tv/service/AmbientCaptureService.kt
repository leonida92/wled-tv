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
import android.graphics.RectF
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
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.wled.tv.R
import com.wled.tv.data.PreferencesRepository
import com.wled.tv.model.WledConfig
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
    private var connectivityManager: ConnectivityManager? = null

    private val colorProcessor = ScreenColorProcessor()
    private val udpSender = WledUdpSender()
    private val httpClient = WledHttpClient()
    private val serviceScope = CoroutineScope(Dispatchers.IO)

    private var ledZones: List<RectF> = emptyList()
    private var outputRgbBuffer: ByteArray = ByteArray(0)
    private var isCapturing = AtomicBoolean(false)
    private var isScreenOff = AtomicBoolean(false)
    private var lastFrameTime = 0L
    private var lastSendTime = 0L

    private val keepaliveRunnable = object : Runnable {
        override fun run() {
            if (!isCapturing.get()) return

            val now = System.currentTimeMillis()
            // Re-send current frame every 500ms to maintain WLED realtime connection
            if (now - lastSendTime >= 500L && !isTestingOverride) {
                val currentZones = ledZones
                val currentLeds = currentZones.size
                if (currentLeds > 0 && outputRgbBuffer.size >= currentLeds * 3) {
                    udpSender.sendDrgbFrame(
                        ip = config.ip,
                        port = config.port,
                        timeoutSeconds = 5,
                        rgb = outputRgbBuffer,
                        ledCount = currentLeds,
                        colorOrder = config.calibration.colorOrder
                    )
                    lastSendTime = now
                }
            }

            backgroundHandler?.postDelayed(this, 500L)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF, Intent.ACTION_SHUTDOWN -> {
                    Log.i(TAG, "Screen turned OFF / Standby detected")
                    isScreenOff.set(true)
                    blackoutLeds()
                }
                Intent.ACTION_SCREEN_ON -> {
                    Log.i(TAG, "Screen turned ON - resuming ambient lighting")
                    isScreenOff.set(false)
                    wakeAndResume()
                }
            }
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.i(TAG, "Network interface connected / available")
            if (isCapturing.get()) {
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

        // Acquire Partial WakeLock to ensure TV standby does not kill foreground service
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WLED_TV:AmbientWakeLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WakeLock", e)
        }

        try {
            connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder().build()
            connectivityManager?.registerNetworkCallback(request, networkCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SHUTDOWN)
        }
        registerReceiver(screenReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_STOP) {
            stopCapture()
            stopSelf()
            return START_NOT_STICKY
        }

        if (action == ACTION_RELOAD_CONFIG) {
            reloadConfig()
            return START_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode != 0 && resultData != null) {
            config = prefsRepo.loadConfig()
            startForegroundServiceWithNotification()
            initCapture(resultCode, resultData)
        } else {
            stopSelf()
        }

        return START_STICKY
    }

    fun reloadConfig() {
        try {
            config = prefsRepo.loadConfig()
            ledZones = config.perimeter.computeLedZones()
            val totalLeds = ledZones.size
            if (outputRgbBuffer.size != totalLeds * 3) {
                outputRgbBuffer = ByteArray(totalLeds * 3)
            }
            Log.i(TAG, "Live config reloaded ($totalLeds LEDs @ ${config.calibration.fps} FPS, IP=${config.ip})")
        } catch (e: Exception) {
            Log.e(TAG, "Error reloading config live", e)
        }
    }

    private fun startForegroundServiceWithNotification() {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun initCapture(resultCode: Int, resultData: Intent) {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        if (mediaProjection == null) {
            Log.e(TAG, "Failed to obtain MediaProjection instance")
            stopSelf()
            return
        }

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "MediaProjection session stopped by system")
                stopCapture()
                stopSelf()
            }
        }, null)

        ledZones = config.perimeter.computeLedZones()
        val totalLeds = ledZones.size
        outputRgbBuffer = ByteArray(totalLeds * 3)

        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val captureWidth = 320
        val captureHeight = 180
        val densityDpi = metrics.densityDpi

        backgroundThread = HandlerThread("WledAmbientThread", android.os.Process.THREAD_PRIORITY_DISPLAY).apply {
            start()
        }
        backgroundHandler = Handler(backgroundThread!!.looper)

        imageReader = ImageReader.newInstance(
            captureWidth,
            captureHeight,
            PixelFormat.RGBA_8888,
            3
        )

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "WledTvVirtualDisplay",
            captureWidth,
            captureHeight,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            backgroundHandler
        )

        isCapturing.set(true)
        isRunning = true
        stateListener?.onStateChanged(true)

        // Wake WLED controller and set master brightness
        ensureWledAwake()

        imageReader?.setOnImageAvailableListener({ reader ->
            if (!isCapturing.get()) return@setOnImageAvailableListener

            val image = try {
                reader.acquireLatestImage()
            } catch (e: Exception) {
                null
            } ?: return@setOnImageAvailableListener

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
                val currentZones = ledZones
                val currentLeds = currentZones.size
                if (colorProcessor.processImage(
                        image = image,
                        zones = currentZones,
                        calibration = config.calibration,
                        perimeterConfig = config.perimeter,
                        outputRgb = outputRgbBuffer
                    )
                ) {
                    // Send UDP DRGB realtime frame to WLED
                    udpSender.sendDrgbFrame(
                        ip = config.ip,
                        port = config.port,
                        timeoutSeconds = 5,
                        rgb = outputRgbBuffer,
                        ledCount = currentLeds,
                        colorOrder = config.calibration.colorOrder
                    )
                    lastSendTime = now

                    // Notify live preview listener if open
                    liveFrameListener?.onFrameProcessed(outputRgbBuffer, currentLeds)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing frame", e)
            } finally {
                image.close()
            }
        }, backgroundHandler)

        // Start heartbeat keepalive to maintain ambient lighting on static/paused screens
        backgroundHandler?.postDelayed(keepaliveRunnable, 500L)

        Log.i(TAG, "Ambient screen capture started ($totalLeds LEDs @ ${config.calibration.fps} FPS)")
    }

    private fun blackoutLeds() {
        serviceScope.launch {
            val totalLeds = ledZones.size
            if (totalLeds > 0) {
                val blackFrame = ByteArray(totalLeds * 3)
                udpSender.sendDrgbFrame(
                    ip = config.ip,
                    port = config.port,
                    timeoutSeconds = 5,
                    rgb = blackFrame,
                    ledCount = totalLeds,
                    colorOrder = config.calibration.colorOrder
                )
            }
        }
    }

    private fun ensureWledAwake() {
        serviceScope.launch {
            for (attempt in 1..5) {
                val success = httpClient.wakeAndSetBrightness(config.ip, config.calibration.maxBrightness)
                if (success) break
                delay(1000L)
            }
        }
    }

    private fun wakeAndResume() {
        ensureWledAwake()
        lastSendTime = 0L
        lastFrameTime = 0L
        backgroundHandler?.removeCallbacks(keepaliveRunnable)
        backgroundHandler?.post(keepaliveRunnable)
    }

    private fun stopCapture() {
        isCapturing.set(false)
        isRunning = false
        stateListener?.onStateChanged(false)
        colorProcessor.reset()

        try {
            backgroundHandler?.removeCallbacks(keepaliveRunnable)
        } catch (e: Exception) {
            // ignore
        }

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

        blackoutLeds()
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
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text, config.ip))
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_power, getString(R.string.action_stop), stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: Exception) {
            // ignore
        }
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
            connectivityManager = null
        } catch (e: Exception) {
            // ignore
        }
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            wakeLock = null
        } catch (e: Exception) {
            // ignore
        }
        stopCapture()
        if (currentServiceInstance == this) {
            currentServiceInstance = null
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    interface LiveFrameListener {
        fun onFrameProcessed(rgb: ByteArray, count: Int)
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
