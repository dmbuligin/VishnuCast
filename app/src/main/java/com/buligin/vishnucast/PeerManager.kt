package com.buligin.vishnucast

import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Менеджер "1 клиент = 1 PeerConnection".
 * Хранит PC в map по clientId, создаёт/закрывает, маршрутизирует ICE.
 */
class PeerManager(private val core: WebRtcCore) {

    data class PeerSession(
        val id: String,
        val pc: PeerConnection,
        val sendToClient: (String) -> Unit  // функция отправки текстовых сообщений клиенту (через WS)
    )

    private val peers = ConcurrentHashMap<String, PeerSession>()

    /** Создать PeerConnection для нового клиента и вернуть его id. */
    fun createPeer(sendToClient: (String) -> Unit, onIceOut: (String, IceCandidate) -> Unit): String {
        val id = UUID.randomUUID().toString()
        val pc = core.createPeerConnection { ice ->
            // исходящие ICE канд. -> наружу, вместе с clientId
            onIceOut(id, ice)
        } ?: throw IllegalStateException("Unable to create PeerConnection")
        peers[id] = PeerSession(id, pc, sendToClient)
        return id
    }

    /** Применить удалённый SDP (offer/answer) для указанного клиента. */
    fun setRemoteDescription(clientId: String, sdp: SessionDescription, onSet: () -> Unit = {}) {
        val ps = peers[clientId] ?: return
        core.setRemoteDescription(ps.pc, sdp, onSet)
    }

    /** Создать локальный answer и вернуть его (через callback). */
    fun createAnswer(clientId: String, onLocalSdp: (SessionDescription) -> Unit) {
        val ps = peers[clientId] ?: return
        core.createAnswer(ps.pc, onLocalSdp)
    }

    /** Принять входящий ICE от клиента. */
    fun addIceCandidate(clientId: String, cand: IceCandidate) {
        peers[clientId]?.pc?.addIceCandidate(cand)
    }

    /** Клиент отвалился или просит закрыть соединение. */
    fun closePeer(clientId: String) {
        val ps = peers.remove(clientId) ?: return
        try { ps.pc.dispose() } catch (_: Throwable) {}
    }

    fun closeAll() {
        for ((id, ps) in peers) {
            try { ps.pc.dispose() } catch (_: Throwable) {}
        }
        peers.clear()
    }
}
