package com.wled.tv

import android.app.Application
import com.wled.tv.data.ConfigRepository

class WledTvApplication : Application() {
    val configRepository: ConfigRepository by lazy {
        ConfigRepository.getInstance(this)
    }

    override fun onCreate() {
        super.onCreate()
        // Eagerly initialize configuration repository
        configRepository
    }
}
