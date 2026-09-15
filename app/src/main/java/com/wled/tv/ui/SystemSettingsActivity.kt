package com.wled.tv.ui

import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
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
import com.wled.tv.service.AmbientCaptureService
import com.wled.tv.updater.GitHubUpdateManager
import com.wled.tv.updater.UpdateInfo

class SystemSettingsActivity : AppCompatActivity() {

    private lateinit var prefsRepo: PreferencesRepository
    private var config: WledConfig = WledConfig()
    private val updateManager = GitHubUpdateManager()

    private lateinit var itemScreenZonesSetting: LinearLayout
    private lateinit var itemDeviceManagerSetting: LinearLayout

    private lateinit var itemFpsSetting: LinearLayout
    private lateinit var tvSystemFpsValue: TextView

    private lateinit var itemResolutionSetting: LinearLayout
    private lateinit var tvSystemResolutionValue: TextView

    private lateinit var itemAutoStartSetting: LinearLayout
    private lateinit var tvSystemAutoStartValue: TextView

    private lateinit var itemHomeAssistantSetting: LinearLayout
    private lateinit var ivSystemHaIcon: ImageView
    private lateinit var btnSystemHaToggle: LinearLayout
    private lateinit var tvSystemHaToggle: TextView
    private lateinit var btnSystemHaConfigure: LinearLayout
    private lateinit var tvSystemHaConfigure: TextView

    private lateinit var itemCheckUpdateSetting: LinearLayout
    private lateinit var tvAppVersionSummary: TextView
    private lateinit var tvUpdateActionText: TextView
    private var isCheckingUpdate = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_system_settings)

        prefsRepo = PreferencesRepository(this)
        config = prefsRepo.loadConfig()

        bindViews()
        setupListeners()
        updateUiValues()
    }

    override fun onResume() {
        super.onResume()
        config = prefsRepo.loadConfig()
        updateUiValues()
    }

    private fun bindViews() {
        itemScreenZonesSetting = findViewById(R.id.itemScreenZonesSetting)
        itemDeviceManagerSetting = findViewById(R.id.itemDeviceManagerSetting)

        itemFpsSetting = findViewById(R.id.itemFpsSetting)
        tvSystemFpsValue = findViewById(R.id.tvSystemFpsValue)

        itemResolutionSetting = findViewById(R.id.itemResolutionSetting)
        tvSystemResolutionValue = findViewById(R.id.tvSystemResolutionValue)

        itemAutoStartSetting = findViewById(R.id.itemAutoStartSetting)
        tvSystemAutoStartValue = findViewById(R.id.tvSystemAutoStartValue)

        itemHomeAssistantSetting = findViewById(R.id.itemHomeAssistantSetting)
        ivSystemHaIcon = findViewById(R.id.ivSystemHaIcon)
        btnSystemHaToggle = findViewById(R.id.btnSystemHaToggle)
        tvSystemHaToggle = findViewById(R.id.tvSystemHaToggle)
        btnSystemHaConfigure = findViewById(R.id.btnSystemHaConfigure)
        tvSystemHaConfigure = findViewById(R.id.tvSystemHaConfigure)

        itemCheckUpdateSetting = findViewById(R.id.itemCheckUpdateSetting)
        tvAppVersionSummary = findViewById(R.id.tvAppVersionSummary)
        tvUpdateActionText = findViewById(R.id.tvUpdateActionText)

        itemScreenZonesSetting.requestFocus()
    }

    private fun setupListeners() {
        itemScreenZonesSetting.setOnClickListener {
            startActivity(Intent(this, ZoneEditorActivity::class.java))
        }

        itemDeviceManagerSetting.setOnClickListener {
            startActivity(Intent(this, DeviceManagerActivity::class.java))
        }

        itemFpsSetting.setOnClickListener { cycleFps() }
        itemFpsSetting.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                cycleFps()
                true
            } else false
        }

        itemResolutionSetting.setOnClickListener { cycleResolution() }
        itemResolutionSetting.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                cycleResolution()
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

        btnSystemHaToggle.setOnClickListener { toggleHomeAssistant() }
        btnSystemHaConfigure.setOnClickListener {
            startActivity(Intent(this, HomeAssistantSettingsActivity::class.java))
        }
        itemHomeAssistantSetting.setOnClickListener {
            startActivity(Intent(this, HomeAssistantSettingsActivity::class.java))
        }

        itemCheckUpdateSetting.setOnClickListener { performUpdateCheck() }
    }

    private fun toggleHomeAssistant() {
        val nextEnabled = !config.homeAssistant.enabled
        config = config.copy(homeAssistant = config.homeAssistant.copy(enabled = nextEnabled))
        saveAndUpdate()
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

    private fun cycleResolution() {
        val (nextWidth, nextHeight) = when (config.calibration.captureWidth) {
            160 -> Pair(320, 180)
            320 -> Pair(480, 270)
            else -> Pair(160, 90)
        }
        config = config.copy(
            calibration = config.calibration.copy(
                captureWidth = nextWidth,
                captureHeight = nextHeight
            )
        )
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
        tvSystemResolutionValue.text = config.calibration.resolutionLabel
        tvAppVersionSummary.text = "Current Version: v${BuildConfig.VERSION_NAME}"

        if (config.autoStartOnBoot) {
            tvSystemAutoStartValue.text = "Enabled"
            tvSystemAutoStartValue.setTextColor(Color.parseColor("#00E676"))
        } else {
            tvSystemAutoStartValue.text = "Disabled"
            tvSystemAutoStartValue.setTextColor(Color.parseColor("#94A3B8"))
        }

        if (config.homeAssistant.enabled) {
            val count = config.homeAssistant.enabledLights.size
            tvSystemHaToggle.text = if (count > 0) "Enabled ($count)" else "Enabled"
            tvSystemHaToggle.setTextColor(Color.parseColor("#00E676"))
            ivSystemHaIcon.setColorFilter(Color.parseColor("#00E676"))
        } else {
            tvSystemHaToggle.text = "Disabled"
            tvSystemHaToggle.setTextColor(Color.parseColor("#94A3B8"))
            ivSystemHaIcon.setColorFilter(Color.parseColor("#94A3B8"))
        }
    }


    private fun performUpdateCheck() {
        if (isCheckingUpdate) return
        isCheckingUpdate = true
        tvUpdateActionText.text = "Checking..."

        lifecycleScope.launch {
            val updateInfo = updateManager.checkForUpdates()
            isCheckingUpdate = false

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
                itemCheckUpdateSetting.requestFocus()
            }
        }
    }

    private fun showUpdateAvailableDialog(updateInfo: UpdateInfo) {
        val notes = if (updateInfo.releaseNotes.isNotBlank()) {
            "\n\nRelease Notes:\n${updateInfo.releaseNotes}"
        } else ""

        val apkDetail = if (updateInfo.apkName.isNotBlank()) {
            "\nPackage: ${updateInfo.apkName}"
        } else ""

        AlertDialog.Builder(this)
            .setTitle("Update Available: v${updateInfo.latestVersion}")
            .setMessage("A new version of WLED TV is available on GitHub.$apkDetail$notes\n\nDownload and install now?")
            .setPositiveButton("Download & Install") { _, _ ->
                startDownloadAndInstall(updateInfo.downloadUrl)
            }
            .setNegativeButton("Later", null)
            .setOnDismissListener {
                itemCheckUpdateSetting.requestFocus()
            }
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
