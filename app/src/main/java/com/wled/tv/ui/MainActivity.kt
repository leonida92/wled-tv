package com.wled.tv.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.wled.tv.R
import com.wled.tv.data.PreferencesRepository
import com.wled.tv.model.WledConfig
import com.wled.tv.network.WledHttpClient
import com.wled.tv.network.WledUdpSender
import com.wled.tv.service.AmbientCaptureService
import com.wled.tv.ui.views.TvPerimeterPreviewView

class MainActivity : AppCompatActivity(),
    AmbientCaptureService.LiveFrameListener,
    AmbientCaptureService.ServiceStateListener {

    private lateinit var prefsRepo: PreferencesRepository
    private var config: WledConfig = WledConfig()

    private val httpClient = WledHttpClient()
    private val udpSender = WledUdpSender()

    private lateinit var btnPower: FrameLayout
    private lateinit var tvPowerText: TextView
    private lateinit var viewStatusDot: View
    private lateinit var tvStatusTitle: TextView
    private lateinit var tvStatusSubtitle: TextView
    private lateinit var tvTotalLedsBadge: TextView
    private lateinit var tvIpSummary: TextView
    private lateinit var tvDirectionSummary: TextView

    private lateinit var btnTestStrip: LinearLayout
    private lateinit var btnZoneEditor: LinearLayout
    private lateinit var btnCalibration: LinearLayout
    private lateinit var btnSettings: LinearLayout
    private lateinit var btnSystemSettings: LinearLayout
    private lateinit var tvPreviewView: TvPerimeterPreviewView

    private var isTesting = false
    private var testPatternJob: Job? = null

    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                startAmbientService(result.resultCode, result.data!!)
            } else {
                Toast.makeText(this, "Screen capture permission is required for ambient lighting", Toast.LENGTH_LONG).show()
                updateUiState()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefsRepo = PreferencesRepository(this)
        config = prefsRepo.loadConfig()

        bindViews()
        setupListeners()
        updateUiState()

        if (intent.getBooleanExtra("EXTRA_AUTO_START_TRIGGERED", false) && !AmbientCaptureService.isRunning) {
            intent.removeExtra("EXTRA_AUTO_START_TRIGGERED")
            requestScreenCapture()
        }
    }

    override fun onResume() {
        super.onResume()
        config = prefsRepo.loadConfig()
        tvPreviewView.updateDevices(config.enabledDevices)
        AmbientCaptureService.liveFrameListener = this
        AmbientCaptureService.stateListener = this
        updateUiState()
        checkWledConnection()
    }

    override fun onPause() {
        super.onPause()
        AmbientCaptureService.liveFrameListener = null
        AmbientCaptureService.stateListener = null
        stopTestPattern()
    }

    private fun bindViews() {
        btnPower = findViewById(R.id.btnPower)
        tvPowerText = findViewById(R.id.tvPowerText)
        viewStatusDot = findViewById(R.id.viewStatusDot)
        tvStatusTitle = findViewById(R.id.tvStatusTitle)
        tvStatusSubtitle = findViewById(R.id.tvStatusSubtitle)
        tvTotalLedsBadge = findViewById(R.id.tvTotalLedsBadge)
        tvIpSummary = findViewById(R.id.tvIpSummary)
        tvDirectionSummary = findViewById(R.id.tvDirectionSummary)

        btnTestStrip = findViewById(R.id.btnTestStrip)
        btnZoneEditor = findViewById(R.id.btnZoneEditor)
        btnSettings = findViewById(R.id.btnSettings)
        btnSystemSettings = findViewById(R.id.btnSystemSettings)
        tvPreviewView = findViewById(R.id.tvPreviewView)

        btnPower.requestFocus()
    }

    private fun setupListeners() {
        btnPower.setOnClickListener {
            if (AmbientCaptureService.isRunning) {
                stopAmbientService()
            } else {
                requestScreenCapture()
            }
        }

        btnTestStrip.setOnClickListener {
            toggleTestPattern()
        }

        btnZoneEditor.setOnClickListener {
            startActivity(Intent(this, ZoneEditorActivity::class.java))
        }

        btnSettings.setOnClickListener {
            startActivity(Intent(this, DeviceManagerActivity::class.java))
        }

        btnSystemSettings.setOnClickListener {
            startActivity(Intent(this, SystemSettingsActivity::class.java))
        }
    }

    private fun toggleTestPattern() {
        if (isTesting) {
            stopTestPattern()
        } else {
            startTestPattern()
        }
    }

    private fun startTestPattern() {
        isTesting = true
        testPatternJob?.cancel()
        AmbientCaptureService.isTestingOverride = true

        testPatternJob = lifecycleScope.launch(Dispatchers.IO) {
            val devices = config.enabledDevices
            var step = 0

            while (isTesting) {
                for (dev in devices) {
                    val leds = dev.totalLeds
                    if (leds <= 0) continue
                    val testBuffer = ByteArray(leds * 3)

                    for (i in 0 until leds) {
                        val idx = i * 3
                        val hue = ((step * 4 + (i * 360 / leds)) % 360).toFloat()
                        val rgb = Color.HSVToColor(floatArrayOf(hue, 1.0f, 1.0f))
                        testBuffer[idx] = Color.red(rgb).toByte()
                        testBuffer[idx + 1] = Color.green(rgb).toByte()
                        testBuffer[idx + 2] = Color.blue(rgb).toByte()
                    }

                    udpSender.sendDrgbFrame(
                        ip = dev.ip,
                        port = dev.port,
                        timeoutSeconds = 2,
                        rgb = testBuffer,
                        ledCount = leds,
                        colorOrder = dev.calibration.colorOrder
                    )

                    launch(Dispatchers.Main) {
                        tvPreviewView.updateDeviceLiveColors(dev.id, testBuffer, leds)
                    }
                }

                step = (step + 1) % 360
                delay(33)
            }
        }
    }

    private fun stopTestPattern() {
        isTesting = false
        testPatternJob?.cancel()
        testPatternJob = null
        AmbientCaptureService.isTestingOverride = false
        lifecycleScope.launch(Dispatchers.IO) {
            for (dev in config.enabledDevices) {
                val leds = dev.totalLeds
                if (leds > 0) {
                    val blackBuffer = ByteArray(leds * 3)
                    udpSender.sendDrgbFrame(
                        ip = dev.ip,
                        port = dev.port,
                        timeoutSeconds = 1,
                        rgb = blackBuffer,
                        ledCount = leds,
                        colorOrder = dev.colorOrder
                    )
                }
            }
        }
    }

    private fun requestScreenCapture() {
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun startAmbientService(resultCode: Int, data: Intent) {
        val intent = Intent(this, AmbientCaptureService::class.java).apply {
            putExtra(AmbientCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(AmbientCaptureService.EXTRA_RESULT_DATA, data)
        }
        startForegroundService(intent)
        AmbientCaptureService.isRunning = true
        updateUiState()
    }

    private fun stopAmbientService() {
        AmbientCaptureService.isRunning = false
        updateUiState()
        val intent = Intent(this, AmbientCaptureService::class.java).apply {
            action = AmbientCaptureService.ACTION_STOP
        }
        startService(intent)
    }

    private fun updateUiState() {
        val running = AmbientCaptureService.isRunning
        val enabledCount = config.enabledDevices.size
        val totalLeds = config.totalActiveLeds

        if (running) {
            btnPower.setBackgroundResource(R.drawable.bg_power_button_on)
            tvPowerText.text = getString(R.string.btn_stop_mirror)
            viewStatusDot.setBackgroundColor(Color.parseColor("#00E676"))
            tvStatusTitle.text = getString(R.string.status_mirroring)
            tvStatusSubtitle.text = if (enabledCount == 1) "Streaming to ${config.ip}" else "Streaming to $enabledCount Lights"
        } else {
            btnPower.setBackgroundResource(R.drawable.bg_power_button_off)
            tvPowerText.text = getString(R.string.btn_start_mirror)
            viewStatusDot.setBackgroundColor(Color.parseColor("#64748B"))
            tvStatusTitle.text = getString(R.string.status_idle)
            tvStatusSubtitle.text = if (enabledCount == 1) "WLED: ${config.ip}" else "$enabledCount Lights Configured"
        }

        tvTotalLedsBadge.text = "$totalLeds LEDs ($enabledCount Lights)"
        tvIpSummary.text = if (enabledCount == 1) "WLED: ${config.ip}:${config.port}" else "$enabledCount Active WLED Controllers"
        tvDirectionSummary.text = "${config.perimeter.direction.name} • ${config.calibration.fps} FPS"
    }

    private fun checkWledConnection() {
        lifecycleScope.launch {
            val connected = httpClient.checkConnection(config.ip)
            if (!AmbientCaptureService.isRunning) {
                if (connected) {
                    viewStatusDot.setBackgroundColor(Color.parseColor("#00E676"))
                    tvStatusTitle.text = getString(R.string.status_connected)
                } else {
                    viewStatusDot.setBackgroundColor(Color.parseColor("#F87171"))
                    tvStatusTitle.text = getString(R.string.status_disconnected)
                }
            }
        }
    }

    override fun onDeviceFrameProcessed(deviceId: String, rgb: ByteArray, count: Int) {
        if (!isTesting) {
            runOnUiThread {
                tvPreviewView.updateDeviceLiveColors(deviceId, rgb, count)
            }
        }
    }

    override fun onStateChanged(running: Boolean) {
        runOnUiThread {
            updateUiState()
        }
    }
}
