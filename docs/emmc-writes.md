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

## Power loss: write barriers on /data

These boxes will have their power pulled routinely. `/data` is mounted
`barrier=0,noauto_da_alloc`, from SHIELD's fstab. `fstab.porg` is
`device/nvidia/foster/initfiles/fstab.emmc` unchanged, installed by foster's
`device.mk` to `/vendor/etc` and the first-stage ramdisk. `/cache` already has
`barrier=1`.

**What the eMMC does itself** (EXT_CSD, read from
`/d/mmc0/mmc0:0001/ext_csd`):

- It has a 4 MiB volatile write cache, enabled (`CACHE_CTRL` 1). The queue
  reports `write back`, so the kernel knows it needs flushing.
- The cache flushes FIFO (`CACHE_FLUSH_POLICY` 1), so writes reach flash in
  the order they were acknowledged.
- Write reliability is set for every partition (`WR_REL_SET` 0x1f). A power
  cut mid-write should not corrupt data already stored.

**What `barrier=0` costs, then:** without cache flushes, fsync returns once
data is in that 4 MiB cache, not on flash. A power pull can lose writes an app
was told were safe: a setting, a database commit, a Kodi library update. FIFO
flushing and write reliability make a broken journal much less likely than on
an ordinary device, but they do nothing for durability.

**What barriers cost** (300 single-row SQLite transactions, 1 KiB each,
`synchronous=FULL`, `/data` remounted live, repeated twice):

| Journal mode | barrier=0 | barrier=1 | |
| --- | --- | --- | --- |
| DELETE (rollback) | 7.6 ms/txn | 12.3 ms/txn | +60% |
| WAL (Android's usual) | 4.4 ms/txn | 5.7 ms/txn | +30% |

That is about 1-1.5 ms more per fsync. At the idle rates above it is
invisible. It shows up in write-heavy moments: installs, dexopt, library
scans.

**The change:**

1. Ship porg's own fstab from the porg fork, rather than forking foster:
   `device/nvidia/porg/initfiles/fstab.porg` and `fstab.porg_sd`, copied from
   foster's `fstab.emmc` and `fstab.sd`. Install them with `PRODUCT_COPY_FILES`
   to `$(TARGET_COPY_OUT_VENDOR)/etc/fstab.porg[_sd]` and
   `$(TARGET_COPY_OUT_RAMDISK)/fstab.porg[_sd]`. porg's own entries come ahead
   of inherited ones, and the first entry for a destination wins. Check
   `out/.../vendor/etc/fstab.porg` after the build.
2. In both, change `/data` to `barrier=1` and drop `noauto_da_alloc`. With
   `auto_da_alloc`, ext4 forces out a file's data when it is renamed over or
   truncated. Apps that save by write-then-rename without fsync, as plenty of
   non-framework code does, then do not come back as zero-length files after a
   pull.
3. Add `check` to `/cache`, so it gets an fsck after an unclean shutdown as
   `/data` does.
4. It is a vendor and ramdisk change, so it needs a full image. Afterwards,
   `mount` must not show `nobarrier` for `/data`, and
   `/proc/fs/ext4/mmcblk0p22/options` shows `barrier`.
5. Prove it with the relay controller: pull power repeatedly in the middle of
   a stream of SQLite commits, and after each boot check what survived against
   what was reported committed, and what `e2fsck` found. Do it before and
   after, so the difference is measured, not assumed.

**The real fix is hardware.** A power-fail signal and a little hold-up
capacitance would let the kernel flush and send the eMMC its power-off
notification before the rails drop. That belongs to the carrier-board
project, not this image.

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
