package com.wled.tv.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast

class OverlayControlReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "OverlayControlReceiver"
        const val ACTION_TOGGLE_OVERLAY = "com.wled.tv.ACTION_TOGGLE_OVERLAY"
        const val ACTION_SHOW_OVERLAY = "com.wled.tv.ACTION_SHOW_OVERLAY"
        const val ACTION_HIDE_OVERLAY = "com.wled.tv.ACTION_HIDE_OVERLAY"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(TAG, "Received broadcast intent: $action")

        val service = AmbientCaptureService.currentServiceInstance
        if (service == null || !AmbientCaptureService.isRunning) {
            Log.w(TAG, "Ambient capture is not running, cannot toggle overlay")
            return
        }

        when (action) {
            ACTION_TOGGLE_OVERLAY -> {
                service.toggleSamplingOverlay()
            }
            ACTION_SHOW_OVERLAY -> {
                service.showSamplingOverlay()
            }
            ACTION_HIDE_OVERLAY -> {
                service.hideSamplingOverlay()
            }
        }
    }
}
