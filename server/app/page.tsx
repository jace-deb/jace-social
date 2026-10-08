export default function Home() {
  return (
    <main className="center">
      <div className="signin">
        <h1>⛏ Jace Social</h1>
        <p>Friends, chat and servers - with Minecraft built in. Works in Jace Launcher, in game with the Jace Social mod,
          on the web and as a desktop app.</p>
        <a className="btn primary" style={{ display: "block", padding: 12, textDecoration: "none", margin: "20px 0 10px" }} href="/app">Open Jace Social</a>
        <a className="muted small" href="https://jace-deb.github.io/jace-social/">Downloads and more</a>
      </div>
    </main>
  );
}
