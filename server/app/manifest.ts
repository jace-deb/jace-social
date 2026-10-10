import type { MetadataRoute } from "next";

// "Add to Home Screen" on phones (iPhone users can install Jace Social this way)
export default function manifest(): MetadataRoute.Manifest {
  return {
    name: "Jace Social",
    short_name: "Jace Social",
    description: "Friends, chat and servers - with Minecraft built in",
    start_url: "/app",
    display: "standalone",
    background_color: "#111317",
    theme_color: "#111317",
    icons: [{ src: "/icon.png", sizes: "512x512", type: "image/png", purpose: "any" }],
  };
}
