package com.buligin.vishnucast

import android.content.Context

object PeerManagers {
    private var inited = false
    lateinit var mic: PeerManager
        private set
    lateinit var player: PeerManager
        private set

    @Synchronized
    fun ensure(ctx: Context) {
        if (inited) return
        val core = WebRtcCoreHolder.get(ctx.applicationContext)
        mic = PeerManager(core, WebRtcCore.PcKind.MIC)
        player = PeerManager(core, WebRtcCore.PcKind.PLAYER)
        inited = true
    }
}
