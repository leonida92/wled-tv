package com.wled.tv.data

import android.content.Context
import com.wled.tv.model.WledConfig

/**
 * Legacy configuration repository maintaining backward compatibility for existing callers.
 * Delegates all reads and mutations directly to [ConfigRepository].
 */
class PreferencesRepository(context: Context) {

    private val configRepository = ConfigRepository.getInstance(context)

    fun loadConfig(): WledConfig = configRepository.currentConfig

    fun saveConfig(config: WledConfig) {
        configRepository.updateConfigBlocking { config }
    }
}
