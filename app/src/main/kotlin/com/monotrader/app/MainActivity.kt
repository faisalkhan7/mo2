package com.monotrader.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat

/**
 * Main settings screen for MonoTrader v5.
 *
 * Two features:
 * 1. System grayscale toggle (true black & white via Android daltonizer)
 * 2. Black overlay (dims + desaturates, positioned on MT5 side)
 *
 * Position options now include "1/4 droit" for MT5 placed at 1/4 right.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: PrefsManager

    private lateinit var btnToggle: Button
    private lateinit var btnGrayscale: Button
    private lateinit var tvGrayscaleStatus: TextView
    private lateinit var seekOpacity: SeekBar
    private lateinit var tvOpacity: TextView
    private lateinit var seekAntiFlash: SeekBar
    private lateinit var tvAntiFlash: TextView
    private lateinit var radioGroup: RadioGroup
    private lateinit var btnPnlToggle: Button
    private lateinit var seekPnlInterval: SeekBar
    private lateinit var tvPnlInterval: TextView
    private lateinit var seekPnlPeek: SeekBar
    private lateinit var tvPnlPeek: TextView

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateToggleButton()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = PrefsManager(this)

        // Bind views
        btnToggle = findViewById(R.id.btn_toggle)
        btnGrayscale = findViewById(R.id.btn_grayscale)
        tvGrayscaleStatus = findViewById(R.id.tv_grayscale_status)
        seekOpacity = findViewById(R.id.seek_opacity)
        tvOpacity = findViewById(R.id.tv_opacity_value)
        seekAntiFlash = findViewById(R.id.seek_anti_flash)
        tvAntiFlash = findViewById(R.id.tv_anti_flash_value)
        radioGroup = findViewById(R.id.radio_position)
        btnPnlToggle = findViewById(R.id.btn_pnl_toggle)
        seekPnlInterval = findViewById(R.id.seek_pnl_interval)
        tvPnlInterval = findViewById(R.id.tv_pnl_interval)
        seekPnlPeek = findViewById(R.id.seek_pnl_peek)
        tvPnlPeek = findViewById(R.id.tv_pnl_peek)

        setupGrayscaleButton()
        setupToggleButton()
        setupOpacitySlider()
        setupPnlCurtain()
        setupAntiFlashSlider()
        setupAntiFlashPresets()
        setupPositionSelector()
        setupDisplaySettings()

        // Listen for state changes from the service
        val filter = IntentFilter("com.monotrader.app.OVERLAY_STATE_CHANGED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stateReceiver, filter)
        }
    }

    override fun onDestroy() {
        unregisterReceiver(stateReceiver)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        updateToggleButton()
        updateGrayscaleButton()
    }

    // ── Grayscale (System) ─────────────────────────────────────

    private fun setupGrayscaleButton() {
        updateGrayscaleButton()
        btnGrayscale.setOnClickListener {
            toggleSystemGrayscale()
        }
    }

    private fun isGrayscaleEnabled(): Boolean {
        return try {
            val enabled = Settings.Secure.getInt(contentResolver, "accessibility_display_daltonizer_enabled", 0)
            val mode = Settings.Secure.getInt(contentResolver, "accessibility_display_daltonizer", -1)
            enabled == 1 && mode == 0 // 0 = grayscale
        } catch (_: Exception) {
            false
        }
    }

    private fun toggleSystemGrayscale() {
        try {
            if (isGrayscaleEnabled()) {
                // Disable grayscale
                Settings.Secure.putInt(contentResolver, "accessibility_display_daltonizer_enabled", 0)
                Settings.Secure.putInt(contentResolver, "accessibility_display_daltonizer", -1)
            } else {
                // Enable grayscale (daltonizer mode 0 = monochromacy)
                Settings.Secure.putInt(contentResolver, "accessibility_display_daltonizer", 0)
                Settings.Secure.putInt(contentResolver, "accessibility_display_daltonizer_enabled", 1)
            }
            updateGrayscaleButton()
            tvGrayscaleStatus.text = ""
        } catch (e: SecurityException) {
            tvGrayscaleStatus.text = "⚠ Permission requise. Connecter via USB et lancer :\nadb shell pm grant com.monotrader.app android.permission.WRITE_SECURE_SETTINGS"
        }
    }

    private fun updateGrayscaleButton() {
        if (isGrayscaleEnabled()) {
            btnGrayscale.text = "⬛  Désactiver Grayscale"
            btnGrayscale.setBackgroundColor(0xFF666666.toInt())
        } else {
            btnGrayscale.text = "🔲  Activer Grayscale"
            btnGrayscale.setBackgroundColor(0xFF1565C0.toInt())
        }
        btnGrayscale.setTextColor(0xFFFFFFFF.toInt())
    }

    // ── Toggle Button ──────────────────────────────────────────

    private fun setupToggleButton() {
        updateToggleButton()
        btnToggle.setOnClickListener {
            if (!checkOverlayPermission()) return@setOnClickListener
            if (!checkNotificationPermission()) return@setOnClickListener

            if (prefs.isOverlayActive) {
                OverlayService.stop(this)
            } else {
                OverlayService.start(this)
            }
        }
    }

    private fun updateToggleButton() {
        if (prefs.isOverlayActive) {
            btnToggle.text = "⏹  Désactiver l'overlay"
            btnToggle.setBackgroundColor(0xFFD32F2F.toInt())
        } else {
            btnToggle.text = "▶  Activer l'overlay"
            btnToggle.setBackgroundColor(0xFF388E3C.toInt())
        }
        btnToggle.setTextColor(0xFFFFFFFF.toInt())
    }

    // ── Opacity Slider ─────────────────────────────────────────

    private fun setupOpacitySlider() {
        seekOpacity.max = 85
        seekOpacity.progress = prefs.opacity
        tvOpacity.text = "${prefs.opacity}%"

        seekOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvOpacity.text = "$progress%"
                if (fromUser) {
                    prefs.opacity = progress
                    if (prefs.isOverlayActive) {
                        OverlayService.update(this@MainActivity)
                    }
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    // ── P&L Curtain ─────────────────────────────────────────────

    private fun setupPnlCurtain() {
        updatePnlButton()

        btnPnlToggle.setOnClickListener {
            prefs.pnlCurtainEnabled = !prefs.pnlCurtainEnabled
            updatePnlButton()
            if (prefs.isOverlayActive) {
                OverlayService.update(this)
            }
        }

        // Interval slider (1-10 seconds)
        seekPnlInterval.progress = prefs.pnlIntervalSec
        tvPnlInterval.text = "${prefs.pnlIntervalSec}s"

        seekPnlInterval.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress.coerceAtLeast(1)
                tvPnlInterval.text = "${value}s"
                if (fromUser) prefs.pnlIntervalSec = value
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                if (prefs.isOverlayActive && prefs.pnlCurtainEnabled) {
                    OverlayService.update(this@MainActivity)
                }
            }
        })

        // Peek duration slider (100-1000 ms)
        seekPnlPeek.progress = prefs.pnlPeekMs
        tvPnlPeek.text = "${prefs.pnlPeekMs}ms"

        seekPnlPeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress.coerceAtLeast(100)
                tvPnlPeek.text = "${value}ms"
                if (fromUser) prefs.pnlPeekMs = value
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                if (prefs.isOverlayActive && prefs.pnlCurtainEnabled) {
                    OverlayService.update(this@MainActivity)
                }
            }
        })
    }

    private fun updatePnlButton() {
        if (prefs.pnlCurtainEnabled) {
            btnPnlToggle.text = "⏹  Désactiver le rideau P&L"
            btnPnlToggle.setBackgroundColor(0xFFD32F2F.toInt())
        } else {
            btnPnlToggle.text = "👁  Activer le rideau P&L"
            btnPnlToggle.setBackgroundColor(0xFF1565C0.toInt())
        }
        btnPnlToggle.setTextColor(0xFFFFFFFF.toInt())
    }

    // ── Anti-Flash Slider ──────────────────────────────────────

    private fun setupAntiFlashSlider() {
        seekAntiFlash.max = 1000
        seekAntiFlash.progress = prefs.antiFlashMs
        tvAntiFlash.text = "${prefs.antiFlashMs}ms"

        seekAntiFlash.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvAntiFlash.text = "${progress}ms"
                if (fromUser) {
                    prefs.antiFlashMs = progress
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                if (prefs.isOverlayActive) {
                    OverlayService.update(this@MainActivity)
                }
            }
        })
    }

    // ── Anti-Flash Presets ─────────────────────────────────────

    private fun setupAntiFlashPresets() {
        findViewById<Button>(R.id.btn_preset_off).setOnClickListener { setAntiFlash(0) }
        findViewById<Button>(R.id.btn_preset_scalp).setOnClickListener { setAntiFlash(200) }
        findViewById<Button>(R.id.btn_preset_swing).setOnClickListener { setAntiFlash(500) }
        findViewById<Button>(R.id.btn_preset_zen).setOnClickListener { setAntiFlash(1000) }
    }

    private fun setAntiFlash(ms: Int) {
        prefs.antiFlashMs = ms
        seekAntiFlash.progress = ms
        tvAntiFlash.text = "${ms}ms"
        if (prefs.isOverlayActive) {
            OverlayService.update(this)
        }
    }

    // ── Display Settings ──────────────────────────────────────

    private fun setupDisplaySettings() {
        findViewById<Button>(R.id.btn_open_display_settings).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
    }

    // ── Position Selector ──────────────────────────────────────

    private fun setupPositionSelector() {
        when (prefs.position) {
            PrefsManager.POSITION_LEFT -> findViewById<RadioButton>(R.id.radio_left).isChecked = true
            PrefsManager.POSITION_RIGHT -> findViewById<RadioButton>(R.id.radio_right).isChecked = true
            PrefsManager.POSITION_FULL -> findViewById<RadioButton>(R.id.radio_full).isChecked = true
            PrefsManager.POSITION_QUARTER_RIGHT -> findViewById<RadioButton>(R.id.radio_quarter_right).isChecked = true
        }

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            prefs.position = when (checkedId) {
                R.id.radio_left -> PrefsManager.POSITION_LEFT
                R.id.radio_right -> PrefsManager.POSITION_RIGHT
                R.id.radio_full -> PrefsManager.POSITION_FULL
                R.id.radio_quarter_right -> PrefsManager.POSITION_QUARTER_RIGHT
                else -> PrefsManager.POSITION_QUARTER_RIGHT
            }
            if (prefs.isOverlayActive) {
                OverlayService.update(this)
            }
        }
    }

    // ── Permissions ────────────────────────────────────────────

    private fun checkOverlayPermission(): Boolean {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ))
            return false
        }
        return true
    }

    private fun checkNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    100
                )
                return false
            }
        }
        return true
    }
}
