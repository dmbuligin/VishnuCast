package com.buligin.vishnucast

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale
import android.app.Application
import android.os.LocaleList
import org.webrtc.PeerConnectionFactory

class App : Application() {

    companion object {
        private const val PREFS_NAME = "vishnucast_prefs"
        private const val KEY_APP_LANG = "app_lang" // "ru" | "en"
    }

    override fun onCreate() {
        super.onCreate()

        // 1) Выбор языка приложения:
        //    - если сохранён в prefs → используем его;
        //    - иначе смотрим язык системы: ru → "ru", иначе → "en"; сохраняем.
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val saved = prefs.getString(KEY_APP_LANG, null)

        val langToApply = saved ?: run {
            val sysLang = systemPrimaryLang()
            val def = if (sysLang.startsWith("ru", ignoreCase = true)) "ru" else "en"
            prefs.edit().putString(KEY_APP_LANG, def).apply()
            def
        }

        applyAppLanguage(langToApply)

        // 2) MUST: инициализируем нативные библиотеки WebRTC до первого JNI вызова
        val init = PeerConnectionFactory.InitializationOptions
            .builder(this)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(init)
    }

    private fun systemPrimaryLang(): String {
        // Android 7+ может иметь список локалей — берём первую.
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
