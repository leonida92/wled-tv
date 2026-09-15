package com.wled.tv.ui

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.RectF
import android.os.Bundle
import android.view.KeyEvent
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
import com.wled.tv.ui.views.HaActiveEdge
import com.wled.tv.ui.views.HaZonePreviewView
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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

        val layoutCustomBoxControls = dialogView.findViewById<LinearLayout>(R.id.layoutCustomBoxControls)
        val tvCustomBoxInstruction = dialogView.findViewById<TextView>(R.id.tvCustomBoxInstruction)
        val btnPresetCenter50 = dialogView.findViewById<TextView>(R.id.btnPresetCenter50)
        val btnPresetTopHalf = dialogView.findViewById<TextView>(R.id.btnPresetTopHalf)
        val btnPresetBottomHalf = dialogView.findViewById<TextView>(R.id.btnPresetBottomHalf)
        val btnPresetFull = dialogView.findViewById<TextView>(R.id.btnPresetFull)
        val itemBoxLeft = dialogView.findViewById<LinearLayout>(R.id.itemBoxLeft)
        val tvBoxLeftValue = dialogView.findViewById<TextView>(R.id.tvBoxLeftValue)
        val itemBoxRight = dialogView.findViewById<LinearLayout>(R.id.itemBoxRight)
        val tvBoxRightValue = dialogView.findViewById<TextView>(R.id.tvBoxRightValue)
        val itemBoxTop = dialogView.findViewById<LinearLayout>(R.id.itemBoxTop)
        val tvBoxTopValue = dialogView.findViewById<TextView>(R.id.tvBoxTopValue)
        val itemBoxBottom = dialogView.findViewById<LinearLayout>(R.id.itemBoxBottom)
        val tvBoxBottomValue = dialogView.findViewById<TextView>(R.id.tvBoxBottomValue)

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

        var activeEdge = HaActiveEdge.NONE

        fun updateEdgeCardsUi() {
            val isCustom = currentLight.zoneType == HomeAssistantZoneType.CUSTOM_RECT
            layoutCustomBoxControls.visibility = if (isCustom) View.VISIBLE else View.GONE
            if (isCustom) {
                val leftPct = (currentLight.customRect.left * 100).roundToInt()
                val rightPct = (currentLight.customRect.right * 100).roundToInt()
                val topPct = (currentLight.customRect.top * 100).roundToInt()
                val bottomPct = (currentLight.customRect.bottom * 100).roundToInt()

                val isLeftActive = (activeEdge == HaActiveEdge.LEFT)
                val isRightActive = (activeEdge == HaActiveEdge.RIGHT)
                val isTopActive = (activeEdge == HaActiveEdge.TOP)
                val isBottomActive = (activeEdge == HaActiveEdge.BOTTOM)

                tvBoxLeftValue.text = if (isLeftActive) "< $leftPct% >" else "$leftPct%"
                tvBoxLeftValue.setBackgroundResource(if (isLeftActive) R.drawable.bg_pill_button_focused else R.drawable.bg_pill_button)

                tvBoxRightValue.text = if (isRightActive) "< $rightPct% >" else "$rightPct%"
                tvBoxRightValue.setBackgroundResource(if (isRightActive) R.drawable.bg_pill_button_focused else R.drawable.bg_pill_button)

                tvBoxTopValue.text = if (isTopActive) "< $topPct% >" else "$topPct%"
                tvBoxTopValue.setBackgroundResource(if (isTopActive) R.drawable.bg_pill_button_focused else R.drawable.bg_pill_button)

                tvBoxBottomValue.text = if (isBottomActive) "< $bottomPct% >" else "$bottomPct%"
                tvBoxBottomValue.setBackgroundResource(if (isBottomActive) R.drawable.bg_pill_button_focused else R.drawable.bg_pill_button)

                haZonePreview.setActiveEdge(activeEdge)

                tvCustomBoxInstruction?.text = when (activeEdge) {
                    HaActiveEdge.LEFT -> "Editing Left Edge: Press Left/Right to adjust, OK to confirm"
                    HaActiveEdge.TOP -> "Editing Top Edge: Press Left/Right to adjust, OK to confirm"
                    HaActiveEdge.RIGHT -> "Editing Right Edge: Press Left/Right to adjust, OK to confirm"
                    HaActiveEdge.BOTTOM -> "Editing Bottom Edge: Press Left/Right to adjust, OK to confirm"
                    HaActiveEdge.NONE -> "Click an edge to adjust with Left / Right"
                }
                tvCustomBoxInstruction?.setTextColor(
                    if (activeEdge != HaActiveEdge.NONE) Color.parseColor("#38BDF8")
                    else Color.parseColor("#94A3B8")
                )
            } else {
                activeEdge = HaActiveEdge.NONE
                haZonePreview.setActiveEdge(HaActiveEdge.NONE)
            }
        }

        fun refreshDialogFields() {
            tvDialogZoneValue.text = currentLight.zoneType.displayName
            val pct = (currentLight.maxBrightness * 100) / 255
            tvDialogBriValue.text = "$pct%"
            haZonePreview.setZone(currentLight.zoneType, currentLight.customRect)
            updateEdgeCardsUi()
        }
        refreshDialogFields()

        fun persistAndNotify() {
            lightsList[lightIndex] = currentLight
            saveConfig()
        }

        fun adjustLeft(delta: Float) {
            val cur = currentLight.customRect
            val newLeft = ((cur.left + delta) * 20f).roundToInt() / 20f
            val clamped = newLeft.coerceIn(0f, cur.right - 0.05f)
            val updated = RectF(clamped, cur.top, cur.right, cur.bottom)
            currentLight = currentLight.copy(customRect = updated)
            persistAndNotify()
            refreshDialogFields()
        }

        fun adjustRight(delta: Float) {
            val cur = currentLight.customRect
            val newRight = ((cur.right + delta) * 20f).roundToInt() / 20f
            val clamped = newRight.coerceIn(cur.left + 0.05f, 1.0f)
            val updated = RectF(cur.left, cur.top, clamped, cur.bottom)
            currentLight = currentLight.copy(customRect = updated)
            persistAndNotify()
            refreshDialogFields()
        }

        fun adjustTop(delta: Float) {
            val cur = currentLight.customRect
            val newTop = ((cur.top + delta) * 20f).roundToInt() / 20f
            val clamped = newTop.coerceIn(0f, cur.bottom - 0.05f)
            val updated = RectF(cur.left, clamped, cur.right, cur.bottom)
            currentLight = currentLight.copy(customRect = updated)
            persistAndNotify()
            refreshDialogFields()
        }

        fun adjustBottom(delta: Float) {
            val cur = currentLight.customRect
            val newBottom = ((cur.bottom + delta) * 20f).roundToInt() / 20f
            val clamped = newBottom.coerceIn(cur.top + 0.05f, 1.0f)
            val updated = RectF(cur.left, cur.top, cur.right, clamped)
            currentLight = currentLight.copy(customRect = updated)
            persistAndNotify()
            refreshDialogFields()
        }

        fun setupEdgeCard(card: View, targetEdge: HaActiveEdge, onAdjust: (Float) -> Unit) {
            card.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus && activeEdge == targetEdge) {
                    activeEdge = HaActiveEdge.NONE
                    updateEdgeCardsUi()
                }
            }
            card.setOnClickListener {
                activeEdge = if (activeEdge == targetEdge) HaActiveEdge.NONE else targetEdge
                updateEdgeCardsUi()
            }
            card.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        activeEdge = if (activeEdge == targetEdge) HaActiveEdge.NONE else targetEdge
                        updateEdgeCardsUi()
                    }
                    return@setOnKeyListener true
                }
                if (activeEdge == targetEdge) {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> {
                                onAdjust(-0.05f)
                                return@setOnKeyListener true
                            }
                            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                onAdjust(0.05f)
                                return@setOnKeyListener true
                            }
                            KeyEvent.KEYCODE_BACK -> {
                                activeEdge = HaActiveEdge.NONE
                                updateEdgeCardsUi()
                                return@setOnKeyListener true
                            }
                            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                                activeEdge = HaActiveEdge.NONE
                                updateEdgeCardsUi()
                                return@setOnKeyListener false
                            }
                        }
                    } else if (event.action == KeyEvent.ACTION_UP) {
                        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_BACK) {
                            return@setOnKeyListener true
                        }
                    }
                }
                false
            }
        }

        setupEdgeCard(itemBoxLeft, HaActiveEdge.LEFT) { adjustLeft(it) }
        setupEdgeCard(itemBoxTop, HaActiveEdge.TOP) { adjustTop(it) }
        setupEdgeCard(itemBoxRight, HaActiveEdge.RIGHT) { adjustRight(it) }
        setupEdgeCard(itemBoxBottom, HaActiveEdge.BOTTOM) { adjustBottom(it) }

        btnPresetCenter50.setOnClickListener {
            activeEdge = HaActiveEdge.NONE
            currentLight = currentLight.copy(customRect = RectF(0.25f, 0.25f, 0.75f, 0.75f))
            persistAndNotify()
            refreshDialogFields()
        }
        btnPresetTopHalf.setOnClickListener {
            activeEdge = HaActiveEdge.NONE
            currentLight = currentLight.copy(customRect = RectF(0.0f, 0.0f, 1.0f, 0.5f))
            persistAndNotify()
            refreshDialogFields()
        }
        btnPresetBottomHalf.setOnClickListener {
            activeEdge = HaActiveEdge.NONE
            currentLight = currentLight.copy(customRect = RectF(0.0f, 0.5f, 1.0f, 1.0f))
            persistAndNotify()
            refreshDialogFields()
        }
        btnPresetFull.setOnClickListener {
            activeEdge = HaActiveEdge.NONE
            currentLight = currentLight.copy(customRect = RectF(0.0f, 0.0f, 1.0f, 1.0f))
            persistAndNotify()
            refreshDialogFields()
        }

        btnDialogSelectZone.setOnClickListener {
            val zones = HomeAssistantZoneType.values()
            val zoneNames = zones.map { it.displayName }.toTypedArray()
            val currentIndex = zones.indexOf(currentLight.zoneType).coerceAtLeast(0)

            AlertDialog.Builder(this)
                .setTitle("Select Sampling Zone")
                .setSingleChoiceItems(zoneNames, currentIndex) { zoneDialog, which ->
                    val selectedZone = zones[which]
                    activeEdge = HaActiveEdge.NONE
                    currentLight = currentLight.copy(zoneType = selectedZone)
                    persistAndNotify()
                    refreshDialogFields()
                    zoneDialog.dismiss()
                    if (selectedZone == HomeAssistantZoneType.CUSTOM_RECT) {
                        itemBoxLeft.requestFocus()
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
