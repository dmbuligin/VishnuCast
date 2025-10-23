package com.buligin.vishnucast

import android.util.Log

/**
 * Централизованный логгер, НЕ зависит от BuildConfig.
 * Переключается в рантайме: Logger.enabled = true/false.
 */
object Logger {
    @Volatile
    var enabled: Boolean = true  // по умолчанию включен

    inline fun d(tag: String, msg: String) {
        if (enabled) Log.d(tag, msg)
    }
    inline fun i(tag: String, msg: String) {
        if (enabled) Log.i(tag, msg)
    }
    inline fun w(tag: String, msg: String, tr: Throwable? = null) {
        if (enabled) Log.w(tag, msg, tr)
    }
    inline fun e(tag: String, msg: String, tr: Throwable? = null) {
        if (enabled) Log.e(tag, msg, tr)
    }
}
