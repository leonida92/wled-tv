package com.wled.tv.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.wled.tv.R
import com.wled.tv.data.ConfigRepository
import com.wled.tv.engine.DiagnosticStreamCoordinator
import com.wled.tv.model.ColorCalibration
import com.wled.tv.model.PresetProfile
import com.wled.tv.model.WledConfig
import com.wled.tv.model.WledDevice
import com.wled.tv.service.AmbientCaptureService
import java.util.Locale
import kotlin.math.roundToInt

class CalibrationActivity : AppCompatActivity() {

    private lateinit var configRepo: ConfigRepository
    private var config: WledConfig = WledConfig()
    private var targetDeviceId: String? = null
    private var draftDevice: WledDevice? = null

    private val targetDevice: WledDevice
        get() = draftDevice ?: if (targetDeviceId != null) {
            config.devices.firstOrNull { it.id == targetDeviceId } ?: config.primaryDevice
        } else {
            config.primaryDevice
        }

    private val currentCalibration: ColorCalibration
        get() = targetDevice.calibration

    private val diagnosticCoordinator: DiagnosticStreamCoordinator = DiagnosticStreamCoordinator(
        engineProvider = { AmbientCaptureService.activeEngine.value }
    )

    private var activePreset: PresetProfile = PresetProfile.DEFAULT

    private lateinit var btnPresetSelector: Button
    private lateinit var btnResetPreset: Button
    private lateinit var tvPresetDescription: TextView

    private lateinit var viewRefBarTop: View
    private lateinit var viewRefBarBottom: View
    private lateinit var viewRefBarLeft: View
    private lateinit var viewRefBarRight: View

    private lateinit var tvActiveColorMode: TextView
    private lateinit var btnColorLive: Button
    private lateinit var btnColorWhite: Button
    private lateinit var btnColorWarm: Button
    private lateinit var btnColorRed: Button
    private lateinit var btnColorGreen: Button
    private lateinit var btnColorBlue: Button
    private lateinit var btnColorCyan: Button
    private lateinit var btnColorMagenta: Button
    private lateinit var btnColorYellow: Button

    private lateinit var itemSaturation: LinearLayout
    private lateinit var tvSaturationValue: TextView
    private lateinit var itemContrast: LinearLayout
    private lateinit var tvContrastValue: TextView
    private lateinit var itemColorOrder: LinearLayout
    private lateinit var tvColorOrderValue: TextView
    private lateinit var itemBrightness: LinearLayout
    private lateinit var tvBrightnessValue: TextView
    private lateinit var itemBlackThreshold: LinearLayout
    private lateinit var tvBlackThresholdValue: TextView
    private lateinit var itemGainR: LinearLayout
    private lateinit var tvGainRValue: TextView
    private lateinit var itemGainG: LinearLayout
    private lateinit var tvGainGValue: TextView
    private lateinit var itemGainB: LinearLayout
    private lateinit var tvGainBValue: TextView
    private lateinit var itemSmoothing: LinearLayout
    private lateinit var tvSmoothingValue: TextView

