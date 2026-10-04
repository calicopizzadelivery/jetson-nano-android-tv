# Wi-Fi and Bluetooth on porg

Status, 4 October 2026: **Wi-Fi works on an Intel Wireless-AC 8265**, from a
cold boot, with no hand-holding. Android enumerates the card, loads the
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

The 8265's Bluetooth half is a USB device and comes up on its own: `btusb`
binds it, `btintel` loads `ibt-12-16.sfi` and `.ddc` (both now shipped by the
same firmware fetcher), and `hci0` exists.

    Bluetooth: hci0: Firmware revision 0.1 build 19 week 44 2021

It is **not usable yet**. The Bluetooth HAL is denied a socket:

    avc: denied { create } for comm="android.hardwar" tclass=socket
         scontext=u:r:hal_bluetooth_default:s0

porg uses the generic `btlinux` HAL, which talks to the kernel over an
`AF_BLUETOOTH` HCI socket. NVIDIA's own path is a UART, so vendor policy
grants `hci_attach_dev` and no socket. Not yet fixed. See
`docs/open-items.md`.

If it can be made to work it answers a question that has been open since this
project started: whether BLE pairing works on ARM64 Tegra, and so whether the
setup wizard's accessory step can ever be completed with a real remote.

## Other cards

| card | Wi-Fi | Bluetooth |
| --- | --- | --- |
| Intel 8265 (`8086:24fd`) | works | `hci0` up, HAL blocked |
| BCM94356Z (`14e4:43ec`) | firmware in image, untested | only chip whose BT firmware ships |
| RTL8822CE (`10ec:c822`) | driver and loader branch in tree, untested | needs `rtl_bt/rtl8822cu_*` firmware |

The Intel branch matches on **vendor**, not device: one driver covers the
family, and the 8265 alone answers to several ids. Any Wireless-AC card
`iwlwifi` knows should come up the same way, given its ucode in
`firmware.json`.
