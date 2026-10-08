"""The setup wizard every download shows the first time it runs (or with --install),
plus the Delete Jace Social and update windows. The same on Windows, macOS and Linux."""
import shutil
import sys
import threading
from pathlib import Path

from PySide6.QtCore import QObject, Qt, Signal
from PySide6.QtGui import QPixmap
from PySide6.QtWidgets import (QCheckBox, QDialog, QFileDialog, QHBoxLayout, QLabel, QLineEdit,
                               QMessageBox, QPlainTextEdit, QProgressBar, QPushButton, QVBoxLayout, QWizard,
                               QWizardPage)

from jace_social_app import APP_NAME, APP_VERSION, DATA_DIR, desktop, macinstall

IS_MAC = sys.platform == "darwin"
IS_WIN = sys.platform == "win32"

# the colors of the web app, so setup looks like the rest of Jace Social
STYLE = """
QWidget { background: #111317; color: #e6e8eb; font-size: 14px; }
QLabel#muted { color: #9aa3ad; }
QLineEdit, QPlainTextEdit { background: #1b1e24; border: 1px solid #2f3540; border-radius: 8px; padding: 6px; }
QPushButton { background: #2a2f39; border: 1px solid #353b47; border-radius: 8px; padding: 7px 14px; font-weight: 600; }
QPushButton:hover { background: #333946; }
QPushButton:default, QPushButton#primary { background: #3ddc84; border-color: #3ddc84; color: #0c1410; }
QPushButton:disabled { color: #6b7380; }
QCheckBox { spacing: 8px; padding: 3px 0; }
QCheckBox::indicator { width: 16px; height: 16px; border: 1px solid #4a5260; border-radius: 4px; background: #1b1e24; }
QCheckBox::indicator:checked { background: #3ddc84; border-color: #3ddc84; image: none; }
QProgressBar { background: #1b1e24; border: 1px solid #2f3540; border-radius: 6px; height: 12px; text-align: center; }
QProgressBar::chunk { background: #3ddc84; border-radius: 5px; }
"""


class Task(QObject):
    """Run fn(status) on a background thread; results come back on the UI thread."""
    status = Signal(str)
    progress = Signal(int, int)
    done = Signal(object)
    failed = Signal(str)

    def __init__(self, fn, parent=None):
        super().__init__(parent)
        self.fn = fn

    def start(self):
        def work():
            try:
                self.done.emit(self.fn(self))
            except Exception as e:  # noqa: BLE001 - shown to the user
                self.failed.emit(str(e) or e.__class__.__name__)
        threading.Thread(target=work, daemon=True).start()
        return self


def _muted(text):
    lab = QLabel(text)
    lab.setObjectName("muted")
    lab.setWordWrap(True)
    return lab


class WelcomePage(QWizardPage):
    def __init__(self, upgrade: bool):
        super().__init__()
        self.setTitle(f"Update {APP_NAME}" if upgrade else f"Welcome to {APP_NAME}")
        lay = QVBoxLayout(self)
        row = QHBoxLayout()
        icon = QLabel()
        icon.setPixmap(QPixmap(str(desktop.ICON_SRC)).scaled(
            96, 96, Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.SmoothTransformation))
        row.addWidget(icon, 0, Qt.AlignmentFlag.AlignTop)
        text = QLabel(
            f"<p style='font-size:15px'>This will {'update' if upgrade else 'set up'} "
            f"<b>{APP_NAME} {APP_VERSION}</b> on your computer.</p>"
            "<p>The next steps will:</p><ul>"
            f"<li>choose where to install {APP_NAME}</li>"
            + ("<li>add it to the Dock, Launchpad and your desktop</li>"
               "<li>stop macOS from warning that the app is \"damaged\"</li>" if IS_MAC else
               "<li>add it to the Start menu and your desktop</li>"
               "<li>list it in Windows \"Installed apps\" so it's easy to uninstall</li>" if IS_WIN else
               "<li>add it to your applications menu and desktop</li>"
               "<li>register it with GNOME Software and other app centers</li>")
            + "<li>if you like, start it when you sign in, so messages and calls reach you</li></ul>"
            + ("<p>A version is already installed. It will be replaced; you stay signed in.</p>" if upgrade else ""))
        text.setWordWrap(True)
        text.setTextFormat(Qt.TextFormat.RichText)
        row.addWidget(text, 1)
        lay.addLayout(row)
        lay.addStretch()
        lay.addWidget(_muted(f"{APP_NAME} needs to be installed before you can use it."))


