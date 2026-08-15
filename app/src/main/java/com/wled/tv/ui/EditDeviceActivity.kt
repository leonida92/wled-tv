package com.wled.tv.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wled.tv.R
import com.wled.tv.data.PreferencesRepository
import com.wled.tv.model.DeviceType
import com.wled.tv.model.WledConfig
import com.wled.tv.model.WledDevice
import com.wled.tv.network.WledHttpClient
import com.wled.tv.network.WledUdpSender
import com.wled.tv.service.AmbientCaptureService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID

class EditDeviceActivity : AppCompatActivity() {

    private lateinit var prefsRepo: PreferencesRepository
    private var config: WledConfig = WledConfig()
    private var deviceId: String? = null
    private lateinit var currentDevice: WledDevice

    private lateinit var tvEditTitle: TextView
    private lateinit var btnIdentifyDevice: Button
    private lateinit var etDeviceName: EditText
    private lateinit var etDeviceIp: EditText
    private lateinit var btnPingDevice: Button
    private lateinit var tvPingStatus: TextView

    private lateinit var btnSelectFixtureMode: Button
    private lateinit var tvPositionHeader: TextView
    private lateinit var btnSelectPosition: Button

    private lateinit var cardPerimeterSettings: LinearLayout
    private lateinit var tvPerimeterSummary: TextView
    private lateinit var cardCalibrationSettings: LinearLayout
    private lateinit var tvCalibrationSummary: TextView

    private lateinit var btnCancelEdit: Button
    private lateinit var btnSaveEdit: Button

    private val udpSender = WledUdpSender()
    private val httpClient = WledHttpClient()
    private var testJob: Job? = null

