package dev.jacedeb.jacesocial.rtc

import android.content.Intent
import dev.jacedeb.jacesocial.data.Api
import dev.jacedeb.jacesocial.data.IceServer
import dev.jacedeb.jacesocial.data.LiveEvent
import dev.jacedeb.jacesocial.data.Participant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.AudioTrack
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack

data class RoomState(
    val channelId: String? = null, val serverId: String? = null, val channelName: String = "",
    val participants: List<Participant> = emptyList(),
    val muted: Boolean = false, val deafened: Boolean = false, val sharing: Boolean = false, val noMic: Boolean = false,
    val connecting: Boolean = false, val camera: Boolean = false, val canVideo: Boolean = true, val speaker: Boolean = true,
    /** who has their camera on / is sharing their screen (you included): uuid -> video */
    val cameras: Map<String, VideoTrack> = emptyMap(), val screens: Map<String, VideoTrack> = emptyMap(),
)

/** Voice rooms (server voice channels and calls in group chats): everyone connects to everyone
 *  (a mesh, fine up to ~8 people). The one who joins starts the connections. If two people
 *  change something at once, the smaller id gives way. See server/lib/voice.ts. */
class Voice(private val api: Api, private val onError: (String) -> Unit, private val onActive: (Boolean) -> Unit) {
    private class Peer(val pc: PeerConnection) {
        var audio: AudioTrack? = null
        var screenSender: RtpSender? = null
        var cameraSender: RtpSender? = null
        var camera: String? = null           // which of their streams is the camera / screen
        var screen: String? = null
        var making = false
        var pending = false
    }

    private val scope = CoroutineScope(Dispatchers.Main)
    private val _state = MutableStateFlow(RoomState())
    val state: StateFlow<RoomState> = _state
    private var me = ""
    private val peers = mutableMapOf<String, Peer>()
    private var mic: AudioTrack? = null
    private var screen: Rtc.Video? = null
    private var cam: Rtc.Video? = null
    private var ice: List<IceServer> = emptyList()
    private var beat: Job? = null

    private fun set(f: (RoomState) -> RoomState) { _state.value = f(_state.value) }

    fun join(myUuid: String, channelId: String, serverId: String?, channelName: String) = scope.launch {
        if (_state.value.channelId == channelId) return@launch
        if (_state.value.channelId != null) leaveNow()
        me = myUuid
        _state.value = RoomState(channelId = channelId, serverId = serverId, channelName = channelName, connecting = true)
        onActive(true)
        try {
            ice = api.ice()
            val room = api.voiceJoin(channelId)
            Rtc.audioOn(speaker = true)
            mic = runCatching { Rtc.mic() }.getOrNull()
            mic?.setEnabled(room.can_speak)
            set { it.copy(participants = room.participants, muted = !room.can_speak, connecting = false, canVideo = room.can_stream, noMic = mic == null) }
            // the one who joins connects to everyone already here
            for (p in room.participants) if (p.uuid != me) connect(p.uuid)
            beat = scope.launch { while (isActive) { delay(20_000); sendState() } }
        } catch (e: Exception) {
            onError(e.message ?: "Couldn't join")
            leaveNow()
        }
    }

    fun leave() = scope.launch { leaveNow() }

    private suspend fun leaveNow() {
        val id = _state.value.channelId
        beat?.cancel(); beat = null
        for (uuid in peers.keys.toList()) drop(uuid)
        mic?.dispose(); mic = null
        screen?.stop(); screen = null
        cam?.stop(); cam = null
        _state.value = RoomState()
        Rtc.audioOff()
        onActive(false)
        if (id != null) api.voiceLeave(id)
    }

    fun toggleMute() = scope.launch {
        val muted = !_state.value.muted
        mic?.setEnabled(!muted && !_state.value.deafened)
        set { it.copy(muted = muted) }
        sendState(muted = muted)
    }

    fun toggleDeafen() = scope.launch {
        val deafened = !_state.value.deafened
        for (p in peers.values) p.audio?.setEnabled(!deafened)
        mic?.setEnabled(!deafened && !_state.value.muted)
        set { it.copy(deafened = deafened) }
        sendState(deafened = deafened)
    }

    fun toggleSpeaker() {
        val on = !_state.value.speaker
        Rtc.setSpeaker(on)
        set { it.copy(speaker = on) }
    }

    /** Turn your camera on or off for everyone in the room. */
    fun toggleCamera() = scope.launch {
        if (_state.value.channelId == null) return@launch
        val c = cam
        if (c != null) {
            for ((uuid, p) in peers) { p.cameraSender?.let { runCatching { p.pc.removeTrack(it) } }; p.cameraSender = null; renegotiate(uuid) }
            c.stop(); cam = null
            set { it.copy(camera = false, cameras = it.cameras - me) }
            return@launch
        }
        val v = Rtc.camera() ?: return@launch onError("Couldn't turn on your camera - check that it's allowed")
        cam = v
        set { it.copy(camera = true, cameras = it.cameras + (me to v.track)) }
        for ((uuid, p) in peers) { p.cameraSender = p.pc.addTrack(v.track, listOf(v.streamId)); renegotiate(uuid) }
    }

    /** Share your screen (permission = the system's screen capture consent), or stop. */
    fun toggleScreen(permission: Intent?) = scope.launch {
        val s = screen
        if (s != null) {
            for ((uuid, p) in peers) { p.screenSender?.let { runCatching { p.pc.removeTrack(it) } }; p.screenSender = null; renegotiate(uuid) }
            s.stop(); screen = null
            set { it.copy(sharing = false, screens = it.screens - me) }
            sendState(streaming = false)
            return@launch
        }
        permission ?: return@launch
        val v = Rtc.screen(permission) { scope.launch { if (screen != null) toggleScreen(null) } }
        screen = v
        set { it.copy(sharing = true, screens = it.screens + (me to v.track)) }
        for ((uuid, p) in peers) { p.screenSender = p.pc.addTrack(v.track, listOf(v.streamId)); renegotiate(uuid) }
        sendState(streaming = true)
    }

