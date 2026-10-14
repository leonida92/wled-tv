package com.wled.tv.ui

import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
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

    private lateinit var itemOverlaySetting: LinearLayout
    private lateinit var tvOverlaySettingStatus: TextView

    private lateinit var itemAdbPermissionSetting: LinearLayout

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

    private fun bindViews() {
        itemScreenZonesSetting = findViewById(R.id.itemScreenZonesSetting)
        itemDeviceManagerSetting = findViewById(R.id.itemDeviceManagerSetting)

        itemFpsSetting = findViewById(R.id.itemFpsSetting)
        tvSystemFpsValue = findViewById(R.id.tvSystemFpsValue)

        itemResolutionSetting = findViewById(R.id.itemResolutionSetting)
        tvSystemResolutionValue = findViewById(R.id.tvSystemResolutionValue)

        itemAutoStartSetting = findViewById(R.id.itemAutoStartSetting)
        tvSystemAutoStartValue = findViewById(R.id.tvSystemAutoStartValue)

        itemOverlaySetting = findViewById(R.id.itemOverlaySetting)
        tvOverlaySettingStatus = findViewById(R.id.tvOverlaySettingStatus)

        itemAdbPermissionSetting = findViewById(R.id.itemAdbPermissionSetting)

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

        itemOverlaySetting.setOnClickListener {
            showOverlayGuideDialog()
        }

        itemAdbPermissionSetting.setOnClickListener {
            showAdbPermissionDialog()
        }

        itemCheckUpdateSetting.setOnClickListener { performUpdateCheck() }
    }

    private fun showOverlayGuideDialog() {
        val hasPerm = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        val permStatus = if (hasPerm) "Permission Granted" else "Permission Required"

        val msg = """
Status: $permStatus

To toggle the real-time sampling overlay while playing video or using other apps, configure your TV button remapper to send this broadcast intent:

Action:
com.wled.tv.ACTION_TOGGLE_OVERLAY

Package:
com.wled.tv

ADB Shell Command:
adb shell am broadcast -a com.wled.tv.ACTION_TOGGLE_OVERLAY -p com.wled.tv

Grant Permission via ADB:
adb shell appops set com.wled.tv SYSTEM_ALERT_WINDOW allow
        """.trimIndent()

        val builder = AlertDialog.Builder(this)
            .setTitle("Real-Time Sampling Overlay")
            .setMessage(msg)
            .setPositiveButton("Close", null)

        if (!hasPerm && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            builder.setNeutralButton("Open Settings") { _, _ ->
                try {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Settings screen not found. Use ADB command above.", Toast.LENGTH_LONG).show()
                }
            }
        }

        builder.show()
    }

    private fun showAdbPermissionDialog() {
        val msg = """
Android TV requires user consent every time screen projection starts.

To permanently grant screen capture permission and enable 100% silent auto-start on TV boot (no remote dialogs), run this command once via ADB:

adb shell appops set com.wled.tv PROJECT_MEDIA allow

This setting persists across reboots. To revoke later:
adb shell appops set com.wled.tv PROJECT_MEDIA default
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("Silent Boot Auto-Start (ADB)")
            .setMessage(msg)
            .setPositiveButton("Close", null)
            .show()
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
            80 -> Pair(160, 90)
            160 -> Pair(320, 180)
            320 -> Pair(480, 270)
            else -> Pair(80, 45)
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

        val hasPerm = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        tvOverlaySettingStatus.text = if (hasPerm) "Ready" else "Action Needed"
        tvOverlaySettingStatus.setTextColor(Color.parseColor(if (hasPerm) "#00E5FF" else "#F59E0B"))
    }

    override fun onResume() {
        super.onResume()
        updateUiValues()
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
