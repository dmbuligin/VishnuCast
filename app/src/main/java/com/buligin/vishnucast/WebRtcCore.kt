package com.buligin.vishnucast

import android.content.Context
import android.util.Log
import org.webrtc.*

class WebRtcCore(private val app: Context) : AutoCloseable {

    enum class PcKind { MIC, PLAYER }

    private val factory: PeerConnectionFactory
    private val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        iceServers = listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
    }

    // Держим PC по типам, чтобы CastService/Signaling могли к ним обращаться
    private var pcMic: PeerConnection? = null
    private var pcPlayer: PeerConnection? = null

    // Треки/сендеры
    private var micTrack: AudioTrack? = null
    private var playerTrack: AudioTrack? = null
    private var micSender: RtpSender? = null
    private var playerSender: RtpSender? = null

    // Состояние mute
    @Volatile private var micMuted: Boolean = true

    init {
        val initOpts = PeerConnectionFactory.InitializationOptions
            .builder(app)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOpts)

        factory = PeerConnectionFactory
            .builder()
            .setOptions(PeerConnectionFactory.Options().apply { disableNetworkMonitor = true })
            .createPeerConnectionFactory()

        // MIC track (встроенный источник)
        val micSource = factory.createAudioSource(MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl2", "true"))
        })
        micTrack = factory.createAudioTrack("ARDAMSa0", micSource).apply { setEnabled(!micMuted) }

        // PLAYER track (пока тот же source; нативный PCM отключён в CMake)
        val plSource = factory.createAudioSource(MediaConstraints())
        playerTrack = factory.createAudioTrack("VC_PLAYER_0", plSource).apply { setEnabled(true) }
    }

    /** Создать (или пересоздать) PeerConnection указанного типа и подписаться на ICE */
    fun createPeerConnection(kind: PcKind, onIce: (IceCandidate) -> Unit): PeerConnection {
        val pc = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d("VishnuRTC", "PC $kind ICE $state")
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate?) { if (candidate != null) onIce(candidate) }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dc: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        }) ?: throw IllegalStateException("createPeerConnection failed")

        when (kind) {
            PcKind.MIC -> {
                try { pcMic?.close() } catch (_: Throwable) {}
                pcMic = pc
                micSender = pc.addTrack(micTrack, listOf("vishnu_audio_stream"))
            }
            PcKind.PLAYER -> {
                try { pcPlayer?.close() } catch (_: Throwable) {}
                pcPlayer = pc
                playerSender = pc.addTrack(playerTrack, listOf("vishnu_player_stream"))
            }
        }
        return pc
    }

    /** ВКЛ/ВЫКЛ активной кодировки — через setParameters (без рефлексии) */
    fun trySetSenderActive(sender: RtpSender?, active: Boolean, tag: String) {
        if (sender == null) return
        try {
            val params = sender.parameters
            val enc = params.encodings
            if (enc != null && enc.isNotEmpty()) {
                if (enc[0].active != active) {
                    enc[0].active = active
                    val ok = sender.setParameters(params)
                    Log.d("VishnuRTC", "enc.active[$tag] -> $active ok=$ok")
                }
            }
        } catch (t: Throwable) {
            Log.w("VishnuRTC", "trySetSenderActive[$tag] failed: ${t.message}")
        }
    }

    /** Кроссфейдер: MIC активен слева, PLAYER — справа (экономия трафика на краях) */
    fun trySetActiveByAlpha(alpha: Float) {
        val a = alpha.coerceIn(0f, 1f)
        trySetSenderActive(micSender, a <= 0.02f && !micMuted, "MIC")
        trySetSenderActive(playerSender, a >= 0.98f, "PLAYER")
    }

    /** Совместимость: форс-пробник уровней по альфе — безопасная заглушка */
    fun setForceProbeByAlpha(alpha: Float, muted: Boolean) {
        // В текущем профиле ничего не делаем (индикатор уровня уже есть),
        // оставляем метод для совместимости вызовов из CastService.
    }

    /** Совместимость: «мьют» — просто включаем/выключаем AudioTrack микрофона */
    fun setMuted(muted: Boolean) {
        micMuted = muted
        try { micTrack?.setEnabled(!muted) } catch (_: Throwable) {}
        Log.d("VishnuRTC", "MIC muted=$muted")
    }

    /** Совместимость: установить удалённый SDP (offer) и вернуть answer */
    fun setRemoteSdp(kind: PcKind, remoteSdp: String, onLocalAnswer: (SessionDescription) -> Unit) {
        val pc = when (kind) { PcKind.MIC -> pcMic ?: return; PcKind.PLAYER -> pcPlayer ?: return }
        val remote = SessionDescription(SessionDescription.Type.OFFER, remoteSdp)
        pc.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                pc.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(desc: SessionDescription) {
                        pc.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() { onLocalAnswer(desc) }
                            override fun onSetFailure(p0: String?) {}
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onCreateFailure(p0: String?) {}
                        }, desc)
                    }
                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetSuccess() {}
                    override fun onSetFailure(p0: String?) {}
                }, MediaConstraints())
            }
            override fun onSetFailure(p0: String?) {}
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, remote)
    }

    /** Совместимость: добавить ICE кандидата к PC по типу */
    fun addIceCandidate(kind: PcKind, cand: IceCandidate) {
        val pc = when (kind) { PcKind.MIC -> pcMic; PcKind.PLAYER -> pcPlayer }
        try { pc?.addIceCandidate(cand) } catch (_: Throwable) {}
    }

    /** Закрыть конкретную PC */
    fun close(kind: PcKind) {
        val pc = when (kind) {
            PcKind.MIC -> pcMic.also { pcMic = null }
            PcKind.PLAYER -> pcPlayer.also { pcPlayer = null }
        }
        try { pc?.close() } catch (_: Throwable) {}
    }

    /** Совместимость: dispose() как синоним полного закрытия */
    fun dispose() {
        try { close(PcKind.MIC) } catch (_: Throwable) {}
        try { close(PcKind.PLAYER) } catch (_: Throwable) {}
        close() // общий close()
    }

    /** Общий close() для AutoCloseable */
    override fun close() {
        try { micSender?.setTrack(null, false) } catch (_: Throwable) {}
        try { playerSender?.setTrack(null, false) } catch (_: Throwable) {}
        micSender = null; playerSender = null

        try { micTrack?.dispose() } catch (_: Throwable) {}
        try { playerTrack?.dispose() } catch (_: Throwable) {}
        micTrack = null; playerTrack = null

        try { factory.dispose() } catch (_: Throwable) {}
    }
}
