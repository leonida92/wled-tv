package com.wled.tv.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.wled.tv.R
import com.wled.tv.data.PreferencesRepository
import com.wled.tv.model.Corner
import com.wled.tv.model.Direction
import com.wled.tv.model.PerimeterConfig
import com.wled.tv.model.WledConfig
import com.wled.tv.model.WledDevice
import com.wled.tv.service.AmbientCaptureService

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefsRepo: PreferencesRepository
    private var config: WledConfig = WledConfig()
    private var targetDeviceId: String? = null

    private val targetDevice: WledDevice
        get() = if (targetDeviceId != null) {
            config.devices.firstOrNull { it.id == targetDeviceId } ?: config.primaryDevice
        } else {
            config.primaryDevice
        }

    private lateinit var tvSettingsTotalBadge: TextView

    private lateinit var itemTopLeds: LinearLayout
    private lateinit var tvTopLedsValue: TextView
    private lateinit var itemRightLeds: LinearLayout
    private lateinit var tvRightLedsValue: TextView
    private lateinit var itemBottomLeds: LinearLayout
    private lateinit var tvBottomLedsValue: TextView
    private lateinit var itemLeftLeds: LinearLayout
    private lateinit var tvLeftLedsValue: TextView

    private lateinit var itemStartCorner: LinearLayout
    private lateinit var tvStartCornerValue: TextView
    private lateinit var itemDirection: LinearLayout
    private lateinit var tvDirectionValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        targetDeviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
        prefsRepo = PreferencesRepository(this)
        config = prefsRepo.loadConfig()

        bindViews()
        setupListeners()
        updateUiValues()
    }

    private fun bindViews() {
        tvSettingsTotalBadge = findViewById(R.id.tvSettingsTotalBadge)

        itemTopLeds = findViewById(R.id.itemTopLeds)
        tvTopLedsValue = findViewById(R.id.tvTopLedsValue)
        itemRightLeds = findViewById(R.id.itemRightLeds)
        tvRightLedsValue = findViewById(R.id.tvRightLedsValue)
        itemBottomLeds = findViewById(R.id.itemBottomLeds)
        tvBottomLedsValue = findViewById(R.id.tvBottomLedsValue)
        itemLeftLeds = findViewById(R.id.itemLeftLeds)
        tvLeftLedsValue = findViewById(R.id.tvLeftLedsValue)

        itemStartCorner = findViewById(R.id.itemStartCorner)
        tvStartCornerValue = findViewById(R.id.tvStartCornerValue)
        itemDirection = findViewById(R.id.itemDirection)
        tvDirectionValue = findViewById(R.id.tvDirectionValue)

        itemTopLeds.requestFocus()
    }

    private fun setupListeners() {
        setupLedCountStepper(itemTopLeds, onAdjust = { delta ->
            val p = targetDevice.perimeter
            val newCount = (p.topLeds + delta).coerceIn(0, 300)
            updateTargetPerimeter(p.copy(topLeds = newCount))
        })

        setupLedCountStepper(itemRightLeds, onAdjust = { delta ->
            val p = targetDevice.perimeter
            val newCount = (p.rightLeds + delta).coerceIn(0, 300)
            updateTargetPerimeter(p.copy(rightLeds = newCount))
        })

        setupLedCountStepper(itemBottomLeds, onAdjust = { delta ->
            val p = targetDevice.perimeter
            val newCount = (p.bottomLeds + delta).coerceIn(0, 300)
            updateTargetPerimeter(p.copy(bottomLeds = newCount))
        })

        setupLedCountStepper(itemLeftLeds, onAdjust = { delta ->
            val p = targetDevice.perimeter
            val newCount = (p.leftLeds + delta).coerceIn(0, 300)
            updateTargetPerimeter(p.copy(leftLeds = newCount))
        })

        itemStartCorner.setOnClickListener { cycleCorner() }
        itemStartCorner.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                cycleCorner()
                true
            } else false
        }

        itemDirection.setOnClickListener { cycleDirection() }
        itemDirection.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                cycleDirection()
                true
            } else false
        }
    }

    private fun updateTargetPerimeter(newPerimeter: PerimeterConfig) {
        val dev = targetDevice
        val updatedDev = dev.copy(perimeter = newPerimeter)
        config = config.updateDevice(updatedDev)
        saveAndUpdate()
    }

    private fun setupLedCountStepper(view: View, onAdjust: (Int) -> Unit) {
        view.setOnClickListener { onAdjust(1) }
        view.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        onAdjust(-1)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        onAdjust(1)
                        true
                    }
                    else -> false
                }
            } else false
        }
    }

    private fun cycleCorner() {
        val p = targetDevice.perimeter
        val nextCorner = when (p.startCorner) {
            Corner.BOTTOM_LEFT -> Corner.TOP_LEFT
            Corner.TOP_LEFT -> Corner.TOP_RIGHT
            Corner.TOP_RIGHT -> Corner.BOTTOM_RIGHT
            Corner.BOTTOM_RIGHT -> Corner.BOTTOM_LEFT
        }
        updateTargetPerimeter(p.copy(startCorner = nextCorner))
    }

    private fun cycleDirection() {
        val p = targetDevice.perimeter
        val nextDir = if (p.direction == Direction.CLOCKWISE) {
            Direction.COUNTER_CLOCKWISE
        } else {
            Direction.CLOCKWISE
        }
        updateTargetPerimeter(p.copy(direction = nextDir))
    }

    private fun saveAndUpdate() {
        prefsRepo.saveConfig(config)
        updateUiValues()
        if (AmbientCaptureService.isRunning) {
            AmbientCaptureService.currentServiceInstance?.reloadConfig()
        }
    }

    private fun updateUiValues() {
        val dev = targetDevice
        val p = dev.perimeter
        tvSettingsTotalBadge.text = "${p.totalLeds} LEDs"
        tvTopLedsValue.text = "${p.topLeds}"
        tvRightLedsValue.text = "${p.rightLeds}"
        tvBottomLedsValue.text = "${p.bottomLeds}"
        tvLeftLedsValue.text = "${p.leftLeds}"

        tvStartCornerValue.text = when (p.startCorner) {
            Corner.BOTTOM_LEFT -> getString(R.string.corner_bottom_left)
            Corner.TOP_LEFT -> getString(R.string.corner_top_left)
            Corner.TOP_RIGHT -> getString(R.string.corner_top_right)
            Corner.BOTTOM_RIGHT -> getString(R.string.corner_bottom_right)
        }

        tvDirectionValue.text = when (p.direction) {
            Direction.CLOCKWISE -> getString(R.string.dir_clockwise)
            Direction.COUNTER_CLOCKWISE -> getString(R.string.dir_counter_clockwise)
        }
    }

    companion object {
        const val EXTRA_DEVICE_ID = "extra_device_id"
    }
}
