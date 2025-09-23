package com.buligin.vishnucast

import android.content.Context
import android.media.MediaRecorder
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.atomic.AtomicInteger

class WebRtcCore(private val ctx: Context) {
    private val egl = EglBase.create()
    private val adm: JavaAudioDeviceModule
    private val factory: PeerConnectionFactory
    private val audioSource: AudioSource
    private val audioTrack: AudioTrack

    private val connectedPeerCount = AtomicInteger(0)

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
    }

    /** Включить/выключить микрофон (mute) без разрушения трека. */
    fun setMuted(muted: Boolean) {
        try { adm.setMicrophoneMute(muted) } catch (_: Throwable) {}
        try { audioTrack.setEnabled(true) } catch (_: Throwable) {} // трек всегда есть
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
        return pc
    }

    /**
     * Перегрузка под текущий PeerManager: применяем удалённый SDP и дергаем onSet() при успехе.
     */
    fun setRemoteDescription(pc: PeerConnection, sdp: SessionDescription, onSet: () -> Unit = {}) {
        pc.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() { onSet() }
            override fun onSetFailure(p0: String?) {}
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, sdp)
    }

    /**
     * Вариант «получили offer (sdp:String) → сразу setRemote + создать answer».
     * Оставляю для совместимости, если где-то используется.
     */
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

    /** Полная очистка Core. */
    fun close() {
        try { audioTrack.setEnabled(false) } catch (_: Throwable) {}
        try { audioTrack.dispose() } catch (_: Throwable) {}
        try { audioSource.dispose() } catch (_: Throwable) {}
        try { factory.dispose() } catch (_: Throwable) {}
        try { egl.release() } catch (_: Throwable) {}
    }
}
