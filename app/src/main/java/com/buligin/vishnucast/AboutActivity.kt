package com.buligin.vishnucast

import android.os.Build
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.MenuItem
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar

class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        val aboutFooter = findViewById<TextView>(R.id.about_footer)
        aboutFooter.movementMethod = LinkMovementMethod.getInstance()

        // Toolbar + стрелка «назад»
        val toolbar = findViewById<Toolbar>(R.id.toolbar_about)
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowTitleEnabled(false) // заголовок задаём в разметке
        }

        // Версия / код сборки / время (BUILD_TIME уже даёт UTC-формат из Gradle)
        val versionNameView = findViewById<TextView>(R.id.about_version_name)
        val buildInfoView = findViewById<TextView>(R.id.about_build_info)

        val pInfo = packageManager.getPackageInfo(packageName, 0)
        val versionCode: Long = if (Build.VERSION.SDK_INT >= 28) {
            pInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pInfo.versionCode.toLong()
        }

        val versionName = BuildConfig.APP_VERSION
        val buildType = BuildConfig.BUILD_TYPE // "debug" или "release"
        val versionWithType = "$versionName-${buildType.lowercase()}"
        val buildTime = BuildConfig.BUILD_TIME
        versionNameView.text = getString(R.string.version_fmt, versionWithType)

        buildInfoView.text = getString(R.string.build_info_fmt, versionCode, buildTime)

        // Ссылки в тексте About
        val aboutText = findViewById<TextView>(R.id.about_text)
        aboutText.movementMethod = LinkMovementMethod.getInstance()

        val aboutRespect = findViewById<TextView>(R.id.about_respect)
        aboutRespect.movementMethod = LinkMovementMethod.getInstance()

    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
