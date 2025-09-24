package com.buligin.vishnucast

import android.app.Application
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import org.webrtc.PeerConnectionFactory
import java.util.Locale

class App : Application() {

    companion object {
        private const val PREFS_NAME = "vishnucast_prefs"
        private const val KEY_APP_LANG = "app_lang" // "ru" | "en"
    }

    override fun onCreate() {
        super.onCreate()

        // Явная загрузка нативной библиотеки WebRTC (подстраховка порядка загрузки)
        try { System.loadLibrary("jingle_peerconnection_so") } catch (_: Throwable) {}

        // Инициализация WebRTC — один раз на процесс, без fieldTrials
        try {
            val init = PeerConnectionFactory.InitializationOptions
                .builder(applicationContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(init)
        } catch (_: Throwable) {}

        // Автостарт: серверы подняты, микрофон по умолчанию mute
        CastService.ensureStarted(applicationContext)

        // Язык приложения
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val saved = prefs.getString(KEY_APP_LANG, null)
        val langToApply = saved ?: run {
            val sysLang = systemPrimaryLang()
            val def = if (sysLang.startsWith("ru", ignoreCase = true)) "ru" else "en"
            prefs.edit().putString(KEY_APP_LANG, def).apply()
            def
        }
        applyAppLanguage(langToApply)
    }

    private fun systemPrimaryLang(): String {
        val locales: LocaleList = resources.configuration.locales
        val primary: Locale = if (locales.size() > 0) locales[0] else Locale.getDefault()
        return primary.language.lowercase(Locale.ROOT)
    }

    private fun applyAppLanguage(lang: String) {
        val tags = when (lang.lowercase(Locale.ROOT)) {
            "ru" -> "ru"
            else -> "en"
        }
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tags))
    }
}
