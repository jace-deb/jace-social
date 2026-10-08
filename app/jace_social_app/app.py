"""The desktop window. It shows the Jace Social web app and adds what a browser can't:
Microsoft (Minecraft) sign-in, system notifications, a tray icon and an unread badge."""
import json
import os
import sys
import threading
from pathlib import Path

from PySide6.QtCore import QObject, Property, QStandardPaths, Qt, QTimer, QUrl, Signal, Slot
from PySide6.QtGui import QAction, QDesktopServices, QIcon
from PySide6.QtNetwork import QLocalServer, QLocalSocket
from PySide6.QtWebChannel import QWebChannel
from PySide6.QtWebEngineCore import QWebEnginePage, QWebEngineProfile, QWebEngineScript, QWebEngineSettings
from PySide6.QtWebEngineWidgets import QWebEngineView
from PySide6.QtWidgets import QApplication, QDialog, QMainWindow, QMenu, QMessageBox, QSystemTrayIcon, QVBoxLayout

from jace_social_app import APP_NAME, APP_VERSION, minecraft

BASE = os.environ.get("JACE_SOCIAL_URL", "https://jace-social.vercel.app").rstrip("/")
# pages that open inside the app; everything else goes to your normal browser
IN_APP_HOSTS = {QUrl(BASE).host(), "jaceo.vercel.app"}
ASSETS = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent.parent)) / "assets"


def data_dir() -> Path:
    p = Path(QStandardPaths.writableLocation(QStandardPaths.StandardLocation.AppDataLocation)) / "jace-social"
    p.mkdir(parents=True, exist_ok=True)
    return p


# JavaScript put into every page: window.jaceDesktop, backed by the Python Bridge below.
BRIDGE_JS = """
(function () {
  const pending = {}; let next = 0;
  window.jaceDesktopPending = true;
  new QWebChannel(qt.webChannelTransport, function (ch) {
    const b = ch.objects.bridge;
    b.reply.connect(function (id, text) { const ok = pending[id]; delete pending[id]; if (ok) ok(JSON.parse(text)); });
    const call = function (fn) {
      const args = Array.prototype.slice.call(arguments, 1);
      return new Promise(function (ok) { const id = ++next; pending[id] = ok; b[fn].apply(b, [id].concat(args)); });
    };
    window.jaceDesktop = {
      version: b.version,
      signInMinecraft: function () { return call("signInMinecraft"); },
      linkMinecraft: function (token) { return call("linkMinecraft", token); },
      notify: function (title, body) { b.notify(String(title), String(body)); },
      setUnread: function (n) { b.setUnread(Number(n) || 0); },
    };
    window.dispatchEvent(new Event("jacedesktop"));
  });
})();
"""


class MicrosoftLogin(QDialog):
    """Microsoft's sign-in page; finishes when it redirects with ?code=."""

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setWindowTitle("Sign in with Microsoft")
        self.resize(520, 680)
        self.url, self.redirect = minecraft.login_url()
        self.code = None
        self.error = None
        self.profile = QWebEngineProfile()                 # private: every sign-in can pick another account
        self.page = QWebEnginePage(self.profile)
        self.view = QWebEngineView(self)
        self.view.setPage(self.page)
        self.view.urlChanged.connect(self._changed)
        self.view.setUrl(QUrl(self.url))
        lay = QVBoxLayout(self)
        lay.setContentsMargins(0, 0, 0, 0)
        lay.addWidget(self.view)

    def _changed(self, url: QUrl):
        s = url.toString()
        if not s.startswith(self.redirect) or self.code or self.error:
            return
        try:
            self.code = minecraft.code_from_redirect(s)
        except minecraft.AuthError as e:
            self.error = str(e)
        QTimer.singleShot(0, self.accept if self.code else self.reject)

    def done(self, result):
        self.view.urlChanged.disconnect(self._changed)
        self.view.setPage(None)
        self.page.deleteLater()
        super().done(result)


