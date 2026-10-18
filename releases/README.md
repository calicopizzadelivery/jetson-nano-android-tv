# Pinned release manifests

One file per release, written by `repo manifest -r -o` at the state the
release was built from. Each records the exact commit of **every** project in
the tree — 1177 of them for 0.1.0 — not a branch that will have moved on.

This is the difference between a tag and a reproducible build. The local
manifest in `scripts/in-container/local-manifest.xml` pins our forks to
`refs/heads/...`, which is right for day-to-day work and useless for
reproducing a release: a branch head is whatever it is today. These files
pin to SHAs.

## Reproducing a release

```bash
mkdir jetsontv-0.1.0 && cd jetsontv-0.1.0
repo init -u https://github.com/LineageOS/android.git -b lineage-22.2
cp /path/to/jetsontv-0.1.0.xml .repo/manifests/
repo init -m jetsontv-0.1.0.xml
repo sync -c -j8
```

Then `extract` and `build` as the top-level README describes. The blob
extraction still downloads NVIDIA's L4T archives and the SHIELD OTAs at build
time; those are not in the manifest because they are not git projects, and
their URLs are pinned in `device/nvidia/porg`'s extract scripts at the commit
this manifest names.

## What is also tagged

Every fork carries a `v0.1.0` tag at the same commit this manifest pins, so a
reader can find the release state from the fork's own history without
consulting the manifest. The tags are a convenience; **the manifest is the
authority**, because it covers the upstream projects too.

## Releases

| version | date | notes |
| --- | --- | --- |
| 0.1.0 | 2026-10-18 | First tagged state. AirPlay mirroring, macOS system audio and Apple Music audio all working; classic RAOP self-test; licensing and attribution complete. Known gaps in `docs/open-items.md`. |
