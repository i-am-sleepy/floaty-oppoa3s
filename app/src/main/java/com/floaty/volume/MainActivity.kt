package com.floaty.volume

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast

/** Single screen: grant overlay permission, then start/stop the bubble. */
class MainActivity : Activity() {

    private lateinit var btnPermission: Button
    private lateinit var btnToggle: Button
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        btnPermission = findViewById(R.id.btnPermission)
        btnToggle = findViewById(R.id.btnToggle)

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
    }
}
