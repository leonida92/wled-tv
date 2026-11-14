package com.wled.tv.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wled.tv.R
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.wled.tv.data.ConfigRepository
import com.wled.tv.data.FleetCoordinator
import com.wled.tv.data.WledFleetCoordinator
import com.wled.tv.engine.AmbientStreamingEngine
import com.wled.tv.engine.DiagnosticStreamCoordinator
import com.wled.tv.model.DeviceThroughputStats
import com.wled.tv.model.DeviceType
import com.wled.tv.model.PresetProfile
import com.wled.tv.model.ThroughputStats
import com.wled.tv.model.WledConfig
import com.wled.tv.model.WledDevice
import com.wled.tv.network.DefaultWledControllerClient
import com.wled.tv.service.AmbientCaptureService
import com.wled.tv.ui.views.PerformanceHistoryChartView
import com.wled.tv.ui.views.TvPerimeterPreviewView
import kotlinx.coroutines.launch
import java.util.Locale


class MainActivity : AppCompatActivity(),
    AmbientStreamingEngine.LiveFrameListener,
    AmbientStreamingEngine.ThroughputListener {

    private lateinit var configRepo: ConfigRepository
    private lateinit var fleetCoordinator: FleetCoordinator
    private var activeEngine: AmbientStreamingEngine? = null
    private var config: WledConfig = WledConfig()

    private val diagnosticCoordinator: DiagnosticStreamCoordinator = DiagnosticStreamCoordinator(
        engineProvider = { AmbientCaptureService.activeEngine.value }
    )
    private val previewBufferLock = Any()
    private val previewStagingBuffers = HashMap<String, ByteArray>()
    private val previewStagingCounts = HashMap<String, Int>()


    private lateinit var btnPower: FrameLayout
    private lateinit var tvPowerText: TextView
    private lateinit var ivPowerIcon: ImageView
    private lateinit var viewStatusDot: View
    private lateinit var tvStatusTitle: TextView

    private lateinit var btnThroughputPill: LinearLayout
    private lateinit var viewThroughputDot: View
    private lateinit var tvThroughputText: TextView

    private lateinit var btnPresetPill: LinearLayout
    private lateinit var tvPresetText: TextView

    private lateinit var btnToggleOverlay: LinearLayout
    private lateinit var tvToggleOverlayText: TextView
    private lateinit var btnTestStrip: LinearLayout
    private lateinit var btnSystemSettings: LinearLayout
    private lateinit var tvPreviewView: TvPerimeterPreviewView
    private lateinit var layoutDeviceDock: LinearLayout

    private var throughputDialog: androidx.appcompat.app.AlertDialog? = null
    private var currentDialogView: View? = null
    private var selectedWindowSeconds = 300

    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                startAmbientService(result.resultCode, result.data!!)
            } else {
                Toast.makeText(this, "Screen capture permission is required for ambient lighting", Toast.LENGTH_LONG).show()
                updateUiState()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        configRepo = ConfigRepository.getInstance(this)
        fleetCoordinator = WledFleetCoordinator(
            configRepo = configRepo,
            controllerClient = DefaultWledControllerClient.getInstance(this)
        )
        config = configRepo.currentConfig
        diagnosticCoordinator.liveFrameListener = this
        diagnosticCoordinator.updateConfig(config)

        bindViews()
        setupListeners()
        renderDeviceDock()
        updateUiState()
        updatePresetPill()
        setupObservers()

        if (intent.getBooleanExtra("EXTRA_AUTO_START_TRIGGERED", false) && !AmbientCaptureService.isRunning.value) {
            intent.removeExtra("EXTRA_AUTO_START_TRIGGERED")
            requestScreenCapture()
        }
    }

    private fun setupObservers() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                fleetCoordinator.startReachabilityPolling(this)
                launch {
                    configRepo.configFlow.collect { newConfig ->
                        config = newConfig
                        diagnosticCoordinator.updateConfig(newConfig)
                        renderDeviceDock()
                        tvPreviewView.updateDevices(newConfig.devices)
                        updateUiState()
                        updatePresetPill()
                    }
                }
                launch {
                    fleetCoordinator.reachabilityFlow.collect { reachability ->
                        for (dev in config.devices) {
                            updateDeviceDockDot(dev.id, dev.enabled, reachability[dev.ip.trim()])
                        }
                        updateUiState()
                    }
                }
                launch {
                    AmbientCaptureService.isRunning.collect {
                        updateUiState()
                    }
                }
                launch {
                    AmbientCaptureService.isOverlayShowing.collect {
                        updateOverlayButtonState()
                    }
                }
                launch {
                    AmbientCaptureService.activeEngine.collect { engine ->
                        activeEngine?.liveFrameListener = null
                        activeEngine?.throughputListener = null
                        activeEngine = engine

                        if (engine != null) {
                            tvPreviewView.setLiveActive(true)
                            engine.liveFrameListener = this@MainActivity
                            engine.throughputListener = this@MainActivity
                            btnThroughputPill.visibility = View.VISIBLE
                            engine.latestStats?.let { updateThroughputUi(it) }
                        } else {
                            tvPreviewView.setLiveActive(false)
                            tvPreviewView.clearLiveColors()
                            btnThroughputPill.visibility = View.GONE
                        }
                        updateUiState()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("EXTRA_AUTO_START_TRIGGERED", false) && !AmbientCaptureService.isRunning.value) {
            intent.removeExtra("EXTRA_AUTO_START_TRIGGERED")
            requestScreenCapture()
        }
    }

    override fun onResume() {
        super.onResume()
        config = configRepo.currentConfig
        diagnosticCoordinator.updateConfig(config)
        renderDeviceDock()
        tvPreviewView.updateDevices(config.devices)
        updatePresetPill()
        val currentEngine = activeEngine ?: AmbientCaptureService.activeEngine.value
        if (currentEngine != null || AmbientCaptureService.isRunning.value) {
            tvPreviewView.setLiveActive(true)
            currentEngine?.liveFrameListener = this
            currentEngine?.throughputListener = this
            btnThroughputPill.visibility = View.VISIBLE
            currentEngine?.latestStats?.let { updateThroughputUi(it) }
        } else {
            tvPreviewView.setLiveActive(diagnosticCoordinator.isRunning)
            if (!diagnosticCoordinator.isRunning) {
                tvPreviewView.clearLiveColors()
            }
            currentEngine?.liveFrameListener = null
            currentEngine?.throughputListener = null
            btnThroughputPill.visibility = View.GONE
        }
        updateUiState()
    }

    override fun onPause() {
        super.onPause()
        throughputDialog?.dismiss()
        throughputDialog = null
        currentDialogView = null
        activeEngine?.liveFrameListener = null
        activeEngine?.throughputListener = null
        if (diagnosticCoordinator.isRunning) {
            stopTestPattern()
        }
    }

    override fun onDestroy() {
        super.onDestroy()

    }

    private fun bindViews() {
        btnPower = findViewById(R.id.btnPower)
        tvPowerText = findViewById(R.id.tvPowerText)
        ivPowerIcon = findViewById(R.id.ivPowerIcon)
        viewStatusDot = findViewById(R.id.viewStatusDot)
        tvStatusTitle = findViewById(R.id.tvStatusTitle)

        btnThroughputPill = findViewById(R.id.btnThroughputPill)
        viewThroughputDot = findViewById(R.id.viewThroughputDot)
        tvThroughputText = findViewById(R.id.tvThroughputText)

        btnPresetPill = findViewById(R.id.btnPresetPill)
        tvPresetText = findViewById(R.id.tvPresetText)

        btnToggleOverlay = findViewById(R.id.btnToggleOverlay)
        tvToggleOverlayText = findViewById(R.id.tvToggleOverlayText)
        btnTestStrip = findViewById(R.id.btnTestStrip)
        btnSystemSettings = findViewById(R.id.btnSystemSettings)
        tvPreviewView = findViewById(R.id.tvPreviewView)
        layoutDeviceDock = findViewById(R.id.layoutDeviceDock)

        btnPower.requestFocus()
    }

    private fun setupListeners() {
        btnPower.setOnClickListener {
            if (AmbientCaptureService.isRunning.value) {
                stopAmbientService()
            } else {
                requestScreenCapture()
            }
        }

        btnThroughputPill.setOnClickListener {
            showThroughputDetailsDialog()
        }

        btnPresetPill.setOnClickListener {
            showPresetPickerDialog()
        }

        btnToggleOverlay.setOnClickListener {
            handleToggleOverlay()
        }

        btnTestStrip.setOnClickListener {
            toggleTestPattern()
        }

        btnSystemSettings.setOnClickListener {
            startActivity(Intent(this, SystemSettingsActivity::class.java))
        }
    }

    private fun renderDeviceDock() {
        layoutDeviceDock.removeAllViews()
        val inflater = LayoutInflater.from(this)

        for ((index, device) in config.devices.withIndex()) {
            val card = inflater.inflate(R.layout.item_device_dock_card, layoutDeviceDock, false)

            val ivIcon = card.findViewById<ImageView>(R.id.ivDeviceIcon)
            val tvName = card.findViewById<TextView>(R.id.tvDockDeviceName)
            val dot = card.findViewById<View>(R.id.viewDockStatusDot)

            val iconRes = when (device.type) {
                DeviceType.PERIMETER -> R.drawable.ic_tv
                DeviceType.LEFT_AMBIENT, DeviceType.RIGHT_AMBIENT -> R.drawable.ic_lamp
                DeviceType.TOP_AMBIENT, DeviceType.BOTTOM_AMBIENT -> R.drawable.ic_ceiling
                DeviceType.FULL_SCREEN, DeviceType.CUSTOM_BOX -> R.drawable.ic_bulb
            }
            ivIcon.setImageResource(iconRes)
            tvName.text = device.name

            dot.tag = "dot_${device.id}"

            val reachability = if (::fleetCoordinator.isInitialized) {
                fleetCoordinator.reachabilityFlow.value[device.ip.trim()]
            } else {
                null
            }
            dot.backgroundTintList = reachabilityDotTint(device.enabled, reachability)

            card.setOnFocusChangeListener { view, hasFocus ->
                view.animate()
                    .scaleX(if (hasFocus) 1.04f else 1.0f)
                    .scaleY(if (hasFocus) 1.04f else 1.0f)
                    .setDuration(120)
                    .start()
            }

            card.setOnClickListener {
                toggleDeviceEnabled(device, index)
            }

            card.setOnLongClickListener {
                val intent = Intent(this, EditDeviceActivity::class.java).apply {
                    putExtra(EditDeviceActivity.EXTRA_DEVICE_ID, device.id)
                }
                startActivity(intent)
                true
            }

            layoutDeviceDock.addView(card)
        }

        // Add subtle circular '+' accessory button at the end
        val addBtn = inflater.inflate(R.layout.item_add_button, layoutDeviceDock, false)
        addBtn.setOnFocusChangeListener { view, hasFocus ->
            view.animate()
                .scaleX(if (hasFocus) 1.08f else 1.0f)
                .scaleY(if (hasFocus) 1.08f else 1.0f)
                .setDuration(120)
                .start()
        }
        addBtn.setOnClickListener {
            showAddDevicePicker()
        }
        layoutDeviceDock.addView(addBtn)
    }

    private fun updateDeviceDockDot(deviceId: String, enabled: Boolean, reachable: Boolean?) {
        val dot = layoutDeviceDock.findViewWithTag<View>("dot_$deviceId") ?: return
        dot.backgroundTintList = reachabilityDotTint(enabled, reachable)
    }

    private fun toggleDeviceEnabled(device: WledDevice, @Suppress("UNUSED_PARAMETER") position: Int) {
        val streamingActive = activeEngine != null || AmbientCaptureService.isRunning.value
        lifecycleScope.launch {
            fleetCoordinator.toggleDeviceEnabled(device.id, streamingActive)
            updateUiState()
        }
    }

    private fun showAddDevicePicker() {
        val types = DeviceType.values()
        val typeNames = types.map { it.displayName }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select Light Preset / Role")
            .setItems(typeNames) { _, which ->
                val selectedType = types[which]
                val intent = Intent(this, EditDeviceActivity::class.java).apply {
                    putExtra(EditDeviceActivity.EXTRA_DEVICE_TYPE, selectedType.name)
                }
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun handleToggleOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("Overlay Permission Required")
                .setMessage("To display the sampling box overlay over movies and other apps, Android TV requires 'Display over other apps' permission.\n\nYou can also grant it via ADB:\nadb shell appops set com.wled.tv SYSTEM_ALERT_WINDOW allow")
                .setPositiveButton("Open Settings") { _, _ ->
                    try {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                        startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(this, "Could not open overlay settings. Please grant via ADB.", Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        if (!AmbientCaptureService.isRunning.value) {
            Toast.makeText(this, "Start ambient capture first to view overlay", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(this, AmbientCaptureService::class.java).apply {
            action = AmbientCaptureService.ACTION_TOGGLE_OVERLAY
        }
        startService(intent)
    }

    private fun updateOverlayButtonState() {
        val isShowing = AmbientCaptureService.isOverlayShowing.value
        if (isShowing) {
            tvToggleOverlayText.text = "Hide Overlay"
            btnToggleOverlay.isSelected = true
        } else {
            tvToggleOverlayText.text = "Overlay"
            btnToggleOverlay.isSelected = false
        }
    }

    private fun toggleTestPattern() {
        if (diagnosticCoordinator.isRunning) {
            stopTestPattern()
        } else {
            startTestPattern()
        }
    }

    private fun startTestPattern() {
        tvPreviewView.setLiveActive(true)
        diagnosticCoordinator.liveFrameListener = this
        diagnosticCoordinator.updateConfig(config)
        diagnosticCoordinator.startRainbow(lifecycleScope)
    }

    private fun stopTestPattern() {
        val isRunning = activeEngine != null || AmbientCaptureService.isRunning.value
        if (isRunning) {
            tvPreviewView.setLiveActive(true)
        } else {
            tvPreviewView.setLiveActive(false)
            tvPreviewView.clearLiveColors()
        }

        lifecycleScope.launch {
            diagnosticCoordinator.stopAndBlackout(sendPowerOff = false)
        }
    }

    private fun requestScreenCapture() {
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun startAmbientService(resultCode: Int, data: Intent) {
        tvPreviewView.setLiveActive(true)
        activeEngine?.liveFrameListener = this
        activeEngine?.throughputListener = this
        btnThroughputPill.visibility = View.VISIBLE
        val intent = Intent(this, AmbientCaptureService::class.java).apply {
            putExtra(AmbientCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(AmbientCaptureService.EXTRA_RESULT_DATA, data)
        }
        startForegroundService(intent)
        updateUiState()
    }

    private fun stopAmbientService() {
        activeEngine?.liveFrameListener = null
        activeEngine?.throughputListener = null
        btnThroughputPill.visibility = View.GONE
        tvPreviewView.setLiveActive(false)
        tvPreviewView.clearLiveColors()
        updateUiState()
        val intent = Intent(this, AmbientCaptureService::class.java).apply {
            action = AmbientCaptureService.ACTION_STOP
        }
        startService(intent)
    }

    private fun updateUiState() {
        val running = activeEngine != null || AmbientCaptureService.isRunning.value
        val reachability = if (::fleetCoordinator.isInitialized) {
            fleetCoordinator.reachabilityFlow.value
        } else {
            emptyMap()
        }
        val pingableActiveCount = config.enabledDevices.count { dev ->
            reachability[dev.ip.trim()] == true
        }
        val totalEnabled = config.enabledDevices.size

        val hasAnyPingResults = config.enabledDevices.any { reachability.containsKey(it.ip.trim()) }

        if (running) {
            btnPower.setBackgroundResource(R.drawable.bg_power_button_on)
            tvPowerText.text = getString(R.string.btn_stop_mirror)
            tvPowerText.setTextColor(Color.parseColor("#F87171"))
            ivPowerIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))

            val isDrm = activeEngine?.isDrmActive == true
            if (isDrm) {
                viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F59E0B"))
                tvStatusTitle.text = "Widevine L1 DRM Active"
            } else if (pingableActiveCount > 0) {
                viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#00E676"))
                tvStatusTitle.text = if (pingableActiveCount == 1) "Active" else "Active ($pingableActiveCount)"
            } else if (!hasAnyPingResults) {
                viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#00E5FF"))
                tvStatusTitle.text = "Active"
            } else {
                viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))
                tvStatusTitle.text = "No Light Found"
            }
        } else {
            btnPower.setBackgroundResource(R.drawable.bg_power_button_off)
            tvPowerText.text = getString(R.string.btn_start_mirror)
            tvPowerText.setTextColor(Color.parseColor("#F8FAFC"))
            ivPowerIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#F8FAFC"))

            if (totalEnabled == 0) {
                viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#64748B"))
                tvStatusTitle.text = "All Inactive"
            } else if (pingableActiveCount > 0) {
                viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#00E676"))
                tvStatusTitle.text = if (pingableActiveCount == 1) "Ready (1)" else "Ready ($pingableActiveCount)"
            } else if (!hasAnyPingResults) {
                viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#64748B"))
                tvStatusTitle.text = "Checking Lights..."
            } else {
                viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))
                tvStatusTitle.text = "No Light Found"
            }
        }
        updateOverlayButtonState()
    }

    override fun onDeviceFrameProcessed(deviceId: String, rgb: ByteArray, count: Int) {
        val isRunning = activeEngine != null || AmbientCaptureService.isRunning.value
        if ((diagnosticCoordinator.isRunning || isRunning) && tvPreviewView.isLiveActive) {
            val byteLen = (count * 3).coerceAtMost(rgb.size)
            synchronized(previewBufferLock) {
                var buf = previewStagingBuffers[deviceId]
                if (buf == null || buf.size < byteLen) {
                    buf = ByteArray(byteLen)
                    previewStagingBuffers[deviceId] = buf
                }
                System.arraycopy(rgb, 0, buf, 0, byteLen)
                previewStagingCounts[deviceId] = count
            }
            runOnUiThread {
                val activeNow = diagnosticCoordinator.isRunning || activeEngine != null || AmbientCaptureService.isRunning.value
                if (activeNow && tvPreviewView.isLiveActive) {
                    synchronized(previewBufferLock) {
                        val buf = previewStagingBuffers[deviceId]
                        val stagedCount = previewStagingCounts[deviceId] ?: 0
                        if (buf != null && stagedCount > 0) {
                            tvPreviewView.updateDeviceLiveColors(deviceId, buf, stagedCount)
                        }
                    }
                }
            }
        }
    }

    override fun onThroughputStatsUpdated(stats: ThroughputStats) {
        runOnUiThread {
            if (activeEngine != null || AmbientCaptureService.isRunning.value) {
                updateThroughputUi(stats)
                updateUiState()
                val dialogView = currentDialogView
                if (throughputDialog?.isShowing == true && dialogView != null) {
                    refreshDialogTelemetry(dialogView, stats, selectedWindowSeconds)
                }
            }
        }
    }

    private fun updateThroughputUi(stats: ThroughputStats) {
        btnThroughputPill.visibility = View.VISIBLE
        tvThroughputText.text = "${stats.fps} fps • ${stats.pps} pps"
        viewThroughputDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#00E5FF"))
        tvThroughputText.setTextColor(Color.parseColor("#F8FAFC"))
    }

    private fun refreshDialogTelemetry(dialogView: View, stats: ThroughputStats, windowSec: Int) {
        val cardDrmNotice = dialogView.findViewById<View>(R.id.cardDrmNotice)
        val isDrm = activeEngine?.isDrmActive == true
        cardDrmNotice?.visibility = if (isDrm) View.VISIBLE else View.GONE

        val tvFpsValue = dialogView.findViewById<TextView>(R.id.tvDiagFpsValue)
        val tvDiagFpsTarget = dialogView.findViewById<TextView>(R.id.tvDiagFpsTarget)
        val tvPpsValue = dialogView.findViewById<TextView>(R.id.tvDiagPpsValue)
        val tvTotalPackets = dialogView.findViewById<TextView>(R.id.tvDiagTotalPackets)
        val layoutDeviceList = dialogView.findViewById<LinearLayout>(R.id.layoutDiagDeviceList)
        val chart = dialogView.findViewById<PerformanceHistoryChartView>(R.id.chartThroughputHistory)
        val tvChartFpsLegend = dialogView.findViewById<TextView>(R.id.tvChartFpsLegend)
        val tvChartPpsLegend = dialogView.findViewById<TextView>(R.id.tvChartPpsLegend)

        val btnWindow5m = dialogView.findViewById<View>(R.id.btnWindow5m)
        val btnWindow15m = dialogView.findViewById<View>(R.id.btnWindow15m)
        val btnWindow30m = dialogView.findViewById<View>(R.id.btnWindow30m)
        val btnWindow1h = dialogView.findViewById<View>(R.id.btnWindow1h)
        val btnWindow3h = dialogView.findViewById<View>(R.id.btnWindow3h)

        btnWindow5m.isSelected = (windowSec == 300)
        btnWindow15m.isSelected = (windowSec == 900)
        btnWindow30m.isSelected = (windowSec == 1800)
        btnWindow1h.isSelected = (windowSec == 3600)
        btnWindow3h.isSelected = (windowSec == 10800)

        tvFpsValue.text = "${stats.fps} FPS"
        tvDiagFpsTarget.text = "Target: ${stats.targetFps.coerceAtLeast(1)} FPS"

        tvPpsValue.text = "${stats.pps} pkts/s"
        tvTotalPackets.text = String.format(Locale.US, "%,d packets sent this session", stats.totalPackets)

        val history = activeEngine?.getHistorySnapshot(windowSec) ?: emptyList()
        chart.setData(history, windowSec, config.capture.fps, config.enabledDevices.size)

        val summary = chart.getSummaryStats()
        tvChartFpsLegend.text = "Capture FPS: Avg %.1f • Peak %d".format(summary.avgFps, summary.peakFps)
        tvChartPpsLegend.text = "UDP PPS: Avg %.1f • Peak %d".format(summary.avgPps, summary.peakPps)

        layoutDeviceList.removeAllViews()
        val devices = if (stats.deviceStats.isNotEmpty()) {
            stats.deviceStats
        } else {
            config.enabledDevices.map { dev ->
                DeviceThroughputStats(
                    deviceId = dev.id,
                    deviceName = dev.name,
                    ip = dev.ip,
                    pps = 0,
                    totalPackets = 0L
                )
            }
        }

        if (devices.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "No enabled devices found"
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 12f
                setPadding(0, 6, 0, 6)
            }
            layoutDeviceList.addView(emptyTv)
        } else {
            for (dev in devices) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 4, 0, 4)
                }
                val nameTv = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    text = "${dev.deviceName} (${dev.ip})"
                    setTextColor(Color.parseColor("#F8FAFC"))
                    textSize = 12f
                }
                val rateTv = TextView(this).apply {
                    text = "${dev.pps} pkts/s  (%,d total)".format(dev.totalPackets)
                    setTextColor(Color.parseColor("#38BDF8"))
                    textSize = 12f
                    textAlignment = View.TEXT_ALIGNMENT_VIEW_END
                }
                row.addView(nameTv)
                row.addView(rateTv)
                layoutDeviceList.addView(row)
            }
        }
    }

    private fun showThroughputDetailsDialog() {
        val stats = activeEngine?.latestStats ?: ThroughputStats(
            fps = 0,
            targetFps = config.capture.fps,
            pps = 0,
            totalPackets = 0L,
            deviceStats = emptyList()
        )

        val dialogView = layoutInflater.inflate(R.layout.dialog_throughput_details, null)
        currentDialogView = dialogView

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        throughputDialog = dialog

        val btnClose = dialogView.findViewById<View>(R.id.btnDismissThroughputDialog)
        val btnWindow5m = dialogView.findViewById<View>(R.id.btnWindow5m)
        val btnWindow15m = dialogView.findViewById<View>(R.id.btnWindow15m)
        val btnWindow30m = dialogView.findViewById<View>(R.id.btnWindow30m)
        val btnWindow1h = dialogView.findViewById<View>(R.id.btnWindow1h)
        val btnWindow3h = dialogView.findViewById<View>(R.id.btnWindow3h)

        btnWindow5m.setOnClickListener {
            selectedWindowSeconds = 300
            val cur = activeEngine?.latestStats ?: stats
            refreshDialogTelemetry(dialogView, cur, 300)
        }
        btnWindow15m.setOnClickListener {
            selectedWindowSeconds = 900
            val cur = activeEngine?.latestStats ?: stats
            refreshDialogTelemetry(dialogView, cur, 900)
        }
        btnWindow30m.setOnClickListener {
            selectedWindowSeconds = 1800
            val cur = activeEngine?.latestStats ?: stats
            refreshDialogTelemetry(dialogView, cur, 1800)
        }
        btnWindow1h.setOnClickListener {
            selectedWindowSeconds = 3600
            val cur = activeEngine?.latestStats ?: stats
            refreshDialogTelemetry(dialogView, cur, 3600)
        }
        btnWindow3h.setOnClickListener {
            selectedWindowSeconds = 10800
            val cur = activeEngine?.latestStats ?: stats
            refreshDialogTelemetry(dialogView, cur, 10800)
        }

        refreshDialogTelemetry(dialogView, stats, selectedWindowSeconds)

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            currentDialogView = null
            throughputDialog = null
        }

        dialog.show()
        btnClose.requestFocus()
    }

    private fun updatePresetPill() {
        tvPresetText.text = "Profile: ${config.activeProfile.name}"
    }

    private fun showPresetPickerDialog() {
        val presets = PresetProfile.BUILT_INS.filter { it.id != PresetProfile.CUSTOM.id }
        val names = presets.map { "${it.name} - ${it.description}" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select Profile Preset")
            .setItems(names) { _, which ->
                val selected = presets[which]
                lifecycleScope.launch {
                    configRepo.applyPreset(selected)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
