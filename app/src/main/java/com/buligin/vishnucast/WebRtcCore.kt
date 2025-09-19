package com.buligin.vishnucast

import android.content.Context
import android.media.MediaRecorder
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.atomic.AtomicInteger

class WebRtcCore(private val ctx: Context) {
    private val egl = EglBase.create()
    private val factory: PeerConnectionFactory
    private val audioSource: AudioSource
    private val audioTrack: AudioTrack

    private val connectedPeerCount = AtomicInteger(0)

    init {
        // 1) ADM: VOICE_RECOGNITION + ОТКЛЮЧАЕМ аппаратные AEC/NS
        val adm = JavaAudioDeviceModule.builder(ctx)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .createAudioDeviceModule()

        val encoderFactory = DefaultVideoEncoderFactory(egl.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(egl.eglBaseContext)

        // 2) Как в «золотой» версии: отключён NetworkMonitor — не трогаем
        val options = PeerConnectionFactory.Options().apply {
            disableNetworkMonitor = true
        }

        factory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        // 3) Мягкие аудио-констрейнты (софт AEC/NS/AGC через goog*)
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl2", "true"))
            // небольшой фильтр НЧ поможет на ряде девайсов
            optional.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        }

        audioSource = factory.createAudioSource(audioConstraints)
        audioTrack  = factory.createAudioTrack("ARDAMSa0", audioSource)
        audioTrack.setEnabled(true)
    }

    fun createPeerConnection(onIce: (IceCandidate) -> Unit): PeerConnection? {
        val rtcConfig = PeerConnection.RTCConfiguration(
            listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val observer = object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) { onIce(c) }
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
            override fun onConnectionChange(s: PeerConnection.PeerConnectionState?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onTrack(t: RtpTransceiver?) {}
            override fun onDataChannel(dc: DataChannel?) {}
            override fun onRenegotiationNeeded() {}

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                when (newState) {
                    PeerConnection.IceConnectionState.CONNECTED -> {
                        val n = connectedPeerCount.incrementAndGet()
                        ClientCount.post(n)
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED,
                    PeerConnection.IceConnectionState.FAILED,
                    PeerConnection.IceConnectionState.CLOSED -> {
                        val n = connectedPeerCount.decrementAndGet().coerceAtLeast(0)
                        ClientCount.post(n)
                    }
                    else -> {}
                }
            }
        }

        val pc = factory.createPeerConnection(rtcConfig, observer) ?: return null

        // Добавляем аудио-трек в именованный поток (как у тебя и было)
        pc.addTrack(audioTrack, listOf("vishnu_audio_stream"))

        // «Пинок» записи у некоторых прошивок (безопасно):
        try { pc.getStats { /* no-op */ } } catch (_: Throwable) {}

        return pc
    }

    // Эти врапперы нужны PeerManager'у
    fun setRemoteDescription(pc: PeerConnection, sdp: SessionDescription, onSet: () -> Unit = {}) {
        pc.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() = onSet()
            override fun onSetFailure(p0: String?) {}
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, sdp)
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
}
