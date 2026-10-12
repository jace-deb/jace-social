package dev.jacedeb.jacesocial.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.jacedeb.jacesocial.data.Person
import dev.jacedeb.jacesocial.rtc.Rtc
import kotlinx.coroutines.delay
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/** A video (camera or screen) from WebRTC. */
@Composable
fun VideoView(track: VideoTrack, modifier: Modifier = Modifier, mirror: Boolean = false, fill: Boolean = false) {
    AndroidView(modifier = modifier, factory = { ctx ->
        SurfaceViewRenderer(ctx).apply {
            init(Rtc.egl.eglBaseContext, null)
            setMirror(mirror)
            setScalingType(if (fill) RendererCommon.ScalingType.SCALE_ASPECT_FILL else RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            setEnableHardwareScaler(true)
        }
    }, update = { v -> v.tag?.let { (it as VideoTrack).removeSink(v) }; track.addSink(v); v.tag = track },
        onRelease = { v -> (v.tag as? VideoTrack)?.let { runCatching { it.removeSink(v) } }; v.release() })
}

/** A round button for calls: icon text, label, red for hanging up. */
@Composable
fun CallButton(icon: String, label: String, on: Boolean = false, danger: Boolean = false, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(6.dp)) {
        Box(Modifier.size(58.dp).clip(CircleShape)
            .background(if (danger) JaceColors.red else if (on) JaceColors.text else JaceColors.panel).clickable(onClick = onClick),
            contentAlignment = Alignment.Center) { Text(icon, style = MaterialTheme.typography.titleLarge) }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = JaceColors.muted)
    }
}

