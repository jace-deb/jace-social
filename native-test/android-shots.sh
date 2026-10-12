#!/usr/bin/env bash
# CI (native.yml): install the Android test build on the emulator and screenshot its screens,
# signed in against the fake API (mock_api.py, running on the host: 10.0.2.2 from the emulator).
set -x
APP=dev.jacedeb.jacesocial
adb install -r dl/*.apk
adb shell monkey -p $APP -c android.intent.category.LAUNCHER 1
sleep 15
adb exec-out screencap -p > android-start.png
adb shell pidof $APP
crashed=""
open() {    # open <route> <name>: start the app on that screen and screenshot it
  adb shell "am start -S -n $APP/.MainActivity --es jace_token demo --es jace_open '$1'"
  sleep 10
  adb exec-out screencap -p > "android-$2.png"
  adb shell pidof $APP > /dev/null || crashed="$crashed $2"
}
adb shell am start -S -n $APP/.MainActivity --es jace_token demo
sleep 10
adb exec-out screencap -p > android-chats.png
open "chat/dm/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb?title=Steve" chat
open "server/22222222-2222-2222-2222-222222222222?title=Jace%20Craft" server
open "profile/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" profile
open "server-settings/22222222-2222-2222-2222-222222222222" server-settings
open "you" you
open "bots" bots
adb logcat -d -s AndroidRuntime:E | tail -80
if [ -n "$crashed" ]; then echo "The app stopped on:$crashed"; exit 1; fi
