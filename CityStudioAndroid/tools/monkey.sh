#!/bin/bash
# Usage: monkey.sh app.apk out-dir   (needs a running emulator or phone on adb)
apk="$1"; out="$2"; pkg=ug.audiocity.studio
mkdir -p "$out"
adb wait-for-device
adb install -r -g "$apk" > "$out/install.txt" 2>&1 || { cat "$out/install.txt"; exit 1; }
adb shell pm grant $pkg android.permission.RECORD_AUDIO || true
adb shell pm grant $pkg android.permission.POST_NOTIFICATIONS || true
adb logcat -c || true
adb shell am start -W -n $pkg/.MainActivity > "$out/start.txt" 2>&1
sleep 15
for seed in 11 22 33 44 55 66 77 88; do
  echo "== seed $seed" >> "$out/monkey.txt"
  adb shell monkey -p $pkg -s $seed --throttle 150 --pct-syskeys 0 --pct-appswitch 2 --pct-anyevent 0 \
    --ignore-crashes --ignore-timeouts --ignore-security-exceptions -v 2500 >> "$out/monkey.txt" 2>&1
  adb shell am start -n $pkg/.MainActivity > /dev/null 2>&1; sleep 4
done
adb logcat -d -b crash > "$out/crash-buffer.txt" 2>&1
adb logcat -d -v time > "$out/logcat.txt" 2>&1
{
  echo "Monkey events sent: $(grep -c ':Sending' "$out/monkey.txt")"
  echo "Crashes seen by monkey: $(grep -c 'CRASH:' "$out/monkey.txt")"
  echo "Freezes (ANR) seen by monkey: $(grep -c 'NOT RESPONDING' "$out/monkey.txt")"
  echo
  echo "===== Distinct crashes ====="
  awk '/FATAL EXCEPTION/{p=1;n=0} p{print; n++; if(n>40){p=0; print "-----"}}' "$out/logcat.txt" | grep -v "^--------- beginning" | head -800
  echo
  echo "===== What happened before each crash of the app ====="
  grep -n "CRASH: $pkg" "$out/monkey.txt" | cut -d: -f1 | while read n; do echo "--- monkey actions before crash (line $n)"; sed -n "$((n>60?n-60:1)),$((n+3))p" "$out/monkey.txt" | grep -v "^\s*//\s*$"; done | head -400
  for pid in $(grep "Process: $pkg, PID:" "$out/logcat.txt" | sed 's/.*PID: //' | sort -u); do
    echo "--- app log, process $pid (last 120 lines before death)"
    grep "( *$pid)" "$out/logcat.txt" | grep -v "Choreographer\|OpenGLRenderer\|EGL_emulation" | tail -120
  done
  echo
  echo "===== ANRs ====="
  grep -A20 "ANR in $pkg" "$out/logcat.txt" | head -200
} > "$out/summary.txt"
cat "$out/summary.txt"
exit 0
