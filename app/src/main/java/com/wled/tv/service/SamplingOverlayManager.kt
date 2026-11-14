package com.wled.tv.service

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import com.wled.tv.engine.AmbientStreamingEngine
import com.wled.tv.ui.views.SamplingOverlayView
import kotlinx.coroutines.flow.StateFlow

/**
 * Manages the floating [SamplingOverlayView] lifecycle, WindowManager attachment,
 * and system metrics sampling cadence.
 */
class SamplingOverlayManager(
    private val context: Context,
    private val activeEngineFlow: StateFlow<AmbientStreamingEngine?>,
    private val onVisibilityChanged: ((Boolean) -> Unit)? = null
) {
    private var windowManager: WindowManager? = null
    private var overlayView: SamplingOverlayView? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val systemStatsCollector by lazy { SystemStatsCollector(context.applicationContext) }

    @Volatile
    var isOverlayVisible: Boolean = false
        private set

    private var lastSystemStatsSampleTime = 0L
    private var latestSystemStatsString = ""
    private var lastOverlayUpdateTime = 0L
    private var lastOverlayTopCrop = -1f
    private var lastOverlayLeftCrop = -1f

    /**
     * Attaches the floating overlay view to the [WindowManager].
     */
    fun show() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Cannot show overlay: SYSTEM_ALERT_WINDOW permission not granted")
            mainHandler.post {
                Toast.makeText(
                    context,
                    "WLED TV: 'Display over other apps' permission required. Enable in Settings or via ADB.",
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }

        mainHandler.post {
            if (isOverlayVisible || overlayView != null) return@post
            val wm = windowManager ?: (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager).also {
                windowManager = it
            } ?: return@post

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
                val view = SamplingOverlayView(context)
                view.bindEngine(activeEngineFlow)
                wm.addView(view, params)
                overlayView = view
                isOverlayVisible = true
                onVisibilityChanged?.invoke(true)
                Log.i(TAG, "Sampling overlay attached to WindowManager")

                lastSystemStatsSampleTime = System.currentTimeMillis()
                latestSystemStatsString = systemStatsCollector.sample()
                update(activeEngineFlow.value)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add overlay view to WindowManager", e)
            }
        }
    }

    /**
     * Removes the floating overlay view from the [WindowManager].
     */
    fun hide() {
        mainHandler.post {
            val view = overlayView ?: return@post
            val wm = windowManager ?: (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
            try {
                wm?.removeView(view)
                Log.i(TAG, "Sampling overlay removed from WindowManager")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove overlay view", e)
            } finally {
                overlayView = null
                isOverlayVisible = false
                onVisibilityChanged?.invoke(false)

                systemStatsCollector.reset()
                lastSystemStatsSampleTime = 0L
                latestSystemStatsString = ""
                lastOverlayTopCrop = -1f
                lastOverlayLeftCrop = -1f
            }
        }
    }

    /**
     * Toggles overlay view between visible and hidden states.
     */
    fun toggle() {
        if (isOverlayVisible) {
            hide()
        } else {
            show()
        }
    }

    /**
     * Samples system metrics on a 1-second cadence and binds engine overlay snapshots
     * to the active [SamplingOverlayView] if crop values changed or 250ms elapsed.
     */
    fun update(engine: AmbientStreamingEngine? = activeEngineFlow.value) {
        val view = overlayView ?: return
        val currentEngine = engine ?: return
        if (!isOverlayVisible) return

        val now = System.currentTimeMillis()
        if (now - lastSystemStatsSampleTime >= 1000L) {
            lastSystemStatsSampleTime = now
            latestSystemStatsString = systemStatsCollector.sample()
        }

        val snapshot = currentEngine.getOverlaySnapshot()
        val currentCrop = snapshot.topCrop
        val currentLeft = snapshot.leftCrop
        if (currentCrop != lastOverlayTopCrop || currentLeft != lastOverlayLeftCrop || now - lastOverlayUpdateTime >= 250L) {
            lastOverlayUpdateTime = now
            lastOverlayTopCrop = currentCrop
            lastOverlayLeftCrop = currentLeft
            view.updateFromEngine(currentEngine, latestSystemStatsString)
        }
    }

    /**
     * Releases overlay resources and detaches view.
     */
    fun release() {
        hide()
    }

    companion object {
        private const val TAG = "SamplingOverlayManager"
    }
}
