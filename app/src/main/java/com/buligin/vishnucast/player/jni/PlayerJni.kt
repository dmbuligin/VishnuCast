package com.buligin.vishnucast.player.jni

import androidx.annotation.Keep

@Keep
object PlayerJni {
    init {
        try {
            System.loadLibrary("vishnuplayer")
        } catch (_: Throwable) { /* ignore to keep AS linter calm; real crash will be visible at runtime */ }
    }

    // --- Старый движок (оставляем как есть, но делаем статическими JNI) ---
    @Keep @JvmStatic external fun createEngine(): Long
    @Keep @JvmStatic external fun destroyEngine(ptr: Long)
    @Keep @JvmStatic external fun pushPcm48kMono(ptr: Long, pcm: ShortArray, samples: Int)
    @Keep @JvmStatic external fun setMuted(ptr: Long, muted: Boolean)

    // --- Новый нативный источник ---
    @Keep @JvmStatic external fun createSource(): Long
    @Keep @JvmStatic external fun destroySource(ptr: Long)
    @Keep @JvmStatic external fun sourcePushPcm48kMono(ptr: Long, pcm: ShortArray, samples: Int)
    @Keep @JvmStatic external fun sourceSetMuted(ptr: Long, muted: Boolean)
}
