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
import com.wled.tv.model.WledConfig
import com.wled.tv.service.AmbientCaptureService

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefsRepo: PreferencesRepository
    private var config: WledConfig = WledConfig()

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
            val p = config.perimeter
            val newCount = (p.topLeds + delta).coerceIn(0, 300)
            config = config.copy(perimeter = p.copy(topLeds = newCount))
            saveAndUpdate()
        })

        setupLedCountStepper(itemRightLeds, onAdjust = { delta ->
            val p = config.perimeter
            val newCount = (p.rightLeds + delta).coerceIn(0, 300)
            config = config.copy(perimeter = p.copy(rightLeds = newCount))
            saveAndUpdate()
        })

        setupLedCountStepper(itemBottomLeds, onAdjust = { delta ->
            val p = config.perimeter
            val newCount = (p.bottomLeds + delta).coerceIn(0, 300)
            config = config.copy(perimeter = p.copy(bottomLeds = newCount))
            saveAndUpdate()
        })

        setupLedCountStepper(itemLeftLeds, onAdjust = { delta ->
            val p = config.perimeter
            val newCount = (p.leftLeds + delta).coerceIn(0, 300)
            config = config.copy(perimeter = p.copy(leftLeds = newCount))
            saveAndUpdate()
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
        val nextCorner = when (config.perimeter.startCorner) {
            Corner.BOTTOM_LEFT -> Corner.TOP_LEFT
            Corner.TOP_LEFT -> Corner.TOP_RIGHT
            Corner.TOP_RIGHT -> Corner.BOTTOM_RIGHT
            Corner.BOTTOM_RIGHT -> Corner.BOTTOM_LEFT
        }
        config = config.copy(perimeter = config.perimeter.copy(startCorner = nextCorner))
        saveAndUpdate()
    }

    private fun cycleDirection() {
        val nextDir = if (config.perimeter.direction == Direction.CLOCKWISE) {
            Direction.COUNTER_CLOCKWISE
        } else {
            Direction.CLOCKWISE
        }
        config = config.copy(perimeter = config.perimeter.copy(direction = nextDir))
        saveAndUpdate()
    }

    private fun saveAndUpdate() {
        prefsRepo.saveConfig(config)
        updateUiValues()
        if (AmbientCaptureService.isRunning) {
            AmbientCaptureService.currentServiceInstance?.reloadConfig()
        }
    }

    private fun updateUiValues() {
        val p = config.perimeter
        tvTopLedsValue.text = "${p.topLeds}"
        tvRightLedsValue.text = "${p.rightLeds}"
        tvBottomLedsValue.text = "${p.bottomLeds}"
        tvLeftLedsValue.text = "${p.leftLeds}"
        tvSettingsTotalBadge.text = "Total: ${p.totalLeds} LEDs"

        tvStartCornerValue.text = when (p.startCorner) {
            Corner.BOTTOM_LEFT -> getString(R.string.corner_bottom_left)
            Corner.BOTTOM_RIGHT -> getString(R.string.corner_bottom_right)
            Corner.TOP_LEFT -> getString(R.string.corner_top_left)
            Corner.TOP_RIGHT -> getString(R.string.corner_top_right)
        }

        tvDirectionValue.text = when (p.direction) {
            Direction.CLOCKWISE -> getString(R.string.dir_clockwise)
            Direction.COUNTER_CLOCKWISE -> getString(R.string.dir_counter_clockwise)
        }
    }
}