class LocationPage(QWizardPage):
    def __init__(self):
        super().__init__()
        self.setTitle("Choose install location and shortcuts")
        lay = QVBoxLayout(self)
        lay.addWidget(QLabel(f"Install {APP_NAME} to:"))
        row = QHBoxLayout()
        current = desktop.installed_path()
        self.path = QLineEdit(str(current.parent if current else desktop.default_install_dir()))
        browse = QPushButton("Browse…")
        browse.clicked.connect(self._browse)
        row.addWidget(self.path, 1)
        row.addWidget(browse)
        lay.addLayout(row)
        self.space = _muted("")
        lay.addWidget(self.space)
        self.path.textChanged.connect(self._update_space)
        self._update_space()
        lay.addSpacing(12)

        # last time's choices when updating, else the defaults
        before = set(desktop.install_record().get("parts", [])) if desktop.is_installed() else None
        self.options = {}
        for key, label, default in desktop.install_options():
            cb = QCheckBox(label)
            cb.setChecked(key in before if before is not None and key != "unquarantine" else default)
            self.options[key] = cb
            lay.addWidget(cb)
        if IS_MAC:
            lay.addWidget(_muted(f"Mac apps normally live in Applications. macOS may ask permission to let {APP_NAME} "
                                 "update the Dock or your Desktop - click OK."))
        elif IS_WIN:
            lay.addWidget(_muted("The default folder is just for you and doesn't need administrator rights."))
        else:
            lay.addWidget(_muted(f"App centers list apps using AppStream info; this adds {APP_NAME}'s description, "
                                 "icon and keywords so app centers that read local metadata can recognise it."))
        lay.addStretch()

    def chosen_options(self) -> dict:
        return {k: cb.isChecked() for k, cb in self.options.items()}

    def _browse(self):
        d = QFileDialog.getExistingDirectory(self, "Install location", self.path.text())
        if d:
            self.path.setText(d)

    def _update_space(self):
        p = Path(self.path.text()).expanduser()
        while not p.exists() and p != p.parent:
            p = p.parent
        try:
            free = shutil.disk_usage(p).free / 1024 ** 3
            self.space.setText(f"{free:.1f} GB free there. {APP_NAME} needs about 0.4 GB. Your sign-in and settings "
                               f"are kept separately, in {DATA_DIR}.")
        except OSError:
            self.space.setText("")

    def validatePage(self):
        if not self.path.text().strip():
            QMessageBox.warning(self, "Choose a folder", f"Pick a folder to install {APP_NAME} into.")
            return False
        p = Path(self.path.text()).expanduser()
        try:
            p.mkdir(parents=True, exist_ok=True)
            probe = p / ".jace-social-write-test"
            probe.write_text("")
            probe.unlink()
        except OSError as e:
            QMessageBox.warning(self, "Can't install there", f"That folder isn't writable:\n{e}")
            return False
        if shutil.disk_usage(p).free < 450 * 1024 ** 2:
            QMessageBox.warning(self, "Not enough space", f"There isn't enough free space there for {APP_NAME}.")
            return False
        return True


class InstallPage(QWizardPage):
    def __init__(self, loc: LocationPage):
        super().__init__()
        self.loc = loc
        self.setTitle("Installing")
        lay = QVBoxLayout(self)
        self.log = QPlainTextEdit()
        self.log.setReadOnly(True)
        lay.addWidget(self.log)
        self.finished_ok = False
        self.result_path = None
        self.task = None

    def initializePage(self):
        self.finished_ok = False
        self.log.clear()
        self.wizard().button(QWizard.WizardButton.BackButton).setEnabled(False)
        target, options = Path(self.loc.path.text()).expanduser(), self.loc.chosen_options()
        self.task = Task(lambda t: desktop.install_app(target, options, status=t.status.emit), self)
        self.task.status.connect(self.log.appendPlainText)
        self.task.done.connect(self._done)
        self.task.failed.connect(self._failed)
        self.task.start()

    def _done(self, path):
        self.result_path = path
        self.finished_ok = True
        self.log.appendPlainText(f"\n{APP_NAME} is installed at {path}")
        self.completeChanged.emit()
        self.wizard().next()

    def _failed(self, msg):
        self.log.appendPlainText(f"\nInstall failed: {msg}")
        self.wizard().button(QWizard.WizardButton.BackButton).setEnabled(True)

    def isComplete(self):
        return self.finished_ok


class FinishPage(QWizardPage):
    def __init__(self, install: InstallPage):
        super().__init__()
        self.install = install
        self.setTitle("All set!")
        lay = QVBoxLayout(self)
        self.summary = QLabel()
        self.summary.setWordWrap(True)
        self.summary.setTextFormat(Qt.TextFormat.RichText)
        lay.addWidget(self.summary)
        lay.addStretch()
        self.launch = QCheckBox(f"Open {APP_NAME} now")
        self.launch.setChecked(True)
        lay.addWidget(self.launch)

    def initializePage(self):
        parts = desktop.install_record().get("parts", [])
        lines = [f"Installed to <b>{self.install.result_path}</b>"]
        lines += [f"✓ {desktop.PART_DESCRIPTIONS[p]}" for p in parts if p in desktop.PART_DESCRIPTIONS]
        if "desktop" in parts and not (IS_MAC or IS_WIN):
            lines.append("(on GNOME you may need the Desktop Icons extension to see desktop shortcuts)")
        if IS_MAC:
            if macinstall.download_left_behind():
                lines.append(f"<br>You can delete the downloaded {APP_NAME} from your Downloads folder now.")
            lines.append(f"<br>To uninstall later, use Settings → Delete {APP_NAME}.")
        elif IS_WIN:
            lines.append(f"<br>The downloaded file is removed automatically. To uninstall later, use Settings → "
                         f"Delete {APP_NAME}, or Windows Settings → Apps → Installed apps.")
        else:
            lines.append(f"<br>The downloaded file is removed automatically. To uninstall later, right-click "
                         f"{APP_NAME} in the applications menu → <i>Uninstall {APP_NAME}</i>, or use Settings → "
                         f"Delete {APP_NAME}.")
        self.summary.setText("<br>".join(lines))


