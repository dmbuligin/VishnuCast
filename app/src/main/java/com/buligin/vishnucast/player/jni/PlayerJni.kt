package com.buligin.vishnucast.player.jni

import org.webrtc.PeerConnectionFactory

object PlayerJni {
    init {
        try { System.loadLibrary("vishnuplayer") } catch (_: Throwable) {}
    }

    // Старый движок (оставляем)
    external fun createEngine(): Long
    external fun destroyEngine(ptr: Long)
    external fun pushPcm48kMono(ptr: Long, pcm: ShortArray, samples: Int)
    external fun setMuted(ptr: Long, muted: Boolean)

    // Новый нативный буфер-источник PCM
    external fun createSource(): Long
    external fun destroySource(ptr: Long)
    external fun sourcePushPcm48kMono(ptr: Long, pcm: ShortArray, samples: Int)
    external fun sourceSetMuted(ptr: Long, muted: Boolean)

    // Создание НАТИВНОГО WebRTC-трека из нашего источника.
    // ВНИМАНИЕ: передаём сам Java PeerConnectionFactory, а не "long ptr".
    external fun createWebRtcPlayerTrack(factory: PeerConnectionFactory, nativeSrcPtr: Long): Long
}
