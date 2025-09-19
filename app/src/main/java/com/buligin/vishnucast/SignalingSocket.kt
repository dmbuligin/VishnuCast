package com.buligin.vishnucast

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import org.webrtc.SdpObserver

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

    // ✨ общий core на все сессии
    private val webrtc = WebRtcCoreHolder.get(ctx)
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
            val obj = JSONObject(text)
            when (obj.optString("type", "")) {
                "ka" -> return

                "ice" -> {
                    val cObj = obj.getJSONObject("candidate")
                    val c = IceCandidate(
                        cObj.optString("sdpMid", null),
                        cObj.optInt("sdpMLineIndex", 0),
                        cObj.optString("candidate", "")
                    )
                    Logger.d("VishnuWS", "ICE from client: ${c.sdp.take(64)}")
                    pc?.addIceCandidate(c)
                }

                "offer" -> {
                    try { send(JSONObject().put("type", "ack").put("stage", "offer-received").toString()) } catch (_: Throwable) {}
                    handleOffer(obj.getString("sdp"))
                }

                else -> Logger.w("VishnuWS", "Unknown message type: ${obj.optString("type", "")}")
            }
        } catch (e: Exception) {
            Logger.e("VishnuWS", "WS onMessage error", e)
            try { send(JSONObject().put("type", "error").put("message", e.message ?: "unknown").toString()) } catch (_: Throwable) {}
        }
    }

    override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
        Logger.w("VishnuWS", "WS onClose: remote=$initiatedByRemote, reason=$reason, code=$code")
        // Закрываем только peer — общий core остаётся жить для других клиентов
        try { pc?.close() } catch (_: Throwable) {}
        pc = null
    }

    override fun onException(exception: java.io.IOException?) {
        Logger.e("VishnuWS", "WS onException", exception)
    }

    private fun handleOffer(sdp: String) {
        Logger.d("VishnuWS", "handleOffer: OFFER len=${sdp.length}")

        pc = webrtc.createPeerConnection { cand ->
            val payload = JSONObject()
                .put("sdpMid", cand.sdpMid)
                .put("sdpMLineIndex", cand.sdpMLineIndex)
                .put("candidate", cand.sdp)
            val ice = JSONObject().put("type", "ice").put("candidate", payload)
            try { send(ice.toString()) } catch (_: Throwable) {}
        }

        val pcLocal = pc ?: run {
            val msg = "PeerConnection == null"
            Logger.e("VishnuWS", msg)
            try { send(JSONObject().put("type", "error").put("message", msg).toString()) } catch (_: Throwable) {}
            return
        }

        val remote = SessionDescription(SessionDescription.Type.OFFER, sdp)
        pcLocal.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                pcLocal.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(desc: SessionDescription) {
                        pcLocal.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                try {
                                    send(JSONObject().put("type", "answer").put("sdp", desc.description).toString())
                                } catch (_: Throwable) {}
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
