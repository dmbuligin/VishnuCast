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
import kotlin.math.max
import kotlin.math.roundToInt

import com.buligin.vishnucast.player.jni.PlayerJni
import com.buligin.vishnucast.player.capture.PlayerSystemCaptureCompat

class WebRtcCore(private val ctx: Context) {

    enum class PcKind { MIC, PLAYER }

    // ===== Factory / ADM =====
    private val adm: JavaAudioDeviceModule
    private val factory: PeerConnectionFactory

    // ===== Tracks (источники те же, что были) =====
    private val audioSource: AudioSource
    private val audioTrack: AudioTrack

    private val playerSource: AudioSource
    private var playerTrack: AudioTrack

    // ===== Native player source handle (как было) =====
    private var nativePlayerSrcHandle: Long = 0L

    // ===== Две PC =====
    @Volatile private var pcMic: PeerConnection? = null
    @Volatile private var pcPlayer: PeerConnection? = null

    @Volatile private var micSender: RtpSender? = null
    @Volatile private var playerSender: RtpSender? = null

    // ===== Состояние/счётчики =====
    private val pendingMic = AtomicInteger(0)
    private val pendingPlayer = AtomicInteger(0)
    private val connectedMic = AtomicInteger(0)
    private val connectedPlayer = AtomicInteger(0)
    private val muted = AtomicBoolean(true)

    // ===== Индикатор уровня (оставляем логику как была) =====
    @Volatile private var statsPc: PeerConnection? = null
    private var statsTimer: Timer? = null
    @Volatile private var lastEnergy: Double? = null
    @Volatile private var lastDuration: Double? = null
    @Volatile private var shownLevel01: Double = 0.0
    @Volatile private var forceProbeDueToAlpha: Boolean = false
    private val probe = MicLevelProbe(ctx)

    // ===== Guard =====
    private var guardTimer: Timer? = null

    companion object {
        @Volatile var LEVEL_TICK_MS: Int = 120
        @Volatile var LEVEL_RELEASE_PER_SEC: Double = 1.50
        @Volatile var LOG_ENABLED: Boolean = true
        private const val TAG = "VishnuCast"
    }

    private fun d(msg: String) { if (LOG_ENABLED) Log.d(TAG, msg) }
    private fun w(msg: String) { if (LOG_ENABLED) Log.w(TAG, msg) }

