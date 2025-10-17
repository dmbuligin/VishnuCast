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

    fun peek(): WebRtcCore? = instance

    fun closeAndClear() = synchronized(this) {
        val inst = instance
        if (inst != null) {
            try {
                inst.javaClass.getMethod("close").invoke(inst)
            } catch (_: Throwable) {
                try { inst.javaClass.getMethod("dispose").invoke(inst) } catch (_: Throwable) { /* no-op */ }
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

    companion object {
        private val sockets = java.util.concurrent.CopyOnWriteArraySet<SignalingSocket>()
        private val wsExec = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "vc-ws-broadcast").apply { isDaemon = true }
        }

        fun broadcastJson(obj: org.json.JSONObject) {
            val text = obj.toString()
            wsExec.execute {
                for (ws in sockets) {
                    try {
                        ws.send(text)
                        android.util.Log.d("VishnuMix", "WS send OK len=" + text.length)
                    } catch (t: Throwable) {
                        android.util.Log.e("VishnuMix", "WS send FAIL: " + t.message, t)
                    }
                }
            }
        }

        fun broadcastMix(alpha: Float, micMuted: Boolean) {
            android.util.Log.d("VishnuMix", "WS broadcastMix alpha=$alpha micMuted=$micMuted sockets=${sockets.size}")
            val msg = org.json.JSONObject()
                .put("type", "mix")
                .put("alpha", alpha.coerceIn(0f, 1f))
                .put("micMuted", micMuted)
            broadcastJson(msg)
        }
    }

    private val webrtc = WebRtcCoreHolder.get(ctx)

    // какой тип PC нужен этому сокету: /ws → MIC, /ws_player → PLAYER
    private val kind: WebRtcCore.PcKind = when (handshake.uri) {
        "/ws_player" -> WebRtcCore.PcKind.PLAYER
        else -> WebRtcCore.PcKind.MIC
    }

    private var pc: org.webrtc.PeerConnection? = null

    // учёт состояния «засчитан в счётчик»
    private val counted = AtomicBoolean(false)
    private var pollTimer: Timer? = null

    override fun onOpen() {
        sockets.add(this)
        android.util.Log.d("VishnuMix", "WS onOpen uri=${handshakeRequest.uri} sockets=${sockets.size} kind=$kind")

        // Тестовое сообщение клиенту сразу после подключения
        try {
            val hello = org.json.JSONObject()
                .put("type", "mix")
                .put("alpha", 0.0)
                .put("micMuted", false)
            val text = hello.toString()
            send(text)
            android.util.Log.d("VishnuMix", "WS onOpen test mix sent len=" + text.length)
        } catch (t: Throwable) {
            android.util.Log.e("VishnuMix", "WS onOpen test mix FAIL: " + t.message, t)
        }
    }

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
        sockets.remove(this)
        android.util.Log.d("VishnuMix", "WS onClose uri=${handshakeRequest.uri} sockets=${sockets.size} kind=$kind")

        stopPolling()
        if (counted.compareAndSet(true, false)) {
            ClientCounterStable.dec()
        }
        try { pc?.dispose() } catch (_: Throwable) {}
        pc = null
    }

    override fun onException(exception: java.io.IOException?) {}

    private fun handleOffer(sdp: String) {
        // создаём PC нужного типа (MIC или PLAYER)
        pc = webrtc.createPeerConnection(kind) { cand ->
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
                        // SDP-мунжинг под конкретный PC
                        val mungedSdp = try {
                            mungeOpusAnswerForKind(desc.description, kind)
                        } catch (_: Throwable) {
                            desc.description // не валим сессию, если что-то пошло не так
                        }
                        val munged = SessionDescription(desc.type, mungedSdp)

                        pcLocal.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                try {
                                    send(JSONObject().put("type", "answer").put("sdp", mungedSdp).toString())
                                } catch (_: Throwable) {}
                            }
                            override fun onSetFailure(p0: String?) {
                                try { send(JSONObject().put("type", "error").put("message", "setLocalDescription").toString()) } catch (_: Throwable) {}
                            }
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onCreateFailure(p0: String?) {}
                        }, munged)
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

    // ====== SDP MUNGE (индивидуально для MIC/PLAYER) ======
    // MIC: 64 kbps, ptime=10, DTX off, CBR, FEC off
    // PLAYER: 128 kbps, ptime=40, DTX off, CBR, FEC off, stereo=1
    private fun mungeOpusAnswerForKind(sdpIn: String, kind: WebRtcCore.PcKind): String {
        val sdp = sdpIn.replace("\r\n", "\n")
        val lines = sdp.split("\n")

        val headers = StringBuilder()
        val sections = mutableListOf<StringBuilder>()
        var cur = StringBuilder()
        var seenMedia = false

        for (ln in lines) {
            if (ln.startsWith("m=")) {
                if (!seenMedia) {
                    headers.append(cur.toString())
                    cur = StringBuilder()
                    seenMedia = true
                } else {
                    sections.add(cur)
                    cur = StringBuilder()
                }
            }
            cur.append(ln).append('\n')
        }
        if (!seenMedia) return sdpIn
        if (cur.isNotEmpty()) sections.add(cur)

        fun processAudioSection(body: String, isPlayer: Boolean): String {
            val ls = body.trim('\n').split("\n").toMutableList()
            var mLineIdx = -1
            var opusPt: String? = null
            val toRemoveIdx = mutableSetOf<Int>()

            for (i in ls.indices) {
                val l = ls[i]
                if (l.startsWith("m=audio ")) mLineIdx = i
                val m = Regex("""^a=rtpmap:(\d+)\s+opus/48000/2""").find(l)
                if (m != null) opusPt = m.groupValues[1]
            }
            if (mLineIdx < 0 || opusPt == null) return body

            // Оставляем в m-line только opus PT
            run {
                val m = ls[mLineIdx]
                val parts = m.split(" ").toMutableList()
                if (parts.size >= 4) {
                    val keep = mutableListOf<String>()
                    keep.add(parts[0]) // m=audio
                    keep.add(parts[1]) // <port>
                    keep.add(parts[2]) // RTP/SAVPF
                    keep.add(parts[3]) // proto
                    keep.add(opusPt!!)
                    ls[mLineIdx] = keep.joinToString(" ")
                }
            }

            // Сносим rtpmap/fmtp/rtcp-fb для всех PT != opus
            val allowed = setOf(opusPt!!)
            for (i in ls.indices) {
                val l = ls[i]
                val mm = Regex("""^a=(rtpmap|fmtp|rtcp-fb):(\d+)""").find(l)
                if (mm != null) {
                    val pt = mm.groupValues[2]
                    if (!allowed.contains(pt)) toRemoveIdx.add(i)
                }
            }
            for (i in toRemoveIdx.sortedDescending()) ls.removeAt(i)

            // fmtp для opus
            val wantFmtp = if (!isPlayer) {
                // MIC
                listOf(
                    "stereo=0",
                    "sprop-stereo=0",
                    "maxaveragebitrate=64000",
                    "usedtx=0",
                    "cbr=1",
                    "useinbandfec=0"
                ).joinToString(";")
            } else {
                // PLAYER
                listOf(
                    "stereo=1",
                    "sprop-stereo=1",
                    "maxaveragebitrate=128000",
                    "usedtx=0",
                    "cbr=1",
                    "useinbandfec=0"
                ).joinToString(";")
            }
            var fmtpSet = false
            for (i in ls.indices) {
                if (ls[i].startsWith("a=fmtp:$opusPt ")) {
                    ls[i] = "a=fmtp:$opusPt $wantFmtp"
                    fmtpSet = true
                    break
                }
            }
            if (!fmtpSet) {
                var inserted = false
                for (i in ls.indices) {
                    if (ls[i].startsWith("a=rtpmap:$opusPt ")) {
                        ls.add(i + 1, "a=fmtp:$opusPt $wantFmtp")
                        inserted = true
                        break
                    }
                }
                if (!inserted) ls.add("a=fmtp:$opusPt $wantFmtp")
            }

            // ptime / maxptime секционные
            val wantPtime = if (!isPlayer) 10 else 40
            val wantMaxPtime = if (!isPlayer) 20 else 60

            fun upsertAttr(name: String, value: Int) {
                var set = false
                for (i in ls.indices) {
                    if (ls[i].startsWith("a=$name:")) {
                        ls[i] = "a=$name:$value"
                        set = true
                        break
                    }
                }
                if (!set) ls.add("a=$name:$value")
            }
            upsertAttr("ptime", wantPtime)
            upsertAttr("maxptime", wantMaxPtime)

            return ls.joinToString("\n", postfix = "\n")
        }

        val out = StringBuilder()
        out.append(headers.toString())
        val isPlayer = (kind == WebRtcCore.PcKind.PLAYER)
        for (sec in sections) {
            val body = sec.toString()
            if (body.startsWith("m=audio ")) {
                out.append(processAudioSection(body, isPlayer))
            } else {
                out.append(body)
            }
        }
        return out.toString().replace("\n", "\r\n")
    }
}
