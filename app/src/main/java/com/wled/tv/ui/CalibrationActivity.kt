package com.wled.tv.ui

import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.wled.tv.R
import com.wled.tv.data.PreferencesRepository
import com.wled.tv.model.WledConfig
import com.wled.tv.network.WledUdpSender
import com.wled.tv.service.AmbientCaptureService
import java.util.Locale
import kotlin.math.roundToInt

class CalibrationActivity : AppCompatActivity() {

    private lateinit var prefsRepo: PreferencesRepository
    private var config: WledConfig = WledConfig()
    private val udpSender = WledUdpSender()

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

    private var activeTestColor: Triple<Int, Int, Int>? = null
    private var testColorJob: Job? = null

    private val colorOrders = listOf("RGB", "GRB", "BGR", "BRG", "RBG", "GBR")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calibration)

        prefsRepo = PreferencesRepository(this)
        config = prefsRepo.loadConfig()

        bindViews()
        setupListeners()
        updateUiValues()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTestColorStream()
    }

    private fun bindViews() {
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
            val c = config.calibration
            val newSat = ((c.saturation + delta) * 10f).roundToInt() / 10f
            config = config.copy(calibration = c.copy(saturation = newSat.coerceIn(0.5f, 3.0f)))
            saveAndUpdate()
        })

        setupSliderItem(itemContrast, step = 0.05f, onAdjust = { delta ->
            val c = config.calibration
            val newContrast = ((c.contrast + delta) * 100f).roundToInt() / 100f
            config = config.copy(calibration = c.copy(contrast = newContrast.coerceIn(0.5f, 1.5f)))
            saveAndUpdate()
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
            val c = config.calibration
            val newBri = (c.maxBrightness + delta.toInt()).coerceIn(25, 255)
            config = config.copy(calibration = c.copy(maxBrightness = newBri))
            saveAndUpdate()
        })

        // Black level cutoff slider (0 to 50, step 2)
        setupSliderItem(itemBlackThreshold, step = 2f, onAdjust = { delta ->
            val c = config.calibration
            val newThreshold = (c.blackThreshold + delta.toInt()).coerceIn(0, 50)
            config = config.copy(calibration = c.copy(blackThreshold = newThreshold))
            saveAndUpdate()
        })

        setupSliderItem(itemGainR, step = 0.05f, onAdjust = { delta ->
            val c = config.calibration
            val newGain = ((c.gainR + delta) * 100f).roundToInt() / 100f
            config = config.copy(calibration = c.copy(gainR = newGain.coerceIn(0.2f, 2.5f)))
            saveAndUpdate()
        })

        setupSliderItem(itemGainG, step = 0.05f, onAdjust = { delta ->
            val c = config.calibration
            val newGain = ((c.gainG + delta) * 100f).roundToInt() / 100f
            config = config.copy(calibration = c.copy(gainG = newGain.coerceIn(0.2f, 2.5f)))
            saveAndUpdate()
        })

        setupSliderItem(itemGainB, step = 0.05f, onAdjust = { delta ->
            val c = config.calibration
            val newGain = ((c.gainB + delta) * 100f).roundToInt() / 100f
            config = config.copy(calibration = c.copy(gainB = newGain.coerceIn(0.2f, 2.5f)))
            saveAndUpdate()
        })

        setupSliderItem(itemSmoothing, step = 0.05f, onAdjust = { delta ->
            val c = config.calibration
            val newSmooth = ((c.smoothingFactor + delta) * 100f).roundToInt() / 100f
            config = config.copy(calibration = c.copy(smoothingFactor = newSmooth.coerceIn(0.05f, 1.0f)))
            saveAndUpdate()
        })
    }

    private fun cycleColorOrder(direction: Int) {
        val current = config.calibration.colorOrder
        val currentIndex = colorOrders.indexOf(current).let { if (it < 0) 0 else it }
        val newIndex = (currentIndex + direction + colorOrders.size) % colorOrders.size
        config = config.copy(calibration = config.calibration.copy(colorOrder = colorOrders[newIndex]))
        saveAndUpdate()
    }

    private fun selectColorMode(rgb: Triple<Int, Int, Int>?, title: String) {
        activeTestColor = rgb
        tvActiveColorMode.text = title

        if (rgb == null) {
            viewRefBarTop.setBackgroundColor(Color.TRANSPARENT)
            viewRefBarBottom.setBackgroundColor(Color.TRANSPARENT)
            viewRefBarLeft.setBackgroundColor(Color.TRANSPARENT)
            viewRefBarRight.setBackgroundColor(Color.TRANSPARENT)
            stopTestColorStream()
        } else {
            val colorInt = Color.rgb(rgb.first, rgb.second, rgb.third)
            viewRefBarTop.setBackgroundColor(colorInt)
            viewRefBarBottom.setBackgroundColor(colorInt)
            viewRefBarLeft.setBackgroundColor(colorInt)
            viewRefBarRight.setBackgroundColor(colorInt)

            startTestColorStream()
        }
    }

    private fun startTestColorStream() {
        testColorJob?.cancel()
        AmbientCaptureService.isTestingOverride = true

        testColorJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive && activeTestColor != null) {
                val color = activeTestColor ?: break
                val c = config.calibration
                val totalLeds = config.perimeter.totalLeds
                val briScale = c.maxBrightness / 255f

                val r = (color.first * c.gainR * briScale).roundToInt().coerceIn(0, 255).toByte()
                val g = (color.second * c.gainG * briScale).roundToInt().coerceIn(0, 255).toByte()
                val b = (color.third * c.gainB * briScale).roundToInt().coerceIn(0, 255).toByte()

                val frame = ByteArray(totalLeds * 3)
                for (i in 0 until totalLeds) {
                    val idx = i * 3
                    frame[idx] = r
                    frame[idx + 1] = g
                    frame[idx + 2] = b
                }

                udpSender.sendDrgbFrame(
                    ip = config.ip,
                    port = config.port,
                    timeoutSeconds = 5.toByte(),
                    rgb = frame,
                    ledCount = totalLeds,
                    colorOrder = config.calibration.colorOrder
                )
                delay(100)
            }
        }
    }

    private fun stopTestColorStream() {
        testColorJob?.cancel()
        testColorJob = null
        AmbientCaptureService.isTestingOverride = false
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

    private fun saveAndUpdate() {
        prefsRepo.saveConfig(config)
        updateUiValues()
        if (AmbientCaptureService.isRunning) {
            AmbientCaptureService.currentServiceInstance?.reloadConfig()
        }
    }

    private fun updateUiValues() {
        val c = config.calibration
        tvSaturationValue.text = String.format(Locale.US, "%.1fx", c.saturation)
        tvContrastValue.text = String.format(Locale.US, "%.2fx", c.contrast)
        tvColorOrderValue.text = c.colorOrder

        val briPercent = ((c.maxBrightness / 255f) * 100f).roundToInt()
        tvBrightnessValue.text = "$briPercent%"

        tvBlackThresholdValue.text = if (c.blackThreshold == 0) "0 (Off)" else "${c.blackThreshold}"

        tvGainRValue.text = String.format(Locale.US, "%.2f", c.gainR)
        tvGainGValue.text = String.format(Locale.US, "%.2f", c.gainG)
        tvGainBValue.text = String.format(Locale.US, "%.2f", c.gainB)
        tvSmoothingValue.text = String.format(Locale.US, "%.2f", c.smoothingFactor)
    }
}
