package com.buligin.vishnucast.audio

import androidx.annotation.Keep

@Keep
object NativeBridge {

    /**
     * Вытянуть из плеера ровно n сэмплов @48k mono (обычно n=480) за timeoutMs.
     * Возвращает ShortArray(n) или null по таймауту.
     * Никакой микшер тут не участвует.
     */
    @JvmStatic
    fun playerTake48kMono(n: Int, timeoutMs: Int): ShortArray? {
        val floatBuf = PlayerPcmBus.take48kMono(n, timeoutMs.toLong()) ?: return null
        val out = ShortArray(n)
        var i = 0
        while (i < n) {
            val v = (floatBuf[i] * 32767f).toInt()
            out[i] = v.coerceIn(-32768, 32767).toShort()
            i++
        }
        return out
    }
}
