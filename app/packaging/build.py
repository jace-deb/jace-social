"""Build the Jace Social desktop app for the platform this runs on.

    python packaging/build.py

  Windows -> dist/JaceSocial-<ver>-windows-x64-setup.exe   (installer, needs Inno Setup 6)
  macOS   -> dist/JaceSocial-<ver>-macos-<arch>.app.zip
  Linux   -> dist/JaceSocial-<ver>-x86_64.AppImage

PyInstaller can't cross-compile, so GitHub Actions builds each one on its own OS
(.github/workflows/app.yml).
"""
import os
import platform
import plistlib
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent          # app/
sys.path.insert(0, str(ROOT))
from jace_social_app import APP_VERSION  # noqa: E402

APP_ID = "io.github.jace_deb.JaceSocial"
BUILD = ROOT / "build" / sys.platform
DIST = ROOT / "dist"
ASSETS = ROOT / "assets"


def run(cmd, **kw):
    print("+", " ".join(map(str, cmd)), flush=True)
    subprocess.run(list(map(str, cmd)), check=True, **kw)


def pyinstaller(name: str, icon: Path, extra=()) -> Path:
    shutil.rmtree(BUILD, ignore_errors=True)
    run([sys.executable, "-m", "PyInstaller", "--noconfirm", "--clean", "--log-level", "WARN",
         "--name", name, "--windowed", "--icon", icon,
         "--distpath", BUILD / "dist", "--workpath", BUILD / "work", "--specpath", BUILD,
         "--paths", ROOT, "--add-data", f"{ASSETS}{os.pathsep}assets",
         "--hidden-import", "PySide6.QtWebEngineWidgets", "--hidden-import", "PySide6.QtWebEngineCore",
         "--hidden-import", "PySide6.QtWebChannel",
         *extra, ROOT / "packaging" / "entry.py"])
    return BUILD / "dist" / name


VC_RUNTIME = ["vcruntime140.dll", "vcruntime140_1.dll", "msvcp140.dll", "msvcp140_1.dll", "msvcp140_2.dll", "concrt140.dll"]


def build_windows():
    # ship the Visual C++ runtime next to the app (fresh PCs and Wine often don't have it)
    sys32 = Path(os.environ.get("SystemRoot", r"C:\Windows")) / "System32"
    vc = [a for dll in VC_RUNTIME for a in ("--add-binary", f"{sys32 / dll}{os.pathsep}.")]
    folder = pyinstaller("JaceSocial", ASSETS / "icon.ico", vc)
    iscc = shutil.which("iscc") or r"C:\Program Files (x86)\Inno Setup 6\ISCC.exe"
    env = {**os.environ, "APP_VERSION": APP_VERSION, "APP_SOURCE": str(folder)}
    DIST.mkdir(exist_ok=True)
    run([iscc, ROOT / "packaging" / "windows.iss"], env=env, cwd=ROOT / "packaging")
    print("Built", DIST / f"JaceSocial-{APP_VERSION}-windows-x64-setup.exe")


def _minos(binary: Path):
    lines = subprocess.run(["otool", "-l", str(binary)], capture_output=True, text=True).stdout.splitlines()
    for i, line in enumerate(lines):
        if "LC_BUILD_VERSION" in line or "LC_VERSION_MIN_MACOSX" in line:
            for nxt in lines[i + 1:i + 6]:
                parts = nxt.split()
                if parts and parts[0] in ("minos", "version"):
                    return tuple(int(x) for x in parts[1].split("."))
    return None


def build_macos():
    from PIL import Image
    arch = "arm64" if platform.machine() == "arm64" else "x86_64"
    icns = BUILD.parent / "icon.icns"
    icns.parent.mkdir(parents=True, exist_ok=True)
    Image.open(ASSETS / "icon.png").save(icns)
    app = pyinstaller("Jace Social", icns, ["--osx-bundle-identifier", APP_ID, "--target-arch", arch]).parent / "Jace Social.app"
    core = [p for p in app.glob("**/QtWebEngineCore.framework/Versions/A/QtWebEngineCore")] + list(app.glob("Contents/MacOS/*"))
    mins = [v for b in core if (v := _minos(b))]
    need = max(mins) if mins else (12, 0)
    newer = sorted({(v, p.name) for p in app.rglob("*") if p.suffix in (".so", ".dylib") and p.is_file()
                    for v in [_minos(p)] if v and v > need})
    if newer:
        sys.exit("Libraries that need a newer macOS than the app:\n" + "\n".join(f"  {n}: {v}" for v, n in newer))
    info = app / "Contents" / "Info.plist"
    with open(info, "rb") as f:
        plist = plistlib.load(f)
    plist.update({"CFBundleDisplayName": "Jace Social", "CFBundleShortVersionString": APP_VERSION,
                  "CFBundleVersion": APP_VERSION, "LSApplicationCategoryType": "public.app-category.social-networking",
                  "NSHighResolutionCapable": True, "LSMinimumSystemVersion": ".".join(map(str, need))})
    with open(info, "wb") as f:
        plistlib.dump(plist, f)
    run(["codesign", "--force", "--deep", "--sign", "-", app])        # ad-hoc: required on Apple Silicon
    DIST.mkdir(exist_ok=True)
    out = DIST / f"JaceSocial-{APP_VERSION}-macos-{arch}.app.zip"
    out.unlink(missing_ok=True)
    run(["ditto", "-c", "-k", "--sequesterRsrc", "--keepParent", app, out])
    print("Built", out)


def build_linux():
    folder = pyinstaller("jace-social", ASSETS / "icon.png")
    # Qt 6.5+ needs libxcb-cursor on X11; many distros don't install it, so bundle it
    qtlib = next(folder.rglob("PySide6/Qt/lib"), None)
    found = subprocess.run(["sh", "-c", "ldconfig -p | awk '/libxcb-cursor.so.0 /{print $NF; exit}'"],
                           capture_output=True, text=True).stdout.strip()
    if qtlib and found:
        shutil.copy2(found, qtlib / "libxcb-cursor.so.0")
    appdir = BUILD / "AppDir"
    (appdir / "usr/bin").mkdir(parents=True)
    shutil.copytree(folder, appdir / "usr/bin/jace-social")
    shutil.copy2(ROOT / "packaging/AppRun", appdir / "AppRun")
    shutil.copy2(ASSETS / "icon.png", appdir / f"{APP_ID}.png")
    os.symlink(f"{APP_ID}.png", appdir / ".DirIcon")
    (appdir / f"{APP_ID}.desktop").write_text(
        "[Desktop Entry]\nType=Application\nName=Jace Social\nGenericName=Chat\n"
        "Comment=Friends, chat and servers - with Minecraft built in\nExec=jace-social %U\n"
        f"Icon={APP_ID}\nTerminal=false\nCategories=Network;Chat;InstantMessaging;\n")
    tool = ROOT / "build" / "appimagetool"
    if not tool.exists():
        run(["curl", "-sSL", "-o", tool, "https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage"])
        tool.chmod(0o755)
    DIST.mkdir(exist_ok=True)
    out = DIST / f"JaceSocial-{APP_VERSION}-x86_64.AppImage"
    run([tool, "--no-appstream", appdir, out], env={**os.environ, "ARCH": "x86_64", "APPIMAGE_EXTRACT_AND_RUN": "1"})
    print("Built", out)


if __name__ == "__main__":
    {"win32": build_windows, "darwin": build_macos}.get(sys.platform, build_linux)()
