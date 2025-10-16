package com.buligin.vishnucast.player.capture

import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Совместимый слой, чтобы не дёргать напрямую API29+ класс/методы с minSdk 24.
 * Любые вызовы к PlayerSystemCapture идут через этот мост.
 */
object PlayerSystemCaptureCompat {

    /** Безопасно пробрасывает хэндл нативного источника (Q+), на <29 — no-op. */
    fun setNativeSourceHandleCompat(ptr: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Api29Impl.setNativeSourceHandle(ptr)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private object Api29Impl {
        fun setNativeSourceHandle(ptr: Long) {
            PlayerSystemCapture.setNativeSourceHandle(ptr)
        }
    }
}
