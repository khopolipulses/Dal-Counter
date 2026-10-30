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
  dump
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

dump() { # uiautomator sometimes returns an empty tree while the screen animates: retry
  for i in 1 2 3 4; do
    if sh_ uiautomator dump /sdcard/ui.xml 2>&1 | grep -q "dumped to"; then
      adb pull /sdcard/ui.xml "$OUT/ui.xml" > /dev/null 2>&1
      return 0
    fi
    sleep 1
  done
  return 1
}

screen_has() {
  dump
  grep -Eq "$1" "$OUT/ui.xml"
}

# The big number on the result screen (the only text that is just digits).
shown_count() {
  dump
  grep -Eo 'text="[0-9]+"' "$OUT/ui.xml" | head -1 | grep -Eo '[0-9]+'
}

app_log() { adb logcat -d -s GrainCounter | tr -d '\r'; }

resumed() { sh_ dumpsys activity activities | grep -E "ResumedActivity" | head -1; }

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

say "open a photo with the system photo picker, as a user does (lentils + printed text, 100 grains)"
adb push "$DATA/lentils_with_print.jpg" /sdcard/Pictures/ > /dev/null
sh_ content call --uri content://media --method scan_volume --arg external_primary > /dev/null 2>&1
sleep 3
adb logcat -c
tap 'Choose from Photos' && sleep 5
shot 02a_picker
if tap '^Photo' || tap 'lentils_with_print'; then
  sleep 10
  shot 02_result
  N=$(shown_count)
  echo "  count: $N (100 grains in the photo)"
  if [ -n "$N" ] && [ "$N" -ge 95 ] && [ "$N" -le 110 ]; then ok "photo from the picker counted"; else bad "picker photo not counted"; app_log; fi
  screen_has 'print marks ignored' && ok "printed marks reported as ignored" || bad "print marks not reported"
else
  bad "could not pick a photo in the system picker"
fi
no_crash picker

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
screen_has 'text="[0-9]+ grains"' && ok "count kept in Recent" || bad "count not in Recent"

say "history"
tap 'See All' && sleep 2
shot 06_history
screen_has 'History' && ok "history list" || bad "history did not open"
if tap '^[0-9]+ grains$'; then
  sleep 3
  shot 07_picture
  alive && ok "saved picture opens" || bad "picture screen crashed"
  adb shell input keyevent KEYCODE_BACK; sleep 2
fi
adb shell input keyevent KEYCODE_BACK; sleep 2
screen_has 'Take Photo' && ok "back to home" || bad "navigation stack broken"
no_crash history

say "camera (the app's own Camera2 screen)"
tap 'Take Photo' && sleep 5
shot 08_camera
echo "  resumed: $(resumed)"
if resumed | grep -q CameraActivity && tap 'Take photo'; then
  sleep 9
  shot 09_camera_result
  alive && ok "photo taken and counted" || bad "camera flow crashed"
  adb shell input keyevent KEYCODE_BACK; sleep 2
else
  bad "camera screen did not open"
  adb shell input keyevent KEYCODE_BACK; sleep 2
fi
no_crash camera

say "dark mode + mixed grains with glare (60 grains), shared into the app from another app"
sh_ cmd uimode night yes
sleep 3
adb push "$DATA/mixed_with_glare.jpg" /sdcard/Pictures/ > /dev/null
sh_ content call --uri content://media --method scan_volume --arg external_primary > /dev/null 2>&1
sleep 3
URI2=$(media_uri mixed_with_glare.jpg)
adb logcat -c
# "Share to Grain Counter" path. The shell can only pass on access it has itself.
if sh_ content read --uri "$URI2" > /dev/null 2>&1; then
  sh_ am start -W -a android.intent.action.SEND -t image/jpeg --eu android.intent.extra.STREAM "$URI2" \
    --grant-read-uri-permission -n $PKG/.MainActivity > /dev/null
else
  echo "  (the shell cannot read media here, so it cannot share; opening through the picker instead)"
  tap 'Choose from Photos' && sleep 5 && tap '^Photo'
fi
sleep 10
shot 10_dark_result
N=$(shown_count)
echo "  count: $N"
[ "$N" = "60" ] && ok "mixed grains with glare counted 60 (exact)" || { bad "mixed photo counted '$N', not 60"; app_log; }
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
sleep 2
shot 13_file_uri_refused
if app_log | grep -q "refused shared Uri with scheme file"; then ok "file:// Uri into private files refused"; else bad "file:// Uri was not refused"; fi
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
CAM=$(sh_ am start -W -n $PKG/.CameraActivity 2>&1)
echo "$CAM" | sed 's/^/  am: /' | head -4
if echo "$CAM" | grep -Eqi "not exported|security|permission denial|error"; then
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
sh_ am start -W -n $PKG/.MainActivity > /dev/null
sleep 2
tap 'Choose from Photos' && sleep 5 && tap '^Photo' && sleep 10
shot 15a_before_kill
adb shell input keyevent KEYCODE_HOME; sleep 2
sh_ am kill $PKG
sleep 2
sh_ am start -W -n $PKG/.MainActivity > /dev/null
sleep 8
shot 15_after_process_death
alive && ok "restored after process death" || bad "did not come back after process death"
N=$(shown_count)
[ -n "$N" ] && ok "count screen restored ($N grains)" || bad "count screen not restored after process death"
no_crash process-death

adb logcat -d > "$OUT/logcat.txt"
app_log > "$OUT/app-log.txt"
echo
if [ "$FAILS" -eq 0 ]; then echo "DEVICE TEST: ALL PASSED"; else echo "DEVICE TEST: $FAILS FAILED"; exit 1; fi
