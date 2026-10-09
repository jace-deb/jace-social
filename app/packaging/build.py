"""Build the Jace Social desktop app for the platform this runs on.

    python packaging/build.py

  Windows -> dist/JaceSocial-<ver>-windows-x64.exe        (self-installing: setup wizard on first run)
  macOS   -> dist/JaceSocial-<ver>-macos-<arch>.app.zip   (unzips to Jace Social.app, which installs itself)
  Linux   -> run packaging/build_appimage.sh

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
from jace_social_app import APP_ID, APP_VERSION, URL_SCHEME  # noqa: E402
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


# Microsoft Visual C++ runtime DLLs that Qt and Python need. Most Windows PCs have
# them, but fresh installs and Wine prefixes (Bottles, Lutris) often don't, which
# makes QtCore fail with "DLL load failed". Microsoft allows shipping them app-locally.
VC_RUNTIME = ["vcruntime140.dll", "vcruntime140_1.dll", "msvcp140.dll", "msvcp140_1.dll", "msvcp140_2.dll", "concrt140.dll"]


def vc_runtime_args() -> list:
    system32 = Path(os.environ.get("SystemRoot", r"C:\Windows")) / "System32"
    args = []
    for dll in VC_RUNTIME:
        src = system32 / dll
        if not src.is_file():
            sys.exit(f"Missing {src} - install the Visual C++ 2015-2022 redistributable (x64)")
        args += ["--add-binary", f"{src}{os.pathsep}."]
    return args


def build_windows():
    """One self-installing .exe: a onefile build that also carries the small onedir
    launcher, which setup installs next to the unpacked app files."""
    from PIL import Image
    vc = vc_runtime_args()
    launcher = pyinstaller("JaceSocial", ASSETS / "icon.ico", vc) / "JaceSocial.exe"
    keep = ROOT / "build" / "win-launcher"
    shutil.rmtree(keep, ignore_errors=True)
    keep.mkdir(parents=True)
    shutil.copy2(launcher, keep / "JaceSocial.exe")

    splash = ROOT / "build" / "splash.png"
    Image.open(ASSETS / "icon.png").convert("RGBA").resize((256, 256), Image.LANCZOS).save(splash)
    pyinstaller("JaceSocial", ASSETS / "icon.ico",
                [*vc, "--onefile", "--splash", splash, "--add-data", f"{keep / 'JaceSocial.exe'}{os.pathsep}jace_setup"])
    DIST.mkdir(exist_ok=True)
    final = DIST / f"JaceSocial-{APP_VERSION}-windows-x64.exe"
    shutil.copy2(BUILD / "dist" / "JaceSocial.exe", final)
    print("Built", final)


def _minos(binary: Path):
    lines = subprocess.run(["otool", "-l", str(binary)], capture_output=True, text=True).stdout.splitlines()
    for i, line in enumerate(lines):
        if "LC_BUILD_VERSION" in line or "LC_VERSION_MIN_MACOSX" in line:
            for nxt in lines[i + 1:i + 6]:
                parts = nxt.split()
                if parts and parts[0] in ("minos", "version"):
                    return tuple(int(x) for x in parts[1].split("."))
    return None


def macos_min_version(app: Path) -> str:
    """Highest LC_BUILD_VERSION minos among the app's key binaries, i.e. the oldest macOS
    this bundle really runs on (for LSMinimumSystemVersion, so older Macs get a clear
    "needs a newer macOS" message instead of a crash). Fails the build if any other
    library needs a newer macOS than that: pin an older wheel in requirements.txt then."""
    binaries = [p for pat in ("Contents/MacOS/*", "**/QtCore.framework/Versions/A/QtCore",
                              "**/QtWebEngineCore.framework/Versions/A/QtWebEngineCore", "**/libpython3*.dylib",
                              "**/Python.framework/Versions/*/Python")
                for p in app.glob(pat) if p.is_file()]
    found = [(v, b.name) for b in binaries if (v := _minos(b))]
    if not found:
        sys.exit("Couldn't determine the minimum macOS version of the build")
    ver, name = max(found)
    print(f"Minimum macOS: {'.'.join(map(str, ver))} (from {name})")
    newer = sorted({(v, p.name) for p in app.rglob("*") if p.suffix in (".so", ".dylib") and p.is_file()
                    for v in [_minos(p)] if v and v > ver})
    if newer:
        sys.exit("These libraries need a newer macOS than the app (" + ".".join(map(str, ver)) + "):\n" +
                 "\n".join(f"  {n}: macOS {'.'.join(map(str, v))}" for v, n in newer) +
                 "\nPin an older wheel in requirements.txt.")
    return ".".join(map(str, ver))


def build_macos():
    from PIL import Image
    arch = "arm64" if platform.machine() == "arm64" else "x86_64"
    icns = BUILD.parent / "icon.icns"
    icns.parent.mkdir(parents=True, exist_ok=True)
    Image.open(ASSETS / "icon.png").save(icns)
    app = pyinstaller("Jace Social", icns, ["--osx-bundle-identifier", APP_ID, "--target-arch", arch]).parent / "Jace Social.app"
    need = macos_min_version(app)
    info = app / "Contents" / "Info.plist"
    with open(info, "rb") as f:
        plist = plistlib.load(f)
    plist.update({"CFBundleDisplayName": "Jace Social", "CFBundleShortVersionString": APP_VERSION,
                  "CFBundleVersion": APP_VERSION, "LSApplicationCategoryType": "public.app-category.social-networking",
                  "NSHighResolutionCapable": True, "LSMinimumSystemVersion": need,
                  "NSMicrophoneUsageDescription": "Jace Social uses the microphone for voice calls with your friends.",
                  # jacesocial://invite/<code> links from invite pages open in the app
                  "CFBundleURLTypes": [{"CFBundleURLName": APP_ID, "CFBundleURLSchemes": [URL_SCHEME]}]})
    with open(info, "wb") as f:
        plistlib.dump(plist, f)
    # Info.plist changed, so re-sign (ad hoc: no Apple developer account needed,
    # but Apple Silicon won't run the app at all without a signature)
    run(["codesign", "--force", "--deep", "--sign", "-", app])
    # a .app is a folder, so it ships zipped; Safari unzips downloads automatically,
    # leaving "Jace Social.app" in Downloads. ditto keeps symlinks and the signature.
    DIST.mkdir(exist_ok=True)
    out = DIST / f"JaceSocial-{APP_VERSION}-macos-{arch}.app.zip"
    out.unlink(missing_ok=True)
    run(["ditto", "-c", "-k", "--sequesterRsrc", "--keepParent", app, out])
    print("Built", out)


if __name__ == "__main__":
    if sys.platform == "win32":
        build_windows()
    elif sys.platform == "darwin":
        build_macos()
    else:
        sys.exit("On Linux, run packaging/build_appimage.sh")
