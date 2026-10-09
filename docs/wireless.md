# Wi-Fi and Bluetooth on porg

Status, 8 October 2026: **Wi-Fi and Bluetooth both work on an Intel
Wireless-AC 8265**, from a cold boot, with no hand-holding, and a BLE
controller pairs and drives the UI. Android enumerates the card, loads the
firmware, brings up `wlan0`, and scans both bands; the picker in Settings
lists real access points. Associating to one has not been done — it needs a
passphrase — and nothing has been measured for throughput.

That took a kernel change, a firmware drop, and **five separate defects** in
NVIDIA's wireless bring-up, every one of which had been sitting in the tree
unexercised. They are listed below because each of them would bite anyone
fitting any card, not only an Intel one.

## The card

**Intel Dual Band Wireless-AC 8265** (8265NGW, `8086:24fd`, M.2 2230 Key E),
`REV=0x230`. Wi-Fi 5, 2x2, with Bluetooth 4.2 on the USB half.

It is the first **SoftMAC** radio this tree has ever had. Every other radio it
knows about — Broadcom, Cypress, Realtek — is FullMAC, which is why
`CONFIG_MAC80211` was not set in any tegra defconfig and no SoftMAC driver
could be built at all.

Previous notes in this repo said the AC8265 was unsupported. That was wrong,
and it was wrong in a specific way worth keeping: `iwlwifi` was already in the
kernel *source*, naming the 8265 explicitly. What was missing was the config,
the firmware, and a loader that ran.

## What had to change

### 1. The kernel had no SoftMAC stack

`kernel/nvidia/kernel-4.9`, `arch/arm64/configs/tegra_android_defconfig`:
`CONFIG_MAC80211=m`, `CONFIG_MAC80211_RC_MINSTREL_VHT=y`, `CONFIG_IWLWIFI=m`,
`CONFIG_IWLMVM=m`. `mac80211` is a module to match `cfg80211`, which has to
stay modular because Cypress's fork of it is loaded at boot by another path
(see defect 3).

### 2. No firmware shipped

`scripts/in-container/prebuilt_firmware.py` fetches the pinned files listed in
`Firmware/firmware.json` into `/dlcache`, checks their sha256, and stages them
under `vendor/jetson-tv/Firmware` with a generated `firmware.mk` that installs
them to `/vendor/firmware`. Same arrangement as `PrebuiltApps`: **nothing
binary is committed to this repo**, and a cache hit needs no network.

Pinned to a linux-firmware **tag**, not `main`. This kernel's `iwlwifi`
accepts 8265 ucode API 22 to 26 and asks for those filenames exactly;
`main` carries only 34 and 36, which this driver would never request. Before
touching the pins, read `IWL8265_UCODE_API_MIN/MAX` in
`drivers/net/wireless/intel/iwlwifi/iwl-8000.c`.

The driver counts down from the newest API it knows, so the boot log shows
several failures before the hit. That is normal:

    iwlwifi: Direct firmware load for iwlwifi-8265-26.ucode failed, error -2
    ...
    iwlwifi: loaded firmware version 22.391740.0 op_mode iwlmvm
    iwlwifi: Detected Intel(R) Dual Band Wireless AC 8265, REV=0x230

The kernel's direct load is denied by SELinux (the files are `vendor_file`)
and falls back to the userspace helper, which succeeds. The existing Broadcom
firmware behaves identically, so this is the tree's normal path, not a fault.

### 3. The wifi loader never ran its own code

`device/nvidia/tegra-common/initfiles/wifi_loader.sh` defines
`perform_enumeration()` and `load_modules()` **and calls neither** — upstream
included. The script only logged `WiFi auto card detection fail` and went on
to the symlinks. Every board, every card.

Calling them exposed the rest of this list in a single boot, which is the
clearest evidence that nothing had ever executed that path.

### 4. The Cypress stack squats on cfg80211

`device/nvidia/foster/initfiles/lkm_loader_target.sh` loads Cypress's
Broadcom stack on every boot, keyed on **the device tree saying the Broadcom
slot exists** — not on a Broadcom card being in it:

    if [ "`cat /proc/device-tree/brcmfmac_pcie_wlan/status`" = "okay" ]; then
        insmod compat.ko ; insmod cy_cfg80211.ko
        insmod brcmutil.ko ; insmod brcmfmac.ko

`cy_cfg80211` is a fork of `cfg80211` that exports the same symbols, so the
kernel's own `cfg80211` is then refused:

    cfg80211: exports duplicate symbol __cfg80211_alloc_event_skb
              (owned by cy_cfg80211)

and **no SoftMAC driver can load on this board at all**, whatever is fitted.

The fix is in `wifi_loader.sh`, which runs afterwards: its Intel branch
unloads `brcmfmac`, `brcmutil`, `cy_cfg80211`, `compat` before inserting the
real stack. Safe because `brcmfmac` sits at refcount 0 with no Broadcom card
bound to it — and if one were fitted, the Intel branch would not be running.

