import type { NextConfig } from "next";

const config: NextConfig = {
  // hosted code bots' sandbox (lib/codebots.ts) loads its WebAssembly file at runtime: keep the
  // packages as they are so the .wasm ships with the server functions
  serverExternalPackages: ["quickjs-emscripten", "quickjs-emscripten-core", "@jitl/quickjs-wasmfile-release-sync"],
};
export default config;