class Bridge(QObject):
    """What the web page can ask the desktop app for (see BRIDGE_JS)."""
    reply = Signal(int, str)

    def __init__(self, window: "Window"):
        super().__init__()
        self.window = window

    @Property(str, constant=True)
    def version(self):
        return APP_VERSION

    def _microsoft(self, rid: int, finish):
        """Show Microsoft sign-in, then run finish(account) off the UI thread and reply."""
        dlg = MicrosoftLogin(self.window)
        if dlg.exec() != QDialog.DialogCode.Accepted or not dlg.code:
            self.reply.emit(rid, json.dumps({"error": dlg.error or "Sign-in was cancelled"}))
            return
        code = dlg.code

        def work():
            try:
                out = finish(minecraft.minecraft_account(code))
            except minecraft.AuthError as e:
                out = {"error": str(e)}
            except Exception as e:  # noqa: BLE001 - always answer the page
                out = {"error": f"Something went wrong ({e.__class__.__name__})"}
            self.reply.emit(rid, json.dumps(out))
        threading.Thread(target=work, daemon=True).start()

    @Slot(int)
    def signInMinecraft(self, rid: int):
        self._microsoft(rid, lambda acc: minecraft.sign_in(BASE, acc))

    @Slot(int, str)
    def linkMinecraft(self, rid: int, token: str):
        self._microsoft(rid, lambda acc: minecraft.link(BASE, acc, token))

    @Slot(str, str)
    def notify(self, title: str, body: str):
        self.window.notify(title, body)

    @Slot(int)
    def setUnread(self, n: int):
        self.window.set_unread(n)


class Page(QWebEnginePage):
    """Keeps Jace Social (and Jace sign-in) in the app; other links open in the browser."""

    def acceptNavigationRequest(self, url: QUrl, nav_type, is_main_frame: bool) -> bool:
        if is_main_frame and url.scheme() in ("http", "https") and url.host() not in IN_APP_HOSTS:
            QDesktopServices.openUrl(url)
            return False
        return super().acceptNavigationRequest(url, nav_type, is_main_frame)

    def createWindow(self, _type):
        # window.open / target=_blank: open in the normal browser instead of a new app window
        page = QWebEnginePage(self.profile(), self)
        page.urlChanged.connect(lambda u: (QDesktopServices.openUrl(u), page.deleteLater()))
        return page


