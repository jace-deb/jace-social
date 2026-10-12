import SwiftUI
import WebRTC

/// A round button for calls.
struct CallButton: View {
    let icon: String
    let label: String
    var on = false
    var danger = false
    let tap: () -> Void
    var body: some View {
        Button(action: tap) {
            VStack(spacing: 4) {
                Image(systemName: icon).font(.title2).frame(width: 58, height: 58)
                    .background(danger ? JaceColor.red : on ? JaceColor.text : JaceColor.panel, in: Circle())
                    .foregroundStyle(danger ? .white : on ? JaceColor.bg : JaceColor.text)
                Text(label).font(.caption2).foregroundStyle(JaceColor.muted)
            }
        }
        .buttonStyle(.plain)
    }
}

/// A call with a friend: ringing, calling, or in the call (with their camera and screen).
struct CallView: View {
    @EnvironmentObject var store: Store
    let minimize: () -> Void
    @State private var now = Date()
    let tick = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        let c = store.calls.info
        let peer = store.person(c.peer) ?? Person(uuid: c.peer, name: c.peerName)
        VStack(spacing: 16) {
            HStack { if c.state == "in-call" { Button("⌄ Back to the app", action: minimize) }; Spacer() }.padding(.horizontal)
            if let remote = c.remoteScreen ?? c.remoteCamera {
                ZStack(alignment: .topTrailing) {
                    VideoView(track: remote).frame(maxWidth: .infinity, maxHeight: .infinity)
                    VStack(spacing: 8) {
                        if c.remoteScreen != nil, let cam = c.remoteCamera { VideoView(track: cam, fill: true).frame(width: 110, height: 146).clipShape(RoundedRectangle(cornerRadius: 10)) }
                        if let mine = c.camera { VideoView(track: mine, mirror: true, fill: true).frame(width: 100, height: 133).clipShape(RoundedRectangle(cornerRadius: 10)) }
                    }
                    .padding(8)
                }
            } else {
                Spacer()
                Avatar(person: peer, size: 120)
                Text(c.peerName).font(.title2.bold())
                Text(c.state == "ringing" ? "is calling you…" : c.state == "calling" ? "Calling…" : elapsed(c.startedAt)).foregroundStyle(JaceColor.muted)
                if c.noMic && c.state == "in-call" { Text("No microphone - you can listen, but they can't hear you").font(.footnote).foregroundStyle(JaceColor.muted) }
                if let mine = c.camera { VideoView(track: mine, mirror: true, fill: true).frame(width: 140, height: 186).clipShape(RoundedRectangle(cornerRadius: 12)) }
                Spacer()
            }
            HStack(spacing: 18) {
                switch c.state {
                case "ringing":
                    CallButton(icon: "xmark", label: "Decline", danger: true) { store.calls.hangUp() }
                    Spacer().frame(width: 40)
                    CallButton(icon: "phone.fill", label: "Answer", on: true) { store.calls.answer() }
                case "calling":
                    CallButton(icon: "xmark", label: "Cancel", danger: true) { store.calls.hangUp() }
                default:
                    CallButton(icon: c.muted ? "mic.slash.fill" : "mic.fill", label: c.muted ? "Unmute" : "Mute", on: c.muted) { store.calls.toggleMute() }
                    CallButton(icon: "speaker.wave.3.fill", label: "Speaker", on: c.speaker) { store.calls.toggleSpeaker() }
                    CallButton(icon: c.camera != nil ? "video.slash.fill" : "video.fill", label: c.camera != nil ? "Stop video" : "Video", on: c.camera != nil) { store.calls.toggleCamera() }
                    CallButton(icon: "phone.down.fill", label: "Hang up", danger: true) { store.calls.hangUp() }
                }
            }
            .padding(.bottom, 24)
        }
        .padding(.top, 20)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(JaceColor.bg)
        .onReceive(tick) { now = $0 }
    }

    func elapsed(_ start: Date?) -> String {
        guard let start else { return "" }
        let s = Int(now.timeIntervalSince(start))
        return String(format: "%d:%02d", s / 60, s % 60)
    }
}

