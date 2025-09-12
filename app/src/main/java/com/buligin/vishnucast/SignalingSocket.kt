package com.buligin.vishnucast

//import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
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
            try {
                instance?.close()
            } catch (_: Throwable) {
            }
            instance = null
        }
    }
}

class SignalingSocket(
    private val ctx: android.content.Context,
    handshake: NanoHTTPD.IHTTPSession
) : NanoWSD.WebSocket(handshake) {

    // БЕРЁМ ОБЩИЙ CORE (исправление: не создаём новый на каждый сокет)
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
            val text = message.textPayload
            Logger.d("VishnuWS", "RAW in: ${text.take(120)}")
            val obj = JSONObject(text)
            val type = obj.optString("type", "")
            Logger.d("VishnuWS", "MSG type=$type")

            when (type) {
                "ka" -> return

                "ice" -> {
                    // ГИБКИЙ ПАРСЕР: поддерживаем все распространенные формы
                    // 1) {type:"ice", candidate:{ candidate:"...", sdpMid, sdpMLineIndex }}
                    // 2) {type:"ice", ice:{ candidate:"...", sdpMid, sdpMLineIndex }}
                    // 3) {type:"ice", sdp:"...", sdpMid, sdpMLineIndex} (плоский формат)
                    val candObj = when {
                        obj.has("candidate") && obj.opt("candidate") is JSONObject -> obj.optJSONObject("candidate")
                        obj.has("ice") && obj.opt("ice") is JSONObject -> obj.optJSONObject("ice")
                        else -> null
                    }

                    val sdp: String?
                    val mid: String?
                    val mline: Int

                    if (candObj != null) {
                        sdp = candObj.optString("candidate", null) ?: candObj.optString("sdp", null)
                        mid = if (candObj.has("sdpMid")) candObj.optString("sdpMid", null) else null
                        mline = candObj.optInt("sdpMLineIndex", 0)
                    } else {
                        // плоский формат
                        sdp = obj.optString("candidate", null) ?: obj.optString("sdp", null)
                        mid = if (obj.has("sdpMid")) obj.optString("sdpMid", null) else null
                        mline = obj.optInt("sdpMLineIndex", 0)
                    }

                    if (sdp.isNullOrBlank()) {
                        Logger.w("VishnuWS", "ICE ignored: no candidate string in message")
                        return
                    }

                    // В некоторых браузерах sdpMid может быть null — Android-стек это терпит.
                    val c = IceCandidate(mid, mline, sdp)
                    Logger.d("VishnuWS", "ICE from client: ${c.sdp.take(60)} mid=$mid mline=$mline")
                    pc?.addIceCandidate(c)
                }

                "offer" -> {
                    // Для наглядности подтверждаем получение
                    send(JSONObject().put("type", "ack").put("stage", "offer-received").toString())
                    Logger.d("VishnuWS", "Calling handleOffer()...")
                    val sdp = obj.getString("sdp")
                    handleOffer(sdp)
                }

                else -> {
                    Logger.w("VishnuWS", "Unknown message type: $type")
                }
            }
        } catch (e: Exception) {
            Logger.e("VishnuWS", "WS onMessage error", e)
            try {
                send(JSONObject().put("type", "error").put("message", e.message ?: "unknown").toString())
            } catch (_: Throwable) {
            }
        }
    }

    override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
        Logger.w("VishnuWS", "WS onClose: remote=$initiatedByRemote, reason=$reason, code=$code")
        try {
            pc?.close()
        } catch (_: Throwable) {
        }
        pc = null
        // ВАЖНО: общий core НЕ закрываем здесь, иначе уронем всех клиентов.
        // Закрывать WebRtcCoreHolder.closeAndClear() — только при остановке сервиса.
    }

    override fun onException(exception: java.io.IOException?) {
        Logger.e("VishnuWS", "WS onException", exception)
    }

    private fun handleOffer(sdp: String) {
        Logger.d("VishnuWS", "handleOffer: OFFER len=${sdp.length}")

        pc = webrtc.createPeerConnection { cand ->
            Logger.d("VishnuWS", "SEND ICE to client: ${cand.sdp.take(60)}")
            val payload = JSONObject()
                .put("sdpMid", cand.sdpMid)
                .put("sdpMLineIndex", cand.sdpMLineIndex)
                .put("candidate", cand.sdp)
            val ice = JSONObject().put("type", "ice").put("candidate", payload)
            send(ice.toString())
        }

        if (pc == null) {
            val msg = "PeerConnection == null"
            Logger.e("VishnuWS", msg)
            send(JSONObject().put("type", "error").put("message", msg).toString())
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
                                send(JSONObject().put("type", "answer").put("sdp", desc.description).toString())
                            }

                            override fun onSetFailure(p0: String?) {
                                Logger.e("VishnuWS", "setLocalDescription FAIL: $p0")
                                send(
                                    JSONObject().put("type", "error").put("message", "setLocalDescription: $p0")
                                        .toString()
                                )
                            }

                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onCreateFailure(p0: String?) {}
                        }, desc)
                    }

                    override fun onCreateFailure(p0: String?) {
                        Logger.e("VishnuWS", "createAnswer FAIL: $p0")
                        send(JSONObject().put("type", "error").put("message", "createAnswer: $p0").toString())
                    }

                    override fun onSetSuccess() {}
                    override fun onSetFailure(p0: String?) {}
                }, org.webrtc.MediaConstraints())
            }

            override fun onSetFailure(p0: String?) {
                Logger.e("VishnuWS", "setRemoteDescription FAIL: $p0")
                send(JSONObject().put("type", "error").put("message", "setRemoteDescription: $p0").toString())
            }

            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, remote)
    }
}
