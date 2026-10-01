# Publishing an image: the source we owe

Nothing is owed yet. The repo publishes scripts and documentation, not
binaries. The obligations below start **the first time an image is
distributed**: a download link, an image handed to someone else, or a box
shipped with it on the eMMC. From then on they apply to each image we
release.

This is an engineering reading of the licences, not legal advice.

## What the image contains that is copyleft

AOSP records the licences of every module it builds (`.meta_lic` files), and
ships a tool, `compliance_listshare`, that works out which projects' source
has to be shared. Run over the 2,728 modules installed in the 30 September
build:

| Group | Projects | Licences |
| --- | --- | --- |
| **Kernel and its modules** | `kernel/nvidia/kernel-4.9` (our fork), `kernel/nvidia/nvidia`, `nvgpu`, `cypress-fmac`, `exfat`, and the device trees under `hardware/nvidia/{soc,platform}/**/kernel-dts` | GPL-2.0 |
| **Userspace from AOSP and LineageOS** | angle, bcc, dnsmasq, e2fsprogs, erofs-utils, exfatprogs, f2fs-tools, freetype, fsverity-utils, gptfdisk, hyphenation-patterns, icu, iproute2, iptables, iputils, libchrome, libexif, libnl, libxml2, ntfs-3g, openthread, selinux, zstd, NeuralNetworks | GPL, LGPL, MPL, EPL |
| **Ours** | `external/uxplay`, `external/libplist`, `AirPlayReceiver` | GPL-3.0, LGPL-2.1 |
| **Third-party apps** | Moonlight, Kodi, Lemuroid, Jellyfin (`docs/prebuilt-apps.md`) | GPL-2.0, GPL-3.0 |
| **Build scripts** | this repo | GPL-3.0 counts the scripts that build and install the work as part of its source |

Some of the AOSP projects are flagged because of a few files, or because
they are dual-licensed and Android uses the permissive option: angle, icu
and freetype, for example. Shipping their source anyway costs a few hundred
megabytes and removes the question, so include them.

Three things the metadata misses had to be found by hand. The **kernel**
is built outside Soong, so it has no metadata. **ntfs-3g** (`mount.ntfs`,
`fsck.ntfs`, GPL-2.0) has none either. And **UxPlay, libplist and
AirPlayReceiver** had none until 30 September, so their licence texts were
missing from the image's own notices (Settings > Device Preferences > About >
Legal information). Licence modules now fix that, in the two forks and in
`AirPlayReceiver/Android.bp`.

To repeat the scan on a later build, from the tree root:

```
grep -rlE 'installed: +"out/target/product/porg/' --include='*.meta_lic' \
    out/soong/.intermediates out/target/product/porg/obj > roots.txt
mapfile -t R < roots.txt
OUT_DIR=out out/host/linux-x86/bin/compliance_listshare -o shares.csv "${R[@]}"
```

Give it all the roots in one call. Through `xargs` it is run several times,
and each run overwrites the last one's output. `shares.csv` is broad (about
300 projects): it marks anything linked with copyleft code. To see which
projects are copyleft themselves, read `license_kinds` in the same files.

## Where the source has to be, and for how long

- **GPL-2.0** (the kernel, Kodi, Jellyfin, most of the userspace tools):
  for a download, offer the source "from the same place" (section 3). The
  other routes are a written offer valid for three years (3b), or passing on
  the upstream offer (3c), which only covers non-commercial copies of a
  binary you were given that way. Pointing at LineageOS's GitHub does not
  clearly meet any of these. Host it ourselves.
- **GPL-3.0** (UxPlay, AirPlayReceiver, Moonlight, Lemuroid): source may sit
  on another server, including a third party's, if the download page says
  where (6d). We remain responsible for it staying available as long as the
  image is offered. For a box sold with the image on it, a written offer
  must stay valid for at least three years (6b).
- **LGPL** and **MPL**: the same idea for their own files. Recipients must be
  able to get the source of the parts under those licences.

In every case, the source has to be the **exact version built**: the commits
in the tree at build time, not a branch that has moved on.

## The plan

1. **Pin each release.** `repo manifest -r -o jetsontv-<version>.xml` at
   build time records the commit of every project in the tree, ours
   included. Commit it to this repo under `releases/` and tag the repo with
   the same version.
2. **Bundle the copyleft source.** A script reads that manifest and the
   list above, and writes `jetsontv-<version>-source.tar.xz`: every project
   in the table at its pinned commit, the four apps at their tags
   (moonlight-android with its submodules, Kodi with the `tools/depends`
   tarballs its build downloads), and this repo. The tree's part is about
   2.5 GB before compression, mostly the kernel, angle and icu; Kodi and its
   dependency tarballs add to that. GitHub caps a release asset at 2 GB, so
   the bundle may need splitting.
3. **Publish it next to the image.** One GitHub Release per version, on this
   repo, carrying the image, the source bundle and the pinned manifest. That
   is "the same place" for GPL-2.0 and the download page for GPL-3.0, and it
   does not depend on any upstream keeping old commits.
4. **Say so on the device.** A line in the image's notices pointing at the
   releases page, so that someone with only the box can find the source.
5. **Keep old releases up** as long as their images are offered, and for at
   least three years after the last box ships, if boxes are sold.

Steps 1, 2 and 4 are scripting work that can be done now, before anything is
published. Step 3 is the publishing itself (RAIL 18b).

## Related, but not about source

- **NVIDIA's binaries.** The image carries NVIDIA blobs from L4T and from
  SHIELD recovery images. They have no source to offer. Their terms are
  NVIDIA's, and LineageOS's official `porg` builds redistribute the same
  set, so we are in the position LineageOS is in.
- **Names.** Kodi's trademark policy is in `docs/prebuilt-apps.md`. "Jetson"
  is an NVIDIA trademark, which matters more if boxes are sold than for a
  hobby image.