    /** Live "voice" event: who's in the room changed. */
    fun refresh() = scope.launch {
        val id = _state.value.channelId ?: return@launch
        try {
            val participants = api.voiceRoom(id)
            if (participants.none { it.uuid == me }) { leaveNow(); return@launch }      // kicked or timed out
            for (uuid in peers.keys.toList()) if (participants.none { it.uuid == uuid }) drop(uuid)
            set { it.copy(participants = participants) }
        } catch (_: Exception) { /* the next event */ }
    }

    /** Live "voice_signal" event: an offer or answer from someone in the room. */
    fun onSignal(e: LiveEvent) = scope.launch {
        if (e.str("channel_id") != _state.value.channelId) return@launch
        val from = e.str("from") ?: return@launch
        val sid = (e.payload["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: return@launch
        try {
            val signal = api.readVoiceSignal(sid)
            if (signal.kind == "offer") {
                val isNew = !peers.containsKey(from)
                val peer = peers[from] ?: newPeer(from)
                val pc = peer.pc
                val r = Sdp.read(signal.sdp)
                if (pc.signalingState() != PeerConnection.SignalingState.STABLE || peer.making) {
                    // both changed something at once: the bigger id keeps its offer, the smaller one gives way
                    if (me > from && !r.noYield) return@launch
                    if (pc.signalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER) pc.rollback()
                    peer.pending = true
                }
                peer.camera = r.camera; peer.screen = r.screen
                pc.setRemote(SessionDescription.Type.OFFER, r.sdp)
                if (r.camera == null) set { it.copy(cameras = it.cameras - from) }
                if (r.screen == null) set { it.copy(screens = it.screens - from) }
                val sdp = Sdp.tag(pc.describe(offer = false), cam?.streamId, screen?.streamId)
                api.voiceSignal(_state.value.channelId ?: return@launch, from, "answer", sdp)
                // they joined while our camera or screen was on, or our own change gave way: offer ours now
                if ((isNew && (cam != null || screen != null)) || peer.pending) {
                    peer.pending = false
                    delay(300)
                    renegotiate(from)
                }
            } else {
                val peer = peers[from] ?: return@launch
                if (peer.pc.signalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
                    val r = Sdp.read(signal.sdp)
                    if (r.camera != null) peer.camera = r.camera
                    if (r.screen != null) peer.screen = r.screen
                    peer.pc.setRemote(SessionDescription.Type.ANSWER, r.sdp)
                    if (peer.pending) { peer.pending = false; renegotiate(from) }
                }
            }
        } catch (err: Exception) {
            android.util.Log.w("Voice", "signal", err)
        }
    }

    private fun newPeer(uuid: String): Peer {
        lateinit var peer: Peer
        val events = object : PeerEvents() {
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                val track = receiver?.track() ?: return
                val sid = streams?.firstOrNull()?.id
                scope.launch {
                    when (track) {
                        is AudioTrack -> { peer.audio = track; track.setEnabled(!_state.value.deafened) }
                        is VideoTrack -> if (sid != null && sid == peer.camera) set { it.copy(cameras = it.cameras + (uuid to track)) }
                                         else set { it.copy(screens = it.screens + (uuid to track)) }
                    }
                }
            }
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                if (newState == PeerConnection.PeerConnectionState.FAILED) scope.launch { drop(uuid); connect(uuid) }   // try once more
            }
        }
        val pc = Rtc.factory.createPeerConnection(Rtc.config(ice), events) ?: throw IllegalStateException("Couldn't connect")
        peer = Peer(pc)
        val m = mic
        if (m != null) pc.addTrack(m, listOf("mic"))
        else pc.addTransceiver(org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
        screen?.let { peer.screenSender = pc.addTrack(it.track, listOf(it.streamId)) }
        cam?.let { peer.cameraSender = pc.addTrack(it.track, listOf(it.streamId)) }
        peers[uuid] = peer
        return peer
    }

    private suspend fun connect(uuid: String) {
        if (peers.containsKey(uuid)) return
        newPeer(uuid)
        renegotiate(uuid)
    }

    /** Send our current camera / screen / mic setup to one person. One change at a time. */
    private suspend fun renegotiate(uuid: String) {
        val peer = peers[uuid] ?: return
        val channel = _state.value.channelId ?: return
        if (peer.making || peer.pc.signalingState() != PeerConnection.SignalingState.STABLE) { peer.pending = true; return }
        peer.making = true
        try {
            val sdp = Sdp.tag(peer.pc.describe(offer = true), cam?.streamId, screen?.streamId)
            api.voiceSignal(channel, uuid, "offer", sdp)
        } catch (e: Exception) {
            android.util.Log.w("Voice", "offer", e)
        } finally {
            peer.making = false
        }
    }

    private fun drop(uuid: String) {
        val p = peers.remove(uuid) ?: return
        p.pc.close()
        set { it.copy(cameras = it.cameras - uuid, screens = it.screens - uuid) }
    }

    private suspend fun sendState(muted: Boolean? = null, deafened: Boolean? = null, streaming: Boolean? = null) {
        val id = _state.value.channelId ?: return
        try { api.voiceState(id, muted, deafened, streaming) } catch (e: Exception) { onError(e.message ?: "Lost the voice room") }
    }
}
