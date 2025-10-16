package com.buligin.vishnucast.player.jni

object PlayerJni {
    init {
        try {
            System.loadLibrary("vishnuplayer")
        } catch (_: Throwable) {}
    }

    // Старый движок (как было)
    external fun createEngine(): Long
    external fun destroyEngine(ptr: Long)
    external fun pushPcm48kMono(ptr: Long, pcm: ShortArray, samples: Int)
    external fun setMuted(ptr: Long, muted: Boolean)

    // Новый нативный источник
    external fun createSource(): Long
    external fun destroySource(ptr: Long)
    external fun sourcePushPcm48kMono(ptr: Long, pcm: ShortArray, samples: Int)
    external fun sourceSetMuted(ptr: Long, muted: Boolean)
}
