"use client";
// Voice rooms (server voice channels and group calls): everyone in the room connects to
// everyone else with WebRTC (a mesh, fine up to ~8 people). The one who joins starts the
// connections. Screen sharing adds a video track and renegotiates each connection; if
// two people renegotiate at once, the one with the smaller id gives way ("polite peer").
// Like direct calls, each description is sent once ICE gathering is done (no trickle).
import { api } from "./client";

export type Participant = { uuid: string; muted: boolean; deafened: boolean; streaming: boolean; person?: { name: string; avatar_url: string | null } };
export type RoomState = {
  channelId: string | null; serverId: string | null; channelName: string;
  participants: Participant[]; muted: boolean; deafened: boolean; sharing: boolean; noMic: boolean;
  speaking: Record<string, boolean>; screens: Record<string, MediaStream>; connecting: boolean;
};

type Peer = { pc: RTCPeerConnection; audio: HTMLAudioElement; screen: MediaStream | null; screenSenders: RTCRtpSender[] };

const EMPTY: RoomState = { channelId: null, serverId: null, channelName: "", participants: [], muted: false, deafened: false,
  sharing: false, noMic: false, speaking: {}, screens: {}, connecting: false };

async function described(pc: RTCPeerConnection, desc: RTCSessionDescriptionInit) {
  await pc.setLocalDescription(desc);
  if (pc.iceGatheringState !== "complete") {
    await new Promise<void>((ok) => {
      const t = setTimeout(ok, 5000);
      pc.addEventListener("icegatheringstatechange", () => { if (pc.iceGatheringState === "complete") { clearTimeout(t); ok(); } });
    });
  }
  return pc.localDescription!.sdp;
}

export class Voice {
  state: RoomState = EMPTY;
  private me = "";
  private peers = new Map<string, Peer>();
  private mic: MediaStream | null = null;
  private screen: MediaStream | null = null;
  private ice: RTCIceServer[] = [];
  private beat: ReturnType<typeof setInterval> | null = null;
  private meter: ReturnType<typeof setInterval> | null = null;
  private audioCtx: AudioContext | null = null;
  private analysers = new Map<string, AnalyserNode>();
  private listeners = new Set<(s: RoomState) => void>();

  constructor(private onError: (m: string) => void) {}

  subscribe(fn: (s: RoomState) => void) { this.listeners.add(fn); return () => { this.listeners.delete(fn); }; }
  private set(patch: Partial<RoomState>) { this.state = { ...this.state, ...patch }; for (const fn of this.listeners) fn(this.state); }

