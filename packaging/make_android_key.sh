#!/usr/bin/env bash
# Makes the Android release key for Jace Social's phone app and stores it as GitHub secrets
# (ANDROID_KEYSTORE_BASE64, ANDROID_KEYSTORE_PASSWORD), so every release is signed with the
# same key and phones can install updates. Run once, from the repo, with gh signed in.
# A copy is kept in ~/.config/jace-social/ - back it up: without it, the app can't be updated.
set -euo pipefail
KEYTOOL=$(command -v keytool || ls ~/.cache/jace-jdks/*/bin/keytool 2>/dev/null | head -1)
[ -n "$KEYTOOL" ] || { echo "keytool not found (it comes with a JDK)"; exit 1; }
DIR=~/.config/jace-social
KS="$DIR/android-release.jks"
mkdir -p "$DIR" && chmod 700 "$DIR"
if [ -e "$KS" ]; then
  echo "A key already exists at $KS - using it (delete it first to make a new one)"
  PASS=$(cat "$DIR/android-release.pass")
else
  PASS=$(head -c 24 /dev/urandom | base64 | tr -d '/+=')
  "$KEYTOOL" -genkeypair -keystore "$KS" -storetype PKCS12 -alias jace-social -keyalg RSA -keysize 4096 -validity 36500 \
    -storepass "$PASS" -keypass "$PASS" -dname "CN=Jace Social" >/dev/null
  printf '%s' "$PASS" > "$DIR/android-release.pass"
  chmod 600 "$KS" "$DIR/android-release.pass"
fi
base64 -w0 "$KS" | gh secret set ANDROID_KEYSTORE_BASE64
printf '%s' "$PASS" | gh secret set ANDROID_KEYSTORE_PASSWORD
echo "Done: the release key is set on GitHub. Back up $DIR somewhere safe."
