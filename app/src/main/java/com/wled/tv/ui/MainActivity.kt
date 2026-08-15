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
        tvPreviewView.updateConfig(config.perimeter)
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
        btnCalibration = findViewById(R.id.btnCalibration)
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

        btnCalibration.setOnClickListener {
            startActivity(Intent(this, CalibrationActivity::class.java))
        }

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
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
            val totalLeds = config.perimeter.totalLeds
            val testBuffer = ByteArray(totalLeds * 3)
            var step = 0

            while (isTesting) {
                for (i in 0 until totalLeds) {
                    val idx = i * 3
                    val hue = ((step * 4 + (i * 360 / totalLeds)) % 360).toFloat()
                    val rgb = Color.HSVToColor(floatArrayOf(hue, 1.0f, 1.0f))
                    testBuffer[idx] = Color.red(rgb).toByte()
                    testBuffer[idx + 1] = Color.green(rgb).toByte()
                    testBuffer[idx + 2] = Color.blue(rgb).toByte()
                }

                udpSender.sendDrgbFrame(
                    ip = config.ip,
                    port = config.port,
                    timeoutSeconds = 2,
                    rgb = testBuffer,
                    ledCount = totalLeds
                )

                launch(Dispatchers.Main) {
                    tvPreviewView.updateLiveColors(testBuffer, totalLeds)
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
            val totalLeds = config.perimeter.totalLeds
            val blackBuffer = ByteArray(totalLeds * 3)
            udpSender.sendDrgbFrame(
                ip = config.ip,
                port = config.port,
                timeoutSeconds = 1,
                rgb = blackBuffer,
                ledCount = totalLeds
            )
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

        if (running) {
            btnPower.setBackgroundResource(R.drawable.bg_power_button_on)
            tvPowerText.text = getString(R.string.btn_stop_mirror)
            viewStatusDot.setBackgroundColor(Color.parseColor("#00E676"))
            tvStatusTitle.text = getString(R.string.status_mirroring)
            tvStatusSubtitle.text = "Streaming to ${config.ip}"
        } else {
            btnPower.setBackgroundResource(R.drawable.bg_power_button_off)
            tvPowerText.text = getString(R.string.btn_start_mirror)
            viewStatusDot.setBackgroundColor(Color.parseColor("#64748B"))
            tvStatusTitle.text = getString(R.string.status_idle)
            tvStatusSubtitle.text = "WLED: ${config.ip}"
        }

        val total = config.perimeter.totalLeds
        tvTotalLedsBadge.text = "$total LEDs"
        tvIpSummary.text = "WLED: ${config.ip}:${config.port}"
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

    override fun onFrameProcessed(rgb: ByteArray, count: Int) {
        if (!isTesting) {
            runOnUiThread {
                tvPreviewView.updateLiveColors(rgb, count)
            }
        }
    }

    override fun onStateChanged(running: Boolean) {
        runOnUiThread {
            updateUiState()
        }
    }
}
