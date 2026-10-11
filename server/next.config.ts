import type { NextConfig } from "next";

const config: NextConfig = {
  // the build an open page came from (see components/NewVersion.tsx)
  env: { NEXT_PUBLIC_BUILD: process.env.VERCEL_GIT_COMMIT_SHA ?? "dev" },
  // hosted code bots' sandbox (lib/codebots.ts) loads its WebAssembly file at runtime: keep the
  // packages as they are so the .wasm ships with the server functions
  serverExternalPackages: ["quickjs-emscripten", "quickjs-emscripten-core", "@jitl/quickjs-wasmfile-release-sync"],
};
export default config;
