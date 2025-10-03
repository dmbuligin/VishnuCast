package com.buligin.vishnucast

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import com.buligin.vishnucast.service.MixerState
import com.buligin.vishnucast.audio.MixerEngine
import com.buligin.vishnucast.audio.NativeAdm
import com.buligin.vishnucast.audio.MixBusBridge
import org.webrtc.audio.AudioDeviceModule


class WebRtcCore(private val ctx: Context) {

    private val pendingPeerCount = AtomicInteger(0)
    private var guardTimer: Timer? = null

    private val adm: org.webrtc.audio.AudioDeviceModule
    private val factory: PeerConnectionFactory
    private val audioSource: AudioSource
    private val audioTrack: AudioTrack
    private val connectedPeerCount = AtomicInteger(0)
    private val muted = AtomicBoolean(true)

    @Volatile private var statsPc: PeerConnection? = null
    private var statsTimer: Timer? = null
    @Volatile private var lastEnergy: Double? = null
    @Volatile private var lastDuration: Double? = null
    @Volatile private var shownLevel01: Double = 0.0

    private val probe = MicLevelProbe(ctx)

    @Volatile private var lastMixerAlpha: Float = MixerState.alpha01.value ?: 0f

    private fun d(msg: String) { if (LOG_ENABLED) Log.d(TAG, msg) }
    private fun w(msg: String) { if (LOG_ENABLED) Log.w(TAG, msg) }
    private var lastVerboseAt = 0L
    private fun maybeVerbose(msg: String, everyMs: Long = 2000) {
        if (!LOG_ENABLED) return
        val now = System.currentTimeMillis()
        if (now - lastVerboseAt >= everyMs) { lastVerboseAt = now; Log.d(TAG, msg) }
    }

    init {
        WebRtcInit.ensure(ctx.applicationContext)

        // 4.3-A: централизованный выбор ADM через NativeAdm (пока всегда JADM)
        adm = buildAudioDeviceModule(ctx)

        val options = PeerConnectionFactory.Options().apply {
            disableNetworkMonitor = true
        }

        factory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setAudioDeviceModule(adm)
            .createPeerConnectionFactory()

        val audioConstraints = MediaConstraints().apply {
            optional.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            optional.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            optional.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            optional.add(MediaConstraints.KeyValuePair("googAutoGainControl2", "true"))
            optional.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        }

        audioSource = factory.createAudioSource(audioConstraints)
        audioTrack  = factory.createAudioTrack("ARDAMSa0", audioSource)
        audioTrack.setEnabled(true)

        (adm as? JavaAudioDeviceModule)?.setMicrophoneMute(true)
        muted.set(true)
        SignalLevel.post(0)
        d("init: ADM & audioTrack ready, start muted; mixer α=${"%.2f".format(lastMixerAlpha)} (mix2=$MIX20_ENABLED)")

        MixerState.alpha01.observeForever { a -> lastMixerAlpha = a ?: 0f }

        startGuardTimer()
    }

