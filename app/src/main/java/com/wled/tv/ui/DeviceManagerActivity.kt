package com.wled.tv.ui

import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
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
import androidx.lifecycle.lifecycleScope
import com.wled.tv.R
import com.wled.tv.data.PreferencesRepository
import com.wled.tv.model.DeviceType
import com.wled.tv.model.WledConfig
import com.wled.tv.model.WledDevice
import com.wled.tv.network.DeviceReachabilityCache
import com.wled.tv.network.DiscoveredWled
import com.wled.tv.network.WledDiscovery
import com.wled.tv.network.WledHttpClient
import com.wled.tv.network.WledUdpSender
import com.wled.tv.service.AmbientCaptureService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class DeviceManagerActivity : AppCompatActivity() {

    private lateinit var prefsRepo: PreferencesRepository
    private var config: WledConfig = WledConfig()

    private lateinit var layoutDeviceList: LinearLayout
    private lateinit var btnAddDeviceHeader: Button
    private lateinit var btnDiscoverLan: Button
    private lateinit var btnDone: Button

    private val udpSender = WledUdpSender()
    private val httpClient = WledHttpClient()
    private var testJob: Job? = null
    private var pingPollingJob: Job? = null
    private val deviceReachabilityMap get() = DeviceReachabilityCache.map

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_manager)

        prefsRepo = PreferencesRepository(this)
        config = prefsRepo.loadConfig()

        bindViews()
        setupListeners()
        renderDeviceList()
    }

    override fun onResume() {
        super.onResume()
        config = prefsRepo.loadConfig()
        renderDeviceList()
        startPingPolling()
    }

    override fun onPause() {
        super.onPause()
        pingPollingJob?.cancel()
        pingPollingJob = null
    }

    override fun onDestroy() {
        super.onDestroy()
        testJob?.cancel()
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

    private fun startPingPolling() {
        pingPollingJob?.cancel()
        pingPollingJob = lifecycleScope.launch {
            while (isActive) {
                for (dev in config.devices) {
                    launch(Dispatchers.IO) {
                        val reachable = httpClient.checkConnection(dev.ip)
                        deviceReachabilityMap[dev.id] = reachable
                        withContext(Dispatchers.Main) {
                            updateDeviceDot(dev.id, dev.enabled, reachable)
                        }
                    }
                }
                delay(5000)
            }
        }
    }

    private fun updateDeviceDot(deviceId: String, enabled: Boolean, reachable: Boolean?) {
        val viewDot = layoutDeviceList.findViewWithTag<View>("dot_$deviceId") ?: return
        if (!enabled) {
            viewDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#475569"))
        } else {
            when (reachable) {
                true -> viewDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#00E676"))
                false -> viewDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))
                null -> viewDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#64748B"))
            }
        }
    }

    private fun renderDeviceList() {
        layoutDeviceList.removeAllViews()

        val inflater = LayoutInflater.from(this)
        for ((index, device) in config.devices.withIndex()) {
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
            tvDetails.text = "${device.ip}:${device.port} | $extraDetails | ${device.calibration.colorOrder} | Bri: $briPct%"

            viewDot.tag = "dot_${device.id}"

            if (!device.enabled) {
                viewDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#475569"))
                btnToggle.text = "OFF"
                btnToggle.setTextColor(Color.parseColor("#94A3B8"))
            } else {
                when (deviceReachabilityMap[device.id]) {
                    true -> viewDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#00E676"))
                    false -> viewDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))
                    null -> viewDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#64748B"))
                }
                btnToggle.text = "ON"
                btnToggle.setTextColor(Color.parseColor("#00E676"))
            }

            btnToggle.setOnClickListener {
                val currentDev = config.devices.getOrNull(index) ?: return@setOnClickListener
                val newEnabled = !currentDev.enabled
                val updatedList = config.devices.toMutableList()
                val updatedDevice = currentDev.copy(enabled = newEnabled)
                updatedList[index] = updatedDevice
                config = config.copy(devices = updatedList)
                saveAndUpdate()

                if (newEnabled) {
                    btnToggle.text = "ON"
                    btnToggle.setTextColor(Color.parseColor("#00E676"))
                    updateDeviceDot(updatedDevice.id, true, deviceReachabilityMap[updatedDevice.id])
                } else {
                    btnToggle.text = "OFF"
                    btnToggle.setTextColor(Color.parseColor("#94A3B8"))
                    updateDeviceDot(updatedDevice.id, false, null)
                }

                lifecycleScope.launch(Dispatchers.IO) {
                    if (newEnabled) {
                        val reachable = httpClient.checkConnection(updatedDevice.ip)
                        deviceReachabilityMap[updatedDevice.id] = reachable
                        withContext(Dispatchers.Main) {
                            updateDeviceDot(updatedDevice.id, true, reachable)
                        }
                        httpClient.wakeAndSetBrightness(updatedDevice.ip, updatedDevice.calibration.maxBrightness)
                    } else {
                        val totalLeds = updatedDevice.totalLeds
                        if (totalLeds > 0) {
                            val blackFrame = ByteArray(totalLeds * 3)
                            udpSender.sendDrgbFrame(
                                ip = updatedDevice.ip,
                                port = updatedDevice.port,
                                timeoutSeconds = 2,
                                rgb = blackFrame,
                                ledCount = totalLeds,
                                colorOrder = updatedDevice.calibration.colorOrder
                            )
                        }
                        httpClient.turnOff(updatedDevice.ip)
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
                            val updatedList = config.devices.toMutableList()
                            updatedList.removeAt(index)
                            config = config.copy(devices = updatedList)
                            saveAndUpdate()
                            renderDeviceList()
                            lifecycleScope.launch(Dispatchers.IO) {
                                val totalLeds = device.totalLeds
                                if (totalLeds > 0) {
                                    udpSender.sendDrgbFrame(device.ip, device.port, 2, ByteArray(totalLeds * 3), totalLeds, device.calibration.colorOrder)
                                }
                                httpClient.turnOff(device.ip)
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

        testJob = lifecycleScope.launch(Dispatchers.IO) {
            val totalLeds = device.totalLeds
            val whiteFrame = ByteArray(totalLeds * 3) { (255).toByte() }
            val blackFrame = ByteArray(totalLeds * 3)

            for (flash in 1..3) {
                if (!isActive) break
                udpSender.sendDrgbFrame(device.ip, device.port, timeoutSeconds = 2, rgb = whiteFrame, ledCount = totalLeds, colorOrder = device.calibration.colorOrder)
                delay(300)
                udpSender.sendDrgbFrame(device.ip, device.port, timeoutSeconds = 2, rgb = blackFrame, ledCount = totalLeds, colorOrder = device.calibration.colorOrder)
                delay(300)
            }
            if (device.enabled) {
                httpClient.wakeAndSetBrightness(device.ip, device.calibration.maxBrightness)
            } else {
                httpClient.turnOff(device.ip)
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
        val found = mutableListOf<DiscoveredWled>()
        val discovery = WledDiscovery(this)

        lifecycleScope.launch {
            val job = launch(Dispatchers.IO) {
                discovery.discoverDevices().collect { device ->
                    if (found.none { it.ip == device.ip }) {
                        found.add(device)
                    }
                }
            }

            delay(2500)
            job.cancel()

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

    private fun saveAndUpdate() {
        prefsRepo.saveConfig(config)
        if (AmbientCaptureService.isRunning) {
            AmbientCaptureService.currentServiceInstance?.reloadConfig()
        }
    }
}
