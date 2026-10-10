#!/usr/bin/env bash
#
# Follow an AirPlay session live, with the lines that matter called out.
#
#   ./watch.sh            # follow
#   ./watch.sh -d         # dump what has already happened, then exit
#
# These are the markers the checklist in docs/airplay.md refers to. Pairing
# and FairPlay failures appear here, in the UxPlay lines, before anything
# reaches the renderers -- so if a sender misbehaves this is the first place
# to look, not the renderer timings.
#
# SPDX-License-Identifier: Apache-2.0
set -uo pipefail
A() { docker exec adbnode adb "$@"; }

cat <<'LEGEND'
watching AirPlay + UxPlay. What to expect:

  connection from "<device>"        a sender arrived
  showing pairing code              PIN gate, first time for this device
  remembering <device id>           the code was accepted, it is now paired
  a paired device is back           returning device, no code asked
  video decoder OMX.Nvidia...       mirroring started, hardware path
  mirrored picture is WxH           geometry; rotate the phone and watch it
  AAC decoder ... for AAC-ELD       mirroring audio
  AAC decoder ... for AAC-LC        Music audio
  audio: ... mean ... dropped late  per-session renderer timing
  video: ... mean ... from due      ditto; mean should be about 0.0 ms

at DEBUG (setprop persist.jetsontv.airplay.debug 1, then restart) also:

  Got metadata of N bytes           title/artist arrived (x-dmap-tagged)
  Got image data of N bytes         cover art arrived (image/jpeg|png)

LEGEND

# :D so that persist.jetsontv.airplay.debug actually shows. Harmless when
# it is off -- nothing is logged at DEBUG then. That property is what makes
# the content type of each SET_PARAMETER and the metadata and artwork
# payload sizes visible; without it a protocol problem is invisible.
if [ "${1:-}" = "-d" ]; then
  A logcat -d -s AirPlay:D UxPlay:D
else
  A logcat -s AirPlay:D UxPlay:D
fi