That is the workaround, not the cure. The cure is to gate the Broadcom branch
in `lkm_loader_target.sh` on a Broadcom card actually being on the bus, and
that file lives in `device/nvidia/foster`, which is not one of our forks. See
`docs/open-items.md`.

### 5. sepolicy: the loader could not list, and could not load

Both in `device/nvidia/porg/sepolicy/vendor/wifi_loader.te`:

- `perform_enumeration()` globs `/sys/bus/pci/devices/*` and
  `/sys/bus/sdio/devices/*`. Those two directories are plain `sysfs`, and the
  domain had no `read` on `sysfs:dir` — so the glob never expanded and
  `$vendor` stayed empty. (The `vendor` and `device` nodes *inside* them are
  labelled `sysfs_pci_device` and were already readable. Only the listing was
  missing.)
- `module_load` was granted on `system_file` only, while every module the
  script inserts lives in `/vendor/lib/modules`, which is `vendor_file`. So
  every `insmod` in `load_modules()` was denied.

Plus `proc_modules:file r_file_perms`, for the `lsmod` the Intel branch uses
to decide whether the Cypress stack is still holding cfg80211's symbols.

The policy lives in porg rather than `device/nvidia/sepolicy` because that
repository is LineageOS's; same reasoning as the AirPlay domain.

## Verifying it

A clean boot should produce this, and nothing else is needed:

    $ adb logcat -d -s wifiloader
    wifiloader: WiFi PCIE VendorID: 0x8086, DeviceID: 0x24fd
    wifiloader: unload brcmfmac (Cypress stack, no Broadcom card)
    ... brcmutil, cy_cfg80211, compat ...
    wifiloader: load cfg80211 module
    wifiloader: load mac80211 module
    wifiloader: load iwlwifi module
    wifiloader: load iwlmvm module

    $ adb shell lsmod | grep 80211
    mac80211   843776  1 iwlmvm
    cfg80211   741376  3 iwlmvm,iwlwifi,mac80211

    $ adb shell cmd wifi start-scan && sleep 10 && adb shell cmd wifi list-scan-results

The scan is the test that matters: it exercises the driver, the firmware, the
HAL, `wificond` and `wpa_supplicant` together. Results carry both bands and
parse WPA3/SAE and MFPC flags correctly.

Two things mislead while debugging this:

- **`iw dev wlan0 scan` working proves only the driver.** It bypasses
  everything above `cfg80211`. A card can scan there and still be invisible to
  Android, which is exactly what happened when the modules were hand-loaded
  after the framework had already started. Judge by `cmd wifi`.
- **TvSettings hides the access point list while Ethernet is plugged in**
  ("Unplug Ethernet to use Wi-Fi"). The radio is fine; the UI is deliberate.
  `ip link set eth0 down` brings the list back. adb here is over USB, so that
  does not cut the session.

## Bluetooth

**Works, as of 4 October.** The adapter reaches state `ON` at boot, named
JetsonTV, and `com.android.bluetooth` no longer crash-loops — the aborts that
cost ~660 KB of tombstone per boot (`docs/emmc-writes.md`) were the HAL
failing to start, not the missing radio.

The 8265's Bluetooth half is a USB device: `btusb` binds it and `btintel`
loads `ibt-12-16.sfi` and `.ddc`, both shipped by the same firmware fetcher.

    Bluetooth: hci0: Firmware revision 0.1 build 19 week 44 2021

One sepolicy line stood in the way, and it is an upstream bug worth knowing
about on any pre-4.13 kernel. porg selects the AIDL **default** HAL
(`TARGET_TEGRA_BT := btlinux`), which opens
`socket(PF_BLUETOOTH, SOCK_RAW, BTPROTO_HCI)`. AOSP grants that as
`self:bluetooth_socket` — a class that only exists from **Linux 4.13**, where
`da69a5306ab9` gave every address family its own security class. This kernel
is 4.9, its classmap has no AF_BLUETOOTH entry, so the access arrives as the
generic `self:socket`:

    avc: denied { create } for comm="android.hardwar" tclass=socket
         scontext=u:r:hal_bluetooth_default:s0

The rule upstream wrote is simply never consulted. `hal_bluetooth_btlinux`,
which uses the same transport, carries **both** lines for exactly this reason;
`hal_bluetooth_default` was only ever given the new one. Ours is in
`device/nvidia/porg/sepolicy/vendor/hal_bluetooth_default.te` and belongs
upstream in `system/sepolicy/vendor/`.

Two things to know:

- **`/dev/rfkill` is still denied**, and that is fine. The HAL logs
  `unable to open /dev/rfkill` and carries on — its return value is ignored —
  because the file is labelled plain `device` and AOSP grants nothing for it.
  It only costs the chip power-cycle on enable/disable.
