package com.wled.tv.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class OverlayControlReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "OverlayControlReceiver"
        const val ACTION_TOGGLE_OVERLAY = AmbientCaptureService.ACTION_TOGGLE_OVERLAY
        const val ACTION_SHOW_OVERLAY = AmbientCaptureService.ACTION_SHOW_OVERLAY
        const val ACTION_HIDE_OVERLAY = AmbientCaptureService.ACTION_HIDE_OVERLAY
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(TAG, "Received broadcast intent: $action")

        if (!AmbientCaptureService.isRunning) {
            Log.w(TAG, "Ambient capture is not running, cannot toggle overlay")
            return
        }

        val serviceIntent = Intent(context, AmbientCaptureService::class.java).apply {
            this.action = action
        }
        try {
            context.startService(serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to forward overlay action to AmbientCaptureService", e)
        }
    }
}
