"use client";
// Voice calls in the browser and the desktop app. Same signalling as Jace Launcher
// (POST /calls offer/answer/hangup, live "call" events), so anyone can call anyone.
// Like the launcher, each side sends its full description once ICE gathering is done
// (no trickle), and audio goes straight between players or through the TURN relay.
import { api } from "./client";

export type CallState = "idle" | "calling" | "ringing" | "in-call";
export type CallInfo = { state: CallState; peer: string; peerName: string; muted: boolean; startedAt: number | null; noMic: boolean };
type Signal = { id: number; kind: string; call_id: string; from: string; name?: string };

const IDLE: CallInfo = { state: "idle", peer: "", peerName: "", muted: false, startedAt: null, noMic: false };

function newCallId() {
  const b = new Uint8Array(12);
  crypto.getRandomValues(b);
  return btoa(String.fromCharCode(...b)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export class Calls {
  info: CallInfo = IDLE;
  private callId = "";
  private offerSdp = "";
  private pc: RTCPeerConnection | null = null;
  private mic: MediaStream | null = null;
  private audio: HTMLAudioElement | null = null;
  private timeout: ReturnType<typeof setTimeout> | null = null;
  private ring: { stop: () => void } | null = null;
  private listeners = new Set<(i: CallInfo) => void>();

  /** onEnded(reason) is shown to the user as a toast. */
  constructor(private onEnded: (reason: string) => void, private onIncoming: (name: string) => void) {}

  subscribe(fn: (i: CallInfo) => void) { this.listeners.add(fn); return () => { this.listeners.delete(fn); }; }

  static supported() { return typeof window !== "undefined" && "RTCPeerConnection" in window && !!navigator.mediaDevices?.getUserMedia; }

  async call(uuid: string, name: string) {
    if (!Calls.supported()) return this.onEnded("Voice calls don't work in this browser");
    if (this.info.state !== "idle") return this.onEnded("You're already in a call");
    this.callId = newCallId();
    const id = this.callId;
    this.set({ ...IDLE, state: "calling", peer: uuid, peerName: name });
    this.ring = tone("outgoing");
    try {
      const pc = await this.newPeer(id);
      const sdp = await describe(pc, await pc.createOffer());
      if (this.callId !== id) return;
      await api("/calls", { body: { to: uuid, call_id: id, kind: "offer", sdp } });
      this.startTimeout();
    } catch (e) {
      if (this.callId === id) this.end((e as Error).message);
    }
  }

  async answer() {
    if (this.info.state !== "ringing") return;
    const { peer } = this.info;
    const id = this.callId;
    this.stopRing();
    this.clearTimeout();
    this.set({ ...this.info, state: "in-call", startedAt: Date.now() });
    try {
      const pc = await this.newPeer(id);
      await pc.setRemoteDescription({ type: "offer", sdp: this.offerSdp });
      const sdp = await describe(pc, await pc.createAnswer());
      if (this.callId !== id) return;
      await api("/calls", { body: { to: peer, call_id: id, kind: "answer", sdp } });
    } catch (e) {
      if (this.callId === id) this.hangUp((e as Error).message);
    }
  }

  hangUp(reason = "") {
    if (this.info.state === "idle") return;
    void api("/calls", { body: { to: this.info.peer, call_id: this.callId, kind: "hangup" } }).catch(() => {});
    this.end(reason);
  }

  toggleMute() {
    const muted = !this.info.muted;
    this.mic?.getAudioTracks().forEach((t) => { t.enabled = !muted; });
    this.set({ ...this.info, muted });
  }

  /** A live "call" event from the player's channel. */
  async onSignal(e: Signal) {
    if (e.kind === "offer") {
      if (this.info.state !== "idle") {          // busy: tell them
        void api("/calls", { body: { to: e.from, call_id: e.call_id, kind: "hangup" } }).catch(() => {});
        return;
      }
      try {
        const { signal } = await api<{ signal: { sdp: string; sender: string; name: string } }>(`/calls?id=${e.id}`);
        if (this.info.state !== "idle") return;
        this.callId = e.call_id;
        this.offerSdp = signal.sdp;
        this.set({ ...IDLE, state: "ringing", peer: signal.sender, peerName: signal.name || e.name || "A friend" });
        this.ring = tone("incoming");
        this.startTimeout();
        this.onIncoming(this.info.peerName);
      } catch { /* the offer expired */ }
    } else if (e.call_id !== this.callId) {
      return;
    } else if (e.kind === "answer" && this.info.state === "calling") {
      this.stopRing();
      this.clearTimeout();
      this.set({ ...this.info, state: "in-call", startedAt: Date.now() });
      try {
        const { signal } = await api<{ signal: { sdp: string } }>(`/calls?id=${e.id}`);
        await this.pc?.setRemoteDescription({ type: "answer", sdp: signal.sdp });
      } catch (err) {
        this.hangUp((err as Error).message);
      }
    } else if (e.kind === "hangup") {
      const n = this.info.peerName;
      this.end(this.info.state === "in-call" ? `${n} hung up` : this.info.state === "calling" ? `${n} didn't pick up` : "");
    }
  }

  // -- internals
  /** The connection for call `id`, with the microphone added; throws if the call ended meanwhile. */
  private async newPeer(id: string) {
    const { ice_servers } = await api<{ ice_servers: RTCIceServer[] }>("/calls/ice");
    let mic: MediaStream | null = null;
    try {
      mic = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true } });
    } catch { /* no microphone (or not allowed): you can still listen */ }
    if (this.callId !== id) { mic?.getTracks().forEach((t) => t.stop()); throw new Error("Call ended"); }
    const pc = new RTCPeerConnection({ iceServers: ice_servers });
    this.pc = pc;
    this.mic = mic;
    if (mic) mic.getAudioTracks().forEach((t) => { t.enabled = !this.info.muted; pc.addTrack(t, mic); });
    else pc.addTransceiver("audio", { direction: "recvonly" });
    this.set({ ...this.info, noMic: !mic });
    pc.ontrack = (ev) => {
      if (!this.audio) { this.audio = new Audio(); this.audio.autoplay = true; }
      this.audio.srcObject = ev.streams[0] ?? new MediaStream([ev.track]);
      void this.audio.play().catch(() => {});
    };
    pc.onconnectionstatechange = () => {
      if (this.pc === pc && pc.connectionState === "failed") this.hangUp("Couldn't connect the call");
    };
    return pc;
  }

  private startTimeout() {
    this.clearTimeout();
    this.timeout = setTimeout(() => {
      if (this.info.state === "calling") this.hangUp("No answer");
      else if (this.info.state === "ringing") this.end("");   // they gave up or it was answered elsewhere
    }, 45_000);
  }
  private clearTimeout() { if (this.timeout) clearTimeout(this.timeout); this.timeout = null; }
  private stopRing() { this.ring?.stop(); this.ring = null; }

  private end(reason: string) {
    this.clearTimeout();
    this.stopRing();
    this.pc?.close();
    this.pc = null;
    this.mic?.getTracks().forEach((t) => t.stop());
    this.mic = null;
    if (this.audio) { this.audio.srcObject = null; this.audio = null; }
    this.callId = "";
    this.offerSdp = "";
    this.set(IDLE);
    if (reason) this.onEnded(reason);
  }

  private set(i: CallInfo) {
    this.info = i;
    for (const fn of this.listeners) fn(i);
  }
}

