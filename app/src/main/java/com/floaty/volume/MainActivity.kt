package com.floaty.volume

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/** Single screen: grant overlay permission, start/stop the bubble, set opacity. */
class MainActivity : Activity() {

    private lateinit var btnPermission: Button
    private lateinit var btnToggle: Button
    private lateinit var btnAutostart: Button
    private lateinit var seekOpacity: SeekBar
    private lateinit var opacityValue: TextView
    private lateinit var seekPanelOpacity: SeekBar
    private lateinit var panelOpacityValue: TextView
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        btnPermission = findViewById(R.id.btnPermission)
        btnToggle = findViewById(R.id.btnToggle)
        btnAutostart = findViewById(R.id.btnAutostart)
        seekOpacity = findViewById(R.id.seekOpacity)
        opacityValue = findViewById(R.id.opacityValue)
        seekPanelOpacity = findViewById(R.id.seekPanelOpacity)
        panelOpacityValue = findViewById(R.id.panelOpacityValue)

        btnPermission.setOnClickListener {
            if (Settings.canDrawOverlays(this)) {
                Toast.makeText(this, R.string.permission_granted, Toast.LENGTH_SHORT).show()
            } else {
                openOverlaySettings()
            }
        }

        btnToggle.setOnClickListener {
            if (prefs.wantRunning) {
                FloatyService.stop(this)
                prefs.wantRunning = false
                Toast.makeText(this, R.string.toast_stopped, Toast.LENGTH_SHORT).show()
                updateUi()
            } else {
                if (!Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, R.string.toast_permission_needed, Toast.LENGTH_LONG).show()
                    openOverlaySettings()
                } else {
                    FloatyService.start(this)
                    prefs.wantRunning = true
                    Toast.makeText(this, R.string.toast_started, Toast.LENGTH_SHORT).show()
                    updateUi()
                }
            }
        }

        // Bubble transparency: saved to prefs and applied live to the running
        // bubble via a same-package broadcast (the service owns the window).
        seekOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                prefs.bubbleOpacity = progress
                opacityValue.text = "$progress%"
                val intent = Intent(FloatyService.ACTION_OPACITY).setPackage(packageName)
                intent.putExtra(FloatyService.EXTRA_OPACITY, progress)
                sendBroadcast(intent)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        // Panel transparency: saved to prefs and applied live to the open
        // panel via a same-package broadcast (the service owns the window).
        seekPanelOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                prefs.panelOpacity = progress
                panelOpacityValue.text = "$progress%"
                val intent = Intent(FloatyService.ACTION_PANEL_OPACITY).setPackage(packageName)
                intent.putExtra(FloatyService.EXTRA_PANEL_OPACITY, progress)
                sendBroadcast(intent)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        // Autostart after reboot: there is no public API for this, so we open
        // the ROM's autostart page (ColorOS/MIUI/etc.) when available.
        btnAutostart.setOnClickListener { openAutostartSettings() }
    }

    override fun onResume() {
        super.onResume()
        updateUi()
    }

    private fun openAutostartSettings() {
        val candidates = listOf(
            Intent("com.coloros.settings.AUTOSTART_MANAGER"),
            Intent("com.miui.securitycenter.AUTOSTART_MANAGER"),
            Intent("com.android.settings.AUTOSTART_SETTINGS"),
            Intent("com.vivo.settings.AUTOSTART_MANAGER")
        )
        for (intent in candidates) {
            try {
                startActivity(intent)
                return
            } catch (ignored: Exception) {
                // Not available on this ROM; try the next one.
            }
        }
        // Fallback: the app details page, which on most ROMs links to the
        // autostart / battery options.
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (ignored: Exception) {
            Toast.makeText(this, R.string.autostart_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // Some ROMs only expose the global list.
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
    }

    private fun updateUi() {
        val granted = Settings.canDrawOverlays(this)
        btnPermission.text = getString(
            if (granted) R.string.permission_granted else R.string.permission_grant
        )
        btnPermission.isEnabled = !granted
        btnToggle.text = getString(
            if (prefs.wantRunning) R.string.stop_button else R.string.start_button
        )

        val opacity = prefs.bubbleOpacity
        seekOpacity.progress = opacity
        opacityValue.text = "$opacity%"

        val panelOpacity = prefs.panelOpacity
        seekPanelOpacity.progress = panelOpacity
        panelOpacityValue.text = "$panelOpacity%"
    }
}