    /** 4.3-A: пока возвращаем JADM через NativeAdm; SamplesReadyCallback оставляем для дампов/валидации. */
    private fun buildAudioDeviceModule(appContext: Context): org.webrtc.audio.AudioDeviceModule {
        val samplesCb = object : JavaAudioDeviceModule.SamplesReadyCallback {
            override fun onWebRtcAudioRecordSamplesReady(samples: JavaAudioDeviceModule.AudioSamples?) {
                if (samples == null) return
                try {
                    if (samples.audioFormat != AudioFormat.ENCODING_PCM_16BIT) return

                    val mic48Mono: ShortArray = ensure48kMono(
                        samples.data,
                        samples.sampleRate,
                        samples.channelCount
                    )
                    val a = (MixerState.alpha01.value ?: 0f).coerceIn(0f, 1f)
                    val mixed: ShortArray = MixerEngine.mixMicWithPlayer48kMono(mic48Mono, a)

                    // Опциональные дампы
                    if (com.buligin.vishnucast.audio.MixWavDumper.enabled) {
                        if (DUMP_PLAYER_ONLY) {
                            val next = com.buligin.vishnucast.audio.PlayerPcmBus.take48kMono(mic48Mono.size, 300L)
                            if (next != null) {
                                com.buligin.vishnucast.audio.MixWavDumper.push(float48ToShort(next))
                            } else {
                                com.buligin.vishnucast.audio.MixWavDumper.push(ShortArray(mic48Mono.size))
                            }
                        } else if (DUMP_MIC_ONLY) {
                            com.buligin.vishnucast.audio.MixWavDumper.push(mic48Mono)
                        } else {
                            com.buligin.vishnucast.audio.MixWavDumper.push(mixed)
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "mixMicWithPlayer48kMono error: ${t.message}")
                }
            }
        }

        // Пока — Java ADM. На 4.3-B здесь начнём отдавать кадры из MixBus в нативный ADM.
        val adm = NativeAdm.create(appContext, useNative = MIX20_ENABLED, samplesCb = samplesCb)
        return adm
    }

    // --- конвертеры/ресемплер  ---
    private fun bytesLeToShorts(src: ByteArray): ShortArray {
        val n = src.size / 2
        val out = ShortArray(n)
        var bi = 0
        var i = 0
        while (i < n) {
            val lo = src[bi].toInt() and 0xFF
            val hi = src[bi + 1].toInt()
            out[i] = ((hi shl 8) or lo).toShort()
            i++; bi += 2
        }
        return out
    }

    private fun ensure48kMono(srcPcm16: ByteArray, sr: Int, ch: Int): ShortArray {
        val shorts = ShortArray(srcPcm16.size / 2)
        java.nio.ByteBuffer.wrap(srcPcm16).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer().get(shorts)

        val monoFloat: FloatArray = if (ch <= 1) {
            FloatArray(shorts.size) { i -> shorts[i] / 32768f }
        } else {
            val frames = shorts.size / ch
            val out = FloatArray(frames)
            var si = 0; var f = 0
            while (f < frames) {
                var acc = 0f; var c = 0
                while (c < ch) { acc += (shorts[si + c] / 32768f); c++ }
                out[f] = acc / ch
                si += ch; f++
            }
            out
        }

        val mono48: FloatArray = if (sr == 48000) monoFloat else resampleLinear(monoFloat, sr, 48000)
        return float48ToShort(mono48)
    }

    private fun resampleLinear(src: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        if (src.isEmpty()) return src
        val ratio = dstRate.toDouble() / srcRate.toDouble()
        val outLen = kotlin.math.max(1, (src.size * ratio).toInt())
        val out = FloatArray(outLen)
        val maxIdx = src.size - 1
        var i = 0
        while (i < outLen) {
            val pos = i / ratio
            val i0 = kotlin.math.floor(pos).toInt().coerceIn(0, maxIdx)
            val i1 = kotlin.math.min(i0 + 1, maxIdx)
            val frac = (pos - i0)
            val s = src[i0] * (1.0 - frac) + src[i1] * frac
            out[i] = s.toFloat()
            i++
        }
        return out
    }

    private fun float48ToShort(src: FloatArray): ShortArray {
        val out = ShortArray(src.size)
        var i = 0
        while (i < src.size) {
            val v = (src[i] * 32767.0f).toInt()
            out[i] = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            i++
        }
        return out
    }

    // --- остальной код WebRtcCore без изменений (peer, stats, guard и т.д.) ---

    private fun startGuardTimer() {
        if (MIX20_ENABLED) {
            val s = MixBusBridge.stats()
            maybeVerbose("mixbus: pushed=${s[0]} popped=${s[1]} dropped=${s[2]} timeouts=${s[3]} q=${s[4]}", everyMs = 5000)
        }

    }



    private fun enforceRoutingConsistency() {  }
    fun setMuted(mutedNow: Boolean) {  }
    private fun onExternalLevel(level01: Double) {  }
    fun createPeerConnection(onIce: (IceCandidate) -> Unit): PeerConnection? {  return null }
    fun setRemoteDescription(pc: PeerConnection, sdp: SessionDescription, onSet: () -> Unit = {}) {  }
    fun setRemoteSdp(pc: PeerConnection, sdp: String, onLocalSdp: (SessionDescription) -> Unit) {  }
    fun addIceCandidate(pc: PeerConnection, c: IceCandidate) {  }
    fun createAnswer(pc: PeerConnection, onLocalSdp: (SessionDescription) -> Unit) {  }
    fun setLevelReleasePerSec(value: Double) {  }
    fun setLevelTickMs(ms: Int) {  }

    companion object {
        @Volatile var DUMP_PLAYER_ONLY: Boolean = false
        @Volatile var DUMP_MIC_ONLY: Boolean = false
        @Volatile var LEVEL_TICK_MS: Int = 120
        @Volatile var LEVEL_RELEASE_PER_SEC: Double = 1.50
        @Volatile var LOG_ENABLED: Boolean = true
        @Volatile var MIX20_ENABLED: Boolean = false

        fun setMix20Enabled(enabled: Boolean) {
            MIX20_ENABLED = enabled
            Log.i("VishnuCast", "Mix2.0=${if (enabled) "ON" else "OFF"}")
        }

        private const val TAG = "VishnuCast"
    }

    private object WebRtcInit {
        private val done = java.util.concurrent.atomic.AtomicBoolean(false)
        fun ensure(appCtx: Context) {
            if (done.compareAndSet(false, true)) {
                try { System.loadLibrary("jingle_peerconnection_so") } catch (_: Throwable) {}
                try {
                    val init = PeerConnectionFactory.InitializationOptions
                        .builder(appCtx)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions()
                    PeerConnectionFactory.initialize(init)
                } catch (_: Throwable) {}
            }
        }
    }

    private class MicLevelProbe(private val ctx: Context) {  }
}
