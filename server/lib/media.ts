"use client";
// Camera and screen share both arrive as video, so each description we send says which of
// our streams is which, in one extra line ("a=x-jace-streams:camera=<id>,screen=<id>").
// The receiver reads and removes that line before handing the description to the browser.
// Stream ids survive the trip (they're the "msid" in the description), so the receiver can
// match each incoming video to its kind.
// An offer can also carry "a=x-jace-move": the call is moving to another of the caller's devices
// (e.g. from Jace Launcher, which is voice only, to here to see video), so it replaces the call
// in progress instead of ringing.
// "a=x-jace-noyield" comes from Jace Launcher, which can't take back an offer it sent: when both
// sides change something at once, the other side always gives way to it.

export type StreamKinds = { camera?: string; screen?: string };

const TAG = "a=x-jace-streams:";
const MOVE = "a=x-jace-move";
const NO_YIELD = "a=x-jace-noyield";

export function tagStreams(sdp: string, kinds: StreamKinds): string {
  const parts = Object.entries(kinds).filter(([, id]) => id).map(([k, id]) => `${k}=${id}`);
  if (!parts.length) return sdp;
  return sdp.replace(/\r?\n?$/, "\r\n") + `${TAG}${parts.join(",")}\r\n`;
}

export function markMove(sdp: string): string {
  return sdp.replace(/\r?\n?$/, "\r\n") + `${MOVE}\r\n`;
}

export function readStreams(sdp: string): { sdp: string; kinds: StreamKinds; moving: boolean; noYield: boolean } {
  const kinds: StreamKinds = {};
  let moving = false, noYield = false;
  const lines = sdp.split(/\r?\n/).filter((line) => {
    if (line === MOVE) { moving = true; return false; }
    if (line === NO_YIELD) { noYield = true; return false; }
    if (!line.startsWith(TAG)) return !line.startsWith("a=x-jace-");   // other tags (from a newer version): not for the browser
    for (const part of line.slice(TAG.length).split(",")) {
      const [k, id] = part.split("=");
      if ((k === "camera" || k === "screen") && id) kinds[k] = id.trim();
    }
    return false;
  });
  return { sdp: lines.join("\r\n"), kinds, moving, noYield };
}

/** Your camera, at a size that's fine for several people at once. */
export function getCamera(): Promise<MediaStream> {
  return navigator.mediaDevices.getUserMedia({ video: { width: { ideal: 640 }, height: { ideal: 360 }, frameRate: { ideal: 24 } }, audio: false });
}

export const canUseCamera = () => typeof navigator !== "undefined" && !!navigator.mediaDevices?.getUserMedia;
