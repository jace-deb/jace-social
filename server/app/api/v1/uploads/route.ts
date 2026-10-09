// POST {name, type, size}: a one-time signed URL to upload a file for a message (see lib/uploads.ts)
//   -> {path, token, upload_url, url}; then send the message with attachments: [{url, name, type, size}]
import { ApiError, body, handler, me } from "@/lib/server";
import { signUpload } from "@/lib/uploads";

export const POST = handler(async (req) => {
  const p = await me(req);
  const b = await body<{ name?: string; type?: string; size?: number }>(req);
  if (!b.name) throw new ApiError(400, "name is required");
  return signUpload(p.uuid, String(b.name), String(b.type ?? "application/octet-stream"), Number(b.size));
});
