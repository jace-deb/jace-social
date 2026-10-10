"use client";
// Camera and screen share both arrive as video, so each description we send says which of
// our streams is which, in one extra line ("a=x-jace-streams:camera=<id>,screen=<id>").
// The receiver reads and removes that line before handing the description to the browser.
// Stream ids survive the trip (they're the "msid" in the description), so the receiver can
// match each incoming video to its kind.

export type StreamKinds = { camera?: string; screen?: string };

const TAG = "a=x-jace-streams:";

export function tagStreams(sdp: string, kinds: StreamKinds): string {
  const parts = Object.entries(kinds).filter(([, id]) => id).map(([k, id]) => `${k}=${id}`);
  if (!parts.length) return sdp;
  return sdp.replace(/\r?\n?$/, "\r\n") + `${TAG}${parts.join(",")}\r\n`;
}

export function readStreams(sdp: string): { sdp: string; kinds: StreamKinds } {
  const kinds: StreamKinds = {};
  const lines = sdp.split(/\r?\n/).filter((line) => {
    if (!line.startsWith(TAG)) return true;
    for (const part of line.slice(TAG.length).split(",")) {
      const [k, id] = part.split("=");
      if ((k === "camera" || k === "screen") && id) kinds[k] = id.trim();
    }
    return false;
  });
  return { sdp: lines.join("\r\n"), kinds };
}

/** Your camera, at a size that's fine for several people at once. */
export function getCamera(): Promise<MediaStream> {
  return navigator.mediaDevices.getUserMedia({ video: { width: { ideal: 640 }, height: { ideal: 360 }, frameRate: { ideal: 24 } }, audio: false });
}

export const canUseCamera = () => typeof navigator !== "undefined" && !!navigator.mediaDevices?.getUserMedia;
