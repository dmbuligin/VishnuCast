package com.buligin.vishnucast.audio

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Кольцевой буфер плеера: всегда MONO 48 kHz, float [-1..1].
 * На вход: interleaved float[] (ch=1..N) при любом sr.
 * Внутри: downmix→mono, ИНКРЕМЕНТАЛЬНЫЙ resample→48k (с непрерывной фазой), запись в ring.
 */
object PlayerPcmBus {

    // ===== Ринг-буфер 48 kHz mono =====
    private const val RING_CAP = 48_000  // 1 сек буфер

    private val ring = FloatArray(RING_CAP)
    private var writeIdx = 0
    private var filled = 0
    private var lastWriteAtMs: Long = 0L

    // Курсор последовательного чтения
    private var readIdx = -1
    private var seqActive = false

    // ===== Состояние инкрементального ресемплера  → 48 kHz =====
    // Последний моно-образец предыдущего чанка
    private var rsPrev: Float = 0f
    // Фаза внутри текущего отрезка [prev → curr]; 0..1
    private var rsPhase: Double = 0.0
    // Текущая частота источника; при смене — реинициализация
    private var rsSrcRate: Int = 0
    // При первом запуске — используем prev == curr, чтобы не было скачка
    private var rsInitialized: Boolean = false

    // Общий замок на запись/чтение/состояние
    private val lock = Any()

    /** Полный сброс буферов, курсоров и состояния ресемплера. */
    fun clear() {
        synchronized(lock) {
            writeIdx = 0
            filled = 0
            lastWriteAtMs = 0L
            readIdx = -1
            seqActive = false
            rsPrev = 0f
            rsPhase = 0.0
            rsSrcRate = 0
            rsInitialized = false
        }
    }

    /** Сбросить только курсор последовательного чтения (при паузе/переключении трека). */
    fun resetSequential() {
        synchronized(lock) {
            readIdx = -1
            seqActive = false
        }
    }

    /**
     * Принять interleaved float[], downmix→mono, ИНКРЕМЕНТАЛЬНО resample→48k и записать в ринг.
     * Важно: stateful-ресемплер сохраняет фазу между вызовами push(...) — треск исчезает.
     */
    fun push(src: FloatArray, ch: Int, sr: Int) {
        if (src.isEmpty() || ch <= 0 || sr <= 0) return

        // 1) Downmix → mono
        val frames = src.size / ch
        if (frames <= 0) return
        val mono = FloatArray(frames)
        var si = 0
        var f = 0
        if (ch == 1) {
            while (f < frames) {
                mono[f] = src[si]
                si += 1
                f++
            }
        } else {
            while (f < frames) {
                var acc = 0f
                var c = 0
                while (c < ch) { acc += src[si + c]; c++ }
                mono[f] = acc / ch
                si += ch
                f++
            }
        }

        // 2) Инкрементальный ресемпл → 48k (с непрерывной фазой)
        val out48 = if (sr == 48_000) {
            // На 48k тоже поддержим последовательность: prev = последний сэмпл
            synchronized(lock) {
                rsSrcRate = 48_000
                rsInitialized = true
                rsPrev = mono.last()
            }
            mono
        } else {
            resampleTo48kIncremental(mono, sr)
        }

        // 3) Запись в кольцевой буфер (атомарно)
        synchronized(lock) {
            writeContinuous(out48)
            lastWriteAtMs = System.currentTimeMillis()
        }
    }

