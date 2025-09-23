package com.buligin.vishnucast

import android.content.Context
import android.media.MediaRecorder
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

class WebRtcCore(private val ctx: Context) {
    private val egl = EglBase.create()
    private val adm: JavaAudioDeviceModule
    private val factory: PeerConnectionFactory
    private val audioSource: AudioSource
    private val audioTrack: AudioTrack

    private val connectedPeerCount = AtomicInteger(0)
    private val muted = AtomicBoolean(true)

    // Индикатор уровня через getStats()
    @Volatile private var statsPc: PeerConnection? = null
    private var statsTimer: Timer? = null
    @Volatile private var lastEnergy: Double? = null          // totalAudioEnergy (сек-Вт, условно)
    @Volatile private var lastDuration: Double? = null        // totalSamplesDuration (сек)
    @Volatile private var shownLevel01: Double = 0.0          // текущее «показанное» значение 0..1 (для release)

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
    }

    /** Включить/выключить микрофон (mute) без разрушения трека. */
    fun setMuted(mutedNow: Boolean) {
        muted.set(mutedNow)
        try { adm.setMicrophoneMute(mutedNow) } catch (_: Throwable) {}
        try { audioTrack.setEnabled(true) } catch (_: Throwable) {}
        if (mutedNow) {
            shownLevel01 = 0.0
            SignalLevel.post(0)
        }
    }

    /** Создать PeerConnection с нашей конфигурацией. */
    fun createPeerConnection(onIce: (IceCandidate) -> Unit): PeerConnection? {
        val rtcConfig = PeerConnection.RTCConfiguration(
            listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val observer = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) { onIce(candidate) }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) { /* no-op */ }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED -> {
                        val n = connectedPeerCount.incrementAndGet()
                        ClientCount.post(n)
                    }
                    PeerConnection.IceConnectionState.CLOSED,
                    PeerConnection.IceConnectionState.FAILED,
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        val n = connectedPeerCount.decrementAndGet().coerceAtLeast(0)
                        ClientCount.post(n)
                    }
                    else -> {}
                }
            }

            override fun onStandardizedIceConnectionChange(newState: PeerConnection.IceConnectionState?) { /* no-op */ }
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) { /* no-op */ }
            override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent?) { /* no-op */ }

            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dc: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
            override fun onTrack(transceiver: RtpTransceiver?) {}
            override fun onRemoveTrack(receiver: RtpReceiver?) { /* no-op */ }
        }

        val pc = factory.createPeerConnection(rtcConfig, observer) ?: return null

        // Добавляем аудио-трек (sendonly) — стабильный stream id
        pc.addTrack(audioTrack, listOf("vishnu_audio_stream"))

        // Для индикатора уровня будем читать статистику с «последнего» PC
        statsPc = pc
        ensureStatsTimer()

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

    /** Таймер опроса статистики PC → «живой» уровень 0..100. */
    @Synchronized
    private fun ensureStatsTimer() {
        if (statsTimer != null) return
        statsTimer = Timer("vc-stats", true).apply {
            schedule(object : TimerTask() {
                override fun run() {
                    val pc = statsPc ?: return
                    if (muted.get()) {
                        shownLevel01 = 0.0
                        SignalLevel.post(0)
                        // Сбрасываем базу для дельт, чтобы после unmute не было скачка
                        lastEnergy = null
                        lastDuration = null
                        return
                    }
                    try {
                        pc.getStats { report ->
                            var instLevel01: Double? = null
                            var energy: Double? = null
                            var dur: Double? = null

                            // 1) Предпочтительно: outbound-rtp (audio) — totalAudioEnergy / totalSamplesDuration
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

                            // 2) Если нет энергии/длительности — fallback на audioLevel (0..1)
                            if (energy == null || dur == null) {
                                var lv: Double? = null
                                report.statsMap?.values?.forEach { s ->
                                    if (s.type == "media-source" || s.type == "media-source-stats" || s.type == "outbound-rtp") {
                                        val mediaType = (s.members["mediaType"] as? String)
                                            ?: (s.members["kind"] as? String)
                                        if (mediaType == null || mediaType == "audio") {
                                            lv = (s.members["audioLevel"] as? Double)
                                                ?: (s.members["audio_level"] as? Double)
                                                    ?: lv
                                        }
                                    }
                                }
                                instLevel01 = lv
                            } else {
                                // Дельта-метод: берем приращения totalAudioEnergy/totalSamplesDuration
                                val prevE = lastEnergy
                                val prevD = lastDuration
                                lastEnergy = energy
                                lastDuration = dur

                                if (prevE != null && prevD != null) {
                                    val dE = max(0.0, energy!! - prevE)
                                    val dD = max(1e-9, dur!! - prevD) // защита от деления на 0
                                    // dE/dD ≈ средняя мощность в окне (0..1) — грубая оценка
                                    var p = dE / dD
                                    // Нормируем и ограничиваем (иногда бывают всплески >1 из-за дискретности счетчиков)
                                    p = min(1.0, max(0.0, p))
                                    instLevel01 = p
                                } else {
                                    // Первое измерение: ничего не знаем — не дергаем индикатор
                                    instLevel01 = null
                                }
                            }

                            // Преобразуем в «живой» уровень с атакой/релизом
                            val target = (instLevel01 ?: 0.0).coerceIn(0.0, 1.0)

                            // Attack: быстро подхватываем всплески (берём максимум сразу)
                            if (target > shownLevel01) {
                                shownLevel01 = target
                            } else {
                                // Release: плавно отпускаем — ~40 уровней/сек при шаге 120мс
                                val decayPerTick = 0.04
                                shownLevel01 = max(0.0, shownLevel01 - decayPerTick)
                                // но не опускаем ниже реального target
                                if (shownLevel01 < target) shownLevel01 = target
                            }

                            val percent = (shownLevel01 * 100.0).coerceIn(0.0, 100.0).roundToInt()
                            SignalLevel.post(percent)
                        }
                    } catch (_: Throwable) {
                        // При ошибке статистики просто пропускаем тик
                    }
                }
            }, 0L, 120L) // ~120 мс: заметно живее 500 мс
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
        lastEnergy = null
        lastDuration = null
        shownLevel01 = 0.0
        SignalLevel.post(0)
    }
}
