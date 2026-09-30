#!/system/bin/sh
# eMMC write snapshot, run on the device as root. Prints only to stdout, so
# taking it writes nothing. Pair two of these with scripts/emmc-diff.py; see
# docs/emmc-writes.md.
#
#   adb push scripts/emmc-snapshot.sh /dev/snap.sh
#   adb shell sh /dev/snap.sh > t0.txt   ... wait ...   > t1.txt
echo "## time $(cat /proc/uptime | cut -d' ' -f1) $(date +%s)"
echo "## diskstats"; grep -E " mmcblk0(p[0-9]+)? " /proc/diskstats
echo "## procio"
for p in /proc/[0-9]*; do
  w=$(grep -m1 "^write_bytes" $p/io 2>/dev/null | cut -d' ' -f2)
  [ -z "$w" ] && continue
  c=$(tr '\0' ' ' < $p/cmdline 2>/dev/null | cut -c1-80); [ -z "$c" ] && c="[$(cat $p/comm 2>/dev/null)]"
  echo "${p#/proc/}|$(stat -c %U $p 2>/dev/null)|$w|$c"
done
echo "## uidio"; cat /proc/uid_io/stats 2>/dev/null | head -200
