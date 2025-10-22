package com.buligin.vishnucast

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import org.webrtc.SdpObserver
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger


/** Глобальный холдер единственного WebRtcCore на процесс */
object WebRtcCoreHolder {
    @Volatile private var instance: WebRtcCore? = null

    fun get(ctx: android.content.Context): WebRtcCore {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            val core = WebRtcCore(ctx.applicationContext)
            instance = core
            return core
        }
    }

    fun closeAndClear() = synchronized(this) {
        val inst = instance
        if (inst != null) {
            // Без прямой ссылки на WebRtcCore.close() — чтобы не ловить Unresolved reference
            try {
                // Порядок важен: сперва пытаемся вызвать close(), если это наш WebRtcCore
                inst.javaClass.getMethod("close").invoke(inst)
            } catch (_: Throwable) {
                try {
                    // Для WebRTC-объектов (PeerConnection/Factory/Source/Track) корректно dispose()
                    inst.javaClass.getMethod("dispose").invoke(inst)
                } catch (_: Throwable) { /* no-op */ }
            }
        }
        instance = null
    }
}

/** Устойчивый счётчик клиентов (живёт в процессе) */
object ClientCounterStable {
    private val n = AtomicInteger(0)
    fun inc() { val v = n.incrementAndGet(); ClientCount.post(v) }
    fun dec() { val v = n.decrementAndGet().coerceAtLeast(0); ClientCount.post(0.coerceAtLeast(v)) }
    fun reset() { n.set(0); ClientCount.post(0) }
    fun value(): Int = n.get()
}

