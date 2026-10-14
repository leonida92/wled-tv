package com.wled.tv.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wled.tv.R
import com.wled.tv.data.PreferencesRepository
import com.wled.tv.model.CalculatedPerimeter
import com.wled.tv.model.PerimeterCalculator
import com.wled.tv.model.WledConfig
import com.wled.tv.model.WledDevice
import com.wled.tv.network.WledUdpSender
import com.wled.tv.service.AmbientCaptureService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CornerFinderActivity : AppCompatActivity() {

    private lateinit var prefsRepo: PreferencesRepository
    private var config: WledConfig = WledConfig()
    private var targetDeviceId: String? = null
    private lateinit var targetDevice: WledDevice

    private val udpSender = WledUdpSender()
    private var totalLeds: Int = 168

    // UI elements
    private lateinit var tvStepBadge: TextView
    private lateinit var dotTL: View
    private lateinit var tvLabelTL: TextView
    private lateinit var dotTR: View
    private lateinit var tvLabelTR: TextView
    private lateinit var dotBR: View
    private lateinit var tvLabelBR: TextView
    private lateinit var dotBL: View
    private lateinit var tvLabelBL: TextView

    private lateinit var layoutJogCenter: LinearLayout
    private lateinit var tvActiveCornerPrompt: TextView
    private lateinit var tvCurrentLedIndex: TextView
    private lateinit var tvTotalLedsLabel: TextView
    private lateinit var btnJogMinus10: View
    private lateinit var btnJogMinus1: View
    private lateinit var btnJogPlus1: View
    private lateinit var btnJogPlus10: View

    private lateinit var cardSummary: LinearLayout
    private lateinit var tvSummarySegments: TextView
    private lateinit var tvSummaryDirection: TextView

    private lateinit var cardError: LinearLayout
    private lateinit var tvErrorMessage: TextView

    private lateinit var btnCancelWizard: View
    private lateinit var btnPrevCorner: View
    private lateinit var btnNextOrConfirm: View
    private lateinit var tvNextBtnText: TextView

    // Wizard state
    // Step 0: TL, Step 1: TR, Step 2: BR, Step 3: BL, Step 4: Review
    private var currentStep = 0
    private val cornerIndices = IntArray(4) { 0 }
    private var currentLedIndex = 0
    private var calculatedPerimeter: CalculatedPerimeter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_corner_finder)

        prefsRepo = PreferencesRepository(this)
        config = prefsRepo.loadConfig()
        targetDeviceId = intent.getStringExtra(EXTRA_DEVICE_ID)

        targetDevice = if (targetDeviceId != null) {
            config.devices.firstOrNull { it.id == targetDeviceId } ?: config.primaryDevice
        } else {
            config.primaryDevice
        }

        totalLeds = targetDevice.perimeter.totalLeds.coerceAtLeast(4)

        bindViews()
        setupListeners()
        updateStepUi()
        sendSinglePixel(currentLedIndex)
    }

    override fun onDestroy() {
        super.onDestroy()
        turnOffLeds()
        udpSender.close()
    }

    private fun bindViews() {
        tvStepBadge = findViewById(R.id.tvStepBadge)
        dotTL = findViewById(R.id.dotTL)
        tvLabelTL = findViewById(R.id.tvLabelTL)
        dotTR = findViewById(R.id.dotTR)
        tvLabelTR = findViewById(R.id.tvLabelTR)
        dotBR = findViewById(R.id.dotBR)
        tvLabelBR = findViewById(R.id.tvLabelBR)
        dotBL = findViewById(R.id.dotBL)
        tvLabelBL = findViewById(R.id.tvLabelBL)

        layoutJogCenter = findViewById(R.id.layoutJogCenter)
        tvActiveCornerPrompt = findViewById(R.id.tvActiveCornerPrompt)
        tvCurrentLedIndex = findViewById(R.id.tvCurrentLedIndex)
        tvTotalLedsLabel = findViewById(R.id.tvTotalLedsLabel)

        cardSummary = findViewById(R.id.cardSummary)
        tvSummarySegments = findViewById(R.id.tvSummarySegments)
        tvSummaryDirection = findViewById(R.id.tvSummaryDirection)

        cardError = findViewById(R.id.cardError)
        tvErrorMessage = findViewById(R.id.tvErrorMessage)

        btnCancelWizard = findViewById(R.id.btnCancelWizard)
        btnPrevCorner = findViewById(R.id.btnPrevCorner)
        btnNextOrConfirm = findViewById(R.id.btnNextOrConfirm)
        tvNextBtnText = findViewById(R.id.tvNextBtnText)

        btnJogMinus10 = findViewById(R.id.btnJogMinus10)
        btnJogMinus1 = findViewById(R.id.btnJogMinus1)
        btnJogPlus1 = findViewById(R.id.btnJogPlus1)
        btnJogPlus10 = findViewById(R.id.btnJogPlus10)

        tvTotalLedsLabel.text = "of $totalLeds LEDs total"
        btnJogPlus1.requestFocus()
    }

    private fun setupListeners() {
        btnJogMinus10.setOnClickListener { adjustLedIndex(-10) }
        btnJogMinus1.setOnClickListener { adjustLedIndex(-1) }
        btnJogPlus1.setOnClickListener { adjustLedIndex(+1) }
        btnJogPlus10.setOnClickListener { adjustLedIndex(+10) }

        btnCancelWizard.setOnClickListener {
            finish()
        }

        btnPrevCorner.setOnClickListener {
            if (currentStep > 0) {
                currentStep--
                currentLedIndex = cornerIndices[currentStep]
                updateStepUi()
                sendSinglePixel(currentLedIndex)
            }
        }

        btnNextOrConfirm.setOnClickListener {
            handleNextAction()
        }
    }

    private fun handleNextAction() {
        if (currentStep < 3) {
            cornerIndices[currentStep] = currentLedIndex
            currentStep++
            // Default next step index to the current one so jogging is close
            currentLedIndex = cornerIndices[currentStep]
            updateStepUi()
            sendSinglePixel(currentLedIndex)
        } else if (currentStep == 3) {
            cornerIndices[3] = currentLedIndex
            currentStep = 4
            updateStepUi()
        } else {
            // Step 4: Apply or Restart
            val calc = calculatedPerimeter
            if (calc != null) {
                applyAndSave(calc)
            } else {
                // Restart
                currentStep = 0
                currentLedIndex = 0
                updateStepUi()
                sendSinglePixel(currentLedIndex)
            }
        }
    }

    private fun updateStepUi() {
        val colorActive = Color.parseColor("#00E5FF")
        val colorDone = Color.parseColor("#00E676")
        val colorPending = Color.parseColor("#475569")
        val textPrimary = Color.parseColor("#F8FAFC")
        val textMuted = Color.parseColor("#64748B")

        // Helper to tint dots & set label
        fun updateCornerDot(stepIdx: Int, dot: View, label: TextView, name: String) {
            when {
                currentStep > stepIdx -> {
                    dot.backgroundTintList = ColorStateList.valueOf(colorDone)
                    label.text = "$name (#${cornerIndices[stepIdx]})"
                    label.setTextColor(textPrimary)
                }
                currentStep == stepIdx -> {
                    dot.backgroundTintList = ColorStateList.valueOf(colorActive)
                    label.text = "$name (Active)"
                    label.setTextColor(colorActive)
                }
                else -> {
                    dot.backgroundTintList = ColorStateList.valueOf(colorPending)
                    label.text = "$name (?)"
                    label.setTextColor(textMuted)
                }
            }
        }

        updateCornerDot(0, dotTL, tvLabelTL, "Top-Left")
        updateCornerDot(1, dotTR, tvLabelTR, "Top-Right")
        updateCornerDot(2, dotBR, tvLabelBR, "Bottom-Right")
        updateCornerDot(3, dotBL, tvLabelBL, "Bottom-Left")

        btnPrevCorner.visibility = if (currentStep in 1..4) View.VISIBLE else View.GONE

        when (currentStep) {
            0 -> {
                tvStepBadge.text = "Step 1/4: Top-Left Corner"
                tvActiveCornerPrompt.text = "Move pixel to Top-Left Corner"
                layoutJogCenter.visibility = View.VISIBLE
                cardSummary.visibility = View.GONE
                cardError.visibility = View.GONE
                tvNextBtnText.text = "Next Corner (TR) ►"
                updateIndexDisplay()
            }
            1 -> {
                tvStepBadge.text = "Step 2/4: Top-Right Corner"
                tvActiveCornerPrompt.text = "Move pixel to Top-Right Corner"
                layoutJogCenter.visibility = View.VISIBLE
                cardSummary.visibility = View.GONE
                cardError.visibility = View.GONE
                tvNextBtnText.text = "Next Corner (BR) ►"
                updateIndexDisplay()
            }
            2 -> {
                tvStepBadge.text = "Step 3/4: Bottom-Right Corner"
                tvActiveCornerPrompt.text = "Move pixel to Bottom-Right Corner"
                layoutJogCenter.visibility = View.VISIBLE
                cardSummary.visibility = View.GONE
                cardError.visibility = View.GONE
                tvNextBtnText.text = "Next Corner (BL) ►"
                updateIndexDisplay()
            }
            3 -> {
                tvStepBadge.text = "Step 4/4: Bottom-Left Corner"
                tvActiveCornerPrompt.text = "Move pixel to Bottom-Left Corner"
                layoutJogCenter.visibility = View.VISIBLE
                cardSummary.visibility = View.GONE
                cardError.visibility = View.GONE
                tvNextBtnText.text = "Calculate Layout ►"
                updateIndexDisplay()
            }
            4 -> {
                // Review & Validation
                tvStepBadge.text = "Step 5/5: Alignment Review"
                layoutJogCenter.visibility = View.GONE

                val calc = PerimeterCalculator.calculateFromCorners(
                    totalLeds = totalLeds,
                    tl = cornerIndices[0],
                    tr = cornerIndices[1],
                    br = cornerIndices[2],
                    bl = cornerIndices[3]
                )
                calculatedPerimeter = calc

                if (calc != null) {
                    cardSummary.visibility = View.VISIBLE
                    cardError.visibility = View.GONE
                    tvSummarySegments.text = "Top: ${calc.topLeds} • Right: ${calc.rightLeds} • Bottom: ${calc.bottomLeds} • Left: ${calc.leftLeds} LEDs"
                    val dirName = if (calc.direction == com.wled.tv.model.Direction.CLOCKWISE) "Clockwise" else "Counter-Clockwise"
                    val startName = calc.startCorner.name.replace('_', ' ')
                    tvSummaryDirection.text = "Direction: $dirName • Start Corner: $startName"
                    tvNextBtnText.text = "Apply & Save to Light"
                } else {
                    cardSummary.visibility = View.GONE
                    cardError.visibility = View.VISIBLE
                    tvErrorMessage.text = "Corners (TL:${cornerIndices[0]}, TR:${cornerIndices[1]}, BR:${cornerIndices[2]}, BL:${cornerIndices[3]}) do not form a valid perimeter sequence. Please restart and check the LED indices."
                    tvNextBtnText.text = "Restart Wizard"
                }
                btnNextOrConfirm.requestFocus()
            }
        }
    }

    private fun updateIndexDisplay() {
        tvCurrentLedIndex.text = "LED #$currentLedIndex"
    }

    private fun adjustLedIndex(delta: Int) {
        if (currentStep > 3) return
        currentLedIndex = (currentLedIndex + delta + totalLeds) % totalLeds
        updateIndexDisplay()
        sendSinglePixel(currentLedIndex)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (currentStep in 0..3) {
            when (keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    adjustLedIndex(+10)
                    return true
                }
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    adjustLedIndex(-10)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun sendSinglePixel(ledIndex: Int) {
        val total = totalLeds
        if (total <= 0) return
        val buffer = ByteArray(total * 3)
        val idx = (ledIndex.coerceIn(0, total - 1)) * 3
        buffer[idx] = 255.toByte()     // Pure white
        buffer[idx + 1] = 255.toByte()
        buffer[idx + 2] = 255.toByte()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                udpSender.sendDeviceFrame(targetDevice, buffer, total, 2)
            } catch (e: Exception) {
                // Ignore transient network errors during jogging
            }
        }
    }

    private fun turnOffLeds() {
        val total = totalLeds
        if (total <= 0) return
        val buffer = ByteArray(total * 3)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                udpSender.sendDeviceFrame(targetDevice, buffer, total, 2)
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    private fun applyAndSave(calc: CalculatedPerimeter) {
        val updatedPerimeter = targetDevice.perimeter.copy(
            topLeds = calc.topLeds,
            rightLeds = calc.rightLeds,
            bottomLeds = calc.bottomLeds,
            leftLeds = calc.leftLeds,
            startCorner = calc.startCorner,
            direction = calc.direction
        )
        val updatedDevice = targetDevice.copy(
            type = com.wled.tv.model.DeviceType.PERIMETER,
            perimeter = updatedPerimeter
        )
        val updatedConfig = config.updateDevice(updatedDevice)
        prefsRepo.saveConfig(updatedConfig)

        if (AmbientCaptureService.isRunning) {
            AmbientCaptureService.currentServiceInstance?.reloadConfig()
        }

        turnOffLeds()
        Toast.makeText(this, "Corner calibration saved successfully!", Toast.LENGTH_SHORT).show()
        finish()
    }

    companion object {
        const val EXTRA_DEVICE_ID = "extra_device_id"
    }
}
