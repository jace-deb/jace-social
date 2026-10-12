package dev.jacedeb.jacesocial.rtc

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.projection.MediaProjection
import dev.jacedeb.jacesocial.data.IceServer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** WebRTC for calls and voice rooms: one factory for the app, microphone / camera / screen
 *  tracks, and descriptions sent whole once ICE gathering is done (no trickle), like the
 *  website (server/lib/calls.ts, voice.ts) and Jace Launcher. */
object Rtc {
    private lateinit var app: Context
    val egl: EglBase by lazy { EglBase.create() }
    val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions())
        PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .setAudioDeviceModule(JavaAudioDeviceModule.builder(app)
                .setUseHardwareAcousticEchoCanceler(true).setUseHardwareNoiseSuppressor(true).createAudioDeviceModule())
            .createPeerConnectionFactory()
    }

    fun init(context: Context) { app = context.applicationContext }

    fun config(ice: List<IceServer>): PeerConnection.RTCConfiguration {
        val servers = ice.map { s ->
            val urls = when (val u = s.urls) { is JsonArray -> u.map { it.jsonPrimitive.content }; is JsonPrimitive -> listOf(u.content); else -> emptyList() }
            PeerConnection.IceServer.builder(urls).apply {
                s.username?.let { setUsername(it) }
                s.credential?.let { setPassword(it) }
            }.createIceServer()
        }
        return PeerConnection.RTCConfiguration(servers).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
    }

    /** The microphone, with echo cancelling and noise suppression. */
    fun mic(): AudioTrack {
        val c = MediaConstraints().apply {
            for (k in listOf("googEchoCancellation", "googNoiseSuppression", "googAutoGainControl", "googHighpassFilter"))
                mandatory.add(MediaConstraints.KeyValuePair(k, "true"))
        }
        return factory.createAudioTrack("mic-" + UUID.randomUUID(), factory.createAudioSource(c))
    }

    /** Phone calls sound: talk mode, and the speaker on (there's video, or no earpiece in use). */
    fun audioOn(speaker: Boolean = true) {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION") am.isSpeakerphoneOn = speaker
    }
    fun setSpeaker(on: Boolean) {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        @Suppress("DEPRECATION") am.isSpeakerphoneOn = on
    }
    fun audioOff() {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.mode = AudioManager.MODE_NORMAL
        @Suppress("DEPRECATION") am.isSpeakerphoneOn = false
    }

    /** A camera or screen being sent: its track, the id of its stream (the receiver tells camera
     *  and screen apart by it), and how to stop it. */
    class Video(val track: VideoTrack, val streamId: String, private val capturer: VideoCapturer, private val helper: SurfaceTextureHelper) {
        fun stop() {
            runCatching { capturer.stopCapture() }
            capturer.dispose()
            track.dispose()
            helper.dispose()
        }
    }

    /** The front camera at a size that's fine for several people at once (640x360, 24 fps). */
    fun camera(front: Boolean = true): Video? {
        val en = Camera2Enumerator(app)
        val name = en.deviceNames.firstOrNull { en.isFrontFacing(it) == front } ?: en.deviceNames.firstOrNull() ?: return null
        val capturer = en.createCapturer(name, null) ?: return null
        return start(capturer, 640, 360, 24, isScreen = false)
    }

    /** The whole screen (after the person allowed it; the call service must already be running
     *  as a screen capture service). */
    fun screen(permission: Intent, onStopped: () -> Unit): Video {
        val capturer = ScreenCapturerAndroid(permission, object : MediaProjection.Callback() { override fun onStop() { onStopped() } })
        val m = app.resources.displayMetrics
        val scale = minOf(1.0, 1280.0 / maxOf(m.widthPixels, m.heightPixels))
        return start(capturer, (m.widthPixels * scale).toInt(), (m.heightPixels * scale).toInt(), 15, isScreen = true)
    }

    private fun start(capturer: VideoCapturer, w: Int, h: Int, fps: Int, isScreen: Boolean): Video {
        val helper = SurfaceTextureHelper.create("video-" + UUID.randomUUID(), egl.eglBaseContext)
        val source = factory.createVideoSource(isScreen)
        capturer.initialize(helper, app, source.capturerObserver)
        capturer.startCapture(w, h, fps)
        val track = factory.createVideoTrack("video-" + UUID.randomUUID(), source)
        return Video(track, "s-" + UUID.randomUUID().toString().take(12), capturer, helper)
    }
}

