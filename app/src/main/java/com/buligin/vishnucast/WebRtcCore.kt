package com.buligin.vishnucast

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule

class WebRtcCore(private val ctx: Context) {

    private val eglBase = EglBase.create()
    private val factory: PeerConnectionFactory
    private val audioSource: AudioSource
    private val audioTrack: AudioTrack
    private val adm: JavaAudioDeviceModule

    private val probeStoppedByAdm = AtomicBoolean(false)
    private val connectedPeerCount = AtomicInteger(0)

    companion object {
        private const val AUDIO_TRACK_ID = "ARDAMSa0"
        private const val PREFS_NAME = "vishnucast_prefs"
        private const val KEY_AGC_ENABLED = "pref_agc_enabled"
    }

    init {
        // ВАЖНО: PeerConnectionFactory.initialize(...) уже вызван в App.onCreate() c WebRTC-MDNS/Disabled/
        val encoder = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoder = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        adm = JavaAudioDeviceModule.builder(ctx)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                override fun onWebRtcAudioRecordInitError(errorMessage: String?) {}
                override fun onWebRtcAudioRecordStartError(
                    errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode?,
                    errorMessage: String?
                ) {}
                override fun onWebRtcAudioRecordError(errorMessage: String?) {}
            })
            .setAudioRecordStateCallback(object : JavaAudioDeviceModule.AudioRecordStateCallback {
                override fun onWebRtcAudioRecordStart() { /* no-op */ }
                override fun onWebRtcAudioRecordStop() { /* no-op */ }
            })
            .setSamplesReadyCallback(object : JavaAudioDeviceModule.SamplesReadyCallback {
                override fun onWebRtcAudioRecordSamplesReady(samples: JavaAudioDeviceModule.AudioSamples?) {
                    if (samples == null) return
                    try {
                        val buf = samples.data // PCM16 LE
                        val cap = buf.size
                        var max = 0
                        var i = 0
                        while (i + 1 < cap) {
                            val lo = buf[i].toInt() and 0xFF
                            val hi = buf[i + 1].toInt() and 0xFF
                            val u16 = lo or (hi shl 8)
                            val s16 = if (u16 > 32767) u16 - 65536 else u16
                            val a = if (s16 < 0) -s16 else s16
                            if (a > max) max = a
                            i += 2
                        }
                        val level = ((max * 100.0) / 32767.0).coerceAtMost(100.0).toInt()
                        SignalLevel.post(level)

                        if (probeStoppedByAdm.compareAndSet(false, true)) {
                            // Первый рабочий сэмпл от WebRTC — выключаем локальный AudioRecord
                            MicLevelProbe.stop()
                        }
                    } catch (_: Throwable) { /* ignore */ }
                }
            })
            .createAudioDeviceModule()

        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(encoder)
            .setVideoDecoderFactory(decoder)
            .createPeerConnectionFactory()

        // Медиаконтрейнты: софт-AEC/NS/AGC через goog* ключи
        val prefs = ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val agcEnabled = prefs.getBoolean(KEY_AGC_ENABLED, true)
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", agcEnabled.toString()))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl2", agcEnabled.toString()))
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        }

        audioSource = factory.createAudioSource(audioConstraints)
        audioTrack = factory.createAudioTrack(AUDIO_TRACK_ID, audioSource)

        MicLevelProbe.start(ctx)
    }

    fun createPeerConnection(onIce: (IceCandidate) -> Unit): PeerConnection? {
        val rtcConfig = PeerConnection.RTCConfiguration(
            listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            // disableLinkLocalNetworks нет в вашей версии org.webrtc → не используем.
        }

        val pcConnected = AtomicBoolean(false)

        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                when (newState) {
                    PeerConnection.IceConnectionState.CONNECTED -> {
                        if (pcConnected.compareAndSet(false, true)) {
                            val n = connectedPeerCount.incrementAndGet()
                            ClientCount.post(n)
                            if (n == 1) {
                                MicLevelProbe.stop()
                            }
                        }
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED,
                    PeerConnection.IceConnectionState.FAILED,
                    PeerConnection.IceConnectionState.CLOSED -> {
                        if (pcConnected.compareAndSet(true, false)) {
                            val n = connectedPeerCount.decrementAndGet().coerceAtLeast(0)
                            ClientCount.post(n)
                            if (n <= 0) {
                                probeStoppedByAdm.set(false)
                                MicLevelProbe.start(ctx)
                            }
                        }
                    }
                    else -> { /* ignore */ }
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate?) { if (candidate != null) onIce(candidate) }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dc: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
        }

        val pc = factory.createPeerConnection(rtcConfig, observer) ?: return null
        pc.addTrack(audioTrack, listOf("vishnu_audio_stream"))
        return pc
    }

    fun setRemoteDescription(pc: PeerConnection, sdp: SessionDescription, onSet: () -> Unit) {
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

    fun close() {
        ClientCount.reset()
        MicLevelProbe.stop()
        try {
            audioTrack.dispose()
            audioSource.dispose()
            factory.dispose()
            adm.release()
            eglBase.release()
        } catch (_: Throwable) { }
    }
}
