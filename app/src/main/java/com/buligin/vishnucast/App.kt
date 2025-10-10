package com.buligin.vishnucast

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import org.webrtc.PeerConnectionFactory
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class App : Application() {

    companion object {
        private const val PREFS_NAME = "vishnucast_prefs"
        private const val KEY_APP_LANG = "app_lang" // "ru" | "en"
    }

    // Флаг, чтобы стартнуть сервис ровно один раз при первом появлении UI
    private val fgsStartedOnce = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()

        // 1) WebRTC: инициализация один раз на процесс (без fieldTrials)
        try { System.loadLibrary("jingle_peerconnection_so") } catch (_: Throwable) {}
        try {
            val init = PeerConnectionFactory.InitializationOptions
                .builder(applicationContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(init)
        } catch (_: Throwable) {}

        // 2) Локаль приложения (до старта Activity)
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val saved = prefs.getString(KEY_APP_LANG, null)
        val langToApply = saved ?: run {
            val sysLang = systemPrimaryLang()
            val def = if (sysLang.startsWith("ru", ignoreCase = true)) "ru" else "en"
            prefs.edit().putString(KEY_APP_LANG, def).apply()
            def
        }
        applyAppLanguage(langToApply)

        // 3) Без сторонних зависимостей: ждём первый START любой Activity
        //    и только тогда поднимаем ForegroundService.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (fgsStartedOnce.compareAndSet(false, true)) {
                    // Первый раз приложение вышло на экран → безопасно стартуем FGS
                    CastService.ensureStarted(applicationContext)
                    // Один раз хватит — дальше не нужно держать колбэки
                    unregisterActivityLifecycleCallbacks(this)
                }
            }
            override fun onActivityCreated(a: Activity, s: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    private fun systemPrimaryLang(): String {
        return if (Build.VERSION.SDK_INT >= 24) {
            val locales: LocaleList = resources.configuration.locales
            val primary: Locale = if (locales.size() > 0) locales[0] else Locale.getDefault()
            primary.language.lowercase(Locale.ROOT)
        } else {
            // minSdk у проекта 24, но оставим бэкап для ясности
            Locale.getDefault().language.lowercase(Locale.ROOT)
        }
    }

    private fun applyAppLanguage(lang: String) {
        val tags = when (lang.lowercase(Locale.ROOT)) {
            "ru" -> "ru"
            else -> "en"
        }
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tags))
    }
}
