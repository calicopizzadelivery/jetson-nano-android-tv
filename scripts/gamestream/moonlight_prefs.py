#!/usr/bin/env python3
"""
Set Moonlight's preferences on the Jetson without going through its menus.

    scripts/gamestream/moonlight_prefs.py list_resolution=1920x1080 list_fps=60 \
        seekbar_bitrate_kbps=20000 video_format=forceh265 frame_pacing=latency \
        checkbox_enable_perf_overlay=true

Keys and values are Moonlight's own (com.limelight_preferences.xml):
  list_resolution   1280x720 1920x1080 2560x1440 3840x2160 ...
  list_fps          30 60 90 120
  seekbar_bitrate_kbps   an integer
  video_format      auto forceav1 forceh265 neverh265 (neverh265 = H.264)
  frame_pacing      latency balanced cap-fps smoothness
  checkbox_*        true false
Needs adb root. ADB overrides the adb command (on this bench:
ADB="docker exec -i adbnode adb").
"""

import os
import re
import shlex
import subprocess
import sys

PKG = "com.limelight"
PREFS = f"/data/data/{PKG}/shared_prefs/{PKG}_preferences.xml"
ADB = shlex.split(os.environ.get("ADB", "adb"))


def adb_shell(cmd, stdin=None):
    return subprocess.run(ADB + ["shell", cmd], input=stdin, capture_output=True,
                          text=True, check=True).stdout


def main():
    pairs = [a.split("=", 1) for a in sys.argv[1:]]
    if not pairs or any(len(p) != 2 for p in pairs):
        print(__doc__)
        sys.exit(2)
    adb_shell(f"am force-stop {PKG}")
    xml = adb_shell(f"cat {PREFS}")
    for key, value in pairs:
        if key.startswith("checkbox_"):
            line = f'<boolean name="{key}" value="{value}" />'
            pat = rf'<boolean name="{re.escape(key)}" value="[^"]*" />'
        elif key == "seekbar_bitrate_kbps" or key.startswith("seekbar_"):
            line = f'<int name="{key}" value="{int(value)}" />'
            pat = rf'<int name="{re.escape(key)}" value="[^"]*" />'
        else:
            line = f'<string name="{key}">{value}</string>'
            pat = rf'<string name="{re.escape(key)}">[^<]*</string>'
        if re.search(pat, xml):
            xml = re.sub(pat, line, xml)
        else:
            xml = xml.replace("</map>", f"    {line}\n</map>")
    owner = adb_shell(f"stat -c %u:%g {PREFS}").strip()
    adb_shell(f"cat > {PREFS}.new && mv {PREFS}.new {PREFS} && chown {owner} {PREFS} "
              f"&& chmod 660 {PREFS} && restorecon {PREFS}", stdin=xml)
    for key, _ in pairs:
        m = re.search(rf'name="{re.escape(key)}"[^\n]*', xml)
        print(m.group(0) if m else key)


if __name__ == "__main__":
    main()
