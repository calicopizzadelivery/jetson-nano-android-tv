# JetsonTV: name and wallpaper

The image is LineageOS's `porg`, which is a retargeted SHIELD Android TV, and
until 30 September it called itself "SHIELD Android TV". This is what makes
it JetsonTV instead. All of it lives in `scripts/in-container/Branding/`
(installed as `vendor/jetson-tv/Branding`), `jetson-tv.mk`, and one launcher
change, Catapult/0010.

## The name

| Where it shows | Set by | Value |
| --- | --- | --- |
| Settings > Device Preferences > About > Device name; the file picker's title for internal storage | `def_device_name_simple`, overlaid by `Branding/overlay` | JetsonTV |
| Bluetooth, before anyone renames the box | `bluetooth.device.default_name`, a product property | JetsonTV |
| AirPlay, on iPhones and Macs | the device name above, unless `persist.jetsontv.airplay.name` is set | JetsonTV |

SHIELD's tree sets the first two in `device/nvidia/foster`: a device overlay
and a vendor property. Ours override them from the product side. With RROs
enforced, foster's overlay becomes `auto_generated_rro_vendor__` and ours goes
into `auto_generated_rro_product__`, which ranks above it. And init loads the
product `build.prop` after the vendor one.

**The device name is a default, stored at first boot.** A fresh install
starts as JetsonTV. A box that was already set up keeps the name it has
until it is renamed in Settings (or `settings put global device_name
JetsonTV`). Renaming it renames the AirPlay receiver too: the receiver
watches the device name and restarts under the new one.

What stays NVIDIA on purpose: `ro.product.manufacturer` (NVIDIA) and
`ro.product.model` (Jetson Nano). They describe the hardware, which is what
an app reading them wants to know.

## The wallpaper

**NASA's "A Breathtaking Earthset from Orion"** (art002e021278), taken by the
Artemis II crew on 6 April 2026 as Orion passed behind the Moon. Credit and
source are in `Branding/wallpaper/NOTICE`, which goes into the image's
licence notices. NASA imagery is generally not subject to copyright in the
US. The NOTICE also says that using it implies no endorsement by NASA.

It was picked over Apollo 8's Earthrise (as08-14-2383) and an ISS aurora
photograph (iss072e147691), from mockups behind the real home screen. In the
Earthset, the sky is dark behind the clock, the Earth sits in the gap between
the two rows of tiles, and the Moon's surface under the tiles is dark and
even. At a 16:9 crop, Earthrise puts the Earth behind the app tiles, and the
aurora's star trails look like noise at viewing distance.

How it gets on screen:

- **The file:** `/product/media/wallpaper/earthset.jpg`, 1920x1080, 197 KB,
  cropped to 16:9 at the vertical centre and scaled from NASA's 5568x3712
  original.
- **The default:** `ro.config.wallpaper` points at it. The framework reads
  that property before falling back to its `default_wallpaper` resource, so
  this needs no overlay, and does not have to outrank the several
  `default_wallpaper` overlays LineageOS and the ATV tree already ship.
- **The launcher:** Catapult used to paint a flat colour over the wallpaper.
  Catapult/0010 gives its home screen `windowShowWallpaper` and a scrim
  instead: a gradient from 20% black at the top to 50% at the bottom, where
  the rows are. The side panels keep their own theme and do not show it.

**Why 1920x1080 and not 4K.** The UI is laid out at 1920x1080 and scaled to
the panel (`wm size`: physical 3840x2160, override 1920x1080). A 4K
wallpaper would probably be drawn at 4K on a 4K television, as it is the
one layer that is not rendered at the UI's resolution. But the bitmap and
its buffers would be four times the size, about 33 MB each instead of 8 MB,
on a 4 GB board, and the bench cannot show the difference: the capture card
and `screencap` both see 1080p. Try it if a 4K television ever shows the
1080p one as soft.

**Changing it.** Replace `Branding/wallpaper/earthset.jpg` (and its NOTICE)
to change the default for every image. On a running box, any app holding
`SET_WALLPAPER` can set another through `WallpaperManager`. There is no
wallpaper picker on Android TV.

## Verified on porg (30 September)

After a sideload over the previous image:

- `cmd overlay lookup` gives `def_device_name_simple` = JetsonTV, and
  `bluetooth.device.default_name` is JetsonTV. The bench box still had
  "SHIELD Android TV" stored, as expected for an upgrade, and was renamed
  with `settings put global device_name JetsonTV`. Settings > About then
  shows JetsonTV.
- After a restart, the AirPlay receiver advertises "JetsonTV" on both
  `_airplay._tcp` and `_raop._tcp`.
- The home screen shows the Earthset under the scrim. Compared with the
  source image, the bottom of a `screencap` is darkened to 0.56 to 0.59 of
  it, against the 0.51 to 0.53 designed (the blend is not in linear light).
  The system options panel looks as before.
- `/system/etc/NOTICE.xml.gz` carries the wallpaper's NOTICE, and now also
  UxPlay's, libplist's and AirPlayReceiver's licences
  (`docs/source-release.md`).
