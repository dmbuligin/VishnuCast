package com.buligin.vishnucast.audio
import kotlin.math.abs
import kotlin.math.tanh

/**
 * Микшер двух потоков float PCM (interleaved), одинаковый SR/Ch.
 * out = mic*(1-α) + player*α; мягкий лимитер, -6 dB при α=0.5
 */
object AudioMixer {
    fun mixInto(
        mic: FloatArray?,    // может быть null (нет микрофона на B1)
        player: FloatArray?, // может быть null
        alpha01: Float,      // 0..1
        out: FloatArray
    ) {
        val a = alpha01.coerceIn(0f, 1f)
        val b = 1f - a
        val n = out.size
        val pm = player
        val mc = mic
        if (pm == null && mc == null) {
            java.util.Arrays.fill(out, 0f); return
        }
        for (i in 0 until n) {
            val x = (if (mc != null && i < mc.size) mc[i] else 0f) * b +
                (if (pm != null && i < pm.size) pm[i] else 0f) * a
            // -6 dB gain staging + мягкий лимитер
            val y = tanh(x * 0.5f) // 0.5 ≈ -6dB
            out[i] = y
        }
    }
}
