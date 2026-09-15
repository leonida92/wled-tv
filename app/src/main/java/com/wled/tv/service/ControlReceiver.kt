package com.wled.tv.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.wled.tv.ui.MainActivity

class ControlReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "Received control broadcast action: $action")

        when (action) {
            ACTION_START -> {
                if (AmbientCaptureService.isRunning) {
                    Log.i(TAG, "Ambient capture is already running")
                } else {
                    Log.i(TAG, "Starting ambient capture via MainActivity")
                    val launchIntent = Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        putExtra(MainActivity.EXTRA_AUTO_START_TRIGGERED, true)
                    }
                    context.startActivity(launchIntent)
                }
            }
            ACTION_STOP -> {
                if (AmbientCaptureService.isRunning) {
                    Log.i(TAG, "Stopping ambient capture service")
                    val stopIntent = Intent(context, AmbientCaptureService::class.java).apply {
                        this.action = AmbientCaptureService.ACTION_STOP
                    }
                    context.startService(stopIntent)
                } else {
                    Log.i(TAG, "Ambient capture is not running")
                }
            }
            ACTION_TOGGLE -> {
                if (AmbientCaptureService.isRunning) {
                    Log.i(TAG, "Toggling off: stopping ambient capture service")
                    val stopIntent = Intent(context, AmbientCaptureService::class.java).apply {
                        this.action = AmbientCaptureService.ACTION_STOP
                    }
                    context.startService(stopIntent)
                } else {
                    Log.i(TAG, "Toggling on: starting ambient capture via MainActivity")
                    val launchIntent = Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        putExtra(MainActivity.EXTRA_AUTO_START_TRIGGERED, true)
                    }
                    context.startActivity(launchIntent)
                }
            }
            ACTION_RELOAD_CONFIG -> {
                if (AmbientCaptureService.isRunning) {
                    Log.i(TAG, "Reloading config in ambient capture service")
                    val reloadIntent = Intent(context, AmbientCaptureService::class.java).apply {
                        this.action = AmbientCaptureService.ACTION_RELOAD_CONFIG
                    }
                    context.startService(reloadIntent)
                }
            }
        }
    }

    companion object {
        private const val TAG = "ControlReceiver"
        const val ACTION_START = "com.wled.tv.ACTION_START"
        const val ACTION_STOP = "com.wled.tv.ACTION_STOP"
        const val ACTION_TOGGLE = "com.wled.tv.ACTION_TOGGLE"
        const val ACTION_RELOAD_CONFIG = "com.wled.tv.ACTION_RELOAD_CONFIG"
    }
}
