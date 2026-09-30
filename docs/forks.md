# Our forks, and how they get into the build

Six LineageOS projects carry commits of ours. They live in forks under
`calicopizzadelivery`:

| Project in the tree | Fork | Branch | Commits (30 Sep) |
| --- | --- | --- | --- |
| `device/nvidia/porg` | `android_device_nvidia_porg` | `lineage-22.2-jetson-tv` | 8 |
| `device/nvidia/tegra-common` | `android_device_nvidia_tegra-common` | `lineage-22.2-jetson-tv` | 1 |
| `kernel/nvidia/kernel-4.9` | `android_kernel_nvidia_kernel` | `lineage-22.2_4.9-jetson-tv` | 1 |
| `packages/apps/TvSettings` | `android_packages_apps_TvSettings` | `lineage-22.2-jetson-tv` | 2 |
| `packages/apps/Catapult` | `android_packages_apps_Catapult` | `lineage-22.2-jetson-tv` | 6 |
| `vendor/lineage` | `android_vendor_lineage` | `lineage-22.2-jetson-tv` | 1 |

Four more are projects LineageOS does not have at all, added rather than
substituted. Each is a fork of the upstream project with an Android build on
`android-jetson-tv`:

| Project in the tree | Fork of | From | In the image |
| --- | --- | --- | --- |
| `external/uxplay` | FDH2/UxPlay | v1.73.7 | yes: the AirPlay receiver ([airplay.md](airplay.md)) |
| `external/libplist` | libimobiledevice/libplist | 2.7.0 | yes: UxPlay needs it |
| `external/shairport-sync` | mikebrady/shairport-sync | after 5.5.2 | no longer; replaced by UxPlay |
| `external/popt` | rpm-software-management/popt | after 1.19 | no longer; shairport's only |

Shairport and popt are still synced so their history stays buildable; nothing
in `PRODUCT_PACKAGES` names them. UxPlay (GPLv3) and libplist (LGPL) join the
kernel as source that has to be published alongside any image we distribute;
the licensing note in [airplay.md](airplay.md) has the reasoning.

The kernel tracks `lineage-22.2_4.9` upstream rather than `lineage-22.2`, so its
branch is named after the branch it forks. Its history is ~1.1 GB, but forking
is server-side and a fork network shares objects, so pushing our one commit was
instant.

They are GitHub forks rather than fresh repos on purpose: the fork relationship
keeps the upstream remote, so opening a PR or pushing to Gerrit is a normal
operation rather than a reconstruction. They are public, which is inherent —
a fork of a public repo cannot be private.

## Why not submodules

The obvious idea is to submodule these into this repo. It cannot work. This
repo is the *build environment*; the source tree lives at
`/srv/build/jetson-tv/lineage` and is populated by `repo` from the LineageOS
manifest. A submodule here would be an inert second copy that the build never
reads.

The mechanism for substituting a fork into a repo-managed tree is a **local
manifest**: `<remove-project>` the upstream entry, then `<project>` it back
pointing at the fork. `scripts/in-container/local-manifest.xml` is that file,
installed to `.repo/local_manifests/zz-jetson-tv.xml`.

Three details in it are load-bearing:

- **The `zz-` prefix.** repo reads local manifests in sorted order and
  `remove-project` only removes what is already defined. `device/nvidia/porg`
  comes from `roomservice.xml`, so ours has to sort after it.
- **`optional="true"` on every `remove-project`.** On a cold tree roomservice
  has not run and porg is in no manifest; without it repo aborts with
  *"remove-project element specifies non-existent project"*.
- **`remote="github"` with a `calicopizzadelivery/` name.** That remote fetches
  from `".."` relative to the manifest repo (`LineageOS/android.git`), which
  resolves to `https://github.com/` — so a fork is just a different name on the
  same remote, no new `<remote>` needed.

## When it runs

`scripts/in-container/local-manifest.sh`, wired into three places:

- **`extract.sh`** installs it *after the first breakfast*. This ordering is not
  cosmetic: installing it earlier puts `device/nvidia/porg` in the tree, so
  `breakfast porg` finds the device and never calls roomservice — and
  roomservice is what adds the other ~30 NVIDIA projects porg depends on. The
  script refuses to install if `roomservice.xml` is absent, for that reason.
- **`sync.sh`** re-applies it after every `repo sync`.
- **`build.sh`** runs `--check`, which verifies each project's remote points at
  our fork. No network. This catches the one genuinely dangerous failure: a
  plain `repo sync` on a tree without the local manifest puts these projects
  back on LineageOS and the build silently produces an image without our
  changes.

Or by hand: `./scripts/jetson-build forks`.

The sync uses `--force-sync` because the project *name* changes
(`LineageOS/x` → `calicopizzadelivery/x`), so repo has to move the checkout to a
different object directory and refuses without it. It discards uncommitted work
in those four paths — commit before running it. `repo` also prints
`error: hooks is different in ...` while relinking the hook symlinks; it then
reports success and the hooks are intact.

## Pushing a change to a fork

`repo` gives each project a remote named `github` pointing at the fork over
**https**, which cannot be pushed to. Add an ssh remote once per project:

```
git remote add fork git@github.com:calicopizzadelivery/<repo>.git
git push fork HEAD:refs/heads/lineage-22.2-jetson-tv
```

That remote does not survive `local-manifest.sh` re-running: a manifest change
moves the project to a different object directory and the remote goes with it.
Re-adding it is harmless and idempotent enough to just do again.

The four added projects use the branch `android-jetson-tv`, so for those it is
`git push fork HEAD:refs/heads/android-jetson-tv`.

Commits live on a detached HEAD, as everything under `repo` does. Keep a
`jetson-tv` branch pointing at them (`git branch -f jetson-tv HEAD`) so they are
referenced and cannot be garbage-collected between a commit and a push.

## What this means for `patches/`

The patch queue is no longer how the code gets into the tree — the forks are.
`patches/` keeps its original job: changes formatted for posting upstream. The
two are kept in step by hand, and `git format-patch` against the fork branch is
what regenerates them.

## Nothing is patched in place any more

`tree-local-changes.sh` used to `sed` two upstream repos on every run —
`CONFIG_BT_LE` into four tegra defconfigs, and the two `wifi_loader.sh`
defects. Both are now ordinary commits on our forks, so the script no longer
edits code we do not own. What is left of it is installing `vendor/jetson-tv`,
which is ours and is not a repo project at all.

`repo status` across all 1141 projects now reports *"nothing to commit
(working directory clean)"*. That is the point of the exercise: every change is
either upstream, a commit on one of our forks, or a file under
`vendor/jetson-tv`. Nothing depends on a script having run, and nothing is
lost if the tree is wiped.
