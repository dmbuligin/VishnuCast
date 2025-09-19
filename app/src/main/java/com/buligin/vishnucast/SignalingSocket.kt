package com.buligin.vishnucast

//import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

/**
 * Глобальный холдер единственного экземпляра WebRtcCore на процесс.
 * Все WebSocket-сессии берут общий core, чтобы корректно считать клиентов.
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

    // Общий core
    private val webrtc: WebRtcCore = WebRtcCoreHolder.get(ctx)
    private var pc: org.webrtc.PeerConnection? = null

    override fun onOpen() {
        Logger.d("VishnuWS", "WS onOpen")
    }

    override fun onPong(pong: NanoWSD.WebSocketFrame?) {
        Logger.d("VishnuWS", "WS onPong")
    }

    override fun onMessage(message: NanoWSD.WebSocketFrame) {
        try {
            val text = message.textPayload ?: return
            Logger.d("VishnuWS", "RAW in: ${text.take(200)}")

            // Пытаемся распарсить JSON
            val obj = try { JSONObject(text) } catch (_: Throwable) { null }

            if (obj == null) {
                Logger.w("VishnuWS", "Non-JSON message ignored")
                return
            }

            val type = obj.optString("type", "")

            // 1) SDP: flat {type:'offer', sdp:'...'} или nested {sdp:{type:'offer', sdp:'...'}}
            if (type == "offer" || (obj.has("sdp") && obj.opt("sdp") is JSONObject && (obj.optJSONObject("sdp")!!.optString("type") == "offer"))) {
                val sdpStr = when {
                    obj.has("sdp") && obj.opt("sdp") is JSONObject -> obj.optJSONObject("sdp")!!.optString("sdp", "")
                    else -> obj.optString("sdp", "")
                }
                if (sdpStr.isNotBlank()) {
                    // Подтверждаем получение оффера (как и раньше)
                    try { send(JSONObject().put("type", "ack").put("stage", "offer-received").toString()) } catch (_: Throwable) {}
                    Logger.d("VishnuWS", "handleOffer(): len=${sdpStr.length}")
                    handleOffer(sdpStr)
                    return
                }
            }

            // 2) ICE от клиента — поддерживаем ВСЕ формы:
            //    a) flat: {candidate:"candidate:...", sdpMid:"audio", sdpMLineIndex:0}
            //    b) typed: {type:"ice", candidate:{candidate:"candidate:...", sdpMid:"audio", sdpMLineIndex:0}}
            //    c) nested array: {candidates:[{...},{...}]}
            if (obj.has("candidates")) {
                val arr = obj.optJSONArray("candidates") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val it = arr.optJSONObject(i) ?: continue
                    addRemoteCandidateObject(it)
                }
                return
            }

            if (obj.has("candidate") || type == "ice") {
                // может быть строка или объект
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
            val c = IceCandidate(mid, mli, sdp)
            Logger.d("VishnuWS", "ICE from client: ${c.sdp.take(80)} mid=$mid mline=$mli")
            pc?.addIceCandidate(c)
        } catch (e: Throwable) {
            Logger.e("VishnuWS", "addRemoteCandidateObject error", e)
        }
    }

    override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
        Logger.w("VishnuWS", "WS onClose: remote=$initiatedByRemote, reason=$reason, code=$code")
        try { pc?.close() } catch (_: Throwable) {}
        pc = null
        // Общий core НЕ закрываем.
    }

    override fun onException(exception: java.io.IOException?) {
        Logger.e("VishnuWS", "WS onException", exception)
    }

    private fun handleOffer(sdp: String) {
        Logger.d("VishnuWS", "handleOffer: OFFER len=${sdp.length}")

        pc = webrtc.createPeerConnection { cand ->
            try {
                // Строка кандидата ОБЯЗАТЕЛЬНО с префиксом "candidate:"
                val line = if (cand.sdp.startsWith("candidate:")) cand.sdp else "candidate:${cand.sdp}"

                // 1) flat-формат (широкая совместимость)
                val flat = """{"candidate":"$line","sdpMid":"${cand.sdpMid}","sdpMLineIndex":${cand.sdpMLineIndex}}"""
                send(flat)

                // 2) nested-формат (совместим с существующим клиентским парсером)
                val nested = """{"candidates":[{"candidate":"$line","sdpMid":"${cand.sdpMid}","sdpMLineIndex":${cand.sdpMLineIndex}}]}"""
                send(nested)

                Logger.d("VishnuWS", "ICE → client: ${line.take(80)}")
            } catch (e: Throwable) {
                Logger.e("VishnuWS", "send ICE error", e)
            }
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
                                Logger.d("VishnuWS", "setLocalDescription OK → send ANSWER")
                                try {
                                    send(JSONObject().put("type", "answer").put("sdp", desc.description).toString())
                                } catch (e: Throwable) {
                                    Logger.e("VishnuWS", "send ANSWER error", e)
                                }
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
}