    private var isNewDevice = false
    private var isSaved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit_device)

        prefsRepo = PreferencesRepository(this)
        config = prefsRepo.loadConfig()

        deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
        val initialTypeStr = intent.getStringExtra(EXTRA_DEVICE_TYPE)

        val existing = config.devices.firstOrNull { it.id == deviceId }
        if (existing != null) {
            currentDevice = existing
            isNewDevice = false
        } else {
            isNewDevice = true
            val initialType = try {
                if (initialTypeStr != null) DeviceType.valueOf(initialTypeStr) else DeviceType.BOTTOM_AMBIENT
            } catch (_: Exception) {
                DeviceType.BOTTOM_AMBIENT
            }
            val defaultName = when (initialType) {
                DeviceType.PERIMETER -> "TV Backlight"
                DeviceType.LEFT_AMBIENT -> "Left Side Lamp"
                DeviceType.RIGHT_AMBIENT -> "Right Side Lamp"
                DeviceType.TOP_AMBIENT -> "Ceiling Uplight"
                DeviceType.BOTTOM_AMBIENT -> "Floor Light"
                DeviceType.FULL_SCREEN -> "Room Ambient"
                DeviceType.CUSTOM_BOX -> "Custom Zone Light"
            }
            val initialName = intent.getStringExtra(EXTRA_INITIAL_NAME) ?: defaultName
            val initialIp = intent.getStringExtra(EXTRA_INITIAL_IP) ?: "192.168.1.67"

            currentDevice = WledDevice(
                id = deviceId ?: UUID.randomUUID().toString(),
                name = initialName,
                ip = initialIp,
                port = 21324,
                enabled = true,
                type = initialType,
                colorOrder = config.calibration.colorOrder,
                ledCount = if (initialType == DeviceType.PERIMETER) 168 else 1,
                calibration = config.calibration
            )
            // DO NOT auto-save new device to config yet! Only committed when user clicks Save Light
        }

        bindViews()
        setupListeners()
        populateUi()
    }

    override fun onResume() {
        super.onResume()
        config = prefsRepo.loadConfig()
        val reloaded = config.devices.firstOrNull { it.id == currentDevice.id }
        if (reloaded != null) {
            currentDevice = reloaded
            populateUi()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        testJob?.cancel()
        if (isNewDevice && !isSaved) {
            // Clean up any temporary draft saved for sub-activities
            val latest = prefsRepo.loadConfig()
            if (latest.devices.any { it.id == currentDevice.id }) {
                prefsRepo.saveConfig(latest.copy(devices = latest.devices.filter { it.id != currentDevice.id }))
            }
        }
    }

    private fun bindViews() {
        tvEditTitle = findViewById(R.id.tvEditTitle)
        btnIdentifyDevice = findViewById(R.id.btnIdentifyDevice)
        etDeviceName = findViewById(R.id.etDeviceName)
        etDeviceIp = findViewById(R.id.etDeviceIp)
        btnPingDevice = findViewById(R.id.btnPingDevice)
        tvPingStatus = findViewById(R.id.tvPingStatus)

        btnSelectFixtureMode = findViewById(R.id.btnSelectFixtureMode)
        tvPositionHeader = findViewById(R.id.tvPositionHeader)
        btnSelectPosition = findViewById(R.id.btnSelectPosition)

        cardPerimeterSettings = findViewById(R.id.cardPerimeterSettings)
        tvPerimeterSummary = findViewById(R.id.tvPerimeterSummary)
        cardCalibrationSettings = findViewById(R.id.cardCalibrationSettings)
        tvCalibrationSummary = findViewById(R.id.tvCalibrationSummary)

        btnCancelEdit = findViewById(R.id.btnCancelEdit)
        btnSaveEdit = findViewById(R.id.btnSaveEdit)

        etDeviceName.requestFocus()
    }

    private fun setupListeners() {
        btnIdentifyDevice.setOnClickListener {
            identifyDevice()
        }

        btnPingDevice.setOnClickListener {
            val ip = etDeviceIp.text.toString().trim()
            if (ip.isBlank()) {
                Toast.makeText(this, "Enter an IP address first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            btnPingDevice.isEnabled = false
            tvPingStatus.text = "Pinging $ip..."
            tvPingStatus.setTextColor(Color.parseColor("#94A3B8"))
            tvPingStatus.visibility = View.VISIBLE

            lifecycleScope.launch {
                val start = System.currentTimeMillis()
                val reachable = withContext(Dispatchers.IO) { httpClient.checkConnection(ip) }
                val duration = System.currentTimeMillis() - start
                btnPingDevice.isEnabled = true
                if (reachable) {
                    tvPingStatus.text = "Online (${duration}ms response)"
                    tvPingStatus.setTextColor(Color.parseColor("#00E676"))
                    Toast.makeText(this@EditDeviceActivity, "WLED Online ($duration ms)", Toast.LENGTH_SHORT).show()
                } else {
                    tvPingStatus.text = "Unreachable (Check IP / Wi-Fi)"
                    tvPingStatus.setTextColor(Color.parseColor("#F87171"))
                    Toast.makeText(this@EditDeviceActivity, "WLED Unreachable at $ip", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnSelectFixtureMode.setOnClickListener {
            val modes = arrayOf("Single Light (1 LED Spot / Lamp)", "LED Array / Lightstrip")
            AlertDialog.Builder(this)
                .setTitle("Select Fixture Type / Layout")
                .setItems(modes) { _, which ->
                    if (which == 0) {
                        // Single Light (1 LED)
                        if (currentDevice.type == DeviceType.PERIMETER) {
                            currentDevice = currentDevice.copy(type = DeviceType.BOTTOM_AMBIENT, ledCount = 1)
                        } else {
                            currentDevice = currentDevice.copy(ledCount = 1)
                        }
                    } else {
                        // LED Array / Strip
                        currentDevice = currentDevice.copy(type = DeviceType.PERIMETER)
                    }
                    populateUi()
                }
                .show()
        }

        btnSelectPosition.setOnClickListener {
            val singleRoles = listOf(
                DeviceType.BOTTOM_AMBIENT,
                DeviceType.LEFT_AMBIENT,
                DeviceType.RIGHT_AMBIENT,
                DeviceType.TOP_AMBIENT,
                DeviceType.FULL_SCREEN
            )
            val names = singleRoles.map { it.displayName }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Select Physical Position")
                .setItems(names) { _, which ->
                    currentDevice = currentDevice.copy(type = singleRoles[which], ledCount = 1)
                    populateUi()
                }
                .show()
        }

        cardPerimeterSettings.setOnClickListener {
            currentDevice = currentDevice.copy(type = DeviceType.PERIMETER)
            commitDraftChanges()
            val intent = Intent(this, SettingsActivity::class.java).apply {
                putExtra(SettingsActivity.EXTRA_DEVICE_ID, currentDevice.id)
            }
            startActivity(intent)
        }

        cardCalibrationSettings.setOnClickListener {
            commitDraftChanges()
            val intent = Intent(this, CalibrationActivity::class.java).apply {
                putExtra(CalibrationActivity.EXTRA_DEVICE_ID, currentDevice.id)
            }
            startActivity(intent)
        }

        btnCancelEdit.setOnClickListener {
            finish()
        }

        btnSaveEdit.setOnClickListener {
            saveAndFinish()
        }
    }

    private fun populateUi() {
        tvEditTitle.text = if (isNewDevice) "Add New Light Device" else "Configure ${currentDevice.name}"
        etDeviceName.setText(currentDevice.name)
        etDeviceIp.setText(currentDevice.ip)

        val isArray = currentDevice.type == DeviceType.PERIMETER || currentDevice.totalLeds > 1
        if (isArray) {
            btnSelectFixtureMode.text = "LED Array / Lightstrip"
            tvPositionHeader.visibility = View.GONE
            btnSelectPosition.visibility = View.GONE
            cardPerimeterSettings.visibility = View.VISIBLE
        } else {
            btnSelectFixtureMode.text = "Single Light (1 LED Spot / Lamp)"
            tvPositionHeader.visibility = View.VISIBLE
            btnSelectPosition.visibility = View.VISIBLE
            btnSelectPosition.text = currentDevice.type.displayName
            cardPerimeterSettings.visibility = View.GONE
        }

        updateSummaries()
    }

    private fun updateSummaries() {
        val p = currentDevice.perimeter
        val letterboxStr = if (p.autoLetterbox) " • Auto-Letterbox ON" else ""
        tvPerimeterSummary.text = "${p.topLeds} Top / ${p.rightLeds} Right / ${p.bottomLeds} Bottom / ${p.leftLeds} Left (${p.totalLeds} LEDs, ${p.startCorner.name}, ${p.direction.name}$letterboxStr)"

        val c = currentDevice.calibration
        val briPct = ((c.maxBrightness / 255f) * 100).toInt()
        tvCalibrationSummary.text = String.format(
            Locale.US,
            "Brightness %d%%, Order %s, Saturation %.1fx, Smoothing %.2f, Gains (R:%.2f G:%.2f B:%.2f)",
            briPct, c.colorOrder, c.saturation, c.smoothingFactor, c.gainR, c.gainG, c.gainB
        )
    }

    private fun commitDraftChanges() {
        val name = etDeviceName.text.toString().trim().ifBlank { currentDevice.name }
        val ip = etDeviceIp.text.toString().trim().ifBlank { currentDevice.ip }
        currentDevice = currentDevice.copy(name = name, ip = ip)

        // Save temporary draft in config so child settings activity can inspect/modify it
        config = prefsRepo.loadConfig()
        val updated = if (config.devices.any { it.id == currentDevice.id }) {
            config.updateDevice(currentDevice)
        } else {
            config.copy(devices = config.devices + currentDevice)
        }
        prefsRepo.saveConfig(updated)
    }

    private fun saveAndFinish() {
        val name = etDeviceName.text.toString().trim().ifBlank { "Light" }
        val ip = etDeviceIp.text.toString().trim()
        if (ip.isBlank()) {
            Toast.makeText(this, "Please enter a valid IP address", Toast.LENGTH_SHORT).show()
            return
        }

        currentDevice = currentDevice.copy(name = name, ip = ip)
        config = prefsRepo.loadConfig()

        val finalConfig = if (config.devices.any { it.id == currentDevice.id }) {
            config.updateDevice(currentDevice)
        } else {
            config.copy(devices = config.devices + currentDevice)
        }
        prefsRepo.saveConfig(finalConfig)
        isSaved = true

        if (AmbientCaptureService.isRunning) {
            AmbientCaptureService.currentServiceInstance?.reloadConfig()
        }

        Toast.makeText(this, "Saved ${currentDevice.name}", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun identifyDevice() {
        val ip = etDeviceIp.text.toString().trim().ifBlank { currentDevice.ip }
        testJob?.cancel()
        Toast.makeText(this, "Identifying ${currentDevice.name} ($ip)...", Toast.LENGTH_SHORT).show()

        testJob = lifecycleScope.launch(Dispatchers.IO) {
            val totalLeds = currentDevice.totalLeds
            val whiteFrame = ByteArray(totalLeds * 3) { (255).toByte() }
            val blackFrame = ByteArray(totalLeds * 3)

            for (flash in 1..3) {
                if (!isActive) break
                udpSender.sendDrgbFrame(
                    ip,
                    currentDevice.port,
                    timeoutSeconds = 2,
                    rgb = whiteFrame,
                    ledCount = totalLeds,
                    colorOrder = currentDevice.calibration.colorOrder
                )
                delay(300)
                udpSender.sendDrgbFrame(
                    ip,
                    currentDevice.port,
                    timeoutSeconds = 2,
                    rgb = blackFrame,
                    ledCount = totalLeds,
                    colorOrder = currentDevice.calibration.colorOrder
                )
                delay(300)
            }
            if (currentDevice.enabled) {
                httpClient.wakeAndSetBrightness(ip, currentDevice.calibration.maxBrightness)
            } else {
                httpClient.turnOff(ip)
            }
        }
    }

    companion object {
        const val EXTRA_DEVICE_ID = "extra_device_id"
        const val EXTRA_DEVICE_TYPE = "extra_device_type"
        const val EXTRA_INITIAL_NAME = "extra_initial_name"
        const val EXTRA_INITIAL_IP = "extra_initial_ip"
    }
}
