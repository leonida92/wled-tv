package com.wled.tv.ui

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.wled.tv.BuildConfig
import com.wled.tv.R
import com.wled.tv.data.PreferencesRepository
import com.wled.tv.model.WledConfig
import com.wled.tv.network.WledHttpClient
import com.wled.tv.service.AmbientCaptureService
import com.wled.tv.updater.GitHubUpdateManager
import com.wled.tv.updater.UpdateInfo

class SystemSettingsActivity : AppCompatActivity() {

    private lateinit var prefsRepo: PreferencesRepository
    private var config: WledConfig = WledConfig()
    private val httpClient = WledHttpClient()
    private val updateManager = GitHubUpdateManager()

    private lateinit var viewSystemStatusDot: android.view.View
    private lateinit var tvSystemStatusText: TextView

    private lateinit var itemFpsSetting: LinearLayout
    private lateinit var tvSystemFpsValue: TextView

    private lateinit var itemAutoStartSetting: LinearLayout
    private lateinit var tvSystemAutoStartValue: TextView

    private lateinit var itemCheckUpdateSetting: LinearLayout
    private lateinit var tvAppVersionSummary: TextView
    private lateinit var tvUpdateActionText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_system_settings)

        prefsRepo = PreferencesRepository(this)
        config = prefsRepo.loadConfig()

        bindViews()
        setupListeners()
        updateUiValues()
        checkConnection()
    }

    private fun bindViews() {
        viewSystemStatusDot = findViewById(R.id.viewSystemStatusDot)
        tvSystemStatusText = findViewById(R.id.tvSystemStatusText)

        itemFpsSetting = findViewById(R.id.itemFpsSetting)
        tvSystemFpsValue = findViewById(R.id.tvSystemFpsValue)

        itemAutoStartSetting = findViewById(R.id.itemAutoStartSetting)
        tvSystemAutoStartValue = findViewById(R.id.tvSystemAutoStartValue)

        itemCheckUpdateSetting = findViewById(R.id.itemCheckUpdateSetting)
        tvAppVersionSummary = findViewById(R.id.tvAppVersionSummary)
        tvUpdateActionText = findViewById(R.id.tvUpdateActionText)

        itemFpsSetting.requestFocus()
    }

    private fun setupListeners() {
        itemFpsSetting.setOnClickListener { cycleFps() }
        itemFpsSetting.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                cycleFps()
                true
            } else false
        }

        itemAutoStartSetting.setOnClickListener { toggleAutoStart() }
        itemAutoStartSetting.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                toggleAutoStart()
                true
            } else false
        }

        itemCheckUpdateSetting.setOnClickListener { performUpdateCheck() }
    }

    private fun cycleFps() {
        val nextFps = when (config.calibration.fps) {
            15 -> 30
            30 -> 60
            else -> 15
        }
        config = config.copy(calibration = config.calibration.copy(fps = nextFps))
        saveAndUpdate()
    }

    private fun toggleAutoStart() {
        config = config.copy(autoStartOnBoot = !config.autoStartOnBoot)
        saveAndUpdate()
    }

    private fun saveAndUpdate() {
        prefsRepo.saveConfig(config)
        updateUiValues()
        if (AmbientCaptureService.isRunning) {
            AmbientCaptureService.currentServiceInstance?.reloadConfig()
        }
    }

    private fun updateUiValues() {
        tvSystemFpsValue.text = "${config.calibration.fps} FPS"
        tvAppVersionSummary.text = "Current Version: v${BuildConfig.VERSION_NAME}"

        if (config.autoStartOnBoot) {
            tvSystemAutoStartValue.text = "Enabled"
            tvSystemAutoStartValue.setTextColor(Color.parseColor("#00E676"))
        } else {
            tvSystemAutoStartValue.text = "Disabled"
            tvSystemAutoStartValue.setTextColor(Color.parseColor("#94A3B8"))
        }
    }

    private fun checkConnection() {
        viewSystemStatusDot.setBackgroundColor(Color.parseColor("#FBBF24"))
        tvSystemStatusText.text = "Pinging..."

        lifecycleScope.launch(Dispatchers.IO) {
            val connected = httpClient.checkConnection(config.ip)
            launch(Dispatchers.Main) {
                if (connected) {
                    viewSystemStatusDot.setBackgroundColor(Color.parseColor("#00E676"))
                    tvSystemStatusText.text = "Connected (${config.ip})"
                } else {
                    viewSystemStatusDot.setBackgroundColor(Color.parseColor("#F87171"))
                    tvSystemStatusText.text = "Offline / Unreachable"
                }
            }
        }
    }

    private fun performUpdateCheck() {
        tvUpdateActionText.text = "Checking..."
        itemCheckUpdateSetting.isEnabled = false

        lifecycleScope.launch {
            val updateInfo = updateManager.checkForUpdates()
            itemCheckUpdateSetting.isEnabled = true

            if (updateInfo.hasUpdate) {
                tvUpdateActionText.text = "v${updateInfo.latestVersion} Available!"
                showUpdateAvailableDialog(updateInfo)
            } else {
                tvUpdateActionText.text = "Up to Date"
                Toast.makeText(
                    this@SystemSettingsActivity,
                    "You are on the latest version (v${BuildConfig.VERSION_NAME})",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun showUpdateAvailableDialog(updateInfo: UpdateInfo) {
        val notes = if (updateInfo.releaseNotes.isNotBlank()) {
            "\n\nRelease Notes:\n${updateInfo.releaseNotes}"
        } else ""

        AlertDialog.Builder(this)
            .setTitle("Update Available: v${updateInfo.latestVersion}")
            .setMessage("A new version of WLED TV is available on GitHub.$notes\n\nDownload and install now?")
            .setPositiveButton("Download & Install") { _, _ ->
                startDownloadAndInstall(updateInfo.downloadUrl)
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun startDownloadAndInstall(url: String) {
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Downloading Update")
            .setMessage("Downloading APK from GitHub... 0%")
            .setCancelable(false)
            .create()

        progressDialog.show()

        lifecycleScope.launch {
            val success = updateManager.downloadAndInstallApk(
                this@SystemSettingsActivity,
                url
            ) { progress ->
                runOnUiThread {
                    progressDialog.setMessage("Downloading APK from GitHub... $progress%")
                }
            }

            progressDialog.dismiss()
            if (!success) {
                Toast.makeText(
                    this@SystemSettingsActivity,
                    "Failed to download update APK. Please check your network connection.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
