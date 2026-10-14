package com.wled.tv.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wled.tv.R
import com.wled.tv.data.PreferencesRepository
import com.wled.tv.model.Corner
import com.wled.tv.model.DeviceType
import com.wled.tv.model.Direction
import com.wled.tv.model.StreamingProtocol
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

    private lateinit var btnSelectProtocol: Button
    private lateinit var etDevicePort: EditText
    private lateinit var btnSelectRgbw: Button

    private lateinit var btnSelectFixtureMode: Button
    private lateinit var tvPositionHeader: TextView
    private lateinit var btnSelectPosition: Button

    private lateinit var cardPerimeterSettings: LinearLayout
    private lateinit var tvPerimeterSummary: TextView
    private lateinit var cardCalibrationSettings: LinearLayout
    private lateinit var tvCalibrationSummary: TextView

    private lateinit var btnDeleteDevice: Button
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
                ledCount = if (initialType == DeviceType.PERIMETER) 168 else 1,
                calibration = config.calibration
            )
            // DO NOT auto-save new device to config yet! Only committed when user clicks Save Light
        }

        bindViews()
        setupListeners()
        setupNavigation()
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
        udpSender.close()
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

        btnSelectProtocol = findViewById(R.id.btnSelectProtocol)
        etDevicePort = findViewById(R.id.etDevicePort)
        btnSelectRgbw = findViewById(R.id.btnSelectRgbw)

        btnSelectFixtureMode = findViewById(R.id.btnSelectFixtureMode)
        tvPositionHeader = findViewById(R.id.tvPositionHeader)
        btnSelectPosition = findViewById(R.id.btnSelectPosition)

        cardPerimeterSettings = findViewById(R.id.cardPerimeterSettings)
        tvPerimeterSummary = findViewById(R.id.tvPerimeterSummary)
        cardCalibrationSettings = findViewById(R.id.cardCalibrationSettings)
        tvCalibrationSummary = findViewById(R.id.tvCalibrationSummary)

        btnDeleteDevice = findViewById(R.id.btnDeleteDevice)
        btnCancelEdit = findViewById(R.id.btnCancelEdit)
        btnSaveEdit = findViewById(R.id.btnSaveEdit)
    }

    private fun setupListeners() {
        btnDeleteDevice.setOnClickListener {
            confirmDeleteDevice()
        }

        btnIdentifyDevice.setOnClickListener {
            identifyDevice()
        }

        btnSelectProtocol.setOnClickListener {
            val protocols = StreamingProtocol.entries
            val names = protocols.map { "${it.displayName} (Port ${it.defaultPort})" }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Select Streaming Protocol")
                .setItems(names) { _, which ->
                    val chosen = protocols[which]
                    val oldPort = etDevicePort.text.toString().toIntOrNull() ?: currentDevice.port
                    val isOldDefault = (oldPort == currentDevice.protocol.defaultPort)
                    val newPort = if (isOldDefault) chosen.defaultPort else oldPort

                    currentDevice = currentDevice.copy(protocol = chosen, port = newPort)
                    etDevicePort.setText(newPort.toString())
                    populateUi()
                }
                .show()
        }

        btnSelectRgbw.setOnClickListener {
            val options = arrayOf(
                "RGB Standard (3-Channel: WS2812B / WS2811 / WS2815)",
                "SK6812 RGBW (Auto White Subtract - High CRI & Power Saving)",
                "SK6812 RGBW (Auto White Add - Brighter Ambience)",
                "SK6812 RGBW (White Off / WLED Internal Handling)"
            )
            AlertDialog.Builder(this)
                .setTitle("Select Color Channel / RGBW Mode")
                .setItems(options) { _, which ->
                    currentDevice = when (which) {
                        1 -> currentDevice.copy(calibration = currentDevice.calibration.copy(isRgbw = true, rgbwMode = "AUTO_SUBTRACT"))
                        2 -> currentDevice.copy(calibration = currentDevice.calibration.copy(isRgbw = true, rgbwMode = "AUTO_ADD"))
                        3 -> currentDevice.copy(calibration = currentDevice.calibration.copy(isRgbw = true, rgbwMode = "OFF"))
                        else -> currentDevice.copy(calibration = currentDevice.calibration.copy(isRgbw = false, rgbwMode = "AUTO_SUBTRACT"))
                    }
                    populateUi()
                }
                .show()
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
                val info = withContext(Dispatchers.IO) { httpClient.fetchDeviceInfo(ip) }
                val duration = System.currentTimeMillis() - start
                btnPingDevice.isEnabled = true
                if (info != null) {
                    val rgbwStr = if (info.isRgbw) " • RGBW detected" else ""
                    tvPingStatus.text = "Online (${duration}ms) • WLED v${info.version} • ${info.ledCount} LEDs$rgbwStr"
                    tvPingStatus.setTextColor(Color.parseColor("#00E676"))
                    Toast.makeText(this@EditDeviceActivity, "WLED v${info.version} Online ($duration ms)", Toast.LENGTH_SHORT).show()

                    if (info.isRgbw && !currentDevice.calibration.isRgbw) {
                        currentDevice = currentDevice.copy(
                            calibration = currentDevice.calibration.copy(isRgbw = true, rgbwMode = "AUTO_SUBTRACT")
                        )
                        Toast.makeText(this@EditDeviceActivity, "Detected SK6812 RGBW strip! Enabled RGBW mode.", Toast.LENGTH_LONG).show()
                        populateUi()
                    }
                } else {
                    val reachable = withContext(Dispatchers.IO) { httpClient.checkConnection(ip) }
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

    private fun showKeyboard(view: EditText) {
        view.requestFocus()
        view.setSelection(view.text.length)
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard(view: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun setupNavigation() {
        val dpadNavKeys = setOf(
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_LEFT
        )

        // Dismiss keyboard when text boxes lose focus
        etDeviceName.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) hideKeyboard(etDeviceName)
        }
        etDeviceIp.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) hideKeyboard(etDeviceIp)
        }

        // Clicking or pressing Center opens keyboard
        etDeviceName.setOnClickListener { showKeyboard(etDeviceName) }
        etDeviceIp.setOnClickListener { showKeyboard(etDeviceIp) }

        // IME Editor actions
        etDeviceName.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_NEXT || actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                hideKeyboard(etDeviceName)
                etDeviceIp.requestFocus()
                true
            } else false
        }

        etDeviceIp.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_NEXT ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                hideKeyboard(etDeviceIp)
                btnPingDevice.requestFocus()
                true
            } else false
        }

        // D-Pad Navigation for etDeviceName: Never move cursor character-by-character on D-pad
        etDeviceName.setOnKeyListener { _, keyCode, event ->
            if (keyCode in dpadNavKeys) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    hideKeyboard(etDeviceName)
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_DOWN -> etDeviceIp.requestFocus()
                        KeyEvent.KEYCODE_DPAD_UP -> btnIdentifyDevice.requestFocus()
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (cardPerimeterSettings.visibility == View.VISIBLE) {
                                cardPerimeterSettings.requestFocus()
                            } else {
                                cardCalibrationSettings.requestFocus()
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> { /* Keep focus without cursor stepping */ }
                    }
                }
                return@setOnKeyListener true
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                if (event.action == KeyEvent.ACTION_UP) {
                    showKeyboard(etDeviceName)
                }
                return@setOnKeyListener true
            }
            false
        }

        // D-Pad Navigation for etDeviceIp: Jump immediately to adjacent views
        etDeviceIp.setOnKeyListener { _, keyCode, event ->
            if (keyCode in dpadNavKeys) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    hideKeyboard(etDeviceIp)
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> etDeviceName.requestFocus()
                        KeyEvent.KEYCODE_DPAD_DOWN -> btnSelectProtocol.requestFocus()
                        KeyEvent.KEYCODE_DPAD_RIGHT -> btnPingDevice.requestFocus()
                        KeyEvent.KEYCODE_DPAD_LEFT -> { /* Keep focus without cursor stepping */ }
                    }
                }
                return@setOnKeyListener true
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                if (event.action == KeyEvent.ACTION_UP) {
                    showKeyboard(etDeviceIp)
                }
                return@setOnKeyListener true
            }
            false
        }

        // D-Pad Navigation for btnPingDevice
        btnPingDevice.setOnKeyListener { _, keyCode, event ->
            if (keyCode in dpadNavKeys) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> etDeviceIp.requestFocus()
                        KeyEvent.KEYCODE_DPAD_UP -> etDeviceName.requestFocus()
                        KeyEvent.KEYCODE_DPAD_DOWN -> etDevicePort.requestFocus()
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (cardCalibrationSettings.visibility == View.VISIBLE) {
                                cardCalibrationSettings.requestFocus()
                            } else if (cardPerimeterSettings.visibility == View.VISIBLE) {
                                cardPerimeterSettings.requestFocus()
                            }
                        }
                    }
                }
                return@setOnKeyListener true
            }
            false
        }

        // D-Pad Navigation for btnSelectProtocol
        btnSelectProtocol.setOnKeyListener { _, keyCode, event ->
            if (keyCode in dpadNavKeys) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> etDeviceIp.requestFocus()
                        KeyEvent.KEYCODE_DPAD_DOWN -> btnSelectRgbw.requestFocus()
                        KeyEvent.KEYCODE_DPAD_RIGHT -> etDevicePort.requestFocus()
                        KeyEvent.KEYCODE_DPAD_LEFT -> { /* Stay */ }
                    }
                }
                return@setOnKeyListener true
            }
            false
        }

        // D-Pad Navigation for etDevicePort
        etDevicePort.setOnKeyListener { _, keyCode, event ->
            if (keyCode in dpadNavKeys) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    hideKeyboard(etDevicePort)
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> btnPingDevice.requestFocus()
                        KeyEvent.KEYCODE_DPAD_DOWN -> btnSelectRgbw.requestFocus()
                        KeyEvent.KEYCODE_DPAD_LEFT -> btnSelectProtocol.requestFocus()
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (cardCalibrationSettings.visibility == View.VISIBLE) {
                                cardCalibrationSettings.requestFocus()
                            } else if (cardPerimeterSettings.visibility == View.VISIBLE) {
                                cardPerimeterSettings.requestFocus()
                            }
                        }
                    }
                }
                return@setOnKeyListener true
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                if (event.action == KeyEvent.ACTION_UP) {
                    showKeyboard(etDevicePort)
                }
                return@setOnKeyListener true
            }
            false
        }

        // D-Pad Navigation for btnSelectRgbw
        btnSelectRgbw.setOnKeyListener { _, keyCode, event ->
            if (keyCode in dpadNavKeys) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> btnSelectProtocol.requestFocus()
                        KeyEvent.KEYCODE_DPAD_DOWN -> btnSelectFixtureMode.requestFocus()
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (cardCalibrationSettings.visibility == View.VISIBLE) {
                                cardCalibrationSettings.requestFocus()
                            } else if (cardPerimeterSettings.visibility == View.VISIBLE) {
                                cardPerimeterSettings.requestFocus()
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> { /* Stay */ }
                    }
                }
                return@setOnKeyListener true
            }
            false
        }

        // D-Pad Navigation for btnSelectFixtureMode
        btnSelectFixtureMode.setOnKeyListener { _, keyCode, event ->
            if (keyCode in dpadNavKeys) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> btnSelectRgbw.requestFocus()
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (btnSelectPosition.visibility == View.VISIBLE) {
                                btnSelectPosition.requestFocus()
                            } else {
                                btnCancelEdit.requestFocus()
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (cardCalibrationSettings.visibility == View.VISIBLE) {
                                cardCalibrationSettings.requestFocus()
                            } else if (cardPerimeterSettings.visibility == View.VISIBLE) {
                                cardPerimeterSettings.requestFocus()
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> { /* Stay */ }
                    }
                }
                return@setOnKeyListener true
            }
            false
        }

        // D-Pad Navigation for btnSelectPosition
        btnSelectPosition.setOnKeyListener { _, keyCode, event ->
            if (keyCode in dpadNavKeys) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> btnSelectFixtureMode.requestFocus()
                        KeyEvent.KEYCODE_DPAD_DOWN -> btnCancelEdit.requestFocus()
                        KeyEvent.KEYCODE_DPAD_RIGHT -> cardCalibrationSettings.requestFocus()
                        KeyEvent.KEYCODE_DPAD_LEFT -> { /* Stay */ }
                    }
                }
                return@setOnKeyListener true
            }
            false
        }

        // D-Pad Navigation for cardPerimeterSettings
        cardPerimeterSettings.setOnKeyListener { _, keyCode, event ->
            if (keyCode in dpadNavKeys) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> etDeviceName.requestFocus()
                        KeyEvent.KEYCODE_DPAD_DOWN -> cardCalibrationSettings.requestFocus()
                        KeyEvent.KEYCODE_DPAD_UP -> btnIdentifyDevice.requestFocus()
                        KeyEvent.KEYCODE_DPAD_RIGHT -> { /* Stay */ }
                    }
                }
                return@setOnKeyListener true
            }
            false
        }

        // D-Pad Navigation for cardCalibrationSettings
        cardCalibrationSettings.setOnKeyListener { _, keyCode, event ->
            if (keyCode in dpadNavKeys) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> btnPingDevice.requestFocus()
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            if (cardPerimeterSettings.visibility == View.VISIBLE) {
                                cardPerimeterSettings.requestFocus()
                            } else {
                                etDeviceName.requestFocus()
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> btnSaveEdit.requestFocus()
                        KeyEvent.KEYCODE_DPAD_RIGHT -> { /* Stay */ }
                    }
                }
                return@setOnKeyListener true
            }
            false
        }

        // D-Pad Navigation for btnDeleteDevice
        btnDeleteDevice.setOnKeyListener { _, keyCode, event ->
            if (keyCode in dpadNavKeys) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            if (btnSelectPosition.visibility == View.VISIBLE) {
                                btnSelectPosition.requestFocus()
                            } else {
                                btnSelectFixtureMode.requestFocus()
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> btnCancelEdit.requestFocus()
                        KeyEvent.KEYCODE_DPAD_LEFT -> { /* Stay */ }
                        KeyEvent.KEYCODE_DPAD_DOWN -> { /* Stay */ }
                    }
                }
                return@setOnKeyListener true
            }
            false
        }

        // D-Pad Navigation for btnCancelEdit
        btnCancelEdit.setOnKeyListener { _, keyCode, event ->
            if (keyCode in dpadNavKeys) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            if (btnSelectPosition.visibility == View.VISIBLE) {
                                btnSelectPosition.requestFocus()
                            } else {
                                btnSelectFixtureMode.requestFocus()
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> btnSaveEdit.requestFocus()
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            if (btnDeleteDevice.visibility == View.VISIBLE) {
                                btnDeleteDevice.requestFocus()
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> { /* Stay */ }
                    }
                }
                return@setOnKeyListener true
            }
            false
        }

        // D-Pad Navigation for btnSaveEdit
        btnSaveEdit.setOnKeyListener { _, keyCode, event ->
            if (keyCode in dpadNavKeys) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> cardCalibrationSettings.requestFocus()
                        KeyEvent.KEYCODE_DPAD_LEFT -> btnCancelEdit.requestFocus()
                        KeyEvent.KEYCODE_DPAD_RIGHT -> { /* Stay */ }
                        KeyEvent.KEYCODE_DPAD_DOWN -> { /* Stay */ }
                    }
                }
                return@setOnKeyListener true
            }
            false
        }

        // D-Pad Navigation for btnIdentifyDevice
        btnIdentifyDevice.setOnKeyListener { _, keyCode, event ->
            if (keyCode in dpadNavKeys) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> etDeviceName.requestFocus()
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (cardPerimeterSettings.visibility == View.VISIBLE) {
                                cardPerimeterSettings.requestFocus()
                            } else {
                                cardCalibrationSettings.requestFocus()
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> { /* Stay */ }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> { /* Stay */ }
                    }
                }
                return@setOnKeyListener true
            }
            false
        }

        etDeviceName.requestFocus()
    }

    private fun populateUi() {
        tvEditTitle.text = if (isNewDevice) "Add New Light Device" else "Configure ${currentDevice.name}"
        btnDeleteDevice.visibility = if (isNewDevice) View.GONE else View.VISIBLE
        etDeviceName.setText(currentDevice.name)
        etDeviceIp.setText(currentDevice.ip)
        etDevicePort.setText(currentDevice.port.toString())

        btnSelectProtocol.text = "${currentDevice.protocol.displayName} (Port ${currentDevice.port})"

        btnSelectRgbw.text = when {
            !currentDevice.calibration.isRgbw -> "RGB (3-Channel Standard)"
            currentDevice.calibration.rgbwMode == "AUTO_SUBTRACT" -> "SK6812 RGBW (Auto Subtract)"
            currentDevice.calibration.rgbwMode == "AUTO_ADD" -> "SK6812 RGBW (Auto Add)"
            else -> "SK6812 RGBW (White Off)"
        }

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
        val letterboxStr = if (p.autoLetterbox) " • Auto-Crop" else ""
        val corner = when (p.startCorner) {
            Corner.BOTTOM_LEFT -> "Bottom-Left"
            Corner.BOTTOM_RIGHT -> "Bottom-Right"
            Corner.TOP_LEFT -> "Top-Left"
            Corner.TOP_RIGHT -> "Top-Right"
        }
        val dir = when (p.direction) {
            Direction.CLOCKWISE -> "Clockwise"
            Direction.COUNTER_CLOCKWISE -> "Counter-Clockwise"
        }
        tvPerimeterSummary.text = "${p.totalLeds} LEDs • ${p.topLeds}T / ${p.rightLeds}R / ${p.bottomLeds}B / ${p.leftLeds}L\n$corner • $dir$letterboxStr"

        val c = currentDevice.calibration
        val briPct = ((c.maxBrightness / 255f) * 100).toInt()
        val rgbwTag = if (c.isRgbw) "RGBW (${c.rgbwMode})" else "RGB"
        tvCalibrationSummary.text = String.format(
            Locale.US,
            "Brightness %d%% • %s • %s\nSaturation %.1fx • Smoothing %.2f",
            briPct, c.colorOrder, rgbwTag, c.saturation, c.smoothingFactor
        )
    }

    private fun commitDraftChanges() {
        val name = etDeviceName.text.toString().trim().ifBlank { currentDevice.name }
        val ip = etDeviceIp.text.toString().trim().ifBlank { currentDevice.ip }
        val port = etDevicePort.text.toString().trim().toIntOrNull() ?: currentDevice.port
        currentDevice = currentDevice.copy(name = name, ip = ip, port = port)

        // Save temporary draft in config so child settings activity can inspect/modify it
        config = prefsRepo.loadConfig()
        val updated = if (config.devices.any { it.id == currentDevice.id }) {
            config.updateDevice(currentDevice)
        } else {
            config.copy(devices = config.devices + currentDevice)
        }
        prefsRepo.saveConfig(updated)
    }

    private fun confirmDeleteDevice() {
        config = prefsRepo.loadConfig()
        if (config.devices.size <= 1) {
            AlertDialog.Builder(this)
                .setTitle("Cannot Delete")
                .setMessage("At least one light fixture must remain configured.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Delete Light")
            .setMessage("Are you sure you want to delete '${currentDevice.name}'?")
            .setPositiveButton("Delete") { _, _ ->
                val updatedDevices = config.devices.filter { it.id != currentDevice.id }
                config = config.copy(devices = updatedDevices)
                prefsRepo.saveConfig(config)
                isSaved = true

                lifecycleScope.launch(Dispatchers.IO) {
                    val totalLeds = currentDevice.totalLeds
                    if (totalLeds > 0) {
                        udpSender.sendDeviceFrame(
                            device = currentDevice,
                            rgb = ByteArray(totalLeds * 3),
                            ledCount = totalLeds,
                            timeoutSeconds = 2
                        )
                    }
                    httpClient.turnOff(currentDevice.ip)
                }

                if (AmbientCaptureService.isRunning) {
                    AmbientCaptureService.currentServiceInstance?.reloadConfig()
                }

                Toast.makeText(this, "Deleted ${currentDevice.name}", Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveAndFinish() {
        val name = etDeviceName.text.toString().trim().ifBlank { "Light" }
        val ip = etDeviceIp.text.toString().trim()
        val port = etDevicePort.text.toString().trim().toIntOrNull() ?: currentDevice.protocol.defaultPort
        if (ip.isBlank()) {
            Toast.makeText(this, "Please enter a valid IP address", Toast.LENGTH_SHORT).show()
            return
        }

        currentDevice = currentDevice.copy(name = name, ip = ip, port = port)
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
                udpSender.sendDeviceFrame(
                    device = currentDevice.copy(ip = ip),
                    rgb = whiteFrame,
                    ledCount = totalLeds,
                    timeoutSeconds = 2
                )
                delay(300)
                udpSender.sendDeviceFrame(
                    device = currentDevice.copy(ip = ip),
                    rgb = blackFrame,
                    ledCount = totalLeds,
                    timeoutSeconds = 2
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
