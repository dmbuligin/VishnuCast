package com.buligin.vishnucast.player.jni

object PlayerJni {
    init {
        // System.loadLibrary("vishnuplayer") // подключим после CMake
    }
    external fun createEngine(): Long
    external fun destroyEngine(ptr: Long)
    external fun createSource(ptr: Long): Long
    external fun pushPcm48kMono(ptr: Long, pcm: ShortArray, samples: Int)
    external fun setMuted(ptr: Long, muted: Boolean)
}