class SignalingSocket(
    private val ctx: android.content.Context,
    handshake: NanoHTTPD.IHTTPSession
) : NanoWSD.WebSocket(handshake) {

    private val webrtc = WebRtcCoreHolder.get(ctx)
    private var pc: org.webrtc.PeerConnection? = null

    // учёт состояния «засчитан в счётчик»
    private val counted = AtomicBoolean(false)
    private var pollTimer: Timer? = null

    override fun onOpen() {}

    override fun onPong(pong: NanoWSD.WebSocketFrame?) {}

    override fun onMessage(message: NanoWSD.WebSocketFrame) {
        try {
            val text = message.textPayload ?: return
            val obj = try { JSONObject(text) } catch (_: Throwable) { null }
            if (obj == null) return

            // --- keep-alive от клиента ---
            when (obj.optString("type", "")) {
                "ping" -> {
                    try {
                        val pong = JSONObject()
                            .put("type", "pong")
                            .put("t", obj.optLong("t", 0L))
                            .put("ts", System.currentTimeMillis())
                        send(pong.toString())
                    } catch (_: Throwable) {}
                    return
                }
                "ka" -> return
            }

            // --- ICE-кандидаты (поддерживаем оба формата) ---
            if (obj.optString("type", "") == "ice") {
                val cObj = obj.optJSONObject("candidate") ?: JSONObject()
                val c = IceCandidate(
                    cObj.optString("sdpMid", null),
                    cObj.optInt("sdpMLineIndex", 0),
                    cObj.optString("candidate", "")
                )
                pc?.addIceCandidate(c)
                return
            } else if (obj.has("candidate")) {
                val candidateStr = obj.optString("candidate", null)
                val sdpMid = obj.optString("sdpMid", null)
                val sdpMLineIndex = obj.optInt("sdpMLineIndex", 0)
                if (!candidateStr.isNullOrEmpty()) {
                    val c = IceCandidate(sdpMid, sdpMLineIndex, candidateStr)
                    pc?.addIceCandidate(c)
                    return
                }
            }

            // --- SDP offer ---
            when (obj.optString("type", "")) {
                "offer" -> {
                    try {
                        send(JSONObject().put("type", "ack").put("stage", "offer-received").toString())
                    } catch (_: Throwable) {}
                    handleOffer(obj.getString("sdp"))
                }
                else -> { /* no-op */ }
            }
        } catch (_: Exception) {
            try { send(JSONObject().put("type", "error").put("message", "bad message").toString()) } catch (_: Throwable) {}
        }
    }

    override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
        stopPolling()
        if (counted.compareAndSet(true, false)) {
            ClientCounterStable.dec()
        }
        try { pc?.dispose() } catch (_: Throwable) {}
        pc = null
    }

    override fun onException(exception: java.io.IOException?) {}



    private fun normalizeOpusOnlyAnswer(sdpIn: String): String {
        // Нормализуем под один Opus-поток: m=audio ... 111, a=rtpmap/fmtp/rtcp-fb только для opus
        val s = sdpIn.replace("\r\n", "\n")
        val lines = s.lines()

        // 1) Найдём PT для opus/48000/2
        var opusPt: String? = null
        for (l in lines) {
            val m = Regex("""^a=rtpmap:(\d+)\s+opus/48000/2""").find(l)
            if (m != null) { opusPt = m.groupValues[1]; break }
        }
        if (opusPt == null) return sdpIn // нет opus — ничего не меняем

        // 2) Пересобираем SDP
        val out = ArrayList<String>(lines.size)
        var mAudioDone = false

        for (l in lines) {
            if (l.startsWith("m=audio ")) {
                // m=audio <port> <proto> <opusPt>
                val parts = l.split(" ").filter { it.isNotEmpty() }
                val port = if (parts.size >= 2) parts[1] else "9"
                val proto = if (parts.size >= 3) parts[2] else "UDP/TLS/RTP/SAVPF"
                out.add("m=audio $port $proto $opusPt")
                mAudioDone = true
                continue
            }

            // a=rtpmap/fmtp/rtcp-fb — оставляем только для opus PT
            val mm = Regex("""^a=(rtpmap|fmtp|rtcp-fb):(\d+)(.*)$""").find(l)
            if (mm != null) {
                val pt = mm.groupValues[2]
                // rtpmap: оставляем только opus; остальные PT отбрасываем
                if (l.startsWith("a=rtpmap:")) {
                    if (l.contains("opus/48000/2")) out.add(l)
                } else {
                    if (pt == opusPt) out.add(l)
                }
                continue
            }

            // Остальные строки (ice, fingerprint, setup, extmap, msid и т.д.) — пропускаем как есть
            out.add(l)
        }

        // 3) На случай, если m=audio не попалась (крайний случай), ничего не ломаем
        if (!mAudioDone) return sdpIn

        // 4) Гарантируем CRLF и завершающий CRLF
        return out.joinToString("\r\n", postfix = "\r\n")
    }





    private fun handleOffer(sdp: String) {
        pc = webrtc.createPeerConnection { cand ->
            val payload = JSONObject()
                .put("sdpMid", cand.sdpMid)
                .put("sdpMLineIndex", cand.sdpMLineIndex)
                .put("candidate", cand.sdp)
            val ice = JSONObject().put("type", "ice").put("candidate", payload)
            try { send(ice.toString()) } catch (_: Throwable) {}
        }

        val pcLocal = pc ?: run {
            try { send(JSONObject().put("type", "error").put("message", "pc-null").toString()) } catch (_: Throwable) {}
            return
        }

        startPollingIceConnected()

        val remote = SessionDescription(SessionDescription.Type.OFFER, sdp)
        pcLocal.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                pcLocal.createAnswer(object : SdpObserver {

                    override fun onCreateSuccess(desc: SessionDescription) {
                        val munged = normalizeOpusOnlyAnswer(desc.description)
                        val local = SessionDescription(desc.type, munged)

                        pcLocal.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                try {
                                    send(org.json.JSONObject().put("type", "answer").put("sdp", munged).toString())
                                    android.util.Log.d("VishnuWS", "answer sent len=${munged.length}")
                                } catch (_: Throwable) {}
                            }
                            override fun onSetFailure(p0: String?) {}
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onCreateFailure(p0: String?) {}
                        }, local)
                    }



                    override fun onCreateFailure(p0: String?) {
                        try { send(JSONObject().put("type", "error").put("message", "createAnswer").toString()) } catch (_: Throwable) {}
                    }
                    override fun onSetSuccess() {}
                    override fun onSetFailure(p0: String?) {}
                }, org.webrtc.MediaConstraints())
            }
            override fun onSetFailure(p0: String?) {
                try { send(JSONObject().put("type", "error").put("message", "setRemoteDescription").toString()) } catch (_: Throwable) {}
            }
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, remote)
    }

    private fun startPollingIceConnected() {
        stopPolling()
        pollTimer = Timer("vc-icepoll", true).apply {
            schedule(object : TimerTask() {
                override fun run() {
                    val cur = pc ?: return
                    val st = try { cur.iceConnectionState() } catch (_: Throwable) { null }
                    if (st == org.webrtc.PeerConnection.IceConnectionState.CONNECTED) {
                        if (counted.compareAndSet(false, true)) {
                            ClientCounterStable.inc()
                        }
                        stopPolling()
                    } else if (st == org.webrtc.PeerConnection.IceConnectionState.FAILED
                        || st == org.webrtc.PeerConnection.IceConnectionState.CLOSED) {
                        stopPolling()
                    }
                }
            }, 0L, 200L)
        }
    }

    private fun stopPolling() {
        try { pollTimer?.cancel() } catch (_: Throwable) {}
        pollTimer = null
    }
}
