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
        return SignalingSocket(appContext, handshake)
    }

    override fun serve(session: NanoHTTPD.IHTTPSession): Response {

        // Разруливаем WebSocket-апгрейд только на /ws
        @Suppress("DEPRECATION")
        if (session.uri == "/ws" && isWebsocketRequested(session)) {
            Logger.d("VishnuWS", "Upgrading to WebSocket on /ws")
            return super.serve(session)
        }

        // Статические файлы
        return when (session.uri) {
            "/", "/index.html" -> asset("index.html", "text/html; charset=utf-8")
            "/client.js"       -> asset("client.js", "application/javascript; charset=utf-8")
            "/apple-touch-icon.png" -> asset("apple-touch-icon.png", "image/png")
            "/favicon.png" -> asset("favicon.png", "image/png")

            // Частые пути проверки подключений (Captive Portal). Отдаём страницу сразу,
            // чтобы ОС открыла встроенный браузер/"требуется действие сети".
            "/generate_204", "/gen_204", "/hotspot-detect.html", "/connecttest.txt", "/ncsi.txt" ->
                asset("index.html", "text/html; charset=utf-8")

            // Любые другие пути — мягкий редирект на корень, чтобы клиент всегда видел портал
            else -> newFixedLengthResponse(Status.REDIRECT, "text/plain", "").apply {
                addHeader("Location", "/")
            }
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
