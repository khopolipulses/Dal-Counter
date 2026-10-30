#!/usr/bin/env bash
# End-to-end test on a real Android device or emulator (run by CI after the APK is built).
#
#   device-test.sh <app.apk> <testdata dir> <output dir>
#
# Drives the app like a user (share a photo in, review, history, camera, dark mode, rotation), attacks it like a
# hostile app (file:// Uris into private files, reading the private provider, junk intents), stress-tests it with
# thousands of random taps, and fails on any crash, ANR or wrong behaviour. Screenshots go to <output dir>.
set -uo pipefail

APK=$1
DATA=$2
OUT=$3
PKG=com.dalcounter
mkdir -p "$OUT"
FAILS=0

say() { echo "== $*"; }
ok() { echo "  ok   $*"; }
bad() { echo "  FAIL $*"; FAILS=$((FAILS + 1)); }
shot() { adb exec-out screencap -p > "$OUT/$1.png"; }
sh_() { adb shell "$@" | tr -d '\r'; }

# The app must be the resumed activity (it did not crash or close).
alive() {
  sh_ dumpsys activity activities | grep -E "(topResumedActivity|mResumedActivity|ResumedActivity)" | grep -q "$PKG"
}

no_crash() {
  if adb logcat -d -b crash | grep -q "$PKG"; then
    bad "crash in $1"
    adb logcat -d -b crash > "$OUT/crash-$1.txt"
    adb logcat -c -b crash
  else
    ok "no crash: $1"
  fi
}

