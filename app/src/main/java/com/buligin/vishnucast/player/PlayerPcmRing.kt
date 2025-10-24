package com.buligin.vishnucast.player

import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/** Простенький lock-free ринг для PCM 48k mono (short). */
object PlayerPcmRing {
    private const val CAP_SAMPLES = 48000 * 2 // ~2 сек
    private val buf = ShortArray(CAP_SAMPLES)
    private val wr = AtomicInteger(0)
    private val rd = AtomicInteger(0)

    /** Пишем фрейм (mono 48k). Излишки перетрутся по кругу. */
    fun push(frame: ShortArray, samples: Int) {
        val n = min(samples, frame.size)
        var p = wr.get()
        var i = 0
        while (i < n) {
            buf[p] = frame[i]
            p++; if (p >= CAP_SAMPLES) p = 0
            i++
        }
        wr.set(p)
        // если слишком далеко убежали — подтягиваем rd ближе к хвосту
        val lag = distance(rd.get(), wr.get())
        if (lag > CAP_SAMPLES - 480) {
            rd.set((wr.get() + CAP_SAMPLES - 480) % CAP_SAMPLES)
        }
    }

    /** Читаем samples в dest. Если не хватает — дополним нулями. Возвращает запрошенное число сэмплов. */
    fun popInto(dest: ShortArray, samples: Int): Int {
        val need = min(samples, dest.size)
        val avail = distance(rd.get(), wr.get())
        val take = min(need, avail)
        var r = rd.get()
        var i = 0
        while (i < take) {
            dest[i] = buf[r]
            r++; if (r >= CAP_SAMPLES) r = 0
            i++
        }
        rd.set(r)
        // недостачу заполняем нулями
        while (i < need) { dest[i] = 0; i++ }
        return need
    }

    private fun distance(r: Int, w: Int): Int =
        if (w >= r) w - r else (w + CAP_SAMPLES - r)
}