/// A voice room: who's in it, cameras and screens, and the controls.
struct VoiceRoomView: View {
    @EnvironmentObject var store: Store
    @EnvironmentObject var router: Router

    var body: some View {
        let r = store.voice.state
        let me = store.me?.uuid
        VStack(spacing: 0) {
            if r.channelId == nil {
                Text("You left the voice room.").foregroundStyle(JaceColor.muted).frame(maxHeight: .infinity)
            } else {
                List {
                    if r.connecting { Text("Connecting…").foregroundStyle(JaceColor.muted) }
                    if r.noMic { Text("No microphone - you can listen, but nobody can hear you").font(.footnote).foregroundStyle(JaceColor.muted) }
                    ForEach(Array(r.screens.keys).sorted(), id: \.self) { uuid in video(uuid, r.screens[uuid]!, screen: true, me: me) }
                    ForEach(Array(r.cameras.keys).sorted(), id: \.self) { uuid in video(uuid, r.cameras[uuid]!, screen: false, me: me) }
                    ForEach(r.participants) { p in
                        let person = p.person ?? store.person(p.uuid) ?? Person(uuid: p.uuid, name: "Someone")
                        NavigationLink(value: Route.profile(p.uuid, r.serverId)) {
                            HStack {
                                Avatar(person: person, size: 40)
                                Text(person.shownName + (p.uuid == me ? " (you)" : "")).fontWeight(.semibold)
                                Spacer()
                                if p.streaming == true { Image(systemName: "display") }
                                if p.deafened == true { Image(systemName: "speaker.slash.fill") } else if p.muted == true { Image(systemName: "mic.slash.fill") }
                            }
                        }
                        .rowBg()
                    }
                }
                .jaceList()
                HStack(spacing: 14) {
                    CallButton(icon: r.muted ? "mic.slash.fill" : "mic.fill", label: r.muted ? "Unmute" : "Mute", on: r.muted) { Task { await store.voice.toggleMute() } }
                    CallButton(icon: "headphones", label: r.deafened ? "Undeafen" : "Deafen", on: r.deafened) { Task { await store.voice.toggleDeafen() } }
                    CallButton(icon: "speaker.wave.3.fill", label: "Speaker", on: r.speaker) { store.voice.toggleSpeaker() }
                    if r.canVideo { CallButton(icon: r.camera ? "video.slash.fill" : "video.fill", label: r.camera ? "Stop video" : "Video", on: r.camera) { Task { await store.voice.toggleCamera() } } }
                    CallButton(icon: "phone.down.fill", label: "Leave", danger: true) { Task { await store.voice.leave(); router.back() } }
                }
                .padding(.vertical, 12)
            }
        }
        .background(JaceColor.bg)
        .navigationTitle(r.channelName.isEmpty ? "Voice" : r.channelName)
        .navigationBarTitleDisplayMode(.inline)
    }

    func video(_ uuid: String, _ track: RTCVideoTrack, screen: Bool, me: String?) -> some View {
        let who = store.voice.state.participants.first { $0.uuid == uuid }?.person ?? store.person(uuid)
        return ZStack(alignment: .bottomLeading) {
            VideoView(track: track, mirror: uuid == me && !screen).frame(height: 200).background(Color.black).clipShape(RoundedRectangle(cornerRadius: 12))
            Text("\(screen ? "🖥 " : "")\(who?.shownName ?? "Someone")\(uuid == me ? " (you)" : "")")
                .font(.caption).padding(.horizontal, 6).padding(.vertical, 2).background(.black.opacity(0.6), in: RoundedRectangle(cornerRadius: 6))
                .foregroundStyle(.white).padding(8)
        }
        .listRowInsets(EdgeInsets(top: 4, leading: 8, bottom: 4, trailing: 8))
        .listRowBackground(JaceColor.bg)
    }
}
