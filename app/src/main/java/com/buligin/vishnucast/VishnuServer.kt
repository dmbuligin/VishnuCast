package com.buligin.vishnucast

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import fi.iki.elonen.NanoHTTPD.Response.Status

class VishnuServer(
    private val appContext: Context,
    port: Int
) : NanoWSD(port) {

    override fun openWebSocket(handshake: NanoHTTPD.IHTTPSession): WebSocket {
        // handshake ненулевой — NanoWSD сам валидирует
        Logger.d("VishnuWS", "openWebSocket on ${handshake.uri}")
        android.util.Log.d("VishnuMix", "VishnuServer.openWebSocket: new socket uri=${handshake.uri}")
        return SignalingSocket(appContext, handshake)
    }

    override fun serve(session: NanoHTTPD.IHTTPSession): Response {

        // Разруливаем WebSocket-апгрейд на /ws (MIC) и /ws_player (PLAYER)
        @Suppress("DEPRECATION")
        if ((session.uri == "/ws" || session.uri == "/ws_player") && isWebsocketRequested(session)) {
            Logger.d("VishnuWS", "Upgrading to WebSocket on ${session.uri}")
            return super.serve(session)
        }

        // Статические файлы
        return when (session.uri) {
            "/", "/index.html" -> asset("index.html", "text/html; charset=utf-8")
            "/client.js"       -> asset("client.js", "application/javascript; charset=utf-8")
            "/apple-touch-icon.png" -> asset("apple-touch-icon.png", "image/png")
            "/favicon.png" -> asset("favicon.png", "image/png")
            else -> newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Not found")
        }
    }

    private fun asset(name: String, mime: String): Response {
        val am = appContext.assets
        val bytes = am.open(name).use { it.readBytes() }
        return newFixedLengthResponse(Status.OK, mime, bytes.inputStream(), bytes.size.toLong())
    }

    // Удобные обёртки, чтобы управлять таймаутом
    fun launch(socketReadTimeoutMs: Int, daemon: Boolean) {
        Logger.i("VishnuWS", "Starting VishnuServer on :$listeningPort timeout=${socketReadTimeoutMs}ms")
        start(socketReadTimeoutMs, daemon)
        ClientCounterStable.reset()
    }

    fun shutdown() {
        Logger.i("VishnuWS", "Stopping VishnuServer")
        ClientCounterStable.reset()
        stop()
    }
}