/** A call with a friend: ringing, calling, or in the call (with their camera and screen). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CallScreen(onMinimize: () -> Unit) {
    val store = LocalStore.current
    val nav = LocalNav.current
    val c by store.calls.info.collectAsState()
    val peer = store.person(c.peer) ?: Person(c.peer, c.peerName)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(c.state) { while (c.state == "in-call") { now = System.currentTimeMillis(); delay(1000) } }
    Column(Modifier.fillMaxSize().background(JaceColors.bg).padding(top = 40.dp, bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            if (c.state == "in-call") TextButton(onClick = onMinimize) { Text("⌄ Back to the app") }
        }
        val remote = c.remoteScreen ?: c.remoteCamera
        if (remote != null) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                VideoView(remote, Modifier.fillMaxSize())
                if (c.remoteScreen != null && c.remoteCamera != null)
                    VideoView(c.remoteCamera!!, Modifier.align(Alignment.TopEnd).padding(8.dp).width(110.dp).aspectRatio(0.75f).clip(RoundedCornerShape(10.dp)), fill = true)
                c.camera?.let { VideoView(it, Modifier.align(Alignment.BottomEnd).padding(8.dp).width(100.dp).aspectRatio(0.75f).clip(RoundedCornerShape(10.dp)), mirror = true, fill = true) }
            }
        } else {
            Spacer(Modifier.weight(0.4f))
            Avatar(peer, 120)
            Spacer(Modifier.height(16.dp))
            Text(c.peerName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Muted(when (c.state) {
                "ringing" -> "is calling you…"
                "calling" -> "Calling…"
                else -> c.startedAt?.let { val s = (now - it) / 1000; "%d:%02d".format(s / 60, s % 60) } ?: ""
            }, small = false)
            if (c.noMic && c.state == "in-call") Muted("No microphone - you can listen, but they can't hear you")
            c.camera?.let { VideoView(it, Modifier.padding(16.dp).width(140.dp).aspectRatio(0.75f).clip(RoundedCornerShape(12.dp)), mirror = true, fill = true) }
            Spacer(Modifier.weight(0.6f))
        }
        FlowRow(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            when (c.state) {
                "ringing" -> {
                    CallButton("✕", "Decline", danger = true) { store.calls.hangUp() }
                    Spacer(Modifier.width(40.dp))
                    CallButton("📞", "Answer", on = true) { nav.withMic { store.calls.answer() } }
                }
                "calling" -> CallButton("✕", "Cancel", danger = true) { store.calls.hangUp() }
                else -> {
                    CallButton(if (c.muted) "🔇" else "🎙", if (c.muted) "Unmute" else "Mute", on = c.muted) { store.calls.toggleMute() }
                    CallButton("🔊", if (c.speaker) "Speaker on" else "Speaker", on = c.speaker) { store.calls.toggleSpeaker() }
                    CallButton("📷", if (c.camera != null) "Stop video" else "Video", on = c.camera != null) {
                        if (c.camera != null) store.calls.toggleCamera() else nav.withCamera { store.calls.toggleCamera() }
                    }
                    CallButton("🖥", if (c.sharing) "Stop sharing" else "Share screen", on = c.sharing) {
                        if (c.sharing) store.stopScreenShare(inRoom = false) else nav.screenShare { store.startScreenShare(it, inRoom = false) }
                    }
                    CallButton("✕", "Hang up", danger = true) { store.calls.hangUp() }
                }
            }
        }
    }
}

/** A voice room: who's in it (speaking, muted), cameras and screens, and the controls. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoiceRoomScreen() {
    val store = LocalStore.current
    val nav = LocalNav.current
    val r by store.voice.state.collectAsState()
    val me by store.me.collectAsState()
    if (r.channelId == null) {
        LaunchedEffect(Unit) { nav.back() }
        return
    }
    val videos = (r.screens.map { (u, t) -> Triple(u, t, "screen") } + r.cameras.map { (u, t) -> Triple(u, t, "camera") })
    Page(r.channelName, scroll = false) {
        if (r.connecting) Muted("Connecting…")
        if (r.noMic) Muted("No microphone - you can listen, but nobody can hear you")
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(videos, key = { it.first + it.third }) { (uuid, track, kind) ->
                val who = r.participants.firstOrNull { it.uuid == uuid }?.person ?: store.person(uuid)
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Color.Black)) {
                    VideoView(track, Modifier.fillMaxSize(), mirror = uuid == me?.uuid && kind == "camera")
                    Text("${if (kind == "screen") "🖥 " else ""}${who?.shownName ?: "Someone"}${if (uuid == me?.uuid) " (you)" else ""}",
                        color = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
                            .background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
            items(r.participants, key = { "p" + it.uuid }) { p ->
                val person = p.person ?: store.person(p.uuid) ?: Person(p.uuid, "Someone")
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { nav.profile(p.uuid, r.serverId) }.padding(vertical = 6.dp)) {
                    Avatar(person, 40)
                    Spacer(Modifier.width(12.dp))
                    Text(person.shownName + if (p.uuid == me?.uuid) " (you)" else "", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (p.streaming) Text("🖥 ")
                    if (p.deafened) Text("🔇") else if (p.muted) Text("🎙✕")
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
            CallButton(if (r.muted) "🔇" else "🎙", if (r.muted) "Unmute" else "Mute", on = r.muted) { store.voice.toggleMute() }
            CallButton("🎧", if (r.deafened) "Undeafen" else "Deafen", on = r.deafened) { store.voice.toggleDeafen() }
            CallButton("🔊", "Speaker", on = r.speaker) { store.voice.toggleSpeaker() }
            if (r.canVideo) {
                CallButton("📷", if (r.camera) "Stop video" else "Video", on = r.camera) {
                    if (r.camera) store.voice.toggleCamera() else nav.withCamera { store.voice.toggleCamera() }
                }
                CallButton("🖥", if (r.sharing) "Stop sharing" else "Share screen", on = r.sharing) {
                    if (r.sharing) store.stopScreenShare(inRoom = true) else nav.screenShare { store.startScreenShare(it, inRoom = true) }
                }
            }
            CallButton("✕", "Leave", danger = true) { store.voice.leave() }
        }
    }
}
