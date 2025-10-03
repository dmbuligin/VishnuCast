package com.buligin.vishnucast.audio

import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Очередь 10-мс кадров @48k mono (ровно 480 PCM16 сэмплов на кадр).
 * Потокобезопасно: push из микшера, take из (будущего) нативного ADM.
 */
object MixBus48k {

    const val FRAME_SAMPLES = 480              // 10 ms @ 48 kHz
    private const val MAX_FRAMES = 64          // ≈ 640 ms запаса

    private val lock = ReentrantLock()
    private val cond = lock.newCondition()
    private val q: ArrayDeque<ShortArray> = ArrayDeque()

    @Volatile var framesPushed: Long = 0; private set
    @Volatile var framesPopped: Long = 0; private set
    @Volatile var framesDropped: Long = 0; private set
    @Volatile var waitsTimedOut: Long = 0; private set

    /** Кладём кадр (ровно 480 s16). При переполнении — выкидываем самый старый. */
    fun push10ms(frame: ShortArray) {
        if (frame.size != FRAME_SAMPLES) {
            android.util.Log.w("MixBus48k", "push size=${frame.size}, expected=$FRAME_SAMPLES — ignore")
            return
        }
        lock.withLock {
            if (q.size >= MAX_FRAMES) {
                q.removeFirst()
                framesDropped++
            }
            q.addLast(frame.copyOf()) // защитная копия
            framesPushed++
            cond.signal()
        }
    }

    /** Забрать следующий кадр; если пусто — ждём до timeoutMs. null по таймауту. */
    fun take10ms(timeoutMs: Long): ShortArray? {
        val deadlineNs = if (timeoutMs > 0) (System.nanoTime() + timeoutMs * 1_000_000L) else Long.MIN_VALUE
        lock.withLock {
            while (q.isEmpty()) {
                if (timeoutMs <= 0) return null
                val leftNs = deadlineNs - System.nanoTime()
                if (leftNs <= 0) {
                    waitsTimedOut++
                    return null
                }
                try {
                    cond.awaitNanos(leftNs)
                } catch (_: InterruptedException) {
                    waitsTimedOut++
                    return null
                }
            }
            framesPopped++
            return q.removeFirst()
        }
    }

    fun clear() = lock.withLock { q.clear() }
    fun size(): Int = lock.withLock { q.size }
}