class Window(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle(APP_NAME)
        self.resize(1200, 780)
        self.setMinimumSize(380, 480)
        icon = QIcon(str(ASSETS / "icon.png"))
        self.setWindowIcon(icon)
        self.quitting = False

        # a saved browser profile, so you stay signed in
        self.profile = QWebEngineProfile("jace-social", self)
        self.profile.setPersistentStoragePath(str(data_dir() / "web"))
        self.profile.setCachePath(str(data_dir() / "cache"))
        self.profile.setHttpUserAgent(self.profile.httpUserAgent() + f" JaceSocialDesktop/{APP_VERSION}")
        self.page = Page(self.profile, self)
        self.page.settings().setAttribute(QWebEngineSettings.WebAttribute.JavascriptCanOpenWindows, True)

        self.channel = QWebChannel(self.page)
        self.bridge = Bridge(self)
        self.channel.registerObject("bridge", self.bridge)
        self.page.setWebChannel(self.channel)
        script = QWebEngineScript()
        script.setName("jace-desktop")
        script.setSourceCode(_qwebchannel_js() + BRIDGE_JS)
        script.setInjectionPoint(QWebEngineScript.InjectionPoint.DocumentCreation)
        script.setWorldId(QWebEngineScript.ScriptWorldId.MainWorld)
        script.setRunsOnSubFrames(False)
        self.page.scripts().insert(script)

        self.view = QWebEngineView(self)
        self.view.setPage(self.page)
        self.view.loadFinished.connect(self._loaded)
        self.setCentralWidget(self.view)
        self.view.setUrl(QUrl(BASE + "/app"))

        self.tray = None
        if QSystemTrayIcon.isSystemTrayAvailable():
            self.tray = QSystemTrayIcon(icon, self)
            self.tray.setToolTip(APP_NAME)
            menu = QMenu()
            show = QAction("Open Jace Social", menu)
            show.triggered.connect(self.bring_up)
            quit_ = QAction("Quit", menu)
            quit_.triggered.connect(self.quit)
            menu.addAction(show)
            menu.addSeparator()
            menu.addAction(quit_)
            self.tray.setContextMenu(menu)
            self.tray.activated.connect(lambda reason: self.bring_up()
                                        if reason == QSystemTrayIcon.ActivationReason.Trigger else None)
            self.tray.messageClicked.connect(self.bring_up)
            self.tray.show()

    def _loaded(self, ok: bool):
        if not ok:
            self.view.setHtml(f"""<body style="background:#111317;color:#e6e8eb;font:16px system-ui;display:grid;
                place-items:center;height:90vh"><div style="text-align:center"><h2 style="color:#3ddc84">Can't reach Jace Social</h2>
                <p>Check your internet connection.</p><button onclick="location.href='{BASE}/app'"
                style="padding:10px 18px;border-radius:9px;border:0;background:#3ddc84;font-weight:700">Try again</button></div>""",
                              QUrl(BASE + "/"))

    def notify(self, title: str, body: str):
        if self.tray and not self.isActiveWindow():
            self.tray.showMessage(title, body, self.windowIcon(), 6000)
        QApplication.alert(self)

    def set_unread(self, n: int):
        self.setWindowTitle(f"({n}) {APP_NAME}" if n else APP_NAME)
        if self.tray:
            self.tray.setToolTip(f"{APP_NAME} - {n} unread" if n else APP_NAME)

    def bring_up(self):
        self.show()
        self.setWindowState((self.windowState() & ~Qt.WindowState.WindowMinimized) | Qt.WindowState.WindowActive)
        self.raise_()
        self.activateWindow()

    def quit(self):
        self.quitting = True
        QApplication.quit()

    def closeEvent(self, e):
        # keep running in the tray (like Discord), so messages still pop up
        if self.tray and not self.quitting:
            e.ignore()
            self.hide()
            if not getattr(self, "_told", False):
                self._told = True
                self.tray.showMessage(APP_NAME, "Still running here. Right-click the icon to quit.",
                                      self.windowIcon(), 4000)
            return
        self.view.setPage(None)
        self.page.deleteLater()
        super().closeEvent(e)


def _qwebchannel_js() -> str:
    from PySide6.QtCore import QFile, QIODevice
    f = QFile(":/qtwebchannel/qwebchannel.js")
    if f.open(QIODevice.OpenModeFlag.ReadOnly):
        return bytes(f.readAll()).decode()
    raise RuntimeError("qwebchannel.js is missing from this Qt build")


def main():
    QApplication.setApplicationName("jace-social")
    QApplication.setApplicationDisplayName(APP_NAME)
    QApplication.setOrganizationName("jace-social")
    app = QApplication(sys.argv)
    app.setQuitOnLastWindowClosed(False)

    # one copy at a time: a second launch just brings the first window up
    key = "jace-social-desktop"
    probe = QLocalSocket()
    probe.connectToServer(key)
    if probe.waitForConnected(300):
        probe.write(b"show")
        probe.waitForBytesWritten(300)
        return
    QLocalServer.removeServer(key)
    server = QLocalServer()
    server.listen(key)

    try:
        win = Window()
    except Exception as e:  # noqa: BLE001
        QMessageBox.critical(None, APP_NAME, f"Jace Social couldn't start:\n{e}")
        raise
    server.newConnection.connect(lambda: (server.nextPendingConnection(), win.bring_up()))
    if "--hidden" not in sys.argv:
        win.show()
    sys.exit(app.exec())
