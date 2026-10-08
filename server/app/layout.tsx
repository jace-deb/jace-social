import "./globals.css";

export const metadata = { title: "Jace Social", description: "Friends, chat and servers - with Minecraft built in" };
export const viewport = { themeColor: "#111317", width: "device-width", initialScale: 1 };

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