    private val colorOrders = listOf("RGB", "GRB", "BGR", "BRG", "RBG", "GBR")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calibration)

        targetDeviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
        draftDevice = (savedInstanceState?.getString(EXTRA_DEVICE_DRAFT_JSON)
            ?: intent.getStringExtra(EXTRA_DEVICE_DRAFT_JSON))?.let { json ->
            ConfigRepository.decodeDeviceJson(json)
        }
        configRepo = ConfigRepository.getInstance(this)
        val repoConfig = configRepo.currentConfig
        val initialDraft = draftDevice
        config = if (initialDraft != null) {
            targetDeviceId = initialDraft.id
            if (savedInstanceState != null) {
                setResult(RESULT_OK, Intent().putExtra(EXTRA_DEVICE_DRAFT_JSON, ConfigRepository.encodeDeviceJson(initialDraft)))
            }
            withDraftInConfig(repoConfig, initialDraft)
        } else {
            repoConfig
        }
        diagnosticCoordinator.updateConfig(config)
        activePreset = if (config.activeProfile.id == PresetProfile.CUSTOM.id) PresetProfile.DEFAULT else config.activeProfile

        bindViews()
        setupListeners()
        updateUiValues()

        lifecycleScope.launch {
            configRepo.configFlow.collect { latest ->
                config = draftDevice?.let { withDraftInConfig(latest, it) } ?: latest
                diagnosticCoordinator.updateConfig(config)
                updateUiValues()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        draftDevice?.let { outState.putString(EXTRA_DEVICE_DRAFT_JSON, ConfigRepository.encodeDeviceJson(it)) }
    }

    override fun onDestroy() {
        super.onDestroy()
        diagnosticCoordinator.stop()
    }

    private fun bindViews() {
        btnPresetSelector = findViewById(R.id.btnPresetSelector)
        btnResetPreset = findViewById(R.id.btnResetPreset)
        tvPresetDescription = findViewById(R.id.tvPresetDescription)

        viewRefBarTop = findViewById(R.id.viewRefBarTop)
        viewRefBarBottom = findViewById(R.id.viewRefBarBottom)
        viewRefBarLeft = findViewById(R.id.viewRefBarLeft)
        viewRefBarRight = findViewById(R.id.viewRefBarRight)

        tvActiveColorMode = findViewById(R.id.tvActiveColorMode)
        btnColorLive = findViewById(R.id.btnColorLive)
        btnColorWhite = findViewById(R.id.btnColorWhite)
        btnColorWarm = findViewById(R.id.btnColorWarm)
        btnColorRed = findViewById(R.id.btnColorRed)
        btnColorGreen = findViewById(R.id.btnColorGreen)
        btnColorBlue = findViewById(R.id.btnColorBlue)
        btnColorCyan = findViewById(R.id.btnColorCyan)
        btnColorMagenta = findViewById(R.id.btnColorMagenta)
        btnColorYellow = findViewById(R.id.btnColorYellow)

        itemSaturation = findViewById(R.id.itemSaturation)
        tvSaturationValue = findViewById(R.id.tvSaturationValue)
        itemContrast = findViewById(R.id.itemContrast)
        tvContrastValue = findViewById(R.id.tvContrastValue)
        itemColorOrder = findViewById(R.id.itemColorOrder)
        tvColorOrderValue = findViewById(R.id.tvColorOrderValue)
        itemBrightness = findViewById(R.id.itemBrightness)
        tvBrightnessValue = findViewById(R.id.tvBrightnessValue)
        itemBlackThreshold = findViewById(R.id.itemBlackThreshold)
        tvBlackThresholdValue = findViewById(R.id.tvBlackThresholdValue)
        itemGainR = findViewById(R.id.itemGainR)
        tvGainRValue = findViewById(R.id.tvGainRValue)
        itemGainG = findViewById(R.id.itemGainG)
        tvGainGValue = findViewById(R.id.tvGainGValue)
        itemGainB = findViewById(R.id.itemGainB)
        tvGainBValue = findViewById(R.id.tvGainBValue)
        itemSmoothing = findViewById(R.id.itemSmoothing)
        tvSmoothingValue = findViewById(R.id.tvSmoothingValue)

        btnColorLive.requestFocus()
    }

    private fun setupListeners() {
        btnPresetSelector.setOnClickListener { showPresetPicker() }
        btnResetPreset.setOnClickListener { resetToPreset() }

        btnColorLive.setOnClickListener { selectColorMode(null, "Live Screen") }
        btnColorWhite.setOnClickListener { selectColorMode(Triple(255, 255, 255), "White (6500K)") }
        btnColorWarm.setOnClickListener { selectColorMode(Triple(255, 190, 120), "Warm White (3200K)") }
        btnColorRed.setOnClickListener { selectColorMode(Triple(255, 0, 0), "Red") }
        btnColorGreen.setOnClickListener { selectColorMode(Triple(0, 255, 0), "Green") }
        btnColorBlue.setOnClickListener { selectColorMode(Triple(0, 0, 255), "Blue") }
        btnColorCyan.setOnClickListener { selectColorMode(Triple(0, 255, 255), "Cyan") }
        btnColorMagenta.setOnClickListener { selectColorMode(Triple(255, 0, 255), "Magenta") }
        btnColorYellow.setOnClickListener { selectColorMode(Triple(255, 255, 0), "Yellow") }

        setupSliderItem(itemSaturation, step = 0.1f, onAdjust = { delta ->
            updateTargetCalibration { c ->
                val newSat = ((c.saturation + delta) * 10f).roundToInt() / 10f
                c.copy(saturation = newSat.coerceIn(0.5f, 3.0f))
            }
        })

        setupSliderItem(itemContrast, step = 0.05f, onAdjust = { delta ->
            updateTargetCalibration { c ->
                val newContrast = ((c.contrast + delta) * 100f).roundToInt() / 100f
                c.copy(contrast = newContrast.coerceIn(0.5f, 1.5f))
            }
        })

        itemColorOrder.setOnClickListener { cycleColorOrder(1) }
        itemColorOrder.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { cycleColorOrder(-1); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { cycleColorOrder(1); true }
                    else -> false
                }
            } else false
        }

        setupSliderItem(itemBrightness, step = 12.75f, onAdjust = { delta ->
            updateTargetCalibration { c ->
                val newBri = (c.maxBrightness + delta.toInt()).coerceIn(25, 255)
                c.copy(maxBrightness = newBri)
            }
        })

        // Black level cutoff slider (0 to 50, step 2)
        setupSliderItem(itemBlackThreshold, step = 2f, onAdjust = { delta ->
            updateTargetCalibration { c ->
                val newThreshold = (c.blackThreshold + delta.toInt()).coerceIn(0, 50)
                c.copy(blackThreshold = newThreshold)
            }
        })

        setupSliderItem(itemGainR, step = 0.05f, onAdjust = { delta ->
            updateTargetCalibration { c ->
                val newGain = ((c.gainR + delta) * 100f).roundToInt() / 100f
                c.copy(gainR = newGain.coerceIn(0.2f, 2.5f))
            }
        })

        setupSliderItem(itemGainG, step = 0.05f, onAdjust = { delta ->
            updateTargetCalibration { c ->
                val newGain = ((c.gainG + delta) * 100f).roundToInt() / 100f
                c.copy(gainG = newGain.coerceIn(0.2f, 2.5f))
            }
        })

        setupSliderItem(itemGainB, step = 0.05f, onAdjust = { delta ->
            updateTargetCalibration { c ->
                val newGain = ((c.gainB + delta) * 100f).roundToInt() / 100f
                c.copy(gainB = newGain.coerceIn(0.2f, 2.5f))
            }
        })

        setupSliderItem(itemSmoothing, step = 0.05f, onAdjust = { delta ->
            updateTargetCalibration { c ->
                val newSmooth = ((c.smoothingFactor + delta) * 100f).roundToInt() / 100f
                c.copy(smoothingFactor = newSmooth.coerceIn(0.05f, 1.0f))
            }
        })
    }

    private fun cycleColorOrder(direction: Int) {
        updateTargetDeviceStrip { dev ->
            val currentIndex = colorOrders.indexOf(dev.colorOrder).let { if (it < 0) 0 else it }
            val newIndex = (currentIndex + direction + colorOrders.size) % colorOrders.size
            dev.copy(colorOrder = colorOrders[newIndex])
        }
    }

    private fun selectColorMode(rgb: Triple<Int, Int, Int>?, title: String) {
        tvActiveColorMode.text = "${targetDevice.name}: $title"

        if (rgb == null) {
            viewRefBarTop.setBackgroundColor(Color.TRANSPARENT)
            viewRefBarBottom.setBackgroundColor(Color.TRANSPARENT)
            viewRefBarLeft.setBackgroundColor(Color.TRANSPARENT)
            viewRefBarRight.setBackgroundColor(Color.TRANSPARENT)
            lifecycleScope.launch {
                diagnosticCoordinator.stopAndBlackout(sendPowerOff = false)
            }
        } else {
            val colorInt = Color.rgb(rgb.first, rgb.second, rgb.third)
            viewRefBarTop.setBackgroundColor(colorInt)
            viewRefBarBottom.setBackgroundColor(colorInt)
            viewRefBarLeft.setBackgroundColor(colorInt)
            viewRefBarRight.setBackgroundColor(colorInt)

            diagnosticCoordinator.updateConfig(config)
            diagnosticCoordinator.startSolidColor(
                scope = lifecycleScope,
                r = rgb.first,
                g = rgb.second,
                b = rgb.third,
                deviceId = targetDeviceId
            )
        }
    }

    private fun setupSliderItem(view: View, step: Float, onAdjust: (Float) -> Unit) {
        view.setOnClickListener { onAdjust(step) }
        view.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        onAdjust(-step)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        onAdjust(step)
                        true
                    }
                    else -> false
                }
            } else false
        }
    }

    private fun withDraftInConfig(base: WledConfig, draft: WledDevice): WledConfig {
        val updatedList = if (base.devices.any { it.id == draft.id }) {
            base.devices.map { if (it.id == draft.id) draft else it }
        } else {
            base.devices + draft
        }
        return base.copy(devices = updatedList)
    }

    private fun applyDraftUpdate(updated: WledDevice, profileIdOverride: String? = null) {
        draftDevice = updated
        config = withDraftInConfig(config, updated).let { cfg ->
            if (profileIdOverride != null) cfg.copy(activeProfileId = profileIdOverride) else cfg
        }
        setResult(
            RESULT_OK,
            Intent().putExtra(EXTRA_DEVICE_DRAFT_JSON, ConfigRepository.encodeDeviceJson(updated))
        )
        diagnosticCoordinator.updateConfig(config)
        updateUiValues()
    }

    private fun showPresetPicker() {
        val presets = PresetProfile.BUILT_INS
        val names = presets.map { profile ->
            "${profile.name} - ${profile.description}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select Calibration Preset")
            .setItems(names) { _, which ->
                val selected = presets[which]
                if (selected.id == PresetProfile.CUSTOM.id) {
                    val currentDraft = draftDevice
                    if (currentDraft != null) {
                        applyDraftUpdate(currentDraft, profileIdOverride = PresetProfile.CUSTOM.id)
                    } else {
                        lifecycleScope.launch {
                            config = configRepo.updateConfig { cur ->
                                cur.copy(activeProfileId = PresetProfile.CUSTOM.id)
                            }
                            diagnosticCoordinator.updateConfig(config)
                            updateUiValues()
                        }
                    }
                } else {
                    activePreset = selected
                    applySelectedPreset(selected)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applySelectedPreset(preset: PresetProfile) {
        val currentDraft = draftDevice
        if (currentDraft != null) {
            applyDraftUpdate(
                updated = currentDraft.copy(calibration = preset.calibration),
                profileIdOverride = preset.id
            )
            return
        }
        lifecycleScope.launch {
            config = configRepo.applyPreset(preset)
            diagnosticCoordinator.updateConfig(config)
            updateUiValues()
        }
    }

    private fun resetToPreset() {
        applySelectedPreset(activePreset)
    }

    private fun updateTargetCalibration(transform: (ColorCalibration) -> ColorCalibration) {
        val currentDraft = draftDevice
        if (currentDraft != null) {
            applyDraftUpdate(
                updated = currentDraft.copy(calibration = transform(currentDraft.calibration)),
                profileIdOverride = PresetProfile.CUSTOM.id
            )
            return
        }
        lifecycleScope.launch {
            config = configRepo.updateDeviceCalibration(targetDeviceId, transform)
            diagnosticCoordinator.updateConfig(config)
            updateUiValues()
        }
    }

    private fun updateTargetDeviceStrip(transform: (WledDevice) -> WledDevice) {
        val currentDraft = draftDevice
        if (currentDraft != null) {
            applyDraftUpdate(transform(currentDraft))
            return
        }
        lifecycleScope.launch {
            val targetId = targetDeviceId
            config = configRepo.updateConfig { cur ->
                val dev = if (targetId != null) {
                    cur.devices.firstOrNull { it.id == targetId } ?: cur.primaryDevice
                } else {
                    cur.primaryDevice
                }
                cur.updateDevice(transform(dev))
            }
            diagnosticCoordinator.updateConfig(config)
            updateUiValues()
        }
    }

    private fun updateUiValues() {
        val isCustom = config.activeProfileId == PresetProfile.CUSTOM.id
        val currentPresetName = if (isCustom) "Custom" else config.activeProfile.name
        btnPresetSelector.text = currentPresetName

        tvPresetDescription.text = if (isCustom) {
            "Customized calibration curves"
        } else {
            config.activeProfile.description
        }

        val matchesPreset = (currentCalibration == activePreset.calibration) && !isCustom
        btnResetPreset.isEnabled = !matchesPreset
        btnResetPreset.alpha = if (btnResetPreset.isEnabled) 1.0f else 0.4f
        btnResetPreset.text = "Reset to ${activePreset.name}"

        val c = currentCalibration
        tvSaturationValue.text = String.format(Locale.US, "%.1fx", c.saturation)
        tvContrastValue.text = String.format(Locale.US, "%.2fx", c.contrast)
        tvColorOrderValue.text = targetDevice.colorOrder

        val briPercent = ((c.maxBrightness / 255f) * 100f).roundToInt()
        tvBrightnessValue.text = "$briPercent%"

        tvBlackThresholdValue.text = if (c.blackThreshold == 0) "0 (Off)" else "${c.blackThreshold}"

        tvGainRValue.text = String.format(Locale.US, "%.2f", c.gainR)
        tvGainGValue.text = String.format(Locale.US, "%.2f", c.gainG)
        tvGainBValue.text = String.format(Locale.US, "%.2f", c.gainB)
        tvSmoothingValue.text = String.format(Locale.US, "%.2f", c.smoothingFactor)
    }

    companion object {
        const val EXTRA_DEVICE_ID = "extra_device_id"
    }
}
