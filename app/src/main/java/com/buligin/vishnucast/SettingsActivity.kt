package com.buligin.vishnucast

import android.os.Bundle
import android.view.MenuItem
import com.google.android.material.switchmaterial.SwitchMaterial
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar

class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "vishnucast_prefs"
        private const val KEY_AGC_ENABLED = "pref_agc_enabled"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        val swAgc: SwitchMaterial = findViewById(R.id.switchAgc)
        swAgc.isChecked = prefs.getBoolean(KEY_AGC_ENABLED, true)
        swAgc.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_AGC_ENABLED, checked).apply()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { finish(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
