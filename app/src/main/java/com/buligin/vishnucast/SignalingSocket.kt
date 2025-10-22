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
            try { inst.javaClass.getMethod("close").invoke(inst) } catch (_: Throwable) {
                try { inst.javaClass.getMethod("dispose").invoke(inst) } catch (_: Throwable) {}
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
            val obj = try { JSONObject(text) } catch (_: Throwable) { null } ?: return

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
                    try { send(JSONObject().put("type", "ack").put("stage", "offer-received").toString()) } catch (_: Throwable) {}
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

    // --- оставляем только уникальные payload type в строке m=audio; прочее не трогаем ---
    private fun dedupMLinePayloads(sdpIn: String): String {
        val s = sdpIn.replace("\r\n", "\n")
        val lines = s.lines().toMutableList()
        for (i in lines.indices) {
            val l = lines[i]
            if (l.startsWith("m=audio ")) {
                val parts = l.trim().split(Regex("\\s+"))
                if (parts.size >= 4) {
                    val prefix = parts.subList(0, 3) // ["m=audio", "<port>", "<proto>"]
                    val payloads = linkedSetOf<String>() // порядок сохраняем, дубли убираем
                    for (k in 3 until parts.size) {
                        val p = parts[k]
                        if (p.isNotEmpty()) payloads.add(p)
                    }
                    lines[i] = prefix.joinToString(" ") + " " + payloads.joinToString(" ")
                }
                break
            }
        }
        return lines.joinToString("\r\n", postfix = "\r\n")
    }

    private fun handleOffer(sdp: String) {
        // создаём PC и фиксируем ссылку
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
                // ВАЖНО: используем только "замороженную" ссылку pcLocal
                pcLocal.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(desc: SessionDescription) {
                        val mungedSdp = try {
                            dedupMLinePayloads(desc.description)
                        } catch (t: Throwable) {
                            android.util.Log.w("VishnuWS", "dedup m=audio failed: ${t.message}")
                            desc.description
                        }
                        val local = SessionDescription(desc.type, mungedSdp)

                        val localPc = pcLocal // фиксируем ссылку для колбэка
                        localPc.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                try {
                                    val json = JSONObject()
                                        .put("type", "answer")
                                        .put("sdp", mungedSdp)
                                    send(json.toString())
                                    android.util.Log.d("VishnuWS", "answer sent len=${mungedSdp.length}")
                                } catch (_: Throwable) { /* ignore */ }
                            }
                            override fun onSetFailure(p0: String?) {
                                android.util.Log.e("VishnuWS", "setLocalDescription failed: $p0")
                            }
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onCreateFailure(p0: String?) {}
                        }, local)
                    }

                    override fun onCreateFailure(p0: String?) {
                        android.util.Log.e("VishnuWS", "createAnswer failed: $p0")
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
