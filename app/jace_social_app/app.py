"""The desktop window. It shows the Jace Social web app and adds what a browser can't:
Microsoft (Minecraft) sign-in, system notifications, a tray icon and an unread badge."""
import json
import os
import sys
import subprocess
import threading
import time
from pathlib import Path

from PySide6.QtCore import QObject, Property, Qt, QTimer, QUrl, Signal, Slot
from PySide6.QtGui import QAction, QDesktopServices, QIcon
from PySide6.QtNetwork import QLocalServer, QLocalSocket
from PySide6.QtWebChannel import QWebChannel
from PySide6.QtWebEngineCore import (QWebEnginePage, QWebEnginePermission, QWebEngineProfile, QWebEngineScript,
                                      QWebEngineSettings)
from PySide6.QtWebEngineWidgets import QWebEngineView
from PySide6.QtWidgets import QApplication, QDialog, QMainWindow, QMenu, QMessageBox, QSystemTrayIcon, QVBoxLayout

from jace_social_app import APP_ID, APP_NAME, APP_VERSION, DATA_DIR, desktop, macinstall, minecraft, updater, wininstall
from jace_social_app.installer import SetupWizard, confirm_uninstall, run_windows_update, update_app

BASE = os.environ.get("JACE_SOCIAL_URL", "https://jace-social.vercel.app").rstrip("/")
# pages that open inside the app; everything else goes to your normal browser
IN_APP_HOSTS = {QUrl(BASE).host(), "jaceo.vercel.app"}
ASSETS = desktop.ASSETS


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
      installed: b.installed,
      checkForUpdate: function () { return call("checkForUpdate"); },
      applyUpdate: function () { b.applyUpdate(); },
      deleteApp: function () { b.deleteApp(); },
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

    @Property(bool, constant=True)
    def installed(self):
        """Running a packaged build (it can update and delete itself), not from source."""
        return desktop.frozen()

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

    @Slot(int)
    def checkForUpdate(self, rid: int):
        self.window.check_for_update(lambda r: self.reply.emit(rid, json.dumps(r)))

    @Slot()
    def applyUpdate(self):
        self.window.apply_update()

    @Slot()
    def deleteApp(self):
        self.window.delete_app()


class Page(QWebEnginePage):
    """Keeps Jace Social (and Jace sign-in) in the app; other links open in the browser."""

    def __init__(self, profile, parent=None):
        super().__init__(profile, parent)
        self.permissionRequested.connect(self._permission)

    def _permission(self, p: QWebEnginePermission):
        # the microphone, for voice calls - only for Jace Social itself
        if p.permissionType() == QWebEnginePermission.PermissionType.MediaAudioCapture and p.origin().host() == QUrl(BASE).host():
            p.grant()
        else:
            p.deny()

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
    update_ready = Signal(dict, object)           # update check result, and what to do with it

    def __init__(self, url: str = BASE + "/app"):
        super().__init__()
        self.setWindowTitle(APP_NAME)
        self.resize(1200, 780)
        self.setMinimumSize(380, 480)
        icon = QIcon(str(ASSETS / "icon.png"))
        self.setWindowIcon(icon)
        self.quitting = False

        # a saved browser profile, so you stay signed in
        self.profile = QWebEngineProfile("jace-social", self)
        self.profile.setPersistentStoragePath(str(DATA_DIR / "web"))
        self.profile.setCachePath(str(DATA_DIR / "cache"))
        self.profile.setHttpUserAgent(self.profile.httpUserAgent() + f" JaceSocialDesktop/{APP_VERSION}")
        self.page = Page(self.profile, self)
        self.page.settings().setAttribute(QWebEngineSettings.WebAttribute.JavascriptCanOpenWindows, True)
        # so an incoming call can ring before you've clicked anything
        self.page.settings().setAttribute(QWebEngineSettings.WebAttribute.PlaybackRequiresUserGesture, False)

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
        self.view.setUrl(QUrl(url))

        self.tray = None
        if QSystemTrayIcon.isSystemTrayAvailable():
            self.tray = QSystemTrayIcon(icon, self)
            self.tray.setToolTip(APP_NAME)
            menu = QMenu()
            show = QAction("Open Jace Social", menu)
            show.triggered.connect(self.bring_up)
            update = QAction("Check for updates", menu)
            update.triggered.connect(lambda: self.check_for_update(self._update_dialog, fresh=True))
            quit_ = QAction("Quit", menu)
            quit_.triggered.connect(self.quit)
            menu.addAction(show)
            menu.addAction(update)
            menu.addSeparator()
            menu.addAction(quit_)
            self.tray.setContextMenu(menu)
            self.tray.activated.connect(lambda reason: self.bring_up()
                                        if reason == QSystemTrayIcon.ActivationReason.Trigger else None)
            self.tray.messageClicked.connect(self.bring_up)
            self.tray.show()

        # look for a new version a little after starting, and tell once
        self.update = None              # updater.check() result when a newer version is out
        self._checked = 0.0
        self.update_ready.connect(lambda r, cb: cb(r))
        QTimer.singleShot(15_000, lambda: self.check_for_update(self._update_notice))

    def check_for_update(self, done, fresh=False):
        """Ask GitHub off the UI thread (at most every 10 minutes unless fresh);
        done({current, latest, newer} or {error}) runs on the UI thread."""
        def result():
            u = self.update
            return {"current": APP_VERSION, "latest": u["version"] if u else APP_VERSION, "newer": bool(u)}
        if not fresh and time.monotonic() - self._checked < 600:
            done(result())
            return

        def work():
            try:
                self.update = updater.check()
                self._checked = time.monotonic()
                r = result()
            except Exception:  # noqa: BLE001 - offline, rate limited, ...
                r = {"error": "Couldn't check for updates - try again later", "current": APP_VERSION}
            self.update_ready.emit(r, done)
        threading.Thread(target=work, daemon=True).start()

    def apply_update(self):
        """Download the new version, swap it in and restart (asks first when it can't)."""
        if not self.update:
            return
        why = updater.unsupported_reason()
        if why:
            QMessageBox.information(self, APP_NAME, f"{why}\n\nYou can also download it from the website.")
            QDesktopServices.openUrl(QUrl(self.update.get("page") or updater.RELEASES))
            return
        if update_app(self.update, self):
            self.quit()

    def delete_app(self):
        if confirm_uninstall(self):
            self.quit()

    def _update_notice(self, r: dict):
        if r.get("newer") and self.tray:
            self.tray.showMessage(APP_NAME, f"{APP_NAME} {r['latest']} is out. Click Update in the app to get it.",
                                  self.windowIcon(), 8000)

    def _update_dialog(self, r: dict):
        if r.get("error"):
            QMessageBox.warning(self, APP_NAME, r["error"])
        elif not r["newer"]:
            QMessageBox.information(self, APP_NAME, f"You have the newest version ({APP_VERSION}).")
        elif QMessageBox.question(self, APP_NAME, f"{APP_NAME} {r['latest']} is out (you have {APP_VERSION}).\n\n"
                                  "Update now? It takes a minute, then the app restarts.") \
                == QMessageBox.StandardButton.Yes:
            self.apply_update()

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


