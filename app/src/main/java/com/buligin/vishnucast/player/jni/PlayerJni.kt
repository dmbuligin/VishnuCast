package com.buligin.vishnucast.player.jni

object PlayerJni {
    init {
        try {
            System.loadLibrary("vishnuplayer")
        } catch (_: Throwable) {}
    }
    external fun createEngine(): Long
    external fun destroyEngine(ptr: Long)
    external fun pushPcm48kMono(ptr: Long, pcm: ShortArray, samples: Int)
    external fun setMuted(ptr: Long, muted: Boolean)
}