/** Every PeerConnection.Observer method, doing nothing: override the ones you need. */
open class PeerEvents : PeerConnection.Observer {
    override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
    override fun onIceConnectionReceivingChange(receiving: Boolean) {}
    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
    override fun onIceCandidate(candidate: IceCandidate?) {}
    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
    override fun onAddStream(stream: MediaStream?) {}
    override fun onRemoveStream(stream: MediaStream?) {}
    override fun onDataChannel(channel: DataChannel?) {}
    override fun onRenegotiationNeeded() {}
    override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
    override fun onTrack(transceiver: RtpTransceiver?) {}
    override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {}
}

private suspend fun PeerConnection.make(offer: Boolean): SessionDescription = suspendCancellableCoroutine { k ->
    val obs = object : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription) { k.resume(d) }
        override fun onCreateFailure(e: String?) { k.resumeWithException(IllegalStateException(e ?: "Couldn't start the call")) }
        override fun onSetSuccess() {}
        override fun onSetFailure(e: String?) {}
    }
    if (offer) createOffer(obs, MediaConstraints()) else createAnswer(obs, MediaConstraints())
}

private suspend fun PeerConnection.setDesc(local: Boolean, d: SessionDescription) = suspendCancellableCoroutine { k ->
    val obs = object : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription?) {}
        override fun onCreateFailure(e: String?) {}
        override fun onSetSuccess() { k.resume(Unit) }
        override fun onSetFailure(e: String?) { k.resumeWithException(IllegalStateException(e ?: "Couldn't connect")) }
    }
    if (local) setLocalDescription(obs, d) else setRemoteDescription(obs, d)
}

suspend fun PeerConnection.setRemote(type: SessionDescription.Type, sdp: String) = setDesc(false, SessionDescription(type, sdp))
suspend fun PeerConnection.rollback() = setDesc(true, SessionDescription(SessionDescription.Type.ROLLBACK, ""))

/** Make an offer or answer, set it, wait (up to 5 s) for every ICE candidate, then the full description. */
suspend fun PeerConnection.describe(offer: Boolean): String {
    val d = make(offer)
    setDesc(true, d)
    withTimeoutOrNull(5000) { while (iceGatheringState() != PeerConnection.IceGatheringState.COMPLETE) kotlinx.coroutines.delay(50) }
    return localDescription?.description ?: throw IllegalStateException("Couldn't start the call")
}

/** Camera and screen both arrive as video, so each description says which stream is which in one
 *  extra line (server/lib/media.ts). Also "a=x-jace-move" (a call moving to another device) and
 *  "a=x-jace-noyield" (from Jace Launcher, which can't take back an offer). */
object Sdp {
    private const val TAG = "a=x-jace-streams:"
    private const val MOVE = "a=x-jace-move"
    private const val NO_YIELD = "a=x-jace-noyield"

    data class Read(val sdp: String, val camera: String?, val screen: String?, val moving: Boolean, val noYield: Boolean)

    fun tag(sdp: String, camera: String?, screen: String?): String {
        val parts = listOfNotNull(camera?.let { "camera=$it" }, screen?.let { "screen=$it" })
        if (parts.isEmpty()) return sdp
        return sdp.trimEnd('\r', '\n') + "\r\n" + TAG + parts.joinToString(",") + "\r\n"
    }

    fun read(sdp: String): Read {
        var camera: String? = null; var screen: String? = null; var moving = false; var noYield = false
        val lines = sdp.split(Regex("\r?\n")).filter { line ->
            when {
                line == MOVE -> { moving = true; false }
                line == NO_YIELD -> { noYield = true; false }
                line.startsWith(TAG) -> {
                    for (part in line.removePrefix(TAG).split(",")) {
                        val (k, v) = part.split("=").let { (it.getOrNull(0) ?: "") to (it.getOrNull(1)?.trim() ?: "") }
                        if (k == "camera" && v.isNotEmpty()) camera = v
                        if (k == "screen" && v.isNotEmpty()) screen = v
                    }
                    false
                }
                else -> !line.startsWith("a=x-jace-")
            }
        }
        return Read(lines.joinToString("\r\n"), camera, screen, moving, noYield)
    }
}
