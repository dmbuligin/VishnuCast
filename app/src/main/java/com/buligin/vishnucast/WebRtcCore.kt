package com.buligin.vishnucast

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// Для проверки разрешений в MicLevelProbe:
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

class WebRtcCore(private val ctx: Context) {

    private var guardTimer: Timer? = null

    private val egl = EglBase.create()
    private val adm: JavaAudioDeviceModule
    private val factory: PeerConnectionFactory
    private val audioSource: AudioSource
    private val audioTrack: AudioTrack

    private val connectedPeerCount = AtomicInteger(0)
    private val muted = AtomicBoolean(true)

    // Источник индикатора при наличии клиентов → stats с текущего PC
    @Volatile private var statsPc: PeerConnection? = null
    private var statsTimer: Timer? = null
    @Volatile private var lastEnergy: Double? = null
    @Volatile private var lastDuration: Double? = null
    @Volatile private var shownLevel01: Double = 0.0

    // Зонд микрофона для режима «нет клиентов»
    private val probe = MicLevelProbe(ctx)

    private fun startGuardTimer() {
        try { guardTimer?.cancel() } catch (_: Throwable) {}
        guardTimer = Timer("vc-guard", true).apply {
            schedule(object : TimerTask() {
                override fun run() {
                    enforceRoutingConsistency()
                }
            }, 2000L, 2000L) // 2s после старта и каждые 2s
        }
    }

    @Synchronized
    private fun enforceRoutingConsistency() {
        val isMuted = muted.get()
        val clients = connectedPeerCount.get()
        val probeRunning = probe.isRunning()
        val statsActive = (statsPc != null && statsTimer != null)

        // Мьют → всё выключить.
        if (isMuted) {
            if (probeRunning) { probe.stop(); d("guard: stopped probe (muted)") }
            if (statsActive) {
                try { statsTimer?.cancel() } catch (_: Throwable) {}
                statsTimer = null
                statsPc = null
                d("guard: stopped stats (muted)")
            }
            return
        }

        if (clients > 0) {
            // Должны работать stats; зонд — нет.
            if (probeRunning) { probe.stop(); d("guard: stopped probe (clients>0)") }
            if (!statsActive && statsPc != null) {
                restartStatsTimer()
                d("guard: restarted stats (clients>0)")
            }
        } else {
            // Нет клиентов — должен работать зонд; stats — нет.
            if (statsActive) {
                try { statsTimer?.cancel() } catch (_: Throwable) {}
                statsTimer = null
                statsPc = null
                d("guard: stopped stats (no clients)")
            }
            if (!probeRunning) {
                probe.setRelease(LEVEL_RELEASE_PER_SEC)
                probe.setTickMs(LEVEL_TICK_MS)
                probe.start { level01 -> onExternalLevel(level01) }
                d("guard: started probe (no clients)")
            }
        }
    }


    // --- DIAG/LOG ---
    private fun d(msg: String) { if (LOG_ENABLED) Log.d(TAG, msg) }
    private fun w(msg: String) { if (LOG_ENABLED) Log.w(TAG, msg) }
    private var lastVerboseAt = 0L
    private fun maybeVerbose(msg: String, everyMs: Long = 2000) {
        if (!LOG_ENABLED) return
        val now = System.currentTimeMillis()
        if (now - lastVerboseAt >= everyMs) { lastVerboseAt = now; Log.d(TAG, msg) }
    }

