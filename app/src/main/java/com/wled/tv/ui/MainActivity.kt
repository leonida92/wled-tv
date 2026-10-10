package com.wled.tv.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.projection.MediaProjectionManager
import android.os.Bundle
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
import com.wled.tv.data.PreferencesRepository
import com.wled.tv.model.DeviceThroughputStats
import com.wled.tv.model.DeviceType
import com.wled.tv.model.ThroughputStats
import com.wled.tv.model.WledConfig
import com.wled.tv.model.WledDevice
import com.wled.tv.network.DeviceReachabilityCache
import com.wled.tv.network.WledHttpClient
import com.wled.tv.network.WledUdpSender
import com.wled.tv.service.AmbientCaptureService
import com.wled.tv.ui.views.PerformanceHistoryChartView
import com.wled.tv.ui.views.TvPerimeterPreviewView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class MainActivity : AppCompatActivity(),
    AmbientCaptureService.LiveFrameListener,
    AmbientCaptureService.ServiceStateListener,
    AmbientCaptureService.ThroughputListener {

    private lateinit var prefsRepo: PreferencesRepository
    private var config: WledConfig = WledConfig()

    private val httpClient = WledHttpClient()
    private val udpSender = WledUdpSender()

    private lateinit var btnPower: FrameLayout
    private lateinit var tvPowerText: TextView
    private lateinit var ivPowerIcon: ImageView
    private lateinit var viewStatusDot: View
    private lateinit var tvStatusTitle: TextView

    private lateinit var btnThroughputPill: LinearLayout
    private lateinit var viewThroughputDot: View
    private lateinit var tvThroughputText: TextView

    private lateinit var btnTestStrip: LinearLayout
    private lateinit var btnSystemSettings: LinearLayout
    private lateinit var tvPreviewView: TvPerimeterPreviewView
    private lateinit var layoutDeviceDock: LinearLayout

    private var throughputDialog: androidx.appcompat.app.AlertDialog? = null
    private var currentDialogView: View? = null
    private var selectedWindowSeconds = 300

    private var isTesting = false
    private var testPatternJob: Job? = null
    private var pingPollingJob: Job? = null
    private val deviceReachabilityMap get() = DeviceReachabilityCache.map

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

        prefsRepo = PreferencesRepository(this)
        config = prefsRepo.loadConfig()

        bindViews()
        setupListeners()
        renderDeviceDock()
        updateUiState()

        if (intent.getBooleanExtra("EXTRA_AUTO_START_TRIGGERED", false) && !AmbientCaptureService.isRunning) {
            intent.removeExtra("EXTRA_AUTO_START_TRIGGERED")
            requestScreenCapture()
        }
    }

    override fun onResume() {
        super.onResume()
        config = prefsRepo.loadConfig()
        renderDeviceDock()
        tvPreviewView.updateDevices(config.devices)
        if (AmbientCaptureService.isRunning) {
            tvPreviewView.setLiveActive(true)
            AmbientCaptureService.liveFrameListener = this
            AmbientCaptureService.throughputListener = this
            btnThroughputPill.visibility = View.VISIBLE
            AmbientCaptureService.latestStats?.let { updateThroughputUi(it) }
        } else {
            tvPreviewView.setLiveActive(false)
            tvPreviewView.clearLiveColors()
            AmbientCaptureService.liveFrameListener = null
            AmbientCaptureService.throughputListener = null
            btnThroughputPill.visibility = View.GONE
        }
        AmbientCaptureService.stateListener = this
        updateUiState()
        startPingPolling()
    }

    override fun onPause() {
        super.onPause()
        throughputDialog?.dismiss()
        throughputDialog = null
        currentDialogView = null
        AmbientCaptureService.liveFrameListener = null
        AmbientCaptureService.throughputListener = null
        AmbientCaptureService.stateListener = null
        stopTestPattern()
        pingPollingJob?.cancel()
        pingPollingJob = null
    }

    override fun onDestroy() {
        super.onDestroy()
        udpSender.close()
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

        btnTestStrip = findViewById(R.id.btnTestStrip)
        btnSystemSettings = findViewById(R.id.btnSystemSettings)
        tvPreviewView = findViewById(R.id.tvPreviewView)
        layoutDeviceDock = findViewById(R.id.layoutDeviceDock)

        btnPower.requestFocus()
    }

    private fun setupListeners() {
        btnPower.setOnClickListener {
            if (AmbientCaptureService.isRunning) {
                stopAmbientService()
            } else {
                requestScreenCapture()
            }
        }

        btnThroughputPill.setOnClickListener {
            showThroughputDetailsDialog()
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

            if (!device.enabled) {
                dot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#475569"))
            } else {
                when (deviceReachabilityMap[device.id]) {
                    true -> dot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#00E676"))
                    false -> dot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))
                    null -> dot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#64748B"))
                }
            }

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
        if (!enabled) {
            dot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#475569"))
        } else {
            when (reachable) {
                true -> dot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#00E676"))
                false -> dot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))
                null -> dot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#64748B"))
            }
        }
    }

    private fun toggleDeviceEnabled(device: WledDevice, position: Int) {
        val newEnabled = !device.enabled
        val updatedList = config.devices.toMutableList()
        val updatedDevice = device.copy(enabled = newEnabled)
        updatedList[position] = updatedDevice
        config = config.copy(devices = updatedList)
        prefsRepo.saveConfig(config)

        renderDeviceDock()
        tvPreviewView.updateDevices(config.devices)
        updateUiState()

        lifecycleScope.launch(Dispatchers.IO) {
            if (newEnabled) {
                val isReachable = httpClient.checkConnection(updatedDevice.ip)
                deviceReachabilityMap[updatedDevice.id] = isReachable
                withContext(Dispatchers.Main) {
                    updateDeviceDockDot(updatedDevice.id, true, isReachable)
                    updateUiState()
                }
                httpClient.wakeAndSetBrightness(updatedDevice.ip, updatedDevice.calibration.maxBrightness)
            } else {
                val totalLeds = updatedDevice.totalLeds
                if (totalLeds > 0) {
                    val blackBuffer = ByteArray(totalLeds * 3)
                    udpSender.sendDrgbFrame(
                        ip = updatedDevice.ip,
                        port = updatedDevice.port,
                        timeoutSeconds = 2,
                        rgb = blackBuffer,
                        ledCount = totalLeds,
                        colorOrder = updatedDevice.calibration.colorOrder
                    )
                }
                httpClient.turnOff(updatedDevice.ip)
                withContext(Dispatchers.Main) {
                    updateDeviceDockDot(updatedDevice.id, false, null)
                    updateUiState()
                }
            }
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

    private fun toggleTestPattern() {
        if (isTesting) {
            stopTestPattern()
        } else {
            startTestPattern()
        }
    }

    private fun startTestPattern() {
        isTesting = true
        testPatternJob?.cancel()
        AmbientCaptureService.isTestingOverride = true
        tvPreviewView.setLiveActive(true)

        testPatternJob = lifecycleScope.launch(Dispatchers.IO) {
            val devices = config.enabledDevices
            var step = 0

            while (isTesting) {
                for (dev in devices) {
                    val leds = dev.totalLeds
                    if (leds <= 0) continue
                    val testBuffer = ByteArray(leds * 3)

                    for (i in 0 until leds) {
                        val idx = i * 3
                        val hue = ((step * 4 + (i * 360 / leds)) % 360).toFloat()
                        val rgb = Color.HSVToColor(floatArrayOf(hue, 1.0f, 1.0f))
                        testBuffer[idx] = Color.red(rgb).toByte()
                        testBuffer[idx + 1] = Color.green(rgb).toByte()
                        testBuffer[idx + 2] = Color.blue(rgb).toByte()
                    }

                    udpSender.sendDrgbFrame(
                        ip = dev.ip,
                        port = dev.port,
                        timeoutSeconds = 2,
                        rgb = testBuffer,
                        ledCount = leds,
                        colorOrder = dev.calibration.colorOrder
                    )

                    launch(Dispatchers.Main) {
                        if (isTesting) {
                            tvPreviewView.updateDeviceLiveColors(dev.id, testBuffer, leds)
                        }
                    }
                }

                step = (step + 1) % 360
                delay(33)
            }
        }
    }

    private fun stopTestPattern() {
        isTesting = false
        testPatternJob?.cancel()
        testPatternJob = null
        AmbientCaptureService.isTestingOverride = false
        if (AmbientCaptureService.isRunning) {
            tvPreviewView.setLiveActive(true)
        } else {
            tvPreviewView.setLiveActive(false)
            tvPreviewView.clearLiveColors()
        }

        lifecycleScope.launch(Dispatchers.IO) {
            for (dev in config.enabledDevices) {
                val leds = dev.totalLeds
                if (leds > 0) {
                    val blackBuffer = ByteArray(leds * 3)
                    udpSender.sendDrgbFrame(
                        ip = dev.ip,
                        port = dev.port,
                        timeoutSeconds = 1,
                        rgb = blackBuffer,
                        ledCount = leds,
                        colorOrder = dev.calibration.colorOrder
                    )
                }
            }
        }
    }

    private fun requestScreenCapture() {
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun startAmbientService(resultCode: Int, data: Intent) {
        tvPreviewView.setLiveActive(true)
        AmbientCaptureService.liveFrameListener = this
        AmbientCaptureService.throughputListener = this
        btnThroughputPill.visibility = View.VISIBLE
        val intent = Intent(this, AmbientCaptureService::class.java).apply {
            putExtra(AmbientCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(AmbientCaptureService.EXTRA_RESULT_DATA, data)
        }
        startForegroundService(intent)
        AmbientCaptureService.isRunning = true
        updateUiState()
    }

    private fun stopAmbientService() {
        AmbientCaptureService.isRunning = false
        AmbientCaptureService.liveFrameListener = null
        AmbientCaptureService.throughputListener = null
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
        val running = AmbientCaptureService.isRunning
        val pingableActiveCount = config.enabledDevices.count { dev ->
            deviceReachabilityMap[dev.id] == true
        }
        val totalEnabled = config.enabledDevices.size

        if (running) {
            btnPower.setBackgroundResource(R.drawable.bg_power_button_on)
            tvPowerText.text = getString(R.string.btn_stop_mirror)
            tvPowerText.setTextColor(Color.parseColor("#F87171"))
            ivPowerIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))

            if (pingableActiveCount > 0) {
                viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#00E676"))
                tvStatusTitle.text = if (pingableActiveCount == 1) "Active" else "Active ($pingableActiveCount)"
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
            } else {
                viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))
                tvStatusTitle.text = "No Light Found"
            }
        }
    }

    private fun startPingPolling() {
        pingPollingJob?.cancel()
        pingPollingJob = lifecycleScope.launch {
            while (isActive) {
                val devices = config.devices
                for (dev in devices) {
                    launch(Dispatchers.IO) {
                        val isReachable = httpClient.checkConnection(dev.ip)
                        deviceReachabilityMap[dev.id] = isReachable
                        withContext(Dispatchers.Main) {
                            updateDeviceDockDot(dev.id, dev.enabled, isReachable)
                            updateUiState()
                        }
                    }
                }
                delay(5000)
            }
        }
    }

    override fun onDeviceFrameProcessed(deviceId: String, rgb: ByteArray, count: Int) {
        if (!isTesting && AmbientCaptureService.isRunning && tvPreviewView.isLiveActive) {
            runOnUiThread {
                if (AmbientCaptureService.isRunning && tvPreviewView.isLiveActive) {
                    tvPreviewView.updateDeviceLiveColors(deviceId, rgb, count)
                }
            }
        }
    }

    override fun onStateChanged(running: Boolean) {
        runOnUiThread {
            tvPreviewView.setLiveActive(running)
            if (!running) {
                AmbientCaptureService.liveFrameListener = null
                AmbientCaptureService.throughputListener = null
                btnThroughputPill.visibility = View.GONE
                tvPreviewView.clearLiveColors()
            } else {
                AmbientCaptureService.throughputListener = this
                btnThroughputPill.visibility = View.VISIBLE
            }
            updateUiState()
        }
    }

    override fun onThroughputStatsUpdated(stats: ThroughputStats) {
        runOnUiThread {
            if (AmbientCaptureService.isRunning) {
                updateThroughputUi(stats)
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
        tvTotalPackets.text = String.format("%,d packets sent this session", stats.totalPackets)

        val history = AmbientCaptureService.getHistorySnapshot(windowSec)
        chart.setData(history, windowSec, config.calibration.fps, config.enabledDevices.size)

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
        val stats = AmbientCaptureService.latestStats ?: ThroughputStats(
            fps = 0,
            targetFps = config.calibration.fps,
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
            val cur = AmbientCaptureService.latestStats ?: stats
            refreshDialogTelemetry(dialogView, cur, 300)
        }
        btnWindow15m.setOnClickListener {
            selectedWindowSeconds = 900
            val cur = AmbientCaptureService.latestStats ?: stats
            refreshDialogTelemetry(dialogView, cur, 900)
        }
        btnWindow30m.setOnClickListener {
            selectedWindowSeconds = 1800
            val cur = AmbientCaptureService.latestStats ?: stats
            refreshDialogTelemetry(dialogView, cur, 1800)
        }
        btnWindow1h.setOnClickListener {
            selectedWindowSeconds = 3600
            val cur = AmbientCaptureService.latestStats ?: stats
            refreshDialogTelemetry(dialogView, cur, 3600)
        }
        btnWindow3h.setOnClickListener {
            selectedWindowSeconds = 10800
            val cur = AmbientCaptureService.latestStats ?: stats
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
}
