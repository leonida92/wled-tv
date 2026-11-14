package com.wled.tv

import android.app.Application
import com.wled.tv.data.ConfigRepository
import com.wled.tv.network.DefaultWledControllerClient

class WledTvApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ConfigRepository.getInstance(this)
        DefaultWledControllerClient.getInstance(this)
    }
}
