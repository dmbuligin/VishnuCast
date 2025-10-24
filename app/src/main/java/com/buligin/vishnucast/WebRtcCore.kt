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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import com.buligin.vishnucast.player.PlayerPcmRing
import java.nio.ByteBuffer
import java.nio.ByteOrder






class WebRtcCore(private val ctx: Context) {

    @Volatile private var micIceConnected: Boolean = false
    @Volatile private var playerIceConnected: Boolean = false

    @Volatile private var autoBindCount: Int = 0
    private val autoBindLock = Any()

    @Volatile private var lastCreatedSender: RtpSender? = null
    @Volatile private var playerNativeSrc: Long = 0L

    // === MIX (server-side): α и временный буфер плеера для смешивания ===
    @Volatile private var mixAlpha01: Float = 0f
    private val playerTmp = ShortArray(480 * 4) // запас под 10..40мс кадров

    enum class PcKind { MIC, PLAYER }

    @Volatile private var pcMic: PeerConnection? = null
    @Volatile private var pcPlayer: PeerConnection? = null

    @Volatile private var senderMic: RtpSender? = null
    @Volatile private var senderPlayer: RtpSender? = null

    private val pendingPeerCount = AtomicInteger(0)
    private var guardTimer: Timer? = null

    private val adm: JavaAudioDeviceModule
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

        // === ADM + AudioBufferCallback: здесь делаем серверный микс MIC + PLAYER ===
        adm = JavaAudioDeviceModule.builder(ctx)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            // ВАЖНО: у io.github.webrtc-sdk колбэк другой: onBuffer(ByteBuffer,...): Long
            .setAudioBufferCallback(object : JavaAudioDeviceModule.AudioBufferCallback {
                override fun onBuffer(
                    buffer: ByteBuffer,
                    audioFormat: Int,
                    channelCount: Int,
                    sampleRate: Int,
                    bytesRead: Int,
                    captureTimeNs: Long
                ): Long {
                    // серверный микс: buffer = (1-α)*mic + α*player (PCM16, mono, 48k)
                    val a = mixAlpha01.coerceIn(0f, 1f)
                    if (a <= 0.0001f) return captureTimeNs // чистый микрофон — не тратимся
                    if (channelCount != 1 || sampleRate != 48000 || bytesRead <= 0) return captureTimeNs

// работаем с дубликатом и ЖЁСТКО задаём limit(bytesRead)
// иначе asShortBuffer() может иметь limit=0 → crash
                    val dup = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                    dup.position(0)
                    dup.limit(bytesRead)

// число 16-битных сэмплов
                    val shortCount = (bytesRead shr 1)
                    if (shortCount <= 0) return captureTimeNs
                    if (playerTmp.size < shortCount) return captureTimeNs

                    val sb = dup.asShortBuffer()
                    if (sb.limit() < shortCount) return captureTimeNs // дополнительная защита

// читаем плеер в playerTmp; недостача – нули
                    PlayerPcmRing.popInto(playerTmp, shortCount)

                    val micGain = (1.0f - a)
                    val plGain  = a
                    var i = 0
                    while (i < shortCount) {
                        val m = sb.get(i).toInt()
                        val p = playerTmp[i].toInt()
                        var s = (m * micGain + p * plGain).toInt()
                        if (s >  32767) s =  32767
                        if (s < -32768) s = -32768
                        sb.put(i, s.toShort())
                        i++
                    }
                    return captureTimeNs

                }
            })
            .createAudioDeviceModule()


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

        adm.setMicrophoneMute(true)
        muted.set(true)
        SignalLevel.post(0)
        d("init: ADM & audioTrack ready, start muted")

