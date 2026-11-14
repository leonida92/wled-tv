package com.wled.tv.service

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.wled.tv.engine.AmbientStreamingEngine
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages the low-level Android screen capture pipeline:
 * [MediaProjection], [VirtualDisplay], [ImageReader], background capture thread,
 * and the frame capture / streaming cadence ticker loop.
 */
class ScreenCaptureSession(
    private val context: Context,
    private val mediaProjection: MediaProjection,
    private val engineProvider: () -> AmbientStreamingEngine?,
    private val isScreenActive: () -> Boolean = { true },
    private val onScreenStandbyDetected: () -> Unit = {},
    private val onStopCallback: () -> Unit = {},
    var onTick: ((AmbientStreamingEngine) -> Unit)? = null,
    initialWidth: Int = 320,
    initialHeight: Int = 180
) {
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private val _isCapturing = AtomicBoolean(false)
    val isCapturing: Boolean
        get() = _isCapturing.get()

    var captureWidth: Int = initialWidth
        private set

    var captureHeight: Int = initialHeight
        private set

    private val streamingTickerRunnable = object : Runnable {
        override fun run() {
            if (!_isCapturing.get()) return

            if (!isScreenActive()) {
                onScreenStandbyDetected()
                backgroundHandler?.postDelayed(this, 1000L)
                return
            }

            val currentEngine = engineProvider()
            if (currentEngine == null) {
                backgroundHandler?.postDelayed(this, 16L)
                return
            }

            val delayMs = currentEngine.tickStreaming()
            try {
                onTick?.invoke(currentEngine)
            } catch (e: Exception) {
                Log.e(TAG, "Error in onTick callback", e)
            }

            backgroundHandler?.postDelayed(this, delayMs)
        }
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "MediaProjection stopped by system")
            onStopCallback()
        }
    }

    /**
     * Initializes the background capture thread, image reader, virtual display,
     * and begins the streaming cadence loop.
     */
    fun start(width: Int = captureWidth, height: Int = captureHeight) {
        if (_isCapturing.get()) return

        captureWidth = width
        captureHeight = height

        try {
            mediaProjection.registerCallback(projectionCallback, null)

            val thread = HandlerThread(
                "WledCaptureThread",
                android.os.Process.THREAD_PRIORITY_BACKGROUND
            ).apply { start() }
            backgroundThread = thread
            val handler = Handler(thread.looper)
            backgroundHandler = handler

            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)

            val reader = ImageReader.newInstance(
                captureWidth,
                captureHeight,
                PixelFormat.RGBA_8888,
                3
            )
            imageReader = reader
            setupImageAvailableListener(reader, handler)

            virtualDisplay = mediaProjection.createVirtualDisplay(
                "WledAmbientDisplay",
                captureWidth,
                captureHeight,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler
            )

            _isCapturing.set(true)
            handler.removeCallbacks(streamingTickerRunnable)
            handler.post(streamingTickerRunnable)

            Log.i(TAG, "Screen capture session started (${captureWidth}x${captureHeight})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start screen capture session", e)
            release()
            onStopCallback()
        }
    }

    private fun setupImageAvailableListener(reader: ImageReader, handler: Handler) {
        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                if (!_isCapturing.get() || !isScreenActive()) {
                    if (!isScreenActive()) {
                        onScreenStandbyDetected()
                    }
                    return@setOnImageAvailableListener
                }

                val planes = image.planes
                if (planes.isNotEmpty()) {
                    val plane = planes[0]
                    val buffer = plane.buffer
                    if (buffer != null) {
                        engineProvider()?.onFrameAvailable(
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
        }, handler)
    }

    /**
     * Dynamically updates the capture resolution by creating a new [ImageReader]
     * and resizing the active [VirtualDisplay].
     */
    fun reinitResolution(width: Int, height: Int) {
        val handler = backgroundHandler ?: return
        handler.post {
            if (!_isCapturing.get()) return@post
            try {
                captureWidth = width
                captureHeight = height
                val oldReader = imageReader

                val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(metrics)

                val newReader = ImageReader.newInstance(
                    width,
                    height,
                    PixelFormat.RGBA_8888,
                    3
                )
                setupImageAvailableListener(newReader, handler)
                imageReader = newReader

                virtualDisplay?.resize(width, height, metrics.densityDpi)
                virtualDisplay?.setSurface(newReader.surface)

                oldReader?.close()
                Log.i(TAG, "Capture resolution updated to ${width}x${height}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reinit capture resolution", e)
            }
        }
    }

    /**
     * Pauses the streaming ticker loop (e.g. when display is sleeping).
     */
    fun pauseStreaming() {
        backgroundHandler?.removeCallbacks(streamingTickerRunnable)
    }

    /**
     * Resumes the streaming ticker loop (e.g. when display wakes up).
     */
    fun resumeStreaming() {
        if (_isCapturing.get()) {
            backgroundHandler?.removeCallbacks(streamingTickerRunnable)
            backgroundHandler?.post(streamingTickerRunnable)
        }
    }

    /**
     * Stops capture and removes queued ticker callbacks.
     */
    fun stop() {
        if (!_isCapturing.getAndSet(false)) return

        try {
            backgroundHandler?.removeCallbacks(streamingTickerRunnable)
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
    }

    /**
     * Shuts down the capture session, stopping the background thread
     * and releasing the [MediaProjection].
     */
    fun release() {
        stop()

        try {
            backgroundThread?.quitSafely()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping background thread", e)
        }

        try {
            mediaProjection.unregisterCallback(projectionCallback)
        } catch (_: Exception) {}

        try {
            mediaProjection.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping MediaProjection", e)
        }
    }

    companion object {
        private const val TAG = "ScreenCaptureSession"
    }
}
