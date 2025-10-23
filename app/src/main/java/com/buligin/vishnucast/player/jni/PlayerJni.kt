package com.buligin.vishnucast.player.jni

object PlayerJni {
    init {
        // безопасно: наша so не тянет WebRTC, можно грузить на старте
        System.loadLibrary("vishnuplayer")
    }

    @JvmStatic external fun createSource(): Long
    @JvmStatic external fun destroySource(src: Long)
    @JvmStatic external fun sourceSetMuted(src: Long, muted: Boolean)
    @JvmStatic external fun sourcePushPcm48kMono(src: Long, pcm: ShortArray, samples: Int)
}
