package com.wled.tv.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.wled.tv.data.PreferencesRepository
import com.wled.tv.ui.MainActivity

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == "android.intent.action.QUICKBOOT_POWERON") {
            val prefs = PreferencesRepository(context)
            val config = prefs.loadConfig()
            if (config.autoStartOnBoot) {
                Log.i("BootReceiver", "Auto-launching WLED TV on TV boot")
                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("EXTRA_AUTO_START_TRIGGERED", true)
                }
                context.startActivity(launchIntent)
            }
        }
    }
}
