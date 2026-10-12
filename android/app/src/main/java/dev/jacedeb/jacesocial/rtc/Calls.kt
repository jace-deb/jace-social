package dev.jacedeb.jacesocial.rtc

import android.content.Intent
import android.media.AudioManager
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.content.Context
import dev.jacedeb.jacesocial.data.Api
import dev.jacedeb.jacesocial.data.LiveEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.webrtc.AudioTrack
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.security.SecureRandom
import android.util.Base64

data class CallInfo(
    val state: String = "idle",            // idle | calling | ringing | in-call
    val peer: String = "", val peerName: String = "",
    val muted: Boolean = false, val startedAt: Long? = null, val noMic: Boolean = false,
    val sharing: Boolean = false, val camera: VideoTrack? = null,
    val remoteCamera: VideoTrack? = null, val remoteScreen: VideoTrack? = null,
    val speaker: Boolean = false,
)

/** Voice calls with a friend: the same signalling as the website and Jace Launcher (POST /calls
 *  offer / answer / hangup / renegotiate / reanswer, live "call" events), one connection, audio
 *  plus camera and screen sharing added mid-call. See server/lib/calls.ts. */
class Calls(
    private val context: Context,
    private val api: Api,
    private val onEnded: (String) -> Unit,
    private val onIncoming: (String) -> Unit,
    /** true while a call is set up or going on (the app keeps a call service running) */
    private val onActive: (Boolean) -> Unit,
) {
    private val scope = CoroutineScope(Dispatchers.Main)
    private val _info = MutableStateFlow(CallInfo())
    val info: StateFlow<CallInfo> = _info
    private var callId = ""
    private var offerSdp = ""
    private var pc: PeerConnection? = null
    private var mic: AudioTrack? = null
    private var micSender: RtpSender? = null
    private var screen: Rtc.Video? = null
    private var screenSender: RtpSender? = null
    private var cam: Rtc.Video? = null
    private var camSender: RtpSender? = null
    private var remoteCamera: String? = null
    private var remoteScreen: String? = null
    private var making = false
    private var pending = false
    private var callerSide = false
    private var moving = false
    private var timeout: Job? = null
    private var ring: Ringer? = null

    private fun set(f: (CallInfo) -> CallInfo) { _info.value = f(_info.value) }

    fun call(uuid: String, name: String) {
        if (_info.value.state != "idle") return onEnded("You're already in a call")
        val id = newCallId()
        callId = id
        callerSide = true
        _info.value = CallInfo(state = "calling", peer = uuid, peerName = name)
        onActive(true)
        ring = Ringer.outgoing()
        scope.launch {
            try {
                val pc = newPeer(id)
                val sdp = pc.describe(offer = true)
                if (callId != id) return@launch
                api.callSignal(uuid, id, "offer", sdp)
                startTimeout()
            } catch (e: Exception) {
                if (callId == id) end(e.message ?: "Couldn't start the call")
            }
        }
    }

    /** Take over a call we're in on another device (Jace Launcher sends people to a device that can
     *  show video). Same call id; the other side swaps connections without ringing. */
    fun takeOver(uuid: String, name: String, id: String) {
        if (_info.value.state != "idle" || !Regex("^[A-Za-z0-9_-]{8,64}$").matches(id)) return
        callId = id
        callerSide = true
        moving = true
        _info.value = CallInfo(state = "calling", peer = uuid, peerName = name.ifBlank { "A friend" })
        onActive(true)
        scope.launch {
            try {
                val pc = newPeer(id)
                // room for their camera and screen (with its sound): an answer can't add what the offer lacks
                val recv = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
                pc.addTransceiver(org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, recv)
                pc.addTransceiver(org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, recv)
                pc.addTransceiver(org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, recv)
                val sdp = pc.describe(offer = true).trimEnd('\r', '\n') + "\r\na=x-jace-move\r\n"
                if (callId != id) return@launch
                api.callSignal(uuid, id, "offer", sdp)
                timeout = scope.launch { delay(20_000); if (moving && _info.value.state == "calling") end("Couldn't move the call here - it's still going on on your other device") }
            } catch (e: Exception) {
                if (callId == id) end(e.message ?: "Couldn't move the call")
            }
        }
    }

    fun answer() {
        if (_info.value.state != "ringing") return
        val peer = _info.value.peer
        val id = callId
        stopRing()
        timeout?.cancel()
        set { it.copy(state = "in-call", startedAt = System.currentTimeMillis()) }
        scope.launch {
            try {
                val pc = newPeer(id)
                val (offer, camera, scr) = Sdp.read(offerSdp).let { Triple(it.sdp, it.camera, it.screen) }
                remoteCamera = camera; remoteScreen = scr
                pc.setRemote(SessionDescription.Type.OFFER, offer)
                val sdp = pc.describe(offer = false)
                if (callId != id) return@launch
                api.callSignal(peer, id, "answer", sdp)
            } catch (e: Exception) {
                if (callId == id) hangUp(e.message ?: "Couldn't answer")
            }
        }
    }

    fun hangUp(reason: String = "") {
        val i = _info.value
        if (i.state == "idle") return
        val id = callId
        scope.launch { runCatching { api.callSignal(i.peer, id, "hangup") } }
        end(reason)
    }

    fun toggleMute() {
        val muted = !_info.value.muted
        mic?.setEnabled(!muted)
        set { it.copy(muted = muted) }
    }

    fun toggleSpeaker() {
        val on = !_info.value.speaker
        Rtc.setSpeaker(on)
        set { it.copy(speaker = on) }
    }

    /** Turn your camera on or off (adds or removes a video track, then renegotiates). */
    fun toggleCamera() {
        val pc = pc ?: return
        if (_info.value.state != "in-call") return
        val c = cam
        if (c != null) {
            camSender?.let { runCatching { pc.removeTrack(it) } }
            c.stop(); cam = null; camSender = null
            set { it.copy(camera = null) }
        } else {
            val v = Rtc.camera() ?: return onEnded("Couldn't turn on your camera - check that it's allowed")
            cam = v
            camSender = pc.addTrack(v.track, listOf(v.streamId))
            set { it.copy(camera = v.track, speaker = true) }
            Rtc.setSpeaker(true)
        }
        renegotiate()
    }

    /** Share your screen (permission = the screen capture consent from the system), or stop. */
    fun toggleScreen(permission: Intent?) {
        val pc = pc ?: return
        if (_info.value.state != "in-call") return
        val s = screen
        if (s != null) {
            screenSender?.let { runCatching { pc.removeTrack(it) } }
            s.stop(); screen = null; screenSender = null
            set { it.copy(sharing = false) }
        } else {
            permission ?: return
            val v = Rtc.screen(permission) { scope.launch { if (screen != null) toggleScreen(null) } }
            screen = v
            screenSender = pc.addTrack(v.track, listOf(v.streamId))
            set { it.copy(sharing = true) }
        }
        renegotiate()
    }

    /** A new description mid-call, saying which of our streams is the camera and which the screen. */
    private fun renegotiate() {
        val pc = pc ?: return
        if (making || pc.signalingState() != PeerConnection.SignalingState.STABLE) { pending = true; return }
        making = true
        scope.launch {
            try {
                val sdp = Sdp.tag(pc.describe(offer = true), cam?.streamId, screen?.streamId)
                api.callSignal(_info.value.peer, callId, "renegotiate", sdp)
            } catch (e: Exception) {
                onEnded(e.message ?: "Couldn't change the call")
            } finally {
                making = false
            }
        }
    }

    /** A live "call" event: {id, kind, call_id, from, name}. */
    fun onSignal(e: LiveEvent) {
        val kind = e.str("kind") ?: return
        val eventCall = e.str("call_id") ?: ""
        val sid = (e.payload["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: return
        scope.launch {
            try {
                handle(kind, eventCall, sid, e.str("name"))
            } catch (err: Exception) {
                android.util.Log.w("Calls", "signal $kind", err)
            }
        }
    }

    private suspend fun handle(kind: String, eventCall: String, sid: Long, name: String?) {
        val i = _info.value
        when {
            kind == "offer" -> {
                val signal = runCatching { api.readCallSignal(sid) }.getOrNull() ?: return      // the offer expired
                if (i.state == "in-call" && eventCall == callId && signal.sender == i.peer) { replace(signal.sdp); return }
                if (Sdp.read(signal.sdp).moving) return             // someone else's call moving between devices
                if (i.state != "idle") { runCatching { api.callSignal(signal.sender, eventCall, "hangup") }; return }   // busy
                callId = eventCall
                offerSdp = signal.sdp
                _info.value = CallInfo(state = "ringing", peer = signal.sender, peerName = signal.name.ifBlank { name ?: "A friend" })
                onActive(true)
                ring = Ringer.incoming(context)
                startTimeout()
                onIncoming(_info.value.peerName)
            }
            eventCall != callId -> return
            kind == "answer" && i.state == "calling" -> {
                stopRing(); timeout?.cancel(); moving = false
                set { it.copy(state = "in-call", startedAt = System.currentTimeMillis()) }
                try {
                    val r = Sdp.read(api.readCallSignal(sid).sdp)
                    remoteCamera = r.camera; remoteScreen = r.screen
                    pc?.setRemote(SessionDescription.Type.ANSWER, r.sdp)
                } catch (err: Exception) { hangUp(err.message ?: "Couldn't connect the call") }
            }
            kind == "renegotiate" -> {
                // the other side turned their camera or screen sharing on or off
                val pc = pc ?: return
                val r = Sdp.read(api.readCallSignal(sid).sdp)
                if (pc.signalingState() != PeerConnection.SignalingState.STABLE || making) {
                    // both changed something at once: the caller keeps its offer, the one who answered gives way
                    if (i.state == "in-call" && callerSide && !r.noYield) return
                    if (pc.signalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER) pc.rollback()
                    pending = true
                }
                remoteCamera = r.camera; remoteScreen = r.screen
                pc.setRemote(SessionDescription.Type.OFFER, r.sdp)
                if (r.camera == null) set { it.copy(remoteCamera = null) }
                if (r.screen == null) set { it.copy(remoteScreen = null) }
                val sdp = Sdp.tag(pc.describe(offer = false), cam?.streamId, screen?.streamId)
                api.callSignal(i.peer, callId, "reanswer", sdp)
                if (pending) { pending = false; delay(300); renegotiate() }
            }
            kind == "reanswer" && pc?.signalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER -> {
                pc?.setRemote(SessionDescription.Type.ANSWER, api.readCallSignal(sid).sdp)
                if (pending) { pending = false; renegotiate() }
            }
            kind == "answer" && i.state == "in-call" && !moving -> end("Moved the call to another device")
            kind == "hangup" -> end(when (i.state) { "in-call" -> "${i.peerName} hung up"; "calling" -> "${i.peerName} didn't pick up"; else -> "" })
        }
    }

    /** Answer a moved call on a new connection, keeping our camera and screen share going. */
    private suspend fun replace(offerSdp: String) {
        val id = callId
        val old = pc
        val r = Sdp.read(offerSdp)
        remoteCamera = r.camera; remoteScreen = r.screen
        making = false; pending = false; callerSide = false
        set { it.copy(remoteCamera = null, remoteScreen = null) }
        try {
            val pc = newPeer(id)
            old?.close()
            pc.setRemote(SessionDescription.Type.OFFER, r.sdp)
            cam?.let { camSender = pc.addTrack(it.track, listOf(it.streamId)) }
            screen?.let { screenSender = pc.addTrack(it.track, listOf(it.streamId)) }
            val sdp = Sdp.tag(pc.describe(offer = false), cam?.streamId, screen?.streamId)
            if (callId != id) return
            api.callSignal(_info.value.peer, id, "answer", sdp)
        } catch (e: Exception) {
            if (callId == id) hangUp(e.message ?: "Couldn't move the call")
        }
    }

    /** The connection for call `id`, with the microphone added. */
    private suspend fun newPeer(id: String): PeerConnection {
        val ice = api.ice()
        if (callId != id) throw IllegalStateException("Call ended")
        Rtc.audioOn(speaker = _info.value.speaker)
        val events = object : PeerEvents() {
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                val track = receiver?.track() as? VideoTrack ?: return
                val sid = streams?.firstOrNull()?.id
                scope.launch {
                    if (sid != null && sid == remoteCamera) set { it.copy(remoteCamera = track) } else set { it.copy(remoteScreen = track) }
                }
            }
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                if (newState == PeerConnection.PeerConnectionState.FAILED) scope.launch { if (callId == id) hangUp("Couldn't connect the call") }
            }
        }
        val pc = Rtc.factory.createPeerConnection(Rtc.config(ice), events) ?: throw IllegalStateException("Couldn't start the call")
        this.pc = pc
        val m = mic ?: runCatching { Rtc.mic() }.getOrNull()
        mic = m
        if (m != null) {
            m.setEnabled(!_info.value.muted)
            micSender = pc.addTrack(m, listOf("mic"))
        } else {
            pc.addTransceiver(org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
        }
        set { it.copy(noMic = m == null) }
        return pc
    }

    private fun startTimeout() {
        timeout?.cancel()
        timeout = scope.launch {
            delay(45_000)
            when (_info.value.state) {
                "calling" -> hangUp("No answer")
                "ringing" -> end("")                 // they gave up or it was answered elsewhere
            }
        }
    }

    private fun stopRing() { ring?.stop(); ring = null }

    private fun end(reason: String) {
        timeout?.cancel(); timeout = null
        stopRing()
        pc?.close(); pc = null
        mic?.dispose(); mic = null; micSender = null
        screen?.stop(); screen = null; screenSender = null
        cam?.stop(); cam = null; camSender = null
        remoteCamera = null; remoteScreen = null
        making = false; pending = false; callerSide = false; moving = false
        callId = ""; offerSdp = ""
        _info.value = CallInfo()
        Rtc.audioOff()
        onActive(false)
        if (reason.isNotEmpty()) onEnded(reason)
    }

    private fun newCallId(): String {
        val b = ByteArray(12)
        SecureRandom().nextBytes(b)
        return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}

/** Ringing: the phone's ringtone for a call coming in, soft beeps while calling out. */
class Ringer private constructor(private val stopFn: () -> Unit) {
    fun stop() = stopFn()

    companion object {
        fun incoming(context: Context): Ringer {
            val r = runCatching { RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)) }.getOrNull()
            runCatching { r?.play() }
            return Ringer { runCatching { r?.stop() } }
        }

        fun outgoing(): Ringer {
            val tone = runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, 60) }.getOrNull()
            val job = CoroutineScope(Dispatchers.Default).launch {
                while (isActive) { tone?.startTone(ToneGenerator.TONE_SUP_RINGTONE, 1000); delay(3000) }
            }
            return Ringer { job.cancel(); runCatching { tone?.release() } }
        }
    }
}
