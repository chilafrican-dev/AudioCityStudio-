#!/bin/bash
# Usage: screens.sh app.apk   Installs the app on the running emulator, opens a few pages and prints PNG screenshots
# to the log as base64 between ==PNG name== / ==END== markers (so they can be checked without downloading artifacts).
apk="$1"; pkg=ug.audiocity.studio
adb wait-for-device
adb install -r -g "$apk" >/dev/null 2>&1 || { echo "install failed"; exit 1; }
adb shell wm size 1080x2400; adb shell wm density 420
adb shell am start -W -n $pkg/.MainActivity >/dev/null 2>&1; sleep 25
tap_text() { # tap the first view whose text or content-desc contains $1
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
  python3 - "$1" <<'PY'
import re,sys,subprocess
x=open('/tmp/ui.xml',encoding='utf-8',errors='ignore').read()
for m in re.finditer(r'<node [^>]*>',x):
    n=m.group(0)
    t=re.search(r' text="([^"]*)"',n).group(1); d=re.search(r'content-desc="([^"]*)"',n).group(1)
    q=sys.argv[1].lower(); tl=t.lower(); dl=d.lower()
    if tl and (q==tl or q in tl and len(tl)<20) or (dl and q in dl and len(dl)<60):
        b=list(map(int,re.findall(r'\d+',re.search(r'bounds="([^"]*)"',n).group(1))))
        subprocess.call(['adb','shell','input','tap',str((b[0]+b[2])//2),str((b[1]+b[3])//2)]); print("tapped",sys.argv[1]); break
else: print("not found",sys.argv[1])
PY
  sleep 3; }
shot() { adb shell screencap -p /sdcard/s.png; adb pull /sdcard/s.png "/tmp/$1.png" >/dev/null 2>&1; python3 -c "
from PIL import Image; im=Image.open('/tmp/$1.png').convert('RGB'); im=im.resize((im.width//2,im.height//2)); im.save('/tmp/$1.jpg',quality=70)" 2>/dev/null || cp "/tmp/$1.png" "/tmp/$1.jpg"
  echo "==PNG $1=="; base64 -w0 "/tmp/$1.jpg"; echo; echo "==END=="; }
shot start
for w in "OK" "Close" "Not now" "Later" "Skip" "Cancel"; do tap_text "$w"; done
adb shell am start -n $pkg/.MainActivity >/dev/null 2>&1; sleep 4
shot app
for k in 1 2; do tap_text "＋ Track"; tap_text "Apply"; done; shot arrange
tap_text "Mixer"; sleep 2; shot mixer
adb shell input swipe 540 1200 540 500 400; sleep 2; shot mixer-scrolled
exit 0