def self_test(app) -> int:
    """Used by CI on packaged builds: prove the bundle starts (Qt, WebEngine, the bridge
    script and the bundled assets), then exit. Exit code 0 = OK."""
    results = []
    try:
        assert desktop.ICON_SRC.is_file(), f"missing icon {desktop.ICON_SRC}"
        assert _qwebchannel_js(), "qwebchannel.js missing"
        w = Window(url="about:blank")
        w.show()
        for _ in range(20):
            app.processEvents()
            time.sleep(0.05)
        w.quitting = True
        w.close()
        results.append(f"{APP_NAME} {APP_VERSION}")
        results.append("SELF-TEST OK")
        code = 0
    except Exception as e:  # noqa: BLE001
        results.append(f"SELF-TEST FAILED: {e!r}")
        code = 1
    out = os.environ.get("JACE_SELF_TEST_OUT")
    if out:  # windowed Windows builds have no stdout, so CI reads this file
        with open(out, "w") as f:
            f.write("\n".join(results))
    print("\n".join(results))
    return code


def should_run_setup(argv) -> bool:
    if "--install" in argv:
        return True
    # the downloaded AppImage / .app / .exe must be installed before it can be used
    return bool(desktop.setup_available() and not desktop.running_installed_copy())


def start_installed(installed: Path):
    """Start the freshly installed copy and remove the downloaded one."""
    if macinstall.running_bundle():
        macinstall.relaunch_installed(installed, macinstall.running_bundle())
    elif wininstall.running_exe():
        wininstall.relaunch(installed, delete_after=wininstall.running_exe())
    else:
        subprocess.Popen([str(installed)], start_new_session=True, env=updater.clean_env())
        downloaded = desktop.running_appimage()
        if downloaded and downloaded.resolve() != installed.resolve():
            downloaded.unlink(missing_ok=True)   # fine on Linux: the running image stays mounted


def close_splash():
    """Close the PyInstaller splash screen (only the Windows download has one)."""
    if "_PYI_SPLASH_IPC" not in os.environ:
        return
    try:
        import pyi_splash
        pyi_splash.close()
    except Exception:  # noqa: BLE001
        pass


def main():
    close_splash()
    argv = sys.argv[1:]
    if desktop.handle_cli(argv):
        return
    QApplication.setApplicationName("jace-social")
    QApplication.setApplicationDisplayName(APP_NAME)
    QApplication.setOrganizationName("jace-social")
    QApplication.setDesktopFileName(APP_ID)
    app = QApplication(sys.argv)
    app.setStyle("Fusion")
    app.setWindowIcon(QIcon(str(desktop.ICON_SRC)))
    if "--self-test" in argv:
        sys.exit(self_test(app))
    if "--uninstall-gui" in argv:
        confirm_uninstall()
        return
    if "--apply-update" in argv:
        run_windows_update(argv)
        return
    if should_run_setup(argv):
        wiz = SetupWizard()
        if wiz.exec() != QDialog.DialogCode.Accepted:
            return                       # setup cancelled: nothing runs uninstalled
        installed = desktop.installed_path()
        if installed and not desktop.running_installed_copy():
            # we're the download: start the installed app (if asked) and quit
            if wiz.launch_after():
                start_installed(installed)
            elif desktop.running_appimage():
                desktop.running_appimage().unlink(missing_ok=True)
            elif wininstall.is_setup_build():
                wininstall.after_exit(f'del /f /q "{wininstall.running_exe()}"')
            return
        if not wiz.launch_after():
            return
    app.setQuitOnLastWindowClosed(False)

    # one copy at a time: a second launch brings the first window up, and setup or an
    # update asks it to quit (see desktop.ask_running_copy_to_quit)
    key = desktop.SINGLE_INSTANCE_KEY
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

    def connected():
        sock = server.nextPendingConnection()
        if sock is None:
            return

        def got():
            if bytes(sock.readAll()).startswith(b"quit"):
                win.quit()
            else:
                win.bring_up()
        sock.readyRead.connect(got)
        if sock.bytesAvailable():
            got()
    server.newConnection.connect(connected)
    if "--hidden" not in argv:
        win.show()
    sys.exit(app.exec())
