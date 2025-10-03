package com.buligin.vishnucast.audio

import androidx.annotation.Keep

/** JNI-мост: статические методы удобно вызывать из C++ (фаза 4.3-B). */
@Keep
object MixBusBridge {
    @JvmStatic
    fun take10ms(timeoutMs: Int): ShortArray? =
        MixBus48k.take10ms(timeoutMs.toLong())

    @JvmStatic
    fun stats(): LongArray = longArrayOf(
        MixBus48k.framesPushed,
        MixBus48k.framesPopped,
        MixBus48k.framesDropped,
        MixBus48k.waitsTimedOut,
        MixBus48k.size().toLong()
    )

    @JvmStatic
    fun clear() = MixBus48k.clear()
}
