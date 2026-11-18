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
    private lateinit var seekOpacity: SeekBar
    private lateinit var opacityValue: TextView
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        btnPermission = findViewById(R.id.btnPermission)
        btnToggle = findViewById(R.id.btnToggle)
        seekOpacity = findViewById(R.id.seekOpacity)
        opacityValue = findViewById(R.id.opacityValue)

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
    }

    override fun onResume() {
        super.onResume()
        updateUi()
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
    }
}
