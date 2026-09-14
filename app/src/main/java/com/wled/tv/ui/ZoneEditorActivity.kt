package com.wled.tv.ui

import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.wled.tv.R
import com.wled.tv.data.PreferencesRepository
import com.wled.tv.model.AspectRatioPreset
import com.wled.tv.model.WledConfig
import com.wled.tv.service.AmbientCaptureService
import com.wled.tv.ui.views.ActiveBorder
import com.wled.tv.ui.views.ZoneCanvasView
import java.util.Locale

class ZoneEditorActivity : AppCompatActivity() {

    private lateinit var prefsRepo: PreferencesRepository
    private var config: WledConfig = WledConfig()

    private lateinit var zoneCanvas: ZoneCanvasView
    private lateinit var btnAspectPreset: Button
    private lateinit var btnTargetTop: Button
    private lateinit var btnTargetBottom: Button
    private lateinit var btnTargetLeft: Button
    private lateinit var btnTargetRight: Button
    private lateinit var btnTargetDepth: Button
    private lateinit var btnAutoDetect: Button
    private lateinit var btnResetZones: Button
    private lateinit var btnDone: Button
    private lateinit var tvInstruction: TextView

    private var activeBorder: ActiveBorder = ActiveBorder.TOP

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_zone_editor)

        prefsRepo = PreferencesRepository(this)
        config = prefsRepo.loadConfig()

        bindViews()
        setupListeners()
        updateUi()
    }

    private fun bindViews() {
        zoneCanvas = findViewById(R.id.zoneCanvas)
        btnAspectPreset = findViewById(R.id.btnAspectPreset)
        btnTargetTop = findViewById(R.id.btnTargetTop)
        btnTargetBottom = findViewById(R.id.btnTargetBottom)
        btnTargetLeft = findViewById(R.id.btnTargetLeft)
        btnTargetRight = findViewById(R.id.btnTargetRight)
        btnTargetDepth = findViewById(R.id.btnTargetDepth)
        btnAutoDetect = findViewById(R.id.btnAutoDetect)
        btnResetZones = findViewById(R.id.btnResetZones)
        btnDone = findViewById(R.id.btnDone)
        tvInstruction = findViewById(R.id.tvInstruction)

        btnTargetTop.requestFocus()
    }

    private fun setupListeners() {
        btnTargetTop.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) selectBorder(ActiveBorder.TOP)
        }
        btnTargetBottom.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) selectBorder(ActiveBorder.BOTTOM)
        }
        btnTargetLeft.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) selectBorder(ActiveBorder.LEFT)
        }
        btnTargetRight.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) selectBorder(ActiveBorder.RIGHT)
        }
        btnTargetDepth.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                selectBorder(ActiveBorder.NONE)
            }
        }

        btnTargetTop.setOnClickListener { adjustActiveBorder(0.01f) }
        btnTargetBottom.setOnClickListener { adjustActiveBorder(0.01f) }
        btnTargetLeft.setOnClickListener { adjustActiveBorder(0.01f) }
        btnTargetRight.setOnClickListener { adjustActiveBorder(0.01f) }
        btnTargetDepth.setOnClickListener { adjustDepth(0.01f) }

        val borderKeyListener = View.OnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        adjustActiveBorder(0.01f)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        adjustActiveBorder(-0.01f)
                        true
                    }
                    else -> false
                }
            } else false
        }

        btnTargetTop.setOnKeyListener(borderKeyListener)
        btnTargetBottom.setOnKeyListener(borderKeyListener)
        btnTargetLeft.setOnKeyListener(borderKeyListener)
        btnTargetRight.setOnKeyListener(borderKeyListener)

        btnTargetDepth.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        adjustDepth(0.01f)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        adjustDepth(-0.01f)
                        true
                    }
                    else -> false
                }
            } else false
        }

        btnAutoDetect.setOnClickListener {
            toggleAutoDetect()
        }

        btnResetZones.setOnClickListener {
            resetToAspectPreset(AspectRatioPreset.FULL_16_9)
        }

        btnDone.setOnClickListener {
            finish()
        }

        btnAspectPreset.setOnClickListener {
            cycleAspectRatioPreset()
        }
        btnAspectPreset.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) {
                cycleAspectRatioPreset()
                true
            } else false
        }
    }

    private fun selectBorder(border: ActiveBorder) {
        activeBorder = border
        zoneCanvas.setActiveBorder(border)
        updateStatusText()
    }

    private fun toggleAutoDetect() {
        val current = config.perimeter.autoLetterbox
        val newAuto = !current
        val updatedDevices = config.devices.map { dev ->
            dev.copy(perimeter = dev.perimeter.copy(autoLetterbox = newAuto))
        }
        config = config.copy(devices = updatedDevices)
        saveAndUpdate()
    }

    private fun cycleAspectRatioPreset() {
        val current = config.perimeter.getActiveAspectRatioPreset()
        val allPresets = AspectRatioPreset.values().filter { it != AspectRatioPreset.CUSTOM }
        val currentIndex = allPresets.indexOf(current)
        val nextPreset = if (currentIndex < 0 || currentIndex >= allPresets.size - 1) {
            allPresets[0]
        } else {
            allPresets[currentIndex + 1]
        }
        resetToAspectPreset(nextPreset)
    }

    private fun resetToAspectPreset(preset: AspectRatioPreset) {
        val updatedDevices = config.devices.map { dev ->
            dev.copy(
                perimeter = dev.perimeter.copy(
                    topCrop = preset.topCrop,
                    bottomCrop = preset.bottomCrop,
                    leftCrop = preset.leftCrop,
                    rightCrop = preset.rightCrop,
                    autoLetterbox = false
                )
            )
        }
        config = config.copy(devices = updatedDevices)
        saveAndUpdate()
    }

    private fun adjustActiveBorder(delta: Float) {
        val p = config.perimeter
        val newTop = if (activeBorder == ActiveBorder.TOP) (p.topCrop + delta).coerceIn(0f, 0.40f) else p.topCrop
        val newBottom = if (activeBorder == ActiveBorder.BOTTOM) (p.bottomCrop + delta).coerceIn(0f, 0.40f) else p.bottomCrop
        val newLeft = if (activeBorder == ActiveBorder.LEFT) (p.leftCrop + delta).coerceIn(0f, 0.40f) else p.leftCrop
        val newRight = if (activeBorder == ActiveBorder.RIGHT) (p.rightCrop + delta).coerceIn(0f, 0.40f) else p.rightCrop

        val updatedDevices = config.devices.map { dev ->
            dev.copy(
                perimeter = dev.perimeter.copy(
                    topCrop = newTop,
                    bottomCrop = newBottom,
                    leftCrop = newLeft,
                    rightCrop = newRight,
                    autoLetterbox = false
                )
            )
        }
        config = config.copy(devices = updatedDevices)
        saveAndUpdate()
    }

    private fun adjustDepth(delta: Float) {
        val current = config.perimeter.depth
        val newDepth = (current + delta).coerceIn(0.03f, 0.30f)
        val updatedDevices = config.devices.map { dev ->
            dev.copy(perimeter = dev.perimeter.copy(depth = newDepth))
        }
        config = config.copy(devices = updatedDevices)
        saveAndUpdate()
    }

    private fun saveAndUpdate() {
        prefsRepo.saveConfig(config)
        updateUi()
        if (AmbientCaptureService.isRunning) {
            AmbientCaptureService.currentServiceInstance?.reloadConfig()
        }
    }

    private fun updateUi() {
        zoneCanvas.setConfig(config.perimeter, activeBorder)
        val p = config.perimeter
        btnTargetTop.text = String.format(Locale.US, "▲ Top: %.0f%% ▼", p.topCrop * 100)
        btnTargetBottom.text = String.format(Locale.US, "▲ Bot: %.0f%% ▼", p.bottomCrop * 100)
        btnTargetLeft.text = String.format(Locale.US, "▲ Left: %.0f%% ▼", p.leftCrop * 100)
        btnTargetRight.text = String.format(Locale.US, "▲ Right: %.0f%% ▼", p.rightCrop * 100)
        btnTargetDepth.text = String.format(Locale.US, "▲ Depth: %.0f%% ▼", p.depth * 100)

        if (p.autoLetterbox) {
            btnAutoDetect.text = "Auto-Detect: ON"
            btnAutoDetect.setTextColor(Color.parseColor("#00E676"))
            btnAspectPreset.text = "Aspect: Auto-Letterbox"
        } else {
            btnAutoDetect.text = "Auto-Detect: OFF"
            btnAutoDetect.setTextColor(Color.parseColor("#94A3B8"))
            val preset = p.getActiveAspectRatioPreset()
            btnAspectPreset.text = "Aspect: ${preset.displayName}"
        }

        updateStatusText()
    }

    private fun updateStatusText() {
        val p = config.perimeter
        if (btnTargetDepth.hasFocus()) {
            tvInstruction.text = "Active: Sampling Depth ${(p.depth * 100).toInt()}% | D-pad Up/Down: adjust zone depth (3% - 30%)"
            return
        }
        if (p.autoLetterbox) {
            tvInstruction.text = "Dynamic Auto-Detection ACTIVE: Letterbox bars are scanned in real-time."
            return
        }
        val activeVal = when (activeBorder) {
            ActiveBorder.TOP -> "Top: ${(p.topCrop * 100).toInt()}%"
            ActiveBorder.BOTTOM -> "Bottom: ${(p.bottomCrop * 100).toInt()}%"
            ActiveBorder.LEFT -> "Left: ${(p.leftCrop * 100).toInt()}%"
            ActiveBorder.RIGHT -> "Right: ${(p.rightCrop * 100).toInt()}%"
            ActiveBorder.NONE -> ""
        }
        tvInstruction.text = "Active: $activeVal | D-pad Left/Right: select border | D-pad Up/Down: adjust inset"
    }
}
