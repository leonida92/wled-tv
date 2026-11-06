package com.wled.tv

import android.app.Application
import com.wled.tv.data.ConfigRepository
import com.wled.tv.network.DefaultWledControllerClient
import com.wled.tv.network.WledControllerClient

class WledTvApplication : Application() {
    val configRepository: ConfigRepository by lazy {
        ConfigRepository.getInstance(this)
    }

    val controllerClient: WledControllerClient by lazy {
        DefaultWledControllerClient.getInstance()
    }

    override fun onCreate() {
        super.onCreate()
        // Eagerly initialize configuration repository
        configRepository
    }
}