  async join(me: string, channelId: string, serverId: string | null, channelName: string) {
    if (this.state.channelId === channelId) return;
    if (this.state.channelId) await this.leave();
    this.me = me;
    this.set({ ...EMPTY, channelId, serverId, channelName, connecting: true });
    try {
      const [{ ice_servers }, room] = await Promise.all([
        api<{ ice_servers: RTCIceServer[] }>("/calls/ice"),
        api<{ participants: Participant[]; can_speak: boolean }>(`/channels/${channelId}/voice`, { body: { action: "join" } }),
      ]);
      this.ice = ice_servers;
      try {
        this.mic = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true } });
        if (!room.can_speak) this.mic.getAudioTracks().forEach((t) => { t.enabled = false; });
        this.watchLevel(me, this.mic);
      } catch {
        this.set({ noMic: true });
      }
      this.set({ participants: room.participants, muted: !room.can_speak, connecting: false });
      // the one who joins connects to everyone already here
      for (const p of room.participants) if (p.uuid !== me) await this.call(p.uuid);
      this.beat = setInterval(() => void this.sendState({}), 20_000);
      this.meter = setInterval(() => this.measure(), 150);
    } catch (e) {
      this.onError((e as Error).message);
      await this.leave();
    }
  }

  async leave() {
    const id = this.state.channelId;
    if (this.beat) clearInterval(this.beat);
    if (this.meter) clearInterval(this.meter);
    this.beat = this.meter = null;
    for (const uuid of [...this.peers.keys()]) this.dropPeer(uuid);
    this.mic?.getTracks().forEach((t) => t.stop());
    this.screen?.getTracks().forEach((t) => t.stop());
    this.mic = this.screen = null;
    void this.audioCtx?.close().catch(() => {});
    this.audioCtx = null;
    this.analysers.clear();
    this.set(EMPTY);
    if (id) await api(`/channels/${id}/voice`, { body: { action: "leave" } }).catch(() => {});
  }

  async toggleMute() {
    const muted = !this.state.muted;
    this.mic?.getAudioTracks().forEach((t) => { t.enabled = !muted && !this.state.deafened; });
    this.set({ muted });
    await this.sendState({ muted });
  }

  async toggleDeafen() {
    const deafened = !this.state.deafened;
    for (const p of this.peers.values()) p.audio.muted = deafened;
    this.mic?.getAudioTracks().forEach((t) => { t.enabled = !deafened && !this.state.muted; });
    this.set({ deafened });
    await this.sendState({ deafened });
  }

  async toggleScreen() {
    if (this.state.sharing) return this.stopScreen();
    try {
      this.screen = await navigator.mediaDevices.getDisplayMedia({ video: { frameRate: 30 }, audio: true });
    } catch {
      return;   // they cancelled the picker
    }
    this.screen.getVideoTracks()[0]?.addEventListener("ended", () => void this.stopScreen());
    this.set({ sharing: true, screens: { ...this.state.screens, [this.me]: this.screen } });
    for (const [uuid, peer] of this.peers) {
      peer.screenSenders = this.screen.getTracks().map((t) => peer.pc.addTrack(t, this.screen!));
      await this.renegotiate(uuid);
    }
    await this.sendState({ streaming: true });
  }

  async stopScreen() {
    if (!this.screen) return;
    this.screen.getTracks().forEach((t) => t.stop());
    this.screen = null;
    const screens = { ...this.state.screens };
    delete screens[this.me];
    this.set({ sharing: false, screens });
    for (const [uuid, peer] of this.peers) {
      for (const s of peer.screenSenders) { try { peer.pc.removeTrack(s); } catch { /* closed */ } }
      peer.screenSenders = [];
      await this.renegotiate(uuid);
    }
    await this.sendState({ streaming: false });
  }

  /** Live "voice" event: who's in the room changed. */
  async refresh() {
    if (!this.state.channelId) return;
    try {
      const { participants } = await api<{ participants: Participant[] }>(`/channels/${this.state.channelId}/voice`);
      if (!participants.some((p) => p.uuid === this.me)) { await this.leave(); return; }     // kicked or timed out
      for (const uuid of [...this.peers.keys()]) if (!participants.some((p) => p.uuid === uuid)) this.dropPeer(uuid);
      this.set({ participants });
    } catch { /* next event */ }
  }

  /** Live "voice_signal" event: an offer or answer from someone in the room. */
  async onSignal(e: { id: number; from: string; kind: string; channel_id: string }) {
    if (e.channel_id !== this.state.channelId) return;
    try {
      const { signal } = await api<{ signal: { sdp: string; kind: string } }>(`/voice/signal?id=${e.id}`);
      if (signal.kind === "offer") {
        let peer = this.peers.get(e.from);
        if (!peer) peer = this.newPeer(e.from);
        const pc = peer.pc;
        if (pc.signalingState !== "stable") {
          // both sides offered at once: the smaller id gives way
          if (this.me > e.from) return;
          await pc.setLocalDescription({ type: "rollback" });
        }
        await pc.setRemoteDescription({ type: "offer", sdp: signal.sdp });
        const sdp = await described(pc, await pc.createAnswer());
        await api("/voice/signal", { body: { channel_id: this.state.channelId, to: e.from, kind: "answer", sdp } });
      } else {
        const peer = this.peers.get(e.from);
        if (peer && peer.pc.signalingState === "have-local-offer") await peer.pc.setRemoteDescription({ type: "answer", sdp: signal.sdp });
      }
    } catch (err) {
      console.warn("voice signal", err);
    }
  }

  // -- internals
  private newPeer(uuid: string): Peer {
    const pc = new RTCPeerConnection({ iceServers: this.ice });
    const audio = new Audio();
    audio.autoplay = true;
    audio.muted = this.state.deafened;
    const peer: Peer = { pc, audio, screen: null, screenSenders: [] };
    if (this.mic) this.mic.getAudioTracks().forEach((t) => pc.addTrack(t, this.mic!));
    else pc.addTransceiver("audio", { direction: "recvonly" });
    if (this.screen) peer.screenSenders = this.screen.getTracks().map((t) => pc.addTrack(t, this.screen!));
    pc.ontrack = (ev) => {
      if (ev.track.kind === "audio" && !peer.audio.srcObject) {
        const s = ev.streams[0] ?? new MediaStream([ev.track]);
        peer.audio.srcObject = s;
        void peer.audio.play().catch(() => {});
        this.watchLevel(uuid, s);
      } else if (ev.track.kind === "video") {
        const s = ev.streams[0] ?? new MediaStream([ev.track]);
        peer.screen = s;
        this.set({ screens: { ...this.state.screens, [uuid]: s } });
        ev.track.addEventListener("ended", () => this.clearScreen(uuid));
        s.addEventListener("removetrack", () => { if (!s.getVideoTracks().length) this.clearScreen(uuid); });
      }
    };
    pc.onconnectionstatechange = () => {
      if (pc.connectionState === "failed") { this.dropPeer(uuid); void this.call(uuid); }   // try once more
    };
    this.peers.set(uuid, peer);
    return peer;
  }

  private clearScreen(uuid: string) {
    const screens = { ...this.state.screens };
    delete screens[uuid];
    this.set({ screens });
  }

  private async call(uuid: string) {
    if (this.peers.has(uuid)) return;
    const peer = this.newPeer(uuid);
    await this.renegotiate(uuid, peer);
  }

  private async renegotiate(uuid: string, peer = this.peers.get(uuid)) {
    if (!peer || !this.state.channelId) return;
    try {
      const sdp = await described(peer.pc, await peer.pc.createOffer());
      await api("/voice/signal", { body: { channel_id: this.state.channelId, to: uuid, kind: "offer", sdp } });
    } catch (e) {
      console.warn("voice offer", e);
    }
  }

  private dropPeer(uuid: string) {
    const p = this.peers.get(uuid);
    if (!p) return;
    p.pc.close();
    p.audio.srcObject = null;
    this.peers.delete(uuid);
    this.analysers.delete(uuid);
    this.clearScreen(uuid);
  }

  private async sendState(patch: { muted?: boolean; deafened?: boolean; streaming?: boolean }) {
    if (!this.state.channelId) return;
    await api(`/channels/${this.state.channelId}/voice`, { body: { action: "state", ...patch } }).catch((e) => this.onError(e.message));
  }

  private watchLevel(uuid: string, stream: MediaStream) {
    try {
      this.audioCtx ??= new AudioContext();
      const src = this.audioCtx.createMediaStreamSource(stream);
      const an = this.audioCtx.createAnalyser();
      an.fftSize = 512;
      src.connect(an);
      this.analysers.set(uuid, an);
    } catch { /* no level meter */ }
  }

  private measure() {
    const speaking: Record<string, boolean> = {};
    const buf = new Uint8Array(256);
    for (const [uuid, an] of this.analysers) {
      an.getByteTimeDomainData(buf);
      let peak = 0;
      for (const v of buf) peak = Math.max(peak, Math.abs(v - 128));
      speaking[uuid] = peak > 10 && !(uuid === this.me && (this.state.muted || this.state.deafened));
    }
    if (JSON.stringify(speaking) !== JSON.stringify(this.state.speaking)) this.set({ speaking });
  }
}
