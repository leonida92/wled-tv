package com.wled.tv.ui

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.RectF
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wled.tv.R
import com.wled.tv.data.PreferencesRepository
import com.wled.tv.model.HomeAssistantConfig
import com.wled.tv.model.HomeAssistantLight
import com.wled.tv.model.HomeAssistantZoneType
import com.wled.tv.model.LightCapability
import com.wled.tv.network.HomeAssistantWebSocketClient
import com.wled.tv.service.AmbientCaptureService
import com.wled.tv.ui.views.HaZonePreviewView
import kotlinx.coroutines.launch

class HomeAssistantSettingsActivity : AppCompatActivity() {

    private lateinit var prefsRepo: PreferencesRepository
    private var haConfig: HomeAssistantConfig = HomeAssistantConfig()
    private val lightsList = mutableListOf<HomeAssistantLight>()
    private val haClient = HomeAssistantWebSocketClient()
    private var hideDisabledLights: Boolean = false

    // Main Views
    private lateinit var itemHaServerConnection: LinearLayout
    private lateinit var tvHaServerSummary: TextView
    private lateinit var tvHaServerAction: TextView

    private lateinit var tvHaLightsCount: TextView
    private lateinit var btnHaToggleHideDisabled: LinearLayout
    private lateinit var tvHaHideDisabledText: TextView
    private lateinit var btnHaRefreshLights: LinearLayout
    private lateinit var ivHaRefreshIcon: ImageView
    private lateinit var layoutHaLightsContainer: LinearLayout
    private lateinit var tvHaEmptyLights: TextView

    private lateinit var itemHaSyncRate: LinearLayout
    private lateinit var tvHaSyncRateValue: TextView

    private lateinit var itemHaTurnOffOnStop: LinearLayout
    private lateinit var tvHaTurnOffOnStopValue: TextView

    private lateinit var itemHaDarkCutoff: LinearLayout
    private lateinit var tvHaDarkCutoffValue: TextView

