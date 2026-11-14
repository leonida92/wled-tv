package com.wled.tv.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.wled.tv.R
import com.wled.tv.data.ConfigRepository
import com.wled.tv.data.DeviceRemoveResult
import com.wled.tv.data.DeviceToggleResult
import com.wled.tv.data.FleetCoordinator
import com.wled.tv.data.WledFleetCoordinator
import com.wled.tv.model.DeviceType
import com.wled.tv.model.WledConfig
import com.wled.tv.model.WledDevice
import com.wled.tv.network.DefaultWledControllerClient
import com.wled.tv.service.AmbientCaptureService
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class DeviceManagerActivity : AppCompatActivity() {

    private lateinit var configRepo: ConfigRepository
    private lateinit var fleetCoordinator: FleetCoordinator
    private var config: WledConfig = WledConfig()

    private lateinit var layoutDeviceList: LinearLayout
    private lateinit var btnAddDeviceHeader: Button
    private lateinit var btnDiscoverLan: Button
    private lateinit var btnDone: Button

    private var testJob: Job? = null
    private var discoverJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_manager)

        configRepo = ConfigRepository.getInstance(this)
        fleetCoordinator = WledFleetCoordinator(
            configRepo = configRepo,
            controllerClient = DefaultWledControllerClient.getInstance(this)
        )
        config = configRepo.currentConfig

        bindViews()
        setupListeners()
        renderDeviceList()
        setupObservers()
    }

    private fun setupObservers() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                fleetCoordinator.startReachabilityPolling(this)
                launch {
                    configRepo.configFlow.collect { latest ->
                        config = latest
                        renderDeviceList()
                    }
                }
                launch {
                    fleetCoordinator.reachabilityFlow.collect { reachability ->
                        for (dev in config.devices) {
                            updateDeviceDot(dev.id, dev.enabled, reachability[dev.ip.trim()])
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        testJob?.cancel()
        discoverJob?.cancel()
    }

    private fun bindViews() {
        layoutDeviceList = findViewById(R.id.layoutDeviceList)
        btnAddDeviceHeader = findViewById(R.id.btnAddDeviceHeader)
        btnDiscoverLan = findViewById(R.id.btnDiscoverLan)
        btnDone = findViewById(R.id.btnDone)

        btnAddDeviceHeader.requestFocus()
    }

    private fun setupListeners() {
        btnAddDeviceHeader.setOnClickListener {
            showAddDevicePicker()
        }

        btnDiscoverLan.setOnClickListener {
            discoverWledDevices()
        }

        btnDone.setOnClickListener {
            finish()
        }
    }

    private fun updateDeviceDot(deviceId: String, enabled: Boolean, reachable: Boolean?) {
        val viewDot = layoutDeviceList.findViewWithTag<View>("dot_$deviceId") ?: return
        viewDot.backgroundTintList = reachabilityDotTint(enabled, reachable)
    }

    private fun renderDeviceList() {
        layoutDeviceList.removeAllViews()

        val inflater = LayoutInflater.from(this)
        for (device in config.devices) {
            val card = inflater.inflate(R.layout.item_device_card, layoutDeviceList, false)

            val viewDot = card.findViewById<View>(R.id.viewDeviceStatusDot)
            val tvName = card.findViewById<TextView>(R.id.tvDeviceName)
            val tvTypeBadge = card.findViewById<TextView>(R.id.tvDeviceTypeBadge)
            val tvDetails = card.findViewById<TextView>(R.id.tvDeviceDetails)

            val btnTest = card.findViewById<Button>(R.id.btnTestDevice)
            val btnEdit = card.findViewById<Button>(R.id.btnEditDevice)
            val btnToggle = card.findViewById<Button>(R.id.btnToggleDevice)
            val btnDelete = card.findViewById<ImageButton>(R.id.btnDeleteDevice)

            tvName.text = device.name
            tvTypeBadge.text = device.type.displayName.substringBefore(" (")
            val briPct = ((device.calibration.maxBrightness / 255f) * 100).toInt()
            val extraDetails = if (device.type == DeviceType.PERIMETER) {
                "${device.perimeter.topLeds}/${device.perimeter.rightLeds}/${device.perimeter.bottomLeds}/${device.perimeter.leftLeds}"
            } else {
                "${device.totalLeds} LEDs"
            }
            tvDetails.text = "${device.ip}:${device.port} | $extraDetails | ${device.colorOrder} | Bri: $briPct%"

            viewDot.tag = "dot_${device.id}"
            val reachability = fleetCoordinator.reachabilityFlow.value[device.ip.trim()]
            viewDot.backgroundTintList = reachabilityDotTint(device.enabled, reachability)

            if (!device.enabled) {
                btnToggle.text = "OFF"
                btnToggle.setTextColor(Color.parseColor("#94A3B8"))
            } else {
                btnToggle.text = "ON"
                btnToggle.setTextColor(Color.parseColor("#00E676"))
            }

            btnToggle.setOnClickListener {
                val targetId = device.id
                val streamingActive =
                    AmbientCaptureService.activeEngine.value != null || AmbientCaptureService.isRunning.value
                lifecycleScope.launch {
                    val result = fleetCoordinator.toggleDeviceEnabled(targetId, streamingActive)
                    if (result is DeviceToggleResult.Toggled) {
                        val updated = result.device
                        if (updated.enabled) {
                            btnToggle.text = "ON"
                            btnToggle.setTextColor(Color.parseColor("#00E676"))
                            updateDeviceDot(updated.id, true, result.isReachable)
                        } else {
                            btnToggle.text = "OFF"
                            btnToggle.setTextColor(Color.parseColor("#94A3B8"))
                            updateDeviceDot(updated.id, false, null)
                        }
                    }
                }
            }

            btnTest.setOnClickListener {
                identifyDevice(device)
            }

            btnEdit.setOnClickListener {
                val intent = Intent(this, EditDeviceActivity::class.java).apply {
                    putExtra(EditDeviceActivity.EXTRA_DEVICE_ID, device.id)
                }
                startActivity(intent)
            }

            if (config.devices.size <= 1) {
                btnDelete.visibility = View.GONE
            } else {
                btnDelete.visibility = View.VISIBLE
                btnDelete.setOnClickListener {
                    AlertDialog.Builder(this)
                        .setTitle("Remove Light Device")
                        .setMessage("Are you sure you want to remove '${device.name}'?")
                        .setPositiveButton("Remove") { _, _ ->
                            val targetId = device.id
                            lifecycleScope.launch {
                                when (fleetCoordinator.removeDevice(targetId)) {
                                    is DeviceRemoveResult.Removed -> {
                                        config = configRepo.currentConfig
                                        renderDeviceList()
                                    }
                                    is DeviceRemoveResult.RejectedLastDevice -> {
                                        Toast.makeText(
                                            this@DeviceManagerActivity,
                                            "At least one light fixture must remain configured.",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                    is DeviceRemoveResult.NotFound -> Unit
                                }
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }

            layoutDeviceList.addView(card)
        }
    }

    private fun identifyDevice(device: WledDevice) {
        testJob?.cancel()
        Toast.makeText(this, "Identifying ${device.name} (${device.ip})...", Toast.LENGTH_SHORT).show()

        testJob = lifecycleScope.launch {
            val overrideSession = AmbientCaptureService.activeEngine.value?.acquireDiagnosticOverride()
            try {
                fleetCoordinator.identifyDevice(device)
            } finally {
                overrideSession?.close()
            }
        }
    }

    private fun showAddDevicePicker() {
        val types = DeviceType.values()
        val typeNames = types.map { it.displayName }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select Light Role / Region")
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

    private fun discoverWledDevices() {
        Toast.makeText(this, "Scanning local network for WLED devices...", Toast.LENGTH_SHORT).show()
        discoverJob?.cancel()
        discoverJob = lifecycleScope.launch {
            val found = fleetCoordinator.discoverDevices()

            if (found.isEmpty()) {
                Toast.makeText(this@DeviceManagerActivity, "No additional WLED devices found via mDNS", Toast.LENGTH_LONG).show()
            } else {
                val names: Array<CharSequence> = found.map { "${it.name} (${it.ip})" }.toTypedArray()
                AlertDialog.Builder(this@DeviceManagerActivity)
                    .setTitle("Found ${found.size} WLED Devices on LAN")
                    .setItems(names) { _, which ->
                        val selected = found[which]
                        val intent = Intent(this@DeviceManagerActivity, EditDeviceActivity::class.java).apply {
                            putExtra(EditDeviceActivity.EXTRA_DEVICE_TYPE, DeviceType.LEFT_AMBIENT.name)
                            putExtra(EditDeviceActivity.EXTRA_INITIAL_NAME, selected.name)
                            putExtra(EditDeviceActivity.EXTRA_INITIAL_IP, selected.ip)
                        }
                        startActivity(intent)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }
}