class SetupWizard(QWizard):
    def __init__(self, parent=None, required=True):
        super().__init__(parent)
        self.required = required
        self.setWindowTitle(f"{APP_NAME} Setup")
        self.setWizardStyle(QWizard.WizardStyle.ClassicStyle)
        self.setStyleSheet(STYLE + "QLabel#qt_wizard_title { font-size: 19px; font-weight: 700; }")
        self.setOption(QWizard.WizardOption.NoBackButtonOnLastPage)
        self.resize(720, 540)
        self.welcome = WelcomePage(desktop.is_installed())
        self.loc = LocationPage()
        self.install_page = InstallPage(self.loc)
        self.finish = FinishPage(self.install_page)
        for p in (self.welcome, self.loc, self.install_page, self.finish):
            self.addPage(p)
        self.setButtonText(QWizard.WizardButton.CommitButton, "Install")
        self.loc.setCommitPage(True)

    def reject(self):
        # Installing is required: cancelling setup quits the app
        if self.required and self.currentPage() is not self.finish and QMessageBox.question(
                self, "Quit setup?", f"{APP_NAME} has to be installed before you can use it.\n\n"
                f"Quit setup and close {APP_NAME}?") != QMessageBox.StandardButton.Yes:
            return
        super().reject()

    def launch_after(self) -> bool:
        return self.finish.launch.isChecked()


def confirm_uninstall(parent=None) -> bool:
    """Ask, then delete Jace Social (and optionally your sign-in and settings). Returns True
    if deleted; the caller then quits (the removal finishes after that on Windows/macOS)."""
    box = QMessageBox(parent)
    box.setStyleSheet(STYLE)
    box.setIcon(QMessageBox.Icon.Warning)
    box.setWindowTitle(f"Delete {APP_NAME}")
    box.setText(f"<b>Delete {APP_NAME}?</b>")
    box.setInformativeText("This removes:<ul>" + "".join(f"<li>{r}</li>" for r in desktop.removal_summary())
                           + "</ul>Your friends, messages and servers stay in your account.")
    purge = QCheckBox("Also sign out and delete my settings on this computer")
    purge.setToolTip(str(DATA_DIR))
    box.setCheckBox(purge)
    delete = box.addButton("Delete", QMessageBox.ButtonRole.DestructiveRole)
    box.addButton(QMessageBox.StandardButton.Cancel)
    box.exec()
    if box.clickedButton() is not delete:
        return False
    try:
        desktop.delete_app(remove_data=purge.isChecked())
    except Exception as e:  # noqa: BLE001
        QMessageBox.critical(parent, f"Couldn't delete {APP_NAME}", str(e))
        return False
    QMessageBox.information(parent, "Deleted", f"{APP_NAME} has been deleted. The app will now close.")
    return True


class ProgressDialog(QDialog):
    """A small window with a status line and a progress bar, for updating."""

    def __init__(self, title: str, heading: str, parent=None):
        super().__init__(parent)
        self.setWindowTitle(title)
        self.setStyleSheet(STYLE)
        self.setMinimumWidth(440)
        lay = QVBoxLayout(self)
        lay.addWidget(QLabel(f"<b>{heading}</b>"))
        self.label = _muted("Starting…")
        self.bar = QProgressBar()
        self.bar.setRange(0, 0)
        self.bar.setTextVisible(False)
        lay.addWidget(self.label)
        lay.addWidget(self.bar)
        self.error = None

    def run(self, fn) -> bool:
        """Run fn(task) in the background while showing this; True if it worked."""
        task = Task(fn, self)
        task.status.connect(self.label.setText)
        task.progress.connect(self._progress)
        task.done.connect(lambda _: self.accept())
        task.failed.connect(self._failed)
        task.start()
        return self.exec() == QDialog.DialogCode.Accepted

    def _progress(self, done: int, total: int):
        if total:
            self.bar.setRange(0, 1000)
            self.bar.setValue(int(done * 1000 / total))

    def _failed(self, msg: str):
        self.error = msg
        QMessageBox.critical(self, "Something went wrong", msg)
        self.reject()


def run_windows_update(argv) -> None:
    """Shown by the new .exe while it installs itself over the old version."""
    dlg = ProgressDialog(f"Updating {APP_NAME}", f"Updating {APP_NAME} to {APP_VERSION}")
    dlg.run(lambda t: desktop.apply_windows_update(argv, t.status.emit))


def update_app(info: dict, parent=None) -> bool:
    """Download and stage an update (see updater.apply). True means quit now."""
    from jace_social_app import updater
    dlg = ProgressDialog(f"Updating {APP_NAME}", f"Updating {APP_NAME} to {info['version']}", parent)
    return dlg.run(lambda t: updater.apply(info, t.status.emit, t.progress.emit))