- **There are two Bluetooth rfkill nodes** and the useful one is `rfkill0`
  (`name=hci0`, unblocked). `rfkill1` is `bluedroid_pm`, Tegra's on-board BT
  power control, and is soft-blocked — irrelevant with a USB radio, but it
  looks alarming if you read it first.

**Pairing works, over BLE.** An Xbox Wireless Controller (Series X|S,
`045E:0B13`) bonded on 8 October and produced a working Android input device.
This is the answer to the question that had been open since this project
started — whether BLE works on ARM64 Tegra — and it is yes:

    BluetoothBondStateMachine: BOND_BONDING => BOND_BONDED
    btif_hh_transport_select: [BT_TRANSPORT_LE], bredr_acl:false,
        le_acl:true, hogp_available:true, le_preferred:true
    HidHostService: broadcastConnectionState: ... newState=2
    input: Xbox Wireless Controller as
        /devices/virtual/misc/uhid/0005:045E:0B13.0003/input/input5

Note `bredr_acl:false` and `hogp_available:true`: this is **HID over GATT**,
the Bluetooth Low Energy path, not classic HID. The bond is listed `[ LE ]`.
Android classifies the result `KEYBOARD | GAMEPAD | JOYSTICK | LIGHT |
EXTERNAL` as controller 1 on `/dev/input/event5`, and it drives the launcher
and opens apps.

Everything the BLE HID path needs was already in place and is worth checking
first if it ever stops working: `/dev/uhid` exists and is labelled
`uhid_device`, and the **running** kernel (not just the defconfig — read
`/proc/config.gz`) has `CONFIG_UHID=y`, `CONFIG_BT_HIDP=y` and
`CONFIG_BT_LE=y`. That last one is our kernel fork's commit, so this also
confirms it booted.

Every control reported, captured with `getevent -lt`:

| | |
| --- | --- |
| face | `BTN_GAMEPAD` (A), `BTN_EAST` (B), `BTN_WEST` (X), `BTN_NORTH` (Y) |
| shoulders | `BTN_TL`, `BTN_TR` |
| system | `BTN_SELECT`, `BTN_START`, `BTN_MODE` (Xbox), `KEY_RECORD` (Share) |
| sticks | `ABS_X`/`ABS_Y`, `ABS_Z`/`ABS_RZ` |
| triggers | `ABS_GAS`/`ABS_BRAKE` |
| d-pad | `ABS_HAT0X`/`ABS_HAT0Y` |

No key layout of ours is needed: the image already ships
`/vendor/usr/keylayout/Vendor_045e_Product_0b13.kl`, which maps `316` to
`BUTTON_MODE` — the same keycode our `GamepadKeyHandler` intercepts for the
8BitDo, so the Settings toggle for "gamepad button acts as Home" should apply
to this pad too. **Not yet confirmed by a deliberate single press.**

**Discovery works** too, and found a TCL TV, a Samsung QLED and an NVIDIA
device (OUI `00:04:4B`) advertising with a gamepad class. The adapter survived
the bench USB hub being unplugged for 35 minutes without a reboot: the gadget
dropped, the radio did not.

Worth knowing from that screen: launched directly it says **"Press Back to
skip this step"**. The captive version that blocks the setup wizard is captive
only because the wizard passes `no_input_mode=true` — see the setup-wizard
notes in `CLAUDE.md`. Now that a BLE controller pairs, that step should be
completable for real rather than bypassed.

Two loose ends from the pairing session:

- **`uhid_read_inbound_event: Invalid event from internal uhid-dev: 115`**,
  repeatedly, from `bta_hh_co`. Android's uhid reader does not recognise that
  event type. Most likely an output report — LED or rumble — so **force
  feedback over BLE is unproven**. Chase this only if rumble turns out dead.
- **`btm_sec_rmt_name_request_complete ... HCI_ERR_PAGE_TIMEOUT`** on a
  *different* address, every ~20 s during discovery. The stack found something
  over LE and then tried to read its name over BR/EDR, which an LE-only device
  will never answer. Noise, but it makes the log look broken when it is not.

## Other cards

| card | Wi-Fi | Bluetooth |
| --- | --- | --- |
| Intel 8265 (`8086:24fd`) | works | works, adapter ON |
| BCM94356Z (`14e4:43ec`) | firmware in image, untested | only chip whose BT firmware ships |
| RTL8822CE (`10ec:c822`) | driver and loader branch in tree, untested | needs `rtl_bt/rtl8822cu_*` firmware |

The Intel branch matches on **vendor**, not device: one driver covers the
family, and the 8265 alone answers to several ids. Any Wireless-AC card
`iwlwifi` knows should come up the same way, given its ucode in
`firmware.json`.
