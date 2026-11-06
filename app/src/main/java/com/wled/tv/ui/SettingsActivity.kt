package com.wled.tv.ui

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.wled.tv.R
import com.wled.tv.data.ConfigRepository
import com.wled.tv.model.Corner
import com.wled.tv.model.Direction
import com.wled.tv.model.PerimeterConfig
import com.wled.tv.model.WledConfig
import com.wled.tv.model.WledDevice

class SettingsActivity : AppCompatActivity() {

    private lateinit var configRepo: ConfigRepository
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
        configRepo = ConfigRepository.getInstance(this)
        config = configRepo.currentConfig

        bindViews()
        setupListeners()
        updateUiValues()
    }

    override fun onResume() {
        super.onResume()
        config = configRepo.currentConfig
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
            updateTargetPerimeter { p ->
                val newCount = (p.topLeds + delta).coerceIn(0, 300)
                p.copy(topLeds = newCount)
            }
        })

        setupLedCountStepper(itemRightLeds, onAdjust = { delta ->
            updateTargetPerimeter { p ->
                val newCount = (p.rightLeds + delta).coerceIn(0, 300)
                p.copy(rightLeds = newCount)
            }
        })

        setupLedCountStepper(itemBottomLeds, onAdjust = { delta ->
            updateTargetPerimeter { p ->
                val newCount = (p.bottomLeds + delta).coerceIn(0, 300)
                p.copy(bottomLeds = newCount)
            }
        })

        setupLedCountStepper(itemLeftLeds, onAdjust = { delta ->
            updateTargetPerimeter { p ->
                val newCount = (p.leftLeds + delta).coerceIn(0, 300)
                p.copy(leftLeds = newCount)
            }
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

    private fun updateTargetPerimeter(transform: (PerimeterConfig) -> PerimeterConfig) {
        lifecycleScope.launch {
            config = configRepo.updateConfig { current ->
                val targetId = targetDeviceId
                val dev = if (targetId != null) {
                    current.devices.firstOrNull { it.id == targetId } ?: current.primaryDevice
                } else {
                    current.primaryDevice
                }
                current.updateDevice(dev.copy(perimeter = transform(dev.perimeter)))
            }
            updateUiValues()
        }
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
        updateTargetPerimeter { p ->
            val nextCorner = when (p.startCorner) {
                Corner.BOTTOM_LEFT -> Corner.TOP_LEFT
                Corner.TOP_LEFT -> Corner.TOP_RIGHT
                Corner.TOP_RIGHT -> Corner.BOTTOM_RIGHT
                Corner.BOTTOM_RIGHT -> Corner.BOTTOM_LEFT
            }
            p.copy(startCorner = nextCorner)
        }
    }

    private fun cycleDirection() {
        updateTargetPerimeter { p ->
            val nextDir = if (p.direction == Direction.CLOCKWISE) {
                Direction.COUNTER_CLOCKWISE
            } else {
                Direction.CLOCKWISE
            }
            p.copy(direction = nextDir)
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