    init {
        WebRtcInit.ensure(ctx.applicationContext)

        adm = JavaAudioDeviceModule.builder(ctx)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .createAudioDeviceModule()

        val options = PeerConnectionFactory.Options().apply {
            disableNetworkMonitor = true
        }

        factory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setAudioDeviceModule(adm)
            .createPeerConnectionFactory()

        val micConstraints = MediaConstraints().apply {
            optional.add(MediaConstraints.KeyValuePair("googEchoCancellation", "false"))
            optional.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "false"))
            optional.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            optional.add(MediaConstraints.KeyValuePair("googAutoGainControl2", "true"))
            optional.add(MediaConstraints.KeyValuePair("googHighpassFilter", "false"))
        }
        audioSource = factory.createAudioSource(micConstraints)
        audioTrack  = factory.createAudioTrack("ARDAMSa0", audioSource)
        audioTrack.setEnabled(true)

        val playerConstraints = MediaConstraints().apply {
            optional.add(MediaConstraints.KeyValuePair("googEchoCancellation", "false"))
            optional.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "false"))
            optional.add(MediaConstraints.KeyValuePair("googAutoGainControl", "false"))
            optional.add(MediaConstraints.KeyValuePair("googAutoGainControl2", "false"))
            optional.add(MediaConstraints.KeyValuePair("googHighpassFilter", "false"))
        }
        playerSource = factory.createAudioSource(playerConstraints)

        // пока оставляем Java-трек; нативный подключим позже (вариант A)
        playerTrack  = factory.createAudioTrack("VC_PLAYER_0", playerSource)
        playerTrack.setEnabled(true)

        // хэндл нативного источника (уже используется PlayerSystemCaptureCompat.setNativeSourceHandleCompat)
        try {
            nativePlayerSrcHandle = PlayerJni.createSource()
            PlayerSystemCaptureCompat.setNativeSourceHandleCompat(nativePlayerSrcHandle)
            Log.d("VishnuRTC", "NATIVE source handle = $nativePlayerSrcHandle")
        } catch (t: Throwable) {
            Log.w("VishnuRTC", "createSource failed: ${t.message}")
        }

        adm.setMicrophoneMute(true)
        muted.set(true)
        SignalLevel.post(0)

        startGuardTimer()
    }

    // ====== ПУБЛИЧНОЕ API ДЛЯ СИГНАЛИНГА (2PC) ======

    /** Создать PC нужного типа (MIC/PLAYER), добавить соответствующий track и вернуть PC. */
    fun createPeerConnection(kind: PcKind, onIce: (IceCandidate) -> Unit): PeerConnection? {
        val rtcConfig = PeerConnection.RTCConfiguration(
            listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val obs = object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) { onIce(c) }
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent?) {}
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {}
            override fun onStandardizedIceConnectionChange(newState: PeerConnection.IceConnectionState?) {}
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dc: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
            override fun onTrack(transceiver: RtpTransceiver?) {}

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED -> {
                        when (kind) {
                            PcKind.MIC -> {
                                pendingMic.updateAndGet { if (it > 0) it - 1 else 0 }
                                val n = connectedMic.incrementAndGet()
                                d("PC MIC CONNECTED: clients=$n, pending=${pendingMic.get()}")
                            }
                            PcKind.PLAYER -> {
                                pendingPlayer.updateAndGet { if (it > 0) it - 1 else 0 }
                                val n = connectedPlayer.incrementAndGet()
                                d("PC PLAYER CONNECTED: clients=$n, pending=${pendingPlayer.get()}")
                            }
                        }
                        updateClientCountAndStatsTarget()
                    }
                    PeerConnection.IceConnectionState.CLOSED,
                    PeerConnection.IceConnectionState.FAILED,
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        when (kind) {
                            PcKind.MIC -> {
                                if (connectedMic.get() > 0) connectedMic.decrementAndGet()
                                d("PC MIC $state: clients=${connectedMic.get()}")
                            }
                            PcKind.PLAYER -> {
                                if (connectedPlayer.get() > 0) connectedPlayer.decrementAndGet()
                                d("PC PLAYER $state: clients=${connectedPlayer.get()}")
                            }
                        }
                        updateClientCountAndStatsTarget()
                    }
                    else -> {}
                }
            }
        }

        val pc = factory.createPeerConnection(rtcConfig, obs) ?: return null
        when (kind) {
            PcKind.MIC -> {
                pcMic?.close(); pcMic = pc
                val s = pc.addTrack(audioTrack, listOf("vishnu_audio_stream"))
                micSender = s
                pendingMic.incrementAndGet()
                d("createPeerConnection[MIC]: sender=${s != null}")
            }
            PcKind.PLAYER -> {
                pcPlayer?.close(); pcPlayer = pc
                val s = pc.addTrack(playerTrack, listOf("vishnu_player_stream"))
                playerSender = s
                pendingPlayer.incrementAndGet()
                d("createPeerConnection[PLAYER]: sender=${s != null}")
            }
        }

        // для индикатора уровня достаточно любого «живого» PC
        statsPc = pc
        return pc
    }

    fun setRemoteSdp(kind: PcKind, sdp: String, onLocalSdp: (SessionDescription) -> Unit) {
        val pc = when (kind) { PcKind.MIC -> pcMic; PcKind.PLAYER -> pcPlayer } ?: return
        val remote = SessionDescription(SessionDescription.Type.OFFER, sdp)
        pc.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() { createAnswer(pc, onLocalSdp) }
            override fun onSetFailure(p0: String?) {}
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, remote)
    }

    fun addIceCandidate(kind: PcKind, c: IceCandidate) {
        val pc = when (kind) { PcKind.MIC -> pcMic; PcKind.PLAYER -> pcPlayer } ?: return
        try { pc.addIceCandidate(c) } catch (_: Throwable) {}
    }

    fun close(kind: PcKind) {
        when (kind) {
            PcKind.MIC -> { try { pcMic?.close() } catch (_: Throwable) {}; pcMic = null; micSender = null }
            PcKind.PLAYER -> { try { pcPlayer?.close() } catch (_: Throwable) {}; pcPlayer = null; playerSender = null }
        }
        updateClientCountAndStatsTarget()
    }

    // ===== Совместимость со старым API (одна PC) — используем MIC как дефолт =====
    @Deprecated("Use 2PC API")
    fun createPeerConnection(onIce: (IceCandidate) -> Unit) = createPeerConnection(PcKind.MIC, onIce)
    @Deprecated("Use 2PC API")
    fun setRemoteSdp(pc: PeerConnection, sdp: String, onLocalSdp: (SessionDescription) -> Unit) =
        setRemoteSdp(PcKind.MIC, sdp, onLocalSdp)
    @Deprecated("Use 2PC API")
    fun addIceCandidate(pc: PeerConnection, c: IceCandidate) = addIceCandidate(PcKind.MIC, c)

    // ===== Остальное — как было (mute, level, stats, probe и т.п.) =====

    fun setMuted(mutedNow: Boolean) {
        muted.set(mutedNow)
        try { adm.setMicrophoneMute(mutedNow) } catch (_: Throwable) {}
        audioTrack.setEnabled(true)
        if (mutedNow) {
            shownLevel01 = 0.0
            SignalLevel.post(0)
            lastEnergy = null
            lastDuration = null
            probe.stop()
        } else {
            if (totalClients() == 0) {
                probe.setRelease(LEVEL_RELEASE_PER_SEC)
                probe.setTickMs(LEVEL_TICK_MS)
                probe.start { level01 -> onExternalLevel(level01) }
            }
        }
        // проброс mute в нативный source (если потом вернёмся к варианту A)
        try { val ptr = nativePlayerSrcHandle; if (ptr != 0L) PlayerJni.sourceSetMuted(ptr, mutedNow) } catch (_: Throwable) {}
    }

    /** Экономия трафика остаётся логикой клиента; здесь ничего менять не нужно. */

    fun setForceProbeByAlpha(alpha: Float, micMuted: Boolean) {
        val want = (!micMuted)
        if (forceProbeDueToAlpha != want) {
            forceProbeDueToAlpha = want
            enforceRoutingConsistency()
        }
    }

    /** Экономия трафика: на краях фейдера активируем только нужный трек.
     *  alpha∈[0..1]: 0 — MIC, 1 — PLAYER.
     *  ≤0.02 → выключаем PLAYER; ≥0.98 → выключаем MIC; иначе оба активны. */
    fun trySetActiveByAlpha(alpha: Float) {
        val a = alpha.coerceIn(0f, 1f)
        val micActive = a <= 0.98f
        val playerActive = a >= 0.02f

        trySetSenderActive(micSender, micActive, "MIC")
        trySetSenderActive(playerSender, playerActive, "PLAYER")
    }

    /** Безопасно применяет encodings[0].active к sender. */
    private fun trySetSenderActive(sender: RtpSender?, active: Boolean, tag: String) {
        if (sender == null) return
        try {
            val params = sender.parameters
            val enc = params.encodings
            if (enc != null && enc.isNotEmpty()) {
                if (enc[0].active != active) {
                    enc[0].active = active
                    params.encodings = enc
                    sender.parameters = params
                    Log.d("VishnuRTC", "enc.active[$tag] -> $active")
                }
            }
        } catch (t: Throwable) {
            Log.w("VishnuRTC", "trySetSenderActive[$tag] failed: ${t.message}")
        }
    }







    private fun createAnswer(pc: PeerConnection, onLocalSdp: (SessionDescription) -> Unit) {
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

    private fun totalClients(): Int = connectedMic.get() + connectedPlayer.get()
    private fun totalPending(): Int = pendingMic.get() + pendingPlayer.get()

    private fun updateClientCountAndStatsTarget() {
        ClientCount.post(totalClients())
        // переназначаем statsPc: любое живое соединение
        statsPc = pcMic ?: pcPlayer
        if (statsPc == null) {
            try { statsTimer?.cancel() } catch (_: Throwable) {}
            statsTimer = null
            lastEnergy = null; lastDuration = null
            // при отсутствии клиентов — локальный пробник
            if (!muted.get()) {
                probe.setRelease(LEVEL_RELEASE_PER_SEC)
                probe.setTickMs(LEVEL_TICK_MS)
                probe.start { level01 -> onExternalLevel(level01) }
            }
        } else {
            restartStatsTimer()
        }
    }

    private fun startGuardTimer() {
        try { guardTimer?.cancel() } catch (_: Throwable) {}
        guardTimer = Timer("vc-guard", true).apply {
            schedule(object : TimerTask() {
                override fun run() { enforceRoutingConsistency() }
            }, 2000L, 2000L)
        }
    }

    @Synchronized
    private fun enforceRoutingConsistency() {
        val isMuted = muted.get()
        val clients = totalClients()
        val hasActiveOrPending = (clients + totalPending()) > 0
        val probeRunning = probe.isRunning()
        val statsActive = (statsPc != null && statsTimer != null)

        if (!isMuted && forceProbeDueToAlpha) {
            if (statsActive) { try { statsTimer?.cancel() } catch (_: Throwable) {}; statsTimer = null; statsPc = null }
            if (!probeRunning) {
                probe.setRelease(LEVEL_RELEASE_PER_SEC)
                probe.setTickMs(LEVEL_TICK_MS)
                probe.start { level01 -> onExternalLevel(level01) }
            }
            return
        }

        if (isMuted) {
            if (probeRunning) probe.stop()
            if (clients == 0 && statsActive) { try { statsTimer?.cancel() } catch (_: Throwable) {}; statsTimer = null; statsPc = null }
            return
        }

        if (hasActiveOrPending) {
            if (probeRunning) probe.stop()
            if (clients > 0 && !statsActive && statsPc != null) restartStatsTimer()
        } else {
            if (statsActive) { try { statsTimer?.cancel() } catch (_: Throwable) {}; statsTimer = null; statsPc = null }
            if (!probeRunning) {
                probe.setRelease(LEVEL_RELEASE_PER_SEC)
                probe.setTickMs(LEVEL_TICK_MS)
                probe.start { level01 -> onExternalLevel(level01) }
            }
        }
    }

    @Synchronized
    private fun restartStatsTimer() {
        try { statsTimer?.cancel() } catch (_: Throwable) {}
        statsTimer = null
        if (statsPc == null) return
        ensureStatsTimer()
    }

    @Synchronized
    private fun ensureStatsTimer() {
        if (statsTimer != null) return
        val tick = LEVEL_TICK_MS.toLong()
        statsTimer = Timer("vc-stats", true).apply {
            schedule(object : TimerTask() {
                override fun run() {
                    val pc = statsPc ?: return
                    if (muted.get()) {
                        shownLevel01 = 0.0
                        SignalLevel.post(0)
                        lastEnergy = null; lastDuration = null
                        return
                    }
                    try {
                        pc.getStats { report ->
                            var energy: Double? = null
                            var dur: Double? = null

                            report.statsMap?.values?.forEach { s ->
                                if (s.type == "outbound-rtp") {
                                    val mediaType = (s.members["mediaType"] as? String)
                                        ?: (s.members["kind"] as? String)
                                    if (mediaType == null || mediaType == "audio") {
                                        energy = (s.members["totalAudioEnergy"] as? Double)
                                            ?: (s.members["total_audio_energy"] as? Double)
                                        dur = (s.members["totalSamplesDuration"] as? Double)
                                            ?: (s.members["total_samples_duration"] as? Double)
                                    }
                                }
                            }

                            val instLevel01 = if (energy != null && dur != null) {
                                val prevE = lastEnergy
                                val prevD = lastDuration
                                lastEnergy = energy
                                lastDuration = dur
                                if (prevE != null && prevD != null) {
                                    val dE = kotlin.math.max(0.0, energy!! - prevE)
                                    val dD = kotlin.math.max(1e-9, dur!! - prevD)
                                    var p = dE / dD
                                    kotlin.math.min(1.0, kotlin.math.max(0.0, p))
                                } else null
                            } else null

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
                    } catch (t: Throwable) { w("getStats exception: ${t.message}") }
                }
            }, 0L, tick)
        }
    }

    private fun onExternalLevel(level01: Double) {
        if (muted.get()) return
        val target = level01.coerceIn(0.0, 1.0)
        if (target > shownLevel01) {
            shownLevel01 = target
        } else {
            val decayPerTick = LEVEL_RELEASE_PER_SEC * (LEVEL_TICK_MS / 1000.0)
            shownLevel01 = kotlin.math.max(0.0, shownLevel01 - decayPerTick)
            if (shownLevel01 < target) shownLevel01 = target
        }
        val percent = (shownLevel01 * 100.0).coerceIn(0.0, 100.0).roundToInt()
        SignalLevel.post(percent)
    }

    /** Явная очистка нативного источника (когда сервис закрывается полностью). */
    fun dispose() {
        try {
            val ptr = nativePlayerSrcHandle
            if (ptr != 0L) {
                PlayerSystemCaptureCompat.setNativeSourceHandleCompat(0L)
                PlayerJni.destroySource(ptr)
                nativePlayerSrcHandle = 0L
            }
        } catch (_: Throwable) {}
    }

    /** Однократная safe-инициализация WebRTC. */
    private object WebRtcInit {
        private var done = false
        fun ensure(appCtx: Context) {
            if (!done) {
                done = true
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

    // ===== Вспомогательный локальный пробник уровня (как у тебя было) =====
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
                onLevel01(0.0); Log.w(TAG, "MicLevelProbe: no RECORD_AUDIO")
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
                    ar = AudioRecord(
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        rate, chCfg, fmt, bufSize
                    )
                    if (ar.state != AudioRecord.STATE_INITIALIZED) { running = false; onLevel01(0.0); return@Thread }
                    ar.startRecording()
                    val buf = ShortArray(bufSize / 2)
                    while (running) {
                        val toRead = kotlin.math.min(buf.size, (rate * tickMs) / 1000)
                        val n = ar.read(buf, 0, toRead)
                        val inst = if (n > 0) {
                            var peak = 0.0
                            var sum = 0.0
                            for (i in 0 until n) {
                                val v = buf[i] / 32768.0
                                val av = kotlin.math.abs(v)
                                sum += v * v
                                if (av > peak) peak = av
                            }
                            val rms = kotlin.math.sqrt(sum / n.coerceAtLeast(1))
                            val levelRms = if (rms <= 1e-6) 0.0 else (20.0 * kotlin.math.log10(rms) + 60.0) / 60.0
                            val levelPeak = if (peak <= 1e-6) 0.0 else (20.0 * kotlin.math.log10(peak) + 60.0) / 60.0
                            kotlin.math.max(0.0, kotlin.math.min(1.0, kotlin.math.max(levelRms * 0.85, levelPeak * 0.15)))
                        } else 0.0
                        if (inst > shown) shown = inst
                        else {
                            val decay = releasePerSec * (tickMs / 1000.0)
                            shown = kotlin.math.max(0.0, shown - decay)
                            if (shown < inst) shown = inst
                        }
                        onLevel01(shown)
                    }
                } catch (_: Throwable) {} finally {
                    try { ar?.stop() } catch (_: Throwable) {}
                    try { ar?.release() } catch (_: Throwable) {}
                }
            }, "vc-mic-probe").apply { isDaemon = true; start() }
        }
        fun stop() { running = false; try { thread?.join(200) } catch (_: Throwable) {}; thread = null; shown = 0.0 }
        fun isRunning(): Boolean = running
    }
}