    /**
     * Выдать СЛЕДУЮЩИЕ n сэмплов @48k mono из кольца (не «хвост», а непрерывный поток).
     * Если данных пока мало — вернём null (можно подложить тишину и подождать).
     */
    fun take48kMono(n: Int, maxAgeMs: Long = 300L): FloatArray? {
        if (n <= 0) return null
        synchronized(lock) {
            val age = System.currentTimeMillis() - lastWriteAtMs
            if (age > maxAgeMs || filled == 0) return null

            // Инициализируем курсор так, чтобы первый блок заканчивался в текущем writeIdx
            if (readIdx < 0 || !seqActive) {
                val end = writeIdx
                var start = end - n
                if (start < 0) start += RING_CAP
                readIdx = start
                seqActive = true
            }

            // Проверка доступности
            if (filled < n) return null

            val out = FloatArray(n)
            val start = readIdx
            if (start + n <= RING_CAP) {
                System.arraycopy(ring, start, out, 0, n)
            } else {
                val first = RING_CAP - start
                System.arraycopy(ring, start, out, 0, first)
                System.arraycopy(ring, 0, out, first, n - first)
            }
            readIdx = (readIdx + n) % RING_CAP
            return out
        }
    }

    /** Для быстрых срезов (индикаторы), НЕ непрерывное чтение. */
    fun tail48kMono(tailSamples: Int, maxAgeMs: Long = 300L): FloatArray? {
        if (tailSamples <= 0) return null
        synchronized(lock) {
            val age = System.currentTimeMillis() - lastWriteAtMs
            if (age > maxAgeMs || filled == 0) return null

            val n = min(tailSamples, min(filled, RING_CAP))
            val out = FloatArray(n)

            val end = writeIdx
            var start = end - n
            if (start < 0) start += RING_CAP

            if (start + n <= RING_CAP) {
                System.arraycopy(ring, start, out, 0, n)
            } else {
                val first = RING_CAP - start
                System.arraycopy(ring, start, out, 0, first)
                System.arraycopy(ring, 0, out, first, n - first)
            }
            return out
        }
    }

    // ===== helpers =====

    /** Непрерывная запись в ринг. */
    private fun writeContinuous(mono48: FloatArray) {
        var srcIdx = 0
        val n = mono48.size
        if (n == 0) return
        while (srcIdx < n) {
            val toEnd = RING_CAP - writeIdx
            val run = min(toEnd, n - srcIdx)
            System.arraycopy(mono48, srcIdx, ring, writeIdx, run)
            writeIdx = (writeIdx + run) % RING_CAP
            srcIdx += run
            filled = min(RING_CAP, filled + run)
        }
    }

    /**
     * Инкрементальный ресемпл в 48 kHz (линейная интерполяция) с сохранением фазы между чанками.
     * Состояние rsPrev/rsPhase/rsSrcRate/rsInitialized находится под lock.
     */
    private fun resampleTo48kIncremental(srcMono: FloatArray, srcRate: Int): FloatArray {
        synchronized(lock) {
            // Если сменилась ЧД — переинициализируем
            if (rsSrcRate != srcRate) {
                rsSrcRate = srcRate
                rsPhase = 0.0
                rsInitialized = false
            }

            // Соотношение (на сколько исходного "времени" продвигаемся за 1 выходной сэмпл)
            val inc = srcRate.toDouble() / 48_000.0

            val out = ArrayList<Float>( (srcMono.size * 48_000L / srcRate).toInt() + 8 )

            var prev = rsPrev
            var phase = rsPhase
            var initialized = rsInitialized

            var i = 0
            // Если не инициализированы — стартуем без скачка: prev = первый сэмпл
            if (!initialized) {
                prev = if (srcMono.isNotEmpty()) srcMono[0] else 0f
                phase = 0.0
                initialized = true
            }

            while (i < srcMono.size) {
                val curr = srcMono[i]
                // Пока внутри отрезка [prev → curr] помещаются выходные сэмплы — генерим их
                while (phase < 1.0) {
                    val s = (prev * (1.0 - phase) + curr * phase).toFloat()
                    out.add(s)
                    phase += inc
                }
                // Переходим к следующему исходному отрезку, сохраняя "лишнюю" долю фазы
                phase -= 1.0
                prev = curr
                i++
            }

            // Обновляем состояние для следующего чанка
            rsPrev = prev
            rsPhase = phase
            rsInitialized = initialized

            // В массив
            return out.toFloatArray()
        }
    }
}
