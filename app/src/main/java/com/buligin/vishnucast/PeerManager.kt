package com.buligin.vishnucast

import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Менеджер "1 клиент = 1 PeerConnection".
 * Адаптирован под 2PC-ядро: работает с WebRtcCore.PcKind (MIC/PLAYER),
 * но сохраняет прежние сигнатуры setRemoteDescription/createAnswer для обратной совместимости.
 */
class PeerManager(
    private val core: WebRtcCore,
    private val kind: WebRtcCore.PcKind // ОБЯЗАТЕЛЬНО указать: MIC или PLAYER
) {

    data class PeerSession(
        val id: String,
        val pc: PeerConnection,
        val sendToClient: (String) -> Unit  // функция отправки текстовых сообщений клиенту (через WS)
    )

    private val peers = ConcurrentHashMap<String, PeerSession>()
    private val pendingRemoteSdp = ConcurrentHashMap<String, SessionDescription>() // кэш оффера до createAnswer

    /** Создать PeerConnection для нового клиента и вернуть его id. */
    fun createPeer(sendToClient: (String) -> Unit, onIceOut: (String, IceCandidate) -> Unit): String {
        val id = UUID.randomUUID().toString()
        val pc = core.createPeerConnection(kind) { ice ->
            // исходящие ICE канд. -> наружу, вместе с clientId
            onIceOut(id, ice)
        } ?: throw IllegalStateException("Unable to create PeerConnection for $kind")
        peers[id] = PeerSession(id, pc, sendToClient)
        return id
    }

    /**
     * СТАРОЕ API: применить удалённый SDP (offer/answer) для указанного клиента.
     * В новой схеме мы только сохраняем оффер и зовём onSet(),
     * а фактическая установка Remote+Answer произойдёт в createAnswer(...).
     */
    fun setRemoteDescription(clientId: String, sdp: SessionDescription, onSet: () -> Unit = {}) {
        // храним только оффер; answer от клиента нам не нужен в recvonly-сценарии
        if (sdp.type == SessionDescription.Type.OFFER) {
            pendingRemoteSdp[clientId] = sdp
        }
        // совместимость со старым кодом: "успешно установили"
        onSet()
    }

    /**
     * СТАРОЕ API: создать локальный answer и вернуть его (через callback).
     * Реально вызывает новое ядро: setRemoteSdp(kind, sdpString) + onLocalSdp.
     */
    fun createAnswer(clientId: String, onLocalSdp: (SessionDescription) -> Unit) {
        val ps = peers[clientId] ?: return
        val offer = pendingRemoteSdp.remove(clientId)
        if (offer == null) {
            // нечего отвечать — не было оффера; просто игнорируем
            return
        }
        core.setRemoteSdp(kind, offer.description) { localAnswer ->
            // передаём наверх как раньше
            onLocalSdp(localAnswer)
        }
    }

    /** Принять входящий ICE от клиента. */
    fun addIceCandidate(clientId: String, cand: IceCandidate) {
        // можно кидать напрямую в pc, но единообразно пойдём через ядро (оно знает нужный PC)
        core.addIceCandidate(kind, cand)
    }

    /** Клиент отвалился или просит закрыть соединение. */
    fun closePeer(clientId: String) {
        val ps = peers.remove(clientId) ?: return
        try { ps.pc.dispose() } catch (_: Throwable) {}
        pendingRemoteSdp.remove(clientId)
    }

    fun closeAll() {
        for ((_, ps) in peers) {
            try { ps.pc.dispose() } catch (_: Throwable) {}
        }
        peers.clear()
        pendingRemoteSdp.clear()
    }
}
