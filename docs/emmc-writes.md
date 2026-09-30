# eMMC writes

The production modules boot from a 16 GB eMMC that cannot be replaced, so
anything that writes to it constantly is a lifetime problem. This is what the
image writes, measured on porg on 30 September 2026. Nothing in the image is
write-bashing.

## The device

`mmc0:0001`: SanDisk iNAND **DG4016** (manfid 0x45), 16 GB, made 04/2019. Its
own wear report, from `/sys/class/mmc_host/mmc0/mmc0:0001/`, is
`life_time 0x01 0x01` (0-10% of rated life used, for both the SLC and MLC
areas) and `pre_eol_info 01` (normal). Re-read those two files now and then;
they are the only first-hand wear gauge there is.

`/data` (UDA, `mmcblk0p22`) is ext4 with `noatime`. Swap is zram with no
backing device, so it is RAM only. The kernel's writeback settings are the
defaults: 5 s journal commit, 30 s dirty expiry.

## Measured

Per-process `write_bytes`, per-uid `/proc/uid_io/stats` and the eMMC's block
counters were snapshotted every five minutes over a 38-minute window. The
screen stayed on with no input for the first 17 minutes, and the screensaver
ran for the remaining 22. Everything was printed over adb to the host, so the
measurement wrote nothing itself.

| Phase | Written to eMMC | Rate |
| --- | --- | --- |
| Idle, screen on (16.6 min) | 668 KB | 2.4 MB/h, about 0.06 GB/day |
| Screensaver running (21.9 min) | 2.4 MB | 6.4 MB/h, about 0.15 GB/day |
| Boot, first 9 minutes | 17.3 MB (all uids) | once per boot |

All of it went to `/data`, in bursts with minutes of silence between them.
The files behind the bursts:

- **AmbientDream**: one photo into its cache, 487 KB, when it started. It
  shows a new photo every 30 minutes and keeps at most 40, so a full day
  dreaming is about 24 MB at worst. Weather is held in memory.
- **`system_server` bookkeeping**:
  - `batterystats.bin` and `battery-history`, about 110 KB, even though the
    box has no battery;
  - usage stats and `app_idle_stats.xml`, flushed about every 20 minutes;
  - `procstartinfo` and `procexitinfo`;
  - appops history.
  Each burst is a few hundred KB, with the ext4 journal about doubling it.
- **Boot**:
  - `system_server`: 5.5 MB.
  - uid 1037, `shared_relro`: 5.0 MB. WebView's RELRO files are regenerated
    every boot.
  - Bluetooth: 3.8 MB. These are crash dumps, below. `crash_dump` writes the
    tombstone as the crashing uid, which is why they count against Bluetooth.

For scale: a 16 GB MLC part rated around 3,000 program/erase cycles, allowing
generous write amplification, has well over 10 TB of host writes in it. At the
rates above, the image's own writes would take far longer than the hardware
will be in use. Wear-out, if it comes, will come from what users install, or
from something pathological like a crash loop or persistent logging.

## Worth doing anyway

1. **Stop the Bluetooth crash loop on boxes with no radio.** With no
   controller, `com.android.bluetooth` aborts in `hci_backend_aidl.cc:34`
   (`initializationComplete: status == SUCCESS`) about six times per boot. Each
   abort writes about 400 KB of text tombstone, 250 KB of protobuf, and dropbox
   copies. It is bench-only once radios are fitted, but a box shipped without
   one should leave Bluetooth off rather than retry: `settings put global
   bluetooth_on 0`, or better, have the HAL report no hardware.
2. **Keep persistent logging off in release images.**
   `logd.logpersistd.enable` is true (the userdebug default), but nothing has
   turned it on (`persist.logd.logpersistd` is empty, and `/data/misc/logd` is
   empty). `logpersist.start` would write continuously. A user build does not
   offer it.
3. **Kodi, Plex and the like** are where real writes will come from: Kodi's
   texture cache and databases, any download or transcode cache. Point caches
   at RAM where the app allows it, and measure once they are installed. The
   scripts below do that.

Not worth changing on these numbers: the ext4 commit interval, the dirty
writeback timers, batterystats, or usage stats. Each would trade a few hundred
KB an hour for less safety on power loss.

**A separate robustness note, not about wear.** `/data` is mounted
`nobarrier` by the device fstab. That skips cache flushes on journal commits,
so a power cut can leave ext4 inconsistent. For an appliance people unplug,
consider dropping it. Measure first, since barriers cost some write
performance.

## Measuring again

On the device, as root:

```
echo 1 > /sys/block/mmcblk0/queue/iostats   # off by default; resets at boot
```

Without that, `/proc/diskstats` barely moves for the eMMC, which is easy to
mistake for "no writes". Then:

```
adb push scripts/emmc-snapshot.sh /dev/snap.sh      # /dev is tmpfs: no eMMC write
adb shell sh /dev/snap.sh > t0.txt
# ... leave it for as long as the question needs ...
adb shell sh /dev/snap.sh > t1.txt
adb shell pm list packages -U > uids.txt
scripts/emmc-diff.py t0.txt t1.txt uids.txt
```

and `adb shell find /data -xdev -type f -mmin -N` to name the files behind
the numbers.

`settings get global stay_on_while_plugged_in` is 1 on the bench (Developer
options, Stay awake), which stops the screensaver from starting on its own.
Start it with `am start -a com.google.android.pano.action.SLEEP` when
measuring.