/** setLocalDescription, then wait (up to 5 s) for every ICE candidate to be in the description. */
async function describe(pc: RTCPeerConnection, desc: RTCSessionDescriptionInit): Promise<string> {
  await pc.setLocalDescription(desc);
  if (pc.iceGatheringState !== "complete") {
    await new Promise<void>((ok) => {
      const t = setTimeout(ok, 5000);
      pc.addEventListener("icegatheringstatechange", () => { if (pc.iceGatheringState === "complete") { clearTimeout(t); ok(); } });
    });
  }
  const sdp = pc.localDescription?.sdp;
  if (!sdp) throw new Error("Couldn't start the call");
  return sdp;
}

/** A soft ringing sound: two short beeps every 2 s (incoming), or one longer tone (outgoing). */
function tone(kind: "incoming" | "outgoing"): { stop: () => void } {
  let ctx: AudioContext | null = null;
  try { ctx = new AudioContext(); } catch { return { stop() {} }; }
  const beep = (at: number, len: number, freq: number) => {
    const o = ctx!.createOscillator();
    const g = ctx!.createGain();
    o.frequency.value = freq;
    g.gain.setValueAtTime(0, at);
    g.gain.linearRampToValueAtTime(0.08, at + 0.02);
    g.gain.setValueAtTime(0.08, at + len - 0.05);
    g.gain.linearRampToValueAtTime(0, at + len);
    o.connect(g).connect(ctx!.destination);
    o.start(at);
    o.stop(at + len);
  };
  const play = () => {
    if (!ctx) return;
    const t = ctx.currentTime + 0.05;
    if (kind === "incoming") { beep(t, 0.25, 880); beep(t + 0.35, 0.25, 880); } else beep(t, 0.9, 440);
  };
  play();
  const timer = setInterval(play, kind === "incoming" ? 2000 : 3000);
  return { stop() { clearInterval(timer); void ctx?.close(); ctx = null; } };
}
