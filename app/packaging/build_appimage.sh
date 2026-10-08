#!/usr/bin/env bash
# Build dist/JaceSocial-<version>-x86_64.AppImage
#   1. PyInstaller bundles Python + PySide6 + the app into a folder
#   2. we lay out an AppDir (AppRun, desktop file, icon, AppStream metadata)
#   3. appimagetool turns that into a single AppImage file
# Uses .venv/bin/python if there is one, else $PYTHON or python3 (with requirements.txt
# and pyinstaller installed).
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
if [ -x .venv/bin/python ]; then PY="$ROOT/.venv/bin/python"; else PY="${PYTHON:-python3}"; fi
APP_ID="$("$PY" -c 'import jace_social_app as a; print(a.APP_ID)')"
VERSION="$("$PY" -c 'import jace_social_app as a; print(a.APP_VERSION)')"
BUILD="$ROOT/build/appimage"
APPDIR="$BUILD/AppDir"
TOOL="$ROOT/build/appimagetool"

"$PY" -m PyInstaller --version >/dev/null 2>&1 || "$PY" -m pip install -q pyinstaller
if [ ! -x "$TOOL" ]; then
  mkdir -p "$(dirname "$TOOL")"
  curl -sSL -o "$TOOL" https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage
  chmod +x "$TOOL"
fi

echo "==> PyInstaller"
rm -rf "$BUILD"
"$PY" -m PyInstaller --noconfirm --clean --log-level WARN \
  --name jace-social --windowed \
  --distpath "$BUILD/dist" --workpath "$BUILD/work" --specpath "$BUILD" \
  --paths "$ROOT" \
  --add-data "$ROOT/assets:assets" \
  --hidden-import PySide6.QtWebEngineWidgets --hidden-import PySide6.QtWebEngineCore \
  --hidden-import PySide6.QtWebChannel \
  "$ROOT/packaging/entry.py"

# Qt >= 6.5 needs libxcb-cursor on X11 but many distros don't install it.
# Put it next to Qt's own libs (their RUNPATH is $ORIGIN) so it's always found.
QTLIB="$(find "$BUILD/dist/jace-social" -type d -path '*PySide6/Qt/lib' | head -1)"
XCBC="$(ldconfig -p | awk '/libxcb-cursor.so.0 /{print $NF; exit}')"
if [ -n "$XCBC" ]; then cp -L "$XCBC" "$QTLIB/libxcb-cursor.so.0"; else echo "warning: libxcb-cursor0 not bundled (install libxcb-cursor0)"; fi

echo "==> AppDir"
mkdir -p "$APPDIR/usr/bin" "$APPDIR/usr/share/applications" "$APPDIR/usr/share/metainfo" \
         "$APPDIR/usr/share/icons/hicolor/256x256/apps"
cp -a "$BUILD/dist/jace-social" "$APPDIR/usr/bin/"
cp packaging/AppRun "$APPDIR/AppRun"
chmod +x "$APPDIR/AppRun"
cp assets/icon.png "$APPDIR/$APP_ID.png"
cp assets/icon.png "$APPDIR/usr/share/icons/hicolor/256x256/apps/$APP_ID.png"
ln -sf "$APP_ID.png" "$APPDIR/.DirIcon"
cat > "$APPDIR/$APP_ID.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=Jace Social
GenericName=Chat
Comment=Friends, chat, servers and voice calls, with Minecraft built in
Exec=jace-social %U
Icon=$APP_ID
Terminal=false
Categories=Network;Chat;InstantMessaging;
Keywords=minecraft;friends;chat;voice;call;servers;
DESKTOP
cp "$APPDIR/$APP_ID.desktop" "$APPDIR/usr/share/applications/"
"$PY" -c "from jace_social_app.desktop import metainfo; print(metainfo(), end='')" \
  > "$APPDIR/usr/share/metainfo/$APP_ID.metainfo.xml"

echo "==> appimagetool"
mkdir -p dist
OUT="$ROOT/dist/JaceSocial-$VERSION-x86_64.AppImage"
ARCH=x86_64 APPIMAGE_EXTRACT_AND_RUN=1 "$TOOL" --no-appstream "$APPDIR" "$OUT"
echo
echo "Built $OUT ($(du -h "$OUT" | cut -f1))"
echo "Run it once to open the setup wizard, or:  $OUT --install --yes"
