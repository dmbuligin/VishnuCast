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

    private val webrtc = WebRtcCoreHolder.get(ctx)
    private var pc: org.webrtc.PeerConnection? = null

    override fun onOpen() {}

    override fun onPong(pong: NanoWSD.WebSocketFrame?) {}

    override fun onMessage(message: NanoWSD.WebSocketFrame) {
        try {
            val text = message.textPayload ?: return
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
                    pc?.addIceCandidate(c)
                }

                "offer" -> {
                    try { send(JSONObject().put("type", "ack").put("stage", "offer-received").toString()) } catch (_: Throwable) {}
                    handleOffer(obj.getString("sdp"))
                }

                else -> {}
            }
        } catch (_: Exception) {
            try { send(JSONObject().put("type", "error").put("message", "bad message").toString()) } catch (_: Throwable) {}
        }
    }

    override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
        try { pc?.close() } catch (_: Throwable) {}
        pc = null
    }

    override fun onException(exception: java.io.IOException?) {}

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
                                try { send(JSONObject().put("type", "error").put("message", "setLocalDescription").toString()) } catch (_: Throwable) {}
                            }
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onCreateFailure(p0: String?) {}
                        }, desc)
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
}