    init {
        // ADM: VOICE_RECOGNITION, HW AEC/NS off — «золотой» профиль
        adm = JavaAudioDeviceModule.builder(ctx)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .createAudioDeviceModule()

        val encoderFactory = DefaultVideoEncoderFactory(egl.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(egl.eglBaseContext)

        val options = PeerConnectionFactory.Options().apply { disableNetworkMonitor = true }

        factory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        // Софт-флаги (AEC/NS/AGC(+2))
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

        // Стартуем в mute → «пустой поток»
        adm.setMicrophoneMute(true)
        muted.set(true)
        SignalLevel.post(0)
        d("init: ADM & audioTrack ready, start muted")

        startGuardTimer()

    }

    /** Включить/выключить микрофон (mute) без разрушения трека. */
    fun setMuted(mutedNow: Boolean) {
        muted.set(mutedNow)
        try { adm.setMicrophoneMute(mutedNow) } catch (_: Throwable) {}
        try { audioTrack.setEnabled(true) } catch (_: Throwable) {}
        if (mutedNow) {
            shownLevel01 = 0.0
            SignalLevel.post(0)
            lastEnergy = null
            lastDuration = null
            // при mute — стоп зонда в любом случае
            probe.stop()
            d("setMuted: ON → level=0, probe stop")
        } else {
            d("setMuted: OFF")
            // Если нет клиентов — запускаем зонд; если есть — индикатор пойдёт по stats
            if (connectedPeerCount.get() == 0) {
                probe.setRelease(LEVEL_RELEASE_PER_SEC)
                probe.setTickMs(LEVEL_TICK_MS)
                probe.start { level01 -> onExternalLevel(level01) }
                d("setMuted: probe start (no clients)")
            }
        }
    }

    /** Реакция на уровень от внешнего зонда (0..1). */
    private fun onExternalLevel(level01: Double) {
        if (muted.get()) return
        val target = level01.coerceIn(0.0, 1.0)
        if (target > shownLevel01) {
            shownLevel01 = target
        } else {
            val decayPerTick = LEVEL_RELEASE_PER_SEC * (LEVEL_TICK_MS / 1000.0)
            shownLevel01 = max(0.0, shownLevel01 - decayPerTick)
            if (shownLevel01 < target) shownLevel01 = target
        }
        val percent = (shownLevel01 * 100.0).coerceIn(0.0, 100.0).roundToInt()
        SignalLevel.post(percent)
    }

    /** Создать PeerConnection для реального клиента. */
    fun createPeerConnection(onIce: (IceCandidate) -> Unit): PeerConnection? {
        val rtcConfig = PeerConnection.RTCConfiguration(
            listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        var createdPc: PeerConnection? = null
        val observer = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) { onIce(candidate) }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) { /* no-op */ }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED -> {
                        val n = connectedPeerCount.incrementAndGet()
                        ClientCount.post(n)
                        // Клиент есть → выключаем зонд, переходим на stats
                        probe.stop()
                        statsPc = createdPc
                        lastEnergy = null; lastDuration = null
                        restartStatsTimer()
                        d("PC CONNECTED: clients=$n, probe stop, statsPc=real")
                    }
                    PeerConnection.IceConnectionState.CLOSED,
                    PeerConnection.IceConnectionState.FAILED,
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        val n = connectedPeerCount.decrementAndGet().coerceAtLeast(0)
                        ClientCount.post(n)
                        if (n == 0) {
                            // Клиентов нет → отключаем stats и включаем зонд (если не mute)
                            try { statsTimer?.cancel() } catch (_: Throwable) {}
                            statsTimer = null
                            statsPc = null
                            lastEnergy = null; lastDuration = null
                            if (!muted.get()) {
                                probe.setRelease(LEVEL_RELEASE_PER_SEC)
                                probe.setTickMs(LEVEL_TICK_MS)
                                probe.start { level01 -> onExternalLevel(level01) }
                                d("PC DISCONNECT: no clients, probe start")
                            }
                        }
                    }
                    else -> { /* no-op */ }
                }
            }

            override fun onStandardizedIceConnectionChange(newState: PeerConnection.IceConnectionState?) {}
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {}
            override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent?) {}
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dc: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
            override fun onTrack(transceiver: RtpTransceiver?) {}
            override fun onRemoveTrack(receiver: RtpReceiver?) {}
        }

        val pc = factory.createPeerConnection(rtcConfig, observer) ?: return null
        createdPc = pc

        val sender = pc.addTrack(audioTrack, listOf("vishnu_audio_stream"))
        d("createPeerConnection: addTrack sender=${sender != null}")

        // Переходим на stats; зонд на всякий случай стопаем
        probe.stop()
        statsPc = pc
        restartStatsTimer()
        d("createPeerConnection: statsPc switched to newly created PC, probe stop")

        return pc
    }

    /** Перегрузка под PeerManager: применяем удалённый SDP и дергаем onSet() при успехе. */
    fun setRemoteDescription(pc: PeerConnection, sdp: SessionDescription, onSet: () -> Unit = {}) {
        pc.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() { onSet() }
            override fun onSetFailure(p0: String?) {}
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, sdp)
    }

    /** Вариант «получили offer (sdp:String) → сразу setRemote + создать answer». */
    fun setRemoteSdp(pc: PeerConnection, sdp: String, onLocalSdp: (SessionDescription) -> Unit) {
        val remote = SessionDescription(SessionDescription.Type.OFFER, sdp)
        pc.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() { createAnswer(pc, onLocalSdp) }
            override fun onSetFailure(p0: String?) {}
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, remote)
    }

    /** Добавить входящий ICE-кандидат. */
    fun addIceCandidate(pc: PeerConnection, c: IceCandidate) {
        try { pc.addIceCandidate(c) } catch (_: Throwable) {}
    }

    /** Сформировать локальный answer и вернуть его через callback. */
    fun createAnswer(pc: PeerConnection, onLocalSdp: (SessionDescription) -> Unit) {
        pc.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc != null) {
                    pc.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() { onLocalSdp(desc) }
                        override fun onSetFailure(p0: String?) {}
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onCreateFailure(p0: String?) {}
                    }, desc)
                }
            }
            override fun onCreateFailure(p0: String?) {}
            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String?) {}
        }, MediaConstraints())
    }

    /** Внешний тюнинг «скорости затухания» уровня (0.05..3.0 долей шкалы за секунду). */
    fun setLevelReleasePerSec(value: Double) {
        LEVEL_RELEASE_PER_SEC = value.coerceIn(0.05, 3.0)
        d("setLevelReleasePerSec: $LEVEL_RELEASE_PER_SEC")
        probe.setRelease(LEVEL_RELEASE_PER_SEC)
    }

    /** Внешний тюнинг периода опроса/тика релиза. */
    fun setLevelTickMs(ms: Int) {
        LEVEL_TICK_MS = ms.coerceIn(60, 500)
        d("setLevelTickMs: $LEVEL_TICK_MS")
        restartStatsTimer()
        probe.setTickMs(LEVEL_TICK_MS)
    }

    // ------------------- ВНУТРЕННЕЕ: таймер и расчёт уровня по WebRTC stats -------------------

    @Synchronized
    private fun restartStatsTimer() {
        try { statsTimer?.cancel() } catch (_: Throwable) {}
        statsTimer = null
        if (statsPc == null) { d("restartStatsTimer: statsPc is null"); return }
        ensureStatsTimer()
    }

    @Synchronized
    private fun ensureStatsTimer() {
        if (statsTimer != null) return
        val tick = LEVEL_TICK_MS.toLong()
        d("ensureStatsTimer: start, tick=${LEVEL_TICK_MS}ms; source=real")
        statsTimer = Timer("vc-stats", true).apply {
            schedule(object : TimerTask() {
                override fun run() {
                    val pc = statsPc ?: return
                    if (muted.get()) {
                        shownLevel01 = 0.0
                        SignalLevel.post(0)
                        lastEnergy = null; lastDuration = null
                        maybeVerbose("stats: muted → level 0")
                        return
                    }
                    try {
                        pc.getStats { report ->
                            var instLevel01: Double? = null
                            var energy: Double? = null
                            var dur: Double? = null

                            var sawOutbound = false
                            var sawMediaSource = false

                            // Предпочтительно: outbound-rtp (audio)
                            report.statsMap?.values?.forEach { s ->
                                if (s.type == "outbound-rtp") {
                                    val mediaType = (s.members["mediaType"] as? String)
                                        ?: (s.members["kind"] as? String)
                                    if (mediaType == null || mediaType == "audio") {
                                        sawOutbound = true
                                        energy = (s.members["totalAudioEnergy"] as? Double)
                                            ?: (s.members["total_audio_energy"] as? Double)
                                        dur = (s.members["totalSamplesDuration"] as? Double)
                                            ?: (s.members["total_samples_duration"] as? Double)
                                    }
                                }
                            }

                            // Fallback: media-source / track / ssrc
                            if (energy == null || dur == null) {
                                var lv: Double? = null
                                report.statsMap?.values?.forEach { s ->
                                    if (s.type == "media-source" || s.type == "media-source-stats" || s.type == "track" || s.type == "ssrc") {
                                        val mediaType = (s.members["mediaType"] as? String)
                                            ?: (s.members["kind"] as? String)
                                        if (mediaType == null || mediaType == "audio") {
                                            sawMediaSource = sawMediaSource || s.type.startsWith("media") || s.type == "track" || s.type == "ssrc"
                                            lv = (s.members["audioLevel"] as? Double)
                                                ?: (s.members["audio_level"] as? Double)
                                                    ?: ((s.members["audioInputLevel"] as? Number)?.toDouble()?.let { it / 32767.0 })
                                                    ?: lv
                                        }
                                    }
                                }
                                instLevel01 = lv
                            } else {
                                // Дельта-метод
                                val prevE = lastEnergy
                                val prevD = lastDuration
                                lastEnergy = energy
                                lastDuration = dur

                                if (prevE != null && prevD != null) {
                                    val dE = max(0.0, energy!! - prevE)
                                    val dD = max(1e-9, dur!! - prevD)
                                    var p = dE / dD
                                    p = min(1.0, max(0.0, p))
                                    instLevel01 = p
                                } else {
                                    instLevel01 = null
                                }
                            }

                            maybeVerbose(
                                "stats(real): hasOut=$sawOutbound hasSrc=$sawMediaSource inst=" +
                                    (instLevel01?.let { "%.2f".format(it) } ?: "null")
                            )

                            val target = (instLevel01 ?: 0.0).coerceIn(0.0, 1.0)
                            if (target > shownLevel01) {
                                shownLevel01 = target
                            } else {
                                val decayPerTick = LEVEL_RELEASE_PER_SEC * (LEVEL_TICK_MS / 1000.0)
                                shownLevel01 = max(0.0, shownLevel01 - decayPerTick)
                                if (shownLevel01 < target) shownLevel01 = target
                            }
                            val percent = (shownLevel01 * 100.0).coerceIn(0.0, 100.0).roundToInt()
                            SignalLevel.post(percent)
                        }
                    } catch (t: Throwable) {
                        w("getStats exception: ${t.message}")
                    }
                }
            }, 0L, tick)
        }
    }

    /** Полная очистка Core. */
    fun close() {
        try { audioTrack.setEnabled(false) } catch (_: Throwable) {}
        try { audioTrack.dispose() } catch (_: Throwable) {}
        try { audioSource.dispose() } catch (_: Throwable) {}
        try { factory.dispose() } catch (_: Throwable) {}
        try { egl.release() } catch (_: Throwable) {}

        try { statsTimer?.cancel() } catch (_: Throwable) {}
        statsTimer = null
        statsPc = null

        probe.stop()

        lastEnergy = null
        lastDuration = null
        shownLevel01 = 0.0
        SignalLevel.post(0)
        d("close: resources disposed, level=0")

        try { guardTimer?.cancel() } catch (_: Throwable) {}
        guardTimer = null

    }

    companion object {
        /** Период «тика» для индикатора (мс). Рекомендовано 60..200. */
        @Volatile var LEVEL_TICK_MS: Int = 120
        /**
         * Скорость затухания («release») в долях шкалы за секунду.
         * (Тебе понравилось 1.50 — можно выставить в любом месте: WebRtcCore.LEVEL_RELEASE_PER_SEC=1.50)
         */
        @Volatile var LEVEL_RELEASE_PER_SEC: Double = 0.60

        /** Диагностика в Logcat (TAG="VishnuCast"). */
        @Volatile var LOG_ENABLED: Boolean = true

        private const val TAG = "VishnuCast"
    }

    // ======== ВНУТРЕННИЙ КЛАСС ЗОНДА МИКРОФОНА ========
    private class MicLevelProbe(private val ctx: Context) {
        @Volatile private var running = false
        @Volatile private var thread: Thread? = null
        @Volatile private var tickMs = 120
        @Volatile private var releasePerSec = 0.6
        @Volatile private var shown = 0.0

        fun setTickMs(ms: Int) { tickMs = ms.coerceIn(60, 500) }
        fun setRelease(v: Double) { releasePerSec = v.coerceIn(0.05, 3.0) }

        fun start(onLevel01: (Double) -> Unit) {
            if (running) return

            // Проверка разрешения RECORD_AUDIO (линтер/безопасность)
            val hasPerm = ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPerm) {
                onLevel01(0.0)
                Log.w("VishnuCast", "MicLevelProbe: RECORD_AUDIO permission missing; probe disabled")
                return
            }

            running = true
            thread = Thread({
                var ar: AudioRecord? = null
                try {
                    val rate = 48000
                    val chCfg = AudioFormat.CHANNEL_IN_MONO
                    val fmt = AudioFormat.ENCODING_PCM_16BIT
                    val minBuf = AudioRecord.getMinBufferSize(rate, chCfg, fmt)
                    val bufSize = max(minBuf * 2, 960 * 10) // ~100ms+

                    try {
                        ar = AudioRecord(
                            MediaRecorder.AudioSource.VOICE_RECOGNITION,
                            rate, chCfg, fmt, bufSize
                        )
                        if (ar.state != AudioRecord.STATE_INITIALIZED) {
                            running = false
                            onLevel01(0.0)
                            return@Thread
                        }
                        ar.startRecording()
                    } catch (se: SecurityException) {
                        running = false
                        onLevel01(0.0)
                        Log.w("VishnuCast", "MicLevelProbe: SecurityException starting AudioRecord: ${se.message}")
                        return@Thread
                    }

                    val buf = ShortArray(bufSize / 2)

                    while (running) {
                        val toRead = min(buf.size, (rate * tickMs) / 1000)
                        val n = ar.read(buf, 0, toRead)
                        val inst = if (n > 0) {
                            var sum = 0.0
                            var peak = 0.0
                            for (i in 0 until n) {
                                val v = buf[i] / 32768.0
                                val av = abs(v)
                                sum += v * v
                                if (av > peak) peak = av
                            }
                            val rms = sqrt(sum / n.coerceAtLeast(1))
                            val levelRms = if (rms <= 1e-6) 0.0 else (20.0 * kotlin.math.log10(rms) + 60.0) / 60.0
                            val levelPeak = if (peak <= 1e-6) 0.0 else (20.0 * kotlin.math.log10(peak) + 60.0) / 60.0
                            max(0.0, min(1.0, max(levelRms * 0.85, levelPeak * 0.15)))
                        } else 0.0

                        // Attack/Release
                        if (inst > shown) {
                            shown = inst
                        } else {
                            val decay = releasePerSec * (tickMs / 1000.0)
                            shown = max(0.0, shown - decay)
                            if (shown < inst) shown = inst
                        }

                        onLevel01(shown)
                    }
                } catch (_: Throwable) {
                    // молча сворачиваемся
                } finally {
                    try { ar?.stop() } catch (_: Throwable) {}
                    try { ar?.release() } catch (_: Throwable) {}
                }
            }, "vc-mic-probe").apply { isDaemon = true; start() }
        }

        fun stop() {
            running = false
            try { thread?.join(200) } catch (_: Throwable) {}
            thread = null
            shown = 0.0
        }

        fun isRunning(): Boolean = running


    }
}
