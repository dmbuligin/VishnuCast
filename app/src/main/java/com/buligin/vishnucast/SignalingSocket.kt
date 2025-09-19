package com.buligin.vishnucast

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Глобальный холдер единственного экземпляра WebRtcCore на процесс.
 */
object WebRtcCoreHolder {
    @Volatile
    private var instance: WebRtcCore? = null

    fun get(ctx: android.content.Context): WebRtcCore {
        val cur = instance
        if (cur != null) return cur
        synchronized(this) {
            val again = instance
            if (again != null) return again
            val core = WebRtcCore(ctx.applicationContext)
            instance = core
            return core
        }
    }

    fun closeAndClear() {
        synchronized(this) {
            try { instance?.close() } catch (_: Throwable) {}
            instance = null
        }
    }
}

class SignalingSocket(
    private val ctx: android.content.Context,
    handshake: NanoHTTPD.IHTTPSession
) : NanoWSD.WebSocket(handshake) {

    private val webrtc: WebRtcCore = WebRtcCoreHolder.get(ctx)
    private var pc: org.webrtc.PeerConnection? = null

    // Дедупликация оффера
    @Volatile private var offerHandled = false

    // Non-trickle: шлём один ANSWER; кандидаты копим тут (полные строки "candidate:...")
    private val gatheredCandidates = CopyOnWriteArrayList<String>()
    private val answerSent = AtomicBoolean(false)
    private var gatherTimer: Timer? = null

    override fun onOpen() {
        Logger.d("VishnuWS", "WS onOpen")
        offerHandled = false
        answerSent.set(false)
        gatheredCandidates.clear()
        cancelTimer()
    }

    override fun onPong(pong: NanoWSD.WebSocketFrame?) {
        Logger.d("VishnuWS", "WS onPong")
    }

    override fun onMessage(message: NanoWSD.WebSocketFrame) {
        try {
            val text = message.textPayload ?: return
            Logger.d("VishnuWS", "RAW in: ${text.take(200)}")

            val obj = try { JSONObject(text) } catch (_: Throwable) { null }
            if (obj == null) {
                Logger.w("VishnuWS", "Non-JSON message ignored")
                return
            }

            val type = obj.optString("type", "")

            // --- SDP OFFER: flat {type:'offer', sdp:'...'} ИЛИ nested {sdp:{type:'offer', sdp:'...'}}
            val looksLikeOffer = when {
                type == "offer" -> true
                obj.has("sdp") && obj.opt("sdp") is JSONObject && obj.optJSONObject("sdp")!!
                    .optString("type", "") == "offer" -> true
                else -> false
            }
            if (looksLikeOffer) {
                if (offerHandled) {
                    Logger.w("VishnuWS", "Duplicate OFFER ignored")
                    try { send(JSONObject().put("type", "ack").put("stage", "offer-ignored").toString()) } catch (_: Throwable) {}
                    return
                }
                val sdpStr = if (obj.has("sdp") && obj.opt("sdp") is JSONObject)
                    obj.optJSONObject("sdp")!!.optString("sdp", "")
                else
                    obj.optString("sdp", "")

                if (sdpStr.isNotBlank()) {
                    offerHandled = true
                    answerSent.set(false)
                    gatheredCandidates.clear()
                    try { send(JSONObject().put("type", "ack").put("stage", "offer-received").toString()) } catch (_: Throwable) {}
                    Logger.d("VishnuWS", "handleOffer(): len=${sdpStr.length}")
                    handleOfferNonTrickleWithEmbed(sdpStr)
                    return
                }
            }

            // --- ICE от клиента (все формы) — принимаем, чтобы у браузера были его кандидаты
            if (obj.has("candidates")) {
                val arr = obj.optJSONArray("candidates") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val it = arr.optJSONObject(i) ?: continue
                    addRemoteCandidateObject(it)
                }
                return
            }
            if (obj.has("candidate") || type == "ice") {
                val candObj = when {
                    obj.opt("candidate") is JSONObject -> obj.optJSONObject("candidate")
                    obj.opt("candidate") is String -> JSONObject()
                        .put("candidate", obj.optString("candidate"))
                        .put("sdpMid", obj.optString("sdpMid", "audio"))
                        .put("sdpMLineIndex", obj.optInt("sdpMLineIndex", 0))
                    obj.has("ice") && obj.opt("ice") is JSONObject -> obj.optJSONObject("ice")
                    else -> null
                }
                if (candObj != null) addRemoteCandidateObject(candObj)
                return
            }

            Logger.w("VishnuWS", "Unknown message shape: $obj")
        } catch (e: Exception) {
            Logger.e("VishnuWS", "WS onMessage error", e)
            try { send(JSONObject().put("type", "error").put("message", e.message ?: "unknown").toString()) } catch (_: Throwable) {}
        }
    }

    private fun addRemoteCandidateObject(candObj: JSONObject) {
        try {
            val sdp = candObj.optString("candidate", candObj.optString("sdp", ""))
            val mid = if (candObj.has("sdpMid")) candObj.optString("sdpMid", null) else null
            val mli = candObj.optInt("sdpMLineIndex", 0)
            if (sdp.isBlank()) {
                Logger.w("VishnuWS", "ICE ignored: empty candidate")
                return
            }
            val c = IceCandidate(mid, mli, sdp) // строка "candidate:..." остаётся как есть
            Logger.d("VishnuWS", "ICE from client: ${c.sdp.take(80)} mid=$mid mline=$mli")
            pc?.addIceCandidate(c)
        } catch (e: Throwable) {
            Logger.e("VishnuWS", "addRemoteCandidateObject error", e)
        }
    }

    override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
        Logger.w("VishnuWS", "WS onClose: remote=$initiatedByRemote, reason=$reason, code=$code")
        cancelTimer()
        try { pc?.close() } catch (_: Throwable) {}
        pc = null
        offerHandled = false
        answerSent.set(false)
        gatheredCandidates.clear()
    }

    override fun onException(exception: java.io.IOException?) {
        Logger.e("VishnuWS", "WS onException", exception)
    }

    /**
     * NON-TRICKLE ICE с «вшивкой» кандидатов в SDP:
     * - Создаём PC, копим кандидаты в список.
     * - Принимаем OFFER, делаем ANSWER + setLocalDescription.
     * - Ждём до ICE-GATHERING COMPLETE (или таймаут).
     * - Вшиваем собранные "a=candidate" в SDP и отправляем один ANSWER.
     */
    private fun handleOfferNonTrickleWithEmbed(sdp: String) {
        Logger.d("VishnuWS", "handleOfferNonTrickleWithEmbed: OFFER len=${sdp.length}")

        pc = webrtc.createPeerConnection { cand ->
            try {
                val full = if (cand.sdp.startsWith("candidate:")) cand.sdp else "candidate:${cand.sdp}"
                gatheredCandidates.add(full)
                Logger.d("VishnuWS", "ICE local gathered: ${full.take(80)}")
            } catch (_: Throwable) {}
        }

        if (pc == null) {
            val msg = "PeerConnection == null"
            Logger.e("VishnuWS", msg)
            try { send(JSONObject().put("type", "error").put("message", msg).toString()) } catch (_: Throwable) {}
            return
        }

        val remote = SessionDescription(SessionDescription.Type.OFFER, sdp)
        pc!!.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                Logger.d("VishnuWS", "setRemoteDescription OK → createAnswer")
                pc!!.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(desc: SessionDescription) {
                        Logger.d("VishnuWS", "createAnswer OK, len=${desc.description.length}")
                        pc!!.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                Logger.d("VishnuWS", "setLocalDescription OK → wait for ICE COMPLETE")
                                waitAndSendSingleAnswerWithEmbed()
                            }
                            override fun onSetFailure(p0: String?) {
                                Logger.e("VishnuWS", "setLocalDescription FAIL: $p0")
                                try { send(JSONObject().put("type", "error").put("message", "setLocalDescription: $p0").toString()) } catch (_: Throwable) {}
                            }
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onCreateFailure(p0: String?) {}
                        }, desc)
                    }
                    override fun onCreateFailure(p0: String?) {
                        Logger.e("VishnuWS", "createAnswer FAIL: $p0")
                        try { send(JSONObject().put("type", "error").put("message", "createAnswer: $p0").toString()) } catch (_: Throwable) {}
                    }
                    override fun onSetSuccess() {}
                    override fun onSetFailure(p0: String?) {}
                }, org.webrtc.MediaConstraints())
            }

            override fun onSetFailure(p0: String?) {
                Logger.e("VishnuWS", "setRemoteDescription FAIL: $p0")
                try { send(JSONObject().put("type", "error").put("message", "setRemoteDescription: $p0").toString()) } catch (_: Throwable) {}
            }

            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, remote)
    }

    /**
     * Ждём до COMPLETE, но не дольше maxWaitMs; затем шлём один ANSWER с прошитыми a=candidate.
     */
    private fun waitAndSendSingleAnswerWithEmbed() {
        cancelTimer()
        val maxWaitMs = 2500L
        val interval = 100L
        val start = System.currentTimeMillis()

        gatherTimer = Timer("vc-icewait", true).apply {
            schedule(object : TimerTask() {
                override fun run() {
                    val cur = pc ?: return
                    val done = (cur.iceGatheringState() == org.webrtc.PeerConnection.IceGatheringState.COMPLETE)
                    val timeup = (System.currentTimeMillis() - start) >= maxWaitMs
                    if (done || timeup) {
                        cancelTimer()
                        sendAnswerOnceWithEmbed()
                    }
                }
            }, 0L, interval)
        }
    }

    private fun sendAnswerOnceWithEmbed() {
        if (!answerSent.compareAndSet(false, true)) return
        try {
            val desc = pc?.localDescription
            var sdpOut = desc?.description ?: ""
            // Вшиваем кандидаты в медиасекцию (audio m= — одна секция с mid:0)
            val sb = StringBuilder(sdpOut)
            for (line in gatheredCandidates) {
                sb.append("a=").append(line).append("\r\n")
            }
            sb.append("a=end-of-candidates\r\n")
            sdpOut = sb.toString()

            Logger.d("VishnuWS", "SEND ANSWER (non-trickle + embed), len=${sdpOut.length}, candCount=${gatheredCandidates.size}")
            send(JSONObject().put("type", "answer").put("sdp", sdpOut).toString())
        } catch (e: Throwable) {
            Logger.e("VishnuWS", "send ANSWER error", e)
        }
    }

    private fun cancelTimer() {
        try { gatherTimer?.cancel() } catch (_: Throwable) {}
        gatherTimer = null
    }
}