# Taps the centre of the first on-screen element whose text or description matches (regex).
tap_text() {
  sh_ uiautomator dump /sdcard/ui.xml > /dev/null
  adb pull /sdcard/ui.xml "$OUT/ui.xml" > /dev/null 2>&1
  python3 - "$OUT/ui.xml" "$1" <<'PY'
import re, sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
pat = re.compile(sys.argv[2])
for n in root.iter("node"):
    if pat.search(n.get("text", "")) or pat.search(n.get("content-desc", "")):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
        print((x1 + x2) // 2, (y1 + y2) // 2)
        sys.exit(0)
sys.exit(1)
PY
}

tap() {
  local xy
  if xy=$(tap_text "$1"); then
    adb shell input tap $xy
    return 0
  fi
  return 1
}

screen_has() {
  sh_ uiautomator dump /sdcard/ui.xml > /dev/null
  adb pull /sdcard/ui.xml "$OUT/ui.xml" > /dev/null 2>&1
  grep -Eq "$1" "$OUT/ui.xml"
}

media_uri() { # content Uri of a picture pushed to /sdcard/Pictures
  sh_ content query --uri content://media/external/images/media --projection _id:_display_name \
    | grep "_display_name=$1" | sed 's/.*_id=\([0-9]*\).*/\1/' | head -1 | sed 's#^#content://media/external/images/media/#'
}

say "install"
adb install -r -g "$APK" || { echo "install failed"; exit 1; }
adb logcat -c
adb logcat -c -b crash
sh_ settings put system accelerometer_rotation 0
sh_ settings put system user_rotation 0
sh_ cmd uimode night no

say "first launch"
sh_ am start -W -n $PKG/.MainActivity > /dev/null
sleep 4
shot 01_home
alive && ok "home screen shows" || bad "app not running after launch"
screen_has "Take Photo" && ok "Take Photo button present" || bad "Take Photo missing"

say "share a photo into the app (lentils + printed text)"
for f in "$DATA"/*.jpg; do adb push "$f" /sdcard/Pictures/ > /dev/null; done
sh_ content call --uri content://media --method scan_volume --arg external_primary > /dev/null 2>&1
sleep 3
URI=$(media_uri lentils_with_print.jpg)
echo "  uri: $URI"
sh_ am start -W -a android.intent.action.SEND -t image/jpeg --eu android.intent.extra.STREAM "$URI" \
  --grant-read-uri-permission -n $PKG/.MainActivity > /dev/null
sleep 9
shot 02_result
if screen_has 'grains'; then ok "count shown"; else bad "no count after sharing a photo"; fi
grep -Eo 'text="[0-9]+"' "$OUT/ui.xml" | head -1 | sed 's/^/  count: /'
screen_has 'print marks ignored' && ok "printed marks reported as ignored" || bad "print marks not reported"
no_crash share

say "guided review"
if tap 'Review [0-9]+' || tap '[0-9]+ to check'; then
  sleep 2
  shot 03_review
  screen_has 'exactly one grain' && ok "review card shows" || bad "review card missing"
  for i in $(seq 1 30); do
    tap 'Yes, a grain' || break
    sleep 1
  done
  sleep 2
  shot 04_review_done
  screen_has 'Checked by you' && ok "all doubtful grains checked" || bad "status not 'Checked by you' after review"
else
  bad "no Review button"
fi
no_crash review

say "back goes home, not out of the app (predictive back callback)"
adb shell input keyevent KEYCODE_BACK
sleep 3
alive && ok "still in the app after Back" || bad "Back closed the app"
screen_has 'Take Photo' && ok "Back returned to Home" || bad "Back did not return to Home"
shot 05_home_recent
screen_has 'grains' && ok "count kept in Recent" || bad "count not in Recent"

say "history"
tap 'See All' && sleep 2
shot 06_history
screen_has 'History' && ok "history list" || bad "history did not open"
tap '[0-9]+ grains' && sleep 3
shot 07_picture
alive && ok "saved picture opens" || bad "picture screen crashed"
adb shell input keyevent KEYCODE_BACK; sleep 1
adb shell input keyevent KEYCODE_BACK; sleep 2
screen_has 'Take Photo' && ok "back to home" || bad "navigation stack broken"
no_crash history

say "camera (the app's own Camera2 screen)"
tap 'Take Photo' && sleep 4
shot 08_camera
if tap 'Take photo'; then
  sleep 9
  shot 09_camera_result
  alive && ok "photo taken and counted" || bad "camera flow crashed"
  adb shell input keyevent KEYCODE_BACK; sleep 2
else
  bad "camera screen did not open"
  adb shell input keyevent KEYCODE_BACK; sleep 2
fi
no_crash camera

say "dark mode + mixed grains with glare"
sh_ cmd uimode night yes
sleep 3
URI2=$(media_uri mixed_with_glare.jpg)
sh_ am start -W -a android.intent.action.SEND -t image/jpeg --eu android.intent.extra.STREAM "$URI2" \
  --grant-read-uri-permission -n $PKG/.MainActivity > /dev/null
sleep 9
shot 10_dark_result
screen_has 'text="60"' && ok "mixed photo with glare counted 60 (exact)" || bad "mixed photo not counted 60"
adb shell input keyevent KEYCODE_BACK; sleep 2
shot 11_dark_home
no_crash dark

say "rotation"
sh_ settings put system user_rotation 1
sleep 3
shot 12_landscape_home
alive && ok "landscape" || bad "rotation crashed"
sh_ settings put system user_rotation 0
sh_ cmd uimode night no
sleep 2

say "security: hostile intents"
# 1. a file:// Uri into the app's own private files must never be opened
sh_ am start -W -a android.intent.action.SEND -t image/jpeg \
  --eu android.intent.extra.STREAM "file:///data/data/$PKG/files/history/index.json" -n $PKG/.MainActivity > /dev/null
sleep 1 # the refusal message is shown for about 2.5 s
shot 13_file_uri_refused
if screen_has 'cannot be opened'; then ok "file:// Uri refused"; else bad "file:// Uri was not refused"; fi
# 2. junk: no stream, wrong type, bogus content Uri
sh_ am start -W -a android.intent.action.SEND -t image/jpeg -n $PKG/.MainActivity > /dev/null
sh_ am start -W -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT hello -n $PKG/.MainActivity > /dev/null
sh_ am start -W -a android.intent.action.SEND -t image/jpeg --eu android.intent.extra.STREAM \
  "content://media/external/images/media/999999999" -n $PKG/.MainActivity > /dev/null
sleep 4
alive && ok "junk intents handled" || bad "junk intent closed the app"
no_crash hostile-intents
adb shell input keyevent KEYCODE_BACK; sleep 1
# 3. the private provider is not reachable from outside, and path tricks go nowhere
for u in "content://com.dalcounter.files/share/x.png" "content://com.dalcounter.files/share/..%2F..%2Ffiles%2Fhistory%2Findex.json"; do
  if sh_ content read --uri "$u" 2>&1 | grep -Eqi "exception|denial|not exported|permission|no content provider|could not"; then
    ok "provider refused $u"
  else
    bad "provider answered $u"
  fi
done
# 4. CameraActivity is not exported
if sh_ am start -n $PKG/.CameraActivity 2>&1 | grep -Eqi "not exported|security|permission denial|error"; then
  ok "camera screen not reachable from other apps"
else
  bad "camera screen is exported"
fi
# 5. no network permission at all
if sh_ dumpsys package $PKG | grep -q "android.permission.INTERNET"; then bad "app has INTERNET"; else ok "no INTERNET permission"; fi

say "stress: 4000 random events"
sh_ am start -W -n $PKG/.MainActivity > /dev/null
adb logcat -c -b crash
MONKEY=$(sh_ monkey -p $PKG --pct-syskeys 0 --pct-appswitch 5 --throttle 25 -s 4242 -v 4000 2>&1)
echo "$MONKEY" | tail -3
if echo "$MONKEY" | grep -Eq "CRASH|ANR|Exception"; then
  bad "monkey found a crash or ANR"
  echo "$MONKEY" > "$OUT/monkey.txt"
else
  ok "4000 random events, no crash or ANR"
fi
no_crash monkey
shot 14_after_monkey

say "process death: the app is killed in the background and comes back"
sh_ am start -W -a android.intent.action.SEND -t image/jpeg --eu android.intent.extra.STREAM "$URI" \
  --grant-read-uri-permission -n $PKG/.MainActivity > /dev/null
sleep 8
adb shell input keyevent KEYCODE_HOME; sleep 2
sh_ am kill $PKG
sleep 2
sh_ am start -W -n $PKG/.MainActivity > /dev/null
sleep 8
shot 15_after_process_death
alive && ok "restored after process death" || bad "did not come back after process death"
no_crash process-death

adb logcat -d > "$OUT/logcat.txt"
echo
if [ "$FAILS" -eq 0 ]; then echo "DEVICE TEST: ALL PASSED"; else echo "DEVICE TEST: $FAILS FAILED"; exit 1; fi