        // безопасная заглушка native-источника
        try {
            playerNativeSrc = com.buligin.vishnucast.player.jni.PlayerJni.createSource()
            Log.d("VishnuJNI", "playerNativeSrc=0x${java.lang.Long.toHexString(playerNativeSrc)}")
            com.buligin.vishnucast.player.jni.PlayerJni.sourceSetMuted(playerNativeSrc, true)
        } catch (t: Throwable) {
            Log.w("VishnuJNI", "createSource failed: ${t.message}")
            playerNativeSrc = 0L
        }

        startGuardTimer()
    }

    private fun startGuardTimer() {
        try { guardTimer?.cancel() } catch (_: Throwable) {}
        guardTimer = Timer("vc-guard", true).apply {
            schedule(object : TimerTask() { override fun run() { enforceRoutingConsistency() } }, 2000L, 2000L)
        }
    }

    @Synchronized
    private fun enforceRoutingConsistency() {
        val isMuted = muted.get()
        val clients = connectedPeerCount.get()
        val probeRunning = probe.isRunning()
        val statsActive = (statsPc != null && statsTimer != null)
        val pending = pendingPeerCount.get()
        val hasActiveOrPending = (clients + pending) > 0

        if (isMuted) {
            if (probeRunning) { probe.stop(); d("guard: stopped probe (muted)") }
            if (clients == 0 && statsActive) {
                try { statsTimer?.cancel() } catch (_: Throwable) {}
                statsTimer = null; statsPc = null
                d("guard: stopped stats (muted & no clients)")
            } else if (clients > 0 && !statsActive && statsPc != null) {
                restartStatsTimer()
                d("guard: restarted stats (muted & clients>0)")
            }
            d("guard: hb muted=$isMuted clients=$clients pending=$pending probe=$probeRunning stats=$statsActive")
            return
        }

        if (hasActiveOrPending) {
            if (probeRunning) { probe.stop(); d("guard: stopped probe (active/pending)") }
            if (clients > 0 && !statsActive && statsPc != null) {
                restartStatsTimer()
                d("guard: restarted stats (clients>0)")
            }
        } else {
            if (statsActive) {
                try { statsTimer?.cancel() } catch (_: Throwable) {}
                statsTimer = null; statsPc = null
                d("guard: stopped stats (no clients)")
            }
            if (!probeRunning) {
                probe.setRelease(LEVEL_RELEASE_PER_SEC)
                probe.setTickMs(LEVEL_TICK_MS)
                probe.start { level01 -> onExternalLevel(level01) }
                d("guard: started probe (no clients)")
            }
        }
        d("guard: hb muted=$isMuted clients=$clients pending=$pending probe=$probeRunning stats=$statsActive")
    }

    fun setMuted(mutedNow: Boolean) {
        muted.set(mutedNow)
        try { adm.setMicrophoneMute(mutedNow) } catch (_: Throwable) {}
        try {
            val src = playerNativeSrc
            if (src != 0L) com.buligin.vishnucast.player.jni.PlayerJni.sourceSetMuted(src, mutedNow)
        } catch (_: Throwable) {}
        try { audioTrack.setEnabled(true) } catch (_: Throwable) {}

        if (mutedNow) {
            shownLevel01 = 0.0
            SignalLevel.post(0)
            lastEnergy = null; lastDuration = null
            probe.stop()
            d("setMuted: ON → level=0, probe stop")
        } else {
            d("setMuted: OFF")
            if (connectedPeerCount.get() == 0) {
                probe.setRelease(LEVEL_RELEASE_PER_SEC)
                probe.setTickMs(LEVEL_TICK_MS)
                probe.start { level01 -> onExternalLevel(level01) }
                d("setMuted: probe start (no clients)")
            }
        }
    }

    /** Хэндл тонкого нативного источника (0 если не создан) */
    fun getNativeSourceHandle(): Long = playerNativeSrc

    /** Альфа для серверного микса (0..1). */
    fun mixSetAlpha(alpha: Float) {
        mixAlpha01 = alpha.coerceIn(0f, 1f)
    }

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

    // ===== базовый PC-конструктор =====
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
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                val isMic = (createdPc === pcMic)
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED -> {
                        if (isMic) micIceConnected = true else playerIceConnected = true

                        pendingPeerCount.updateAndGet { n -> if (n > 0) n - 1 else 0 }
                        val n = connectedPeerCount.incrementAndGet()
                        ClientCount.post(n)

                        statsPc = createdPc
                        lastEnergy = null; lastDuration = null
                        restartStatsTimer()

                        d("PC CONNECTED (${if (isMic) "MIC" else "PLAYER"}): clients=$n, pending=${pendingPeerCount.get()} → stats=ON")
                    }
                    PeerConnection.IceConnectionState.CLOSED,
                    PeerConnection.IceConnectionState.FAILED,
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        if (isMic) micIceConnected = false else playerIceConnected = false
                        if (connectedPeerCount.get() > 0) {
                            val n = connectedPeerCount.decrementAndGet().coerceAtLeast(0)
                            ClientCount.post(n)
                            if (statsPc === createdPc) {
                                statsPc = pickAliveStatsPc()
                                if (statsPc == null) {
                                    try { statsTimer?.cancel() } catch (_: Throwable) {}
                                    statsTimer = null
                                    lastEnergy = null; lastDuration = null
                                    d("PC $state (${if (isMic) "MIC" else "PLAYER"}): clients=$n → stats=OFF")
                                } else {
                                    d("PC $state (${if (isMic) "MIC" else "PLAYER"}): clients=$n → stats=SWITCH")
                                }
                            } else {
                                d("PC $state (${if (isMic) "MIC" else "PLAYER"}): clients=$n → stats=KEEP")
                            }
                        } else {
                            pendingPeerCount.updateAndGet { n -> if (n > 0) n - 1 else 0 }
                            d("PC $state (${if (isMic) "MIC" else "PLAYER"}): pending now ${pendingPeerCount.get()}")
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
        lastCreatedSender = sender

        synchronized(autoBindLock) {
            if (pcMic == null) {
                pcMic = pc
                senderMic = sender
                Log.d(TAG, "auto-bind: MIC sender set: ${senderMic != null}")
                autoBindCount = 1
            } else if (pcPlayer == null) {
                pcPlayer = pc
                senderPlayer = sender
                Log.d(TAG, "auto-bind: PLAYER sender set: ${senderPlayer != null}")
                autoBindCount = 2
            } else {
                Log.d(TAG, "auto-bind: extra PC created (ignored for MIC/PLAYER binding)")
            }
        }

        pendingPeerCount.incrementAndGet()
        statsPc = pc
        d("createPeerConnection: pending += 1; waiting CONNECTED to start stats")
        return pc
    }

    // ===== kind API (для совместимости маршрутизации) =====
    fun createPeerConnection(kind: PcKind, onIce: (IceCandidate) -> Unit): PeerConnection? {
        val pc = createPeerConnection(onIce) ?: return null
        when (kind) {
            PcKind.MIC -> {
                pcMic = pc
                senderMic = lastCreatedSender ?: try { pc.senders.firstOrNull { it.track()?.kind() == "audio" } } catch (_: Throwable) { null }
                Log.d(TAG, "MIC sender set: ${senderMic != null}")
            }
            PcKind.PLAYER -> {
                pcPlayer = pc
                senderPlayer = lastCreatedSender ?: try { pc.senders.firstOrNull { it.track()?.kind() == "audio" } } catch (_: Throwable) { null }
                Log.d(TAG, "PLAYER sender set: ${senderPlayer != null}")
            }
        }
        lastCreatedSender = null
        return pc
    }

    fun setRemoteSdp(pc: PeerConnection, sdp: String, onLocalSdp: (SessionDescription) -> Unit) {
        val remote = SessionDescription(SessionDescription.Type.OFFER, sdp)
        pc.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() { createAnswer(pc, onLocalSdp) }
            override fun onSetFailure(p0: String?) {}
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, remote)
    }

    fun addIceCandidate(pc: PeerConnection, c: IceCandidate) {
        try { pc.addIceCandidate(c) } catch (_: Throwable) {}
    }

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

    fun setLevelReleasePerSec(value: Double) {
        LEVEL_RELEASE_PER_SEC = value.coerceIn(0.05, 3.0)
        d("setLevelReleasePerSec: $LEVEL_RELEASE_PER_SEC")
        probe.setRelease(LEVEL_RELEASE_PER_SEC)
    }

    fun setLevelTickMs(ms: Int) {
        LEVEL_TICK_MS = ms.coerceIn(60, 500)
        d("setLevelTickMs: $LEVEL_TICK_MS")
        restartStatsTimer()
        probe.setTickMs(LEVEL_TICK_MS)
    }

    @Synchronized private fun restartStatsTimer() {
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
                    try {
                        val st = pc.iceConnectionState()
                        if (st != PeerConnection.IceConnectionState.CONNECTED) {
                            val alt = pickAliveStatsPc() ?: return
                            statsPc = alt
                        }
                    } catch (_: Throwable) { return }

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
                                val prevE = lastEnergy; val prevD = lastDuration
                                lastEnergy = energy;    lastDuration = dur
                                if (prevE != null && prevD != null) {
                                    val dE = max(0.0, energy!! - prevE)
                                    val dD = max(1e-9, dur!! - prevD)
                                    var p = dE / dD
                                    p = min(1.0, max(0.0, p))
                                    instLevel01 = p
                                } else instLevel01 = null
                            }

                            maybeVerbose("stats(real): hasOut=$sawOutbound hasSrc=$sawMediaSource inst=" +
                                (instLevel01?.let { "%.2f".format(it) } ?: "null"))

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

    @Synchronized
    private fun pickAliveStatsPc(): PeerConnection? {
        if (micIceConnected && pcMic != null) return pcMic
        if (playerIceConnected && pcPlayer != null) return pcPlayer
        return null
    }

    fun setCrossfadeAlpha(alpha: Float) {
        val a = alpha.coerceIn(0f, 1f)
        val singleTrackMode = (senderPlayer == null || pcPlayer == null)
        Log.d(TAG, "setCrossfadeAlpha a=${"%.2f".format(a)} mic=${senderMic!=null} player=${senderPlayer!=null} single=$singleTrackMode")

        // В режиме 1 PC/1 трека НЕЛЬЗЯ выключать MIC — он несёт смешанный сигнал.
        if (singleTrackMode) {
            // Убедимся, что MIC всегда активен
            try {
                val s = senderMic ?: return
                val p = s.parameters
                val encs = p.encodings ?: return
                if (encs.isNotEmpty() && encs[0].active == false) {
                    encs[0].active = true
                    val ok = s.setParameters(p)
                    Log.d(TAG, "enc.active[MIC] -> true ok=$ok (single-track)")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "force MIC active (single-track) failed: ${t.message}")
            }
            return // никаких выключений по α
        }

        // Двухтрекавая экономия (исторический путь): MIC↔PLAYER по порогам
        fun trySetSenderActive(sender: RtpSender?, active: Boolean, tag: String) {
            if (sender == null) return
            try {
                val params = sender.parameters
                val encs = params.encodings ?: return
                if (encs.isEmpty()) return
                if (encs[0].active == active) return
                encs[0].active = active
                val ok = sender.setParameters(params)
                Log.d(TAG, "enc.active[$tag] -> $active ok=$ok")
            } catch (t: Throwable) {
                Log.w(TAG, "trySetSenderActive[$tag] failed: ${t.message}")
            }
        }
        trySetSenderActive(senderMic,    a < 0.98f, "MIC")
        trySetSenderActive(senderPlayer, a > 0.02f, "PLAYER")
    }


    companion object {
        @Volatile var LEVEL_TICK_MS: Int = 120
        @Volatile var LEVEL_RELEASE_PER_SEC: Double = 1.50
        @Volatile var LOG_ENABLED: Boolean = true
        private const val TAG = "VishnuCast"
    }

    private object WebRtcInit {
        @Volatile private var done = false
        fun ensure(appCtx: android.content.Context) {
            if (done) return
            synchronized(this) {
                if (done) return
                try {
                    val init = PeerConnectionFactory.InitializationOptions
                        .builder(appCtx)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions()
                    PeerConnectionFactory.initialize(init)
                } catch (t: Throwable) {
                    android.util.Log.w("VishnuRTC", "initialize threw: ${t.message}")
                }
                done = true
            }
        }
    }

    // ===== MicLevelProbe (как было) =====
    private class MicLevelProbe(private val ctx: Context) {
        @Volatile private var running = false
        @Volatile private var thread: Thread? = null
        @Volatile private var tickMs = 120
        @Volatile private var releasePerSec = 1.5
        @Volatile private var shown = 0.0

        fun setTickMs(ms: Int) { tickMs = ms.coerceIn(60, 500) }
        fun setRelease(v: Double) { releasePerSec = v.coerceIn(0.05, 3.0) }

        fun start(onLevel01: (Double) -> Unit) {
            if (running) return
            val hasPerm = ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPerm) {
                onLevel01(0.0)
                Log.w(TAG, "MicLevelProbe: RECORD_AUDIO permission missing; probe disabled")
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
                    val bufSize = max(minBuf * 2, 960 * 10)
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
                    } catch (_: SecurityException) {
                        running = false
                        onLevel01(0.0)
                        Log.w(TAG, "MicLevelProbe: SecurityException starting AudioRecord")
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
                            val rms = kotlin.math.sqrt(sum / n.coerceAtLeast(1))
                            val levelRms = if (rms <= 1e-6) 0.0 else (20.0 * kotlin.math.log10(rms) + 60.0) / 60.0
                            val levelPeak = if (peak <= 1e-6) 0.0 else (20.0 * kotlin.math.log10(peak) + 60.0) / 60.0
                            max(0.0, min(1.0, max(levelRms * 0.85, levelPeak * 0.15)))
                        } else 0.0

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

    // ===== kind-API совместимость =====
    fun setRemoteSdp(kind: PcKind, remoteSdp: String, onLocalSdp: (SessionDescription) -> Unit) {
        val pc = when (kind) { PcKind.MIC -> pcMic; PcKind.PLAYER -> pcPlayer }
        if (pc == null) { w("setRemoteSdp(kind=$kind): pc is null"); return }
        setRemoteSdp(pc, remoteSdp, onLocalSdp)
    }

    fun addIceCandidate(kind: PcKind, c: IceCandidate) {
        val pc = when (kind) { PcKind.MIC -> pcMic; PcKind.PLAYER -> pcPlayer }
        if (pc == null) { w("addIceCandidate(kind=$kind): pc is null"); return }
        addIceCandidate(pc, c)
    }

    fun close(kind: PcKind) {
        val pc = when (kind) {
            PcKind.MIC -> pcMic.also { pcMic = null; senderMic = null }
            PcKind.PLAYER -> pcPlayer.also { pcPlayer = null; senderPlayer = null }
        }
        try { pc?.close() } catch (_: Throwable) {}
        if (statsPc === pc) {
            try { statsTimer?.cancel() } catch (_: Throwable) {}
            statsTimer = null
            statsPc = null
            lastEnergy = null
            lastDuration = null
        }
        playerNativeSrc = 0L
    }

    fun dispose() {
        close(PcKind.MIC)
        close(PcKind.PLAYER)
        d("dispose(): both PCs closed")
    }

    // совместимость c прежним API
    fun setForceProbeByAlpha(alpha: Float, muted: Boolean) { /* no-op */ }
}