    private lateinit var itemHaAutomationGuide: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home_assistant_settings)

        prefsRepo = PreferencesRepository(this)
        val fullConfig = prefsRepo.loadConfig()
        haConfig = fullConfig.homeAssistant
        lightsList.clear()
        lightsList.addAll(haConfig.lights)

        hideDisabledLights = getSharedPreferences("wled_tv_prefs", MODE_PRIVATE)
            .getBoolean("ha_hide_disabled", false)

        bindViews()
        populateUi()
        setupListeners()
        renderLightsList()

        itemHaServerConnection.requestFocus()
    }

    private fun bindViews() {
        itemHaServerConnection = findViewById(R.id.itemHaServerConnection)
        tvHaServerSummary = findViewById(R.id.tvHaServerSummary)
        tvHaServerAction = findViewById(R.id.tvHaServerAction)

        tvHaLightsCount = findViewById(R.id.tvHaLightsCount)
        btnHaToggleHideDisabled = findViewById(R.id.btnHaToggleHideDisabled)
        tvHaHideDisabledText = findViewById(R.id.tvHaHideDisabledText)
        btnHaRefreshLights = findViewById(R.id.btnHaRefreshLights)
        ivHaRefreshIcon = findViewById(R.id.ivHaRefreshIcon)
        layoutHaLightsContainer = findViewById(R.id.layoutHaLightsContainer)
        tvHaEmptyLights = findViewById(R.id.tvHaEmptyLights)

        itemHaSyncRate = findViewById(R.id.itemHaSyncRate)
        tvHaSyncRateValue = findViewById(R.id.tvHaSyncRateValue)

        itemHaTurnOffOnStop = findViewById(R.id.itemHaTurnOffOnStop)
        tvHaTurnOffOnStopValue = findViewById(R.id.tvHaTurnOffOnStopValue)

        itemHaDarkCutoff = findViewById(R.id.itemHaDarkCutoff)
        tvHaDarkCutoffValue = findViewById(R.id.tvHaDarkCutoffValue)

        itemHaAutomationGuide = findViewById(R.id.itemHaAutomationGuide)
    }

    private fun populateUi() {
        updateServerSummaryUi()
        updateHideDisabledUi()
        updateSyncRateUi()
        updateTurnOffOnStopUi()
        updateDarkCutoffUi()
    }

    private fun updateServerSummaryUi() {
        if (haConfig.host.isBlank()) {
            tvHaServerSummary.text = "Not configured"
            tvHaServerAction.text = "Configure"
            tvHaServerAction.setTextColor(Color.parseColor("#00E5FF"))
        } else {
            val scheme = if (haConfig.useSsl) "https" else "http"
            tvHaServerSummary.text = "$scheme://${haConfig.host}:${haConfig.port}"
            tvHaServerAction.text = "Edit"
            tvHaServerAction.setTextColor(Color.parseColor("#00E5FF"))
        }
    }

    private fun updateHideDisabledUi() {
        if (hideDisabledLights) {
            tvHaHideDisabledText.text = "Hide Disabled: ON"
            tvHaHideDisabledText.setTextColor(Color.parseColor("#00E676"))
        } else {
            tvHaHideDisabledText.text = "Hide Disabled: OFF"
            tvHaHideDisabledText.setTextColor(Color.parseColor("#94A3B8"))
        }
    }

    private fun updateSyncRateUi() {
        val text = when (haConfig.updateIntervalMs) {
            150L -> "150 ms (Responsive)"
            500L -> "500 ms (Eco)"
            else -> "300 ms (Balanced)"
        }
        tvHaSyncRateValue.text = text
    }

    private fun updateTurnOffOnStopUi() {
        if (haConfig.turnOffOnStop) {
            tvHaTurnOffOnStopValue.text = "Enabled"
            tvHaTurnOffOnStopValue.setTextColor(Color.parseColor("#00E676"))
        } else {
            tvHaTurnOffOnStopValue.text = "Disabled"
            tvHaTurnOffOnStopValue.setTextColor(Color.parseColor("#94A3B8"))
        }
    }

    private fun updateDarkCutoffUi() {
        if (haConfig.darkCutoffEnabled) {
            tvHaDarkCutoffValue.text = "Enabled"
            tvHaDarkCutoffValue.setTextColor(Color.parseColor("#00E676"))
        } else {
            tvHaDarkCutoffValue.text = "Disabled"
            tvHaDarkCutoffValue.setTextColor(Color.parseColor("#94A3B8"))
        }
    }

    private fun setupListeners() {
        // Server Connection Dialog
        itemHaServerConnection.setOnClickListener {
            showServerConnectionDialog()
        }

        // Toggle Hide Disabled Lights
        btnHaToggleHideDisabled.setOnClickListener {
            hideDisabledLights = !hideDisabledLights
            getSharedPreferences("wled_tv_prefs", MODE_PRIVATE)
                .edit()
                .putBoolean("ha_hide_disabled", hideDisabledLights)
                .apply()
            updateHideDisabledUi()
            renderLightsList()
        }

        // Refresh / Discover Smart Lights button
        btnHaRefreshLights.setOnClickListener {
            discoverLights()
        }

        // Sync Rate Card (Cycles 150ms -> 300ms -> 500ms)
        itemHaSyncRate.setOnClickListener {
            val next = when (haConfig.updateIntervalMs) {
                150L -> 300L
                300L -> 500L
                else -> 150L
            }
            haConfig = haConfig.copy(updateIntervalMs = next)
            updateSyncRateUi()
            saveConfig()
        }

        // Turn Off on Standby
        itemHaTurnOffOnStop.setOnClickListener {
            haConfig = haConfig.copy(turnOffOnStop = !haConfig.turnOffOnStop)
            updateTurnOffOnStopUi()
            saveConfig()
        }

        // Dark Scene Cutoff
        itemHaDarkCutoff.setOnClickListener {
            haConfig = haConfig.copy(darkCutoffEnabled = !haConfig.darkCutoffEnabled)
            updateDarkCutoffUi()
            saveConfig()
        }

        // Automation Control Guide Dialog
        itemHaAutomationGuide.setOnClickListener {
            showAutomationGuideDialog()
        }
    }

    private fun showServerConnectionDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_ha_server_connection, null)
        val etHost = dialogView.findViewById<EditText>(R.id.etDialogHaHost)
        val etPort = dialogView.findViewById<EditText>(R.id.etDialogHaPort)
        val btnToggleSsl = dialogView.findViewById<LinearLayout>(R.id.btnDialogToggleSsl)
        val tvSslStatus = dialogView.findViewById<TextView>(R.id.tvDialogSslStatus)
        val etToken = dialogView.findViewById<EditText>(R.id.etDialogHaToken)
        val btnTest = dialogView.findViewById<Button>(R.id.btnDialogTestConnection)
        val tvStatus = dialogView.findViewById<TextView>(R.id.tvDialogConnectionStatus)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnDialogCancel)
        val btnSave = dialogView.findViewById<Button>(R.id.btnDialogSave)

        etHost.setText(haConfig.host)
        etPort.setText(haConfig.port.toString())
        etToken.setText(haConfig.token)

        var localSsl = haConfig.useSsl
        fun refreshSslLabel() {
            if (localSsl) {
                tvSslStatus.text = "HTTPS / WSS"
                tvSslStatus.setTextColor(Color.parseColor("#00E676"))
            } else {
                tvSslStatus.text = "HTTP / WS"
                tvSslStatus.setTextColor(Color.parseColor("#94A3B8"))
            }
        }
        refreshSslLabel()

        btnToggleSsl.setOnClickListener {
            localSsl = !localSsl
            refreshSslLabel()
        }

        btnTest.setOnClickListener {
            val testHost = etHost.text.toString().trim()
            val testPort = etPort.text.toString().trim().toIntOrNull() ?: 8123
            val testToken = etToken.text.toString().trim()

            if (testHost.isBlank()) {
                tvStatus.text = "Status: Enter host first"
                tvStatus.setTextColor(Color.parseColor("#F87171"))
                return@setOnClickListener
            }

            val testCfg = haConfig.copy(host = testHost, port = testPort, useSsl = localSsl, token = testToken)
            tvStatus.text = "Status: Testing connection..."
            tvStatus.setTextColor(Color.parseColor("#00E5FF"))

            lifecycleScope.launch {
                val ok = if (testCfg.cleanHost.equals("demo", ignoreCase = true)) {
                    true
                } else {
                    haClient.testConnection(testCfg)
                }
                if (ok) {
                    tvStatus.text = "Status: Connected successfully"
                    tvStatus.setTextColor(Color.parseColor("#00E676"))
                } else {
                    tvStatus.text = "Status: Connection failed. Check IP & token"
                    tvStatus.setTextColor(Color.parseColor("#F87171"))
                }
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnSave.setOnClickListener {
            val newHost = etHost.text.toString().trim()
            val newPort = etPort.text.toString().trim().toIntOrNull() ?: 8123
            val newToken = etToken.text.toString().trim()

            haConfig = haConfig.copy(
                host = newHost,
                port = newPort,
                useSsl = localSsl,
                token = newToken
            )
            updateServerSummaryUi()
            saveConfig()
            dialog.dismiss()
        }

        dialog.show()
        etHost.requestFocus()
    }

    private fun discoverLights() {
        if (haConfig.host.isBlank() && !haConfig.cleanHost.equals("demo", ignoreCase = true)) {
            Toast.makeText(this, "Configure Home Assistant server first", Toast.LENGTH_SHORT).show()
            showServerConnectionDialog()
            return
        }

        Toast.makeText(this, "Scanning Home Assistant for lights...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            try {
                val fetched = if (haConfig.cleanHost.equals("demo", ignoreCase = true)) {
                    listOf(
                        HomeAssistantLight("light.living_room_tv_rgb", "Living Room TV Strip", LightCapability.COLOR_AND_BRIGHTNESS, enabled = false, zoneType = HomeAssistantZoneType.FULL_SCREEN_AVERAGE),
                        HomeAssistantLight("light.floor_lamp", "Floor Lamp Dimmer", LightCapability.BRIGHTNESS_ONLY, enabled = false, zoneType = HomeAssistantZoneType.LEFT_AMBIENT),
                        HomeAssistantLight("light.ceiling_accent", "Ceiling Accent RGB", LightCapability.COLOR_AND_BRIGHTNESS, enabled = false, zoneType = HomeAssistantZoneType.TOP_AMBIENT),
                        HomeAssistantLight("light.reading_light", "Reading Spot Light", LightCapability.BRIGHTNESS_ONLY, enabled = false, zoneType = HomeAssistantZoneType.CUSTOM_RECT, customRect = RectF(0.2f, 0.2f, 0.8f, 0.8f))
                    )
                } else {
                    haClient.fetchLights(haConfig)
                }

                val existingMap = lightsList.associateBy { it.entityId }
                val merged = fetched.map { newLight ->
                    val existing = existingMap[newLight.entityId]
                    if (existing != null) {
                        newLight.copy(
                            enabled = existing.enabled,
                            zoneType = existing.zoneType,
                            customRect = existing.customRect,
                            maxBrightness = existing.maxBrightness
                        )
                    } else {
                        newLight.copy(enabled = false)
                    }
                }

                lightsList.clear()
                lightsList.addAll(merged)
                renderLightsList()
                saveConfig()

                Toast.makeText(this@HomeAssistantSettingsActivity, "Discovered ${lightsList.size} smart lights", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@HomeAssistantSettingsActivity, "Discovery error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateLightsCountHeader() {
        val activeCount = lightsList.count { it.enabled && it.capability != LightCapability.UNSUPPORTED }
        val visibleIndices = lightsList.indices.filter { idx ->
            if (hideDisabledLights) {
                lightsList[idx].enabled && lightsList[idx].capability != LightCapability.UNSUPPORTED
            } else {
                true
            }
        }

        val hiddenCount = lightsList.size - visibleIndices.size
        tvHaLightsCount.text = if (hiddenCount > 0) {
            "$activeCount active ($hiddenCount hidden)"
        } else {
            "$activeCount active of ${lightsList.size}"
        }
    }

    private fun renderLightsList(focusActualIndex: Int? = null, focusViewId: Int? = null) {
        layoutHaLightsContainer.removeAllViews()

        val visibleIndices = lightsList.indices.filter { idx ->
            if (hideDisabledLights) {
                lightsList[idx].enabled && lightsList[idx].capability != LightCapability.UNSUPPORTED
            } else {
                true
            }
        }

        updateLightsCountHeader()

        if (visibleIndices.isEmpty()) {
            if (lightsList.isEmpty()) {
                tvHaEmptyLights.text = "No smart lights configured. Click the refresh button above to scan Home Assistant."
            } else {
                tvHaEmptyLights.text = "All disabled lights are hidden. Toggle 'Hide Disabled' above to view them."
            }
            layoutHaLightsContainer.addView(tvHaEmptyLights)
            return
        }

        val inflater = LayoutInflater.from(this)
        for (actualIndex in visibleIndices) {
            val light = lightsList[actualIndex]
            val itemView = inflater.inflate(R.layout.item_ha_light, layoutHaLightsContainer, false)

            val ivIcon = itemView.findViewById<ImageView>(R.id.ivLightBulbIcon)
            val tvName = itemView.findViewById<TextView>(R.id.tvLightName)
            val tvBadge = itemView.findViewById<TextView>(R.id.tvCapabilityBadge)
            val tvEntity = itemView.findViewById<TextView>(R.id.tvEntityId)
            val layoutLightInfo = itemView.findViewById<View>(R.id.layoutLightInfo)
            val btnConfigure = itemView.findViewById<View>(R.id.btnConfigureLight)
            val tvPill = itemView.findViewById<TextView>(R.id.tvLightStatusPill)
            val btnToggle = itemView.findViewById<View>(R.id.btnToggleLight)
            val tvToggle = itemView.findViewById<TextView>(R.id.tvToggleLight)

            tvName.text = light.name
            tvEntity.text = light.entityId

            // Capability Badge
            when (light.capability) {
                LightCapability.COLOR_AND_BRIGHTNESS -> {
                    tvBadge.text = "RGB + Dim"
                    tvBadge.setTextColor(Color.parseColor("#00E5FF"))
                }
                LightCapability.BRIGHTNESS_ONLY -> {
                    tvBadge.text = "Dimmer Only"
                    tvBadge.setTextColor(Color.parseColor("#FBBF24"))
                }
                LightCapability.UNSUPPORTED -> {
                    tvBadge.text = "Unsupported"
                    tvBadge.setTextColor(Color.parseColor("#64748B"))
                }
            }

            fun updateCardUi(l: HomeAssistantLight) {
                val briPct = (l.maxBrightness * 100) / 255
                tvPill.text = "${l.zoneType.displayName} • $briPct%"

                if (l.capability == LightCapability.UNSUPPORTED) {
                    tvPill.text = "Unsupported"
                    tvPill.setTextColor(Color.parseColor("#64748B"))
                    tvToggle.text = "--"
                    tvToggle.setTextColor(Color.parseColor("#64748B"))
                    btnToggle.isEnabled = false
                    btnConfigure.isEnabled = false
                    ivIcon.setColorFilter(Color.parseColor("#64748B"))
                } else if (l.enabled) {
                    tvPill.setTextColor(Color.parseColor("#00E5FF"))
                    tvToggle.text = "ON"
                    tvToggle.setTextColor(Color.parseColor("#00E676"))
                    btnToggle.isEnabled = true
                    btnConfigure.isEnabled = true
                    ivIcon.setColorFilter(Color.parseColor("#00E5FF"))
                } else {
                    tvPill.setTextColor(Color.parseColor("#94A3B8"))
                    tvToggle.text = "OFF"
                    tvToggle.setTextColor(Color.parseColor("#94A3B8"))
                    btnToggle.isEnabled = true
                    btnConfigure.isEnabled = true
                    ivIcon.setColorFilter(Color.parseColor("#64748B"))
                }
            }

            updateCardUi(light)

            // Open dialog from card body or configure pill
            layoutLightInfo.setOnClickListener {
                showLightOptionsDialog(actualIndex)
            }
            btnConfigure.setOnClickListener {
                showLightOptionsDialog(actualIndex)
            }

            // Direct toggle from card
            btnToggle.setOnClickListener {
                val current = lightsList[actualIndex]
                if (current.capability == LightCapability.UNSUPPORTED) return@setOnClickListener
                val newEnabled = !current.enabled
                val updated = current.copy(enabled = newEnabled)
                lightsList[actualIndex] = updated
                saveConfig()

                if (hideDisabledLights && !newEnabled) {
                    renderLightsList()
                } else {
                    updateCardUi(updated)
                    updateLightsCountHeader()
                }
            }

            layoutHaLightsContainer.addView(itemView)
        }

        if (focusActualIndex != null) {
            val targetPos = visibleIndices.indexOf(focusActualIndex)
            if (targetPos in 0 until layoutHaLightsContainer.childCount) {
                val child = layoutHaLightsContainer.getChildAt(targetPos)
                val targetView = if (focusViewId != null) child.findViewById<View>(focusViewId) else child
                targetView?.post { targetView.requestFocus() }
            }
        }
    }

    private fun showLightOptionsDialog(lightIndex: Int) {
        val light = lightsList[lightIndex]
        var currentLight = light

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_ha_light_options, null)
        val tvDialogLightName = dialogView.findViewById<TextView>(R.id.tvDialogLightName)
        val tvDialogCapabilityBadge = dialogView.findViewById<TextView>(R.id.tvDialogCapabilityBadge)
        val tvDialogEntityId = dialogView.findViewById<TextView>(R.id.tvDialogEntityId)
        val haZonePreview = dialogView.findViewById<HaZonePreviewView>(R.id.haZonePreview)

        val btnDialogSelectZone = dialogView.findViewById<LinearLayout>(R.id.btnDialogSelectZone)
        val tvDialogZoneValue = dialogView.findViewById<TextView>(R.id.tvDialogLightZoneValue)

        val btnDialogSelectBri = dialogView.findViewById<LinearLayout>(R.id.btnDialogSelectBri)
        val tvDialogBriValue = dialogView.findViewById<TextView>(R.id.tvDialogLightBriValue)

        val btnDialogLightDone = dialogView.findViewById<Button>(R.id.btnDialogLightDone)

        tvDialogLightName.text = currentLight.name
        tvDialogEntityId.text = currentLight.entityId

        when (currentLight.capability) {
            LightCapability.COLOR_AND_BRIGHTNESS -> {
                tvDialogCapabilityBadge.text = "RGB + Dim"
                tvDialogCapabilityBadge.setTextColor(Color.parseColor("#00E5FF"))
            }
            LightCapability.BRIGHTNESS_ONLY -> {
                tvDialogCapabilityBadge.text = "Dimmer Only"
                tvDialogCapabilityBadge.setTextColor(Color.parseColor("#FBBF24"))
            }
            LightCapability.UNSUPPORTED -> {
                tvDialogCapabilityBadge.text = "Unsupported"
                tvDialogCapabilityBadge.setTextColor(Color.parseColor("#64748B"))
            }
        }

        fun refreshDialogFields() {
            tvDialogZoneValue.text = currentLight.zoneType.displayName
            val pct = (currentLight.maxBrightness * 100) / 255
            tvDialogBriValue.text = "$pct%"
            haZonePreview.setZone(currentLight.zoneType, currentLight.customRect)
        }
        refreshDialogFields()

        fun persistAndNotify() {
            lightsList[lightIndex] = currentLight
            saveConfig()
        }

        btnDialogSelectZone.setOnClickListener {
            val zones = HomeAssistantZoneType.values()
            val zoneNames = zones.map { it.displayName }.toTypedArray()
            val currentIndex = zones.indexOf(currentLight.zoneType).coerceAtLeast(0)

            AlertDialog.Builder(this)
                .setTitle("Select Sampling Zone")
                .setSingleChoiceItems(zoneNames, currentIndex) { zoneDialog, which ->
                    val selectedZone = zones[which]
                    if (selectedZone == HomeAssistantZoneType.CUSTOM_RECT) {
                        zoneDialog.dismiss()
                        showCustomBoxDialog(currentLight) { updatedRect ->
                            currentLight = currentLight.copy(
                                zoneType = HomeAssistantZoneType.CUSTOM_RECT,
                                customRect = updatedRect
                            )
                            persistAndNotify()
                            refreshDialogFields()
                        }
                    } else {
                        currentLight = currentLight.copy(zoneType = selectedZone)
                        persistAndNotify()
                        refreshDialogFields()
                        zoneDialog.dismiss()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        btnDialogSelectBri.setOnClickListener {
            val nextBri = when (currentLight.maxBrightness) {
                255 -> (255 * 0.75f).toInt()
                (255 * 0.75f).toInt() -> (255 * 0.50f).toInt()
                (255 * 0.50f).toInt() -> (255 * 0.25f).toInt()
                else -> 255
            }
            currentLight = currentLight.copy(maxBrightness = nextBri)
            persistAndNotify()
            refreshDialogFields()
        }

        val parentDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        parentDialog.setOnDismissListener {
            persistAndNotify()
            renderLightsList(focusActualIndex = lightIndex, focusViewId = R.id.btnConfigureLight)
        }

        btnDialogLightDone.setOnClickListener {
            parentDialog.dismiss()
        }

        parentDialog.show()
        btnDialogSelectZone.requestFocus()
    }

    private fun showCustomBoxDialog(
        currentLight: HomeAssistantLight,
        onApplied: (RectF) -> Unit
    ) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_ha_custom_box, null)

        val etLeft = dialogView.findViewById<EditText>(R.id.etBoxLeft)
        val etTop = dialogView.findViewById<EditText>(R.id.etBoxTop)
        val etRight = dialogView.findViewById<EditText>(R.id.etBoxRight)
        val etBottom = dialogView.findViewById<EditText>(R.id.etBoxBottom)

        etLeft.setText((currentLight.customRect.left * 100).toInt().toString())
        etTop.setText((currentLight.customRect.top * 100).toInt().toString())
        etRight.setText((currentLight.customRect.right * 100).toInt().toString())
        etBottom.setText((currentLight.customRect.bottom * 100).toInt().toString())

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Apply") { _, _ ->
                val l = (etLeft.text.toString().toFloatOrNull() ?: 0f) / 100f
                val t = (etTop.text.toString().toFloatOrNull() ?: 0f) / 100f
                val r = (etRight.text.toString().toFloatOrNull() ?: 100f) / 100f
                val b = (etBottom.text.toString().toFloatOrNull() ?: 100f) / 100f

                val newRect = RectF(
                    l.coerceIn(0f, 1f),
                    t.coerceIn(0f, 1f),
                    r.coerceIn(0.01f, 1f),
                    b.coerceIn(0.01f, 1f)
                )

                onApplied(newRect)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAutomationGuideDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_ha_automation_guide, null)
        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun saveConfig() {
        val updatedHaConfig = haConfig.copy(lights = lightsList)
        val currentFullConfig = prefsRepo.loadConfig()
        val newFullConfig = currentFullConfig.copy(homeAssistant = updatedHaConfig)
        prefsRepo.saveConfig(newFullConfig)

        if (AmbientCaptureService.isRunning) {
            AmbientCaptureService.currentServiceInstance?.reloadConfig()
        }
    }
}
