package com.buligin.vishnucast.audio

import android.util.Log

object VcMix {
    private const val TAG = "VC/VcMix"
    @Volatile private var loaded = false

    init {
        try {
            System.loadLibrary("vcmix")
            nativeInit()
            loaded = nativeIsReady()
            Log.i(TAG, "JNI loaded=$loaded")
        } catch (t: Throwable) {
            Log.w(TAG, "JNI load failed: ${t.message}")
            loaded = false
        }
    }

    fun isReady(): Boolean = loaded

    @JvmStatic private external fun nativeInit()
    @JvmStatic private external fun nativeIsReady(): Boolean
}
