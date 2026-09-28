# A virtual Bluetooth speaker for the emulator

Testing audio routing, or anything else that needs a Bluetooth peer, does not
need hardware. The Android emulator already runs a virtual Bluetooth controller,
and Google's own Python Bluetooth stack can attach to it as a second device.

This is how the audio output picker in `patches/Catapult/0004` was verified.
Nothing here is specific to this project — it works for any LineageOS or AOSP
emulator target.

    ./scripts/emulator-bt-speaker.sh

Then on the device: **Settings → Remotes & Accessories → Pair accessory**, pick
*Bumble Speaker*, and **answer the "Bluetooth pairing request" prompt**.

## What is already there

- **netsim** runs whenever the emulator does — the launch log says
  `Activated packet streamer for bluetooth emulation`. It hosts **Rootcanal**,
  a Bluetooth controller emulator, and will happily accept more devices onto the
  same virtual radio. `netsim devices` lists them.
- **Bumble** is vendored in the tree at `external/python/bumble`, and ships both
  an `android-netsim` transport and a full A2DP sink (`bumble-speaker`).

So the only thing to install is bumble itself. `netsim beacon` is no use for
this — it creates BLE advertisers only, with no profiles.

## The five things that cost time

**`python3-venv` is not installed and needs root.** `python3 -m venv` fails on
`ensurepip`. `python3 -m venv --without-pip` plus `get-pip.py` needs no root.

**`grpcio` is not pulled in by bumble.** The `android-netsim` transport imports
`grpc` and fails without it. Install `grpcio` and `protobuf` alongside bumble.

**netsim's gRPC port is random and it writes no `.ini`.** Bumble's transport
auto-discovery looks for that file, finds nothing, and cannot connect. The port
is in the daemon's log:

    grep -oP 'Grpc server listening on localhost: \K[0-9]+' \
        /tmp/android-$USER/netsimd/netsim_stderr.log | tail -1

Pass it explicitly: `android-netsim:localhost:<port>`.

**The pairing prompt is a real dialog that needs a real keypress.** Android
sits in `btm_sec_pairing_timeout: State: WAIT_NUM_CONFIRM` for 35 seconds and
then fails with `HCI_ERR_AUTH_FAILURE`. That looks like a stack incompatibility
and is not: `BluetoothPairingDialog` is on screen waiting. Driving the emulator
by `adb shell input` means remembering to answer it.

**Use `bumble-speaker`, not `examples/run_a2dp_sink.py`.** The example pairs
fine but its AVDTP handling is thin — the L2CAP channel opens on PSM 25, then a
`L2CAP_COMMAND_REJECT` goes out and Android gives up with *"Failed to connect
A2DP device"*. The `bumble-speaker` app negotiates properly.

## Verifying audio actually arrives

`bumble-speaker` prints a running RTP byte and packet count once a stream
starts, which is objective and needs no sound card:

    RTP Channel Open
    Sink Started
    [495763 bytes in 604 packets] RTP(v=2,...,payload_size=834)

On the device, `dumpsys audio | grep bt_a2dp` shows whether media is routed to
the sink at all.

## Gotchas that will bite

- **Addresses are assigned per attach, not from the config.** The `address` in
  the device config is ignored; Rootcanal hands out sequential addresses like
  `DA:4C:10:DE:17:02`. Restart the speaker and it is a *different device* as far
  as Android is concerned, so bonds accumulate and old ones never connect. Unpair
  the stale ones, or accept duplicate names in any device list.
- **Killing the wrapper does not kill the speaker.** `nohup ... &` under a shell
  leaves the Python process behind when the wrapper dies. Kill by PID from
  `ps -eo pid,args`, and never with `pkill -f`, which matches your own shell.
- **The UI port is fixed at 7654.** A second `bumble-speaker` exits with
  `address already in use` rather than a useful message. Check for a survivor
  first.
- **`adb install -r` of a priv-app shadows the image copy.** The `/data` update
  keeps its own manifest, so permission changes appear not to take effect no
  matter how many times you rebuild. `dumpsys package <pkg> | grep codePath`
  shows which one is live; `pm uninstall-system-updates <pkg>` reverts to the
  image. Permission allowlists are system files and always need a real image
  build.

## Beyond audio

The same rig should give a virtual BLE remote — bumble has HID examples — which
would let the long-standing "does BLE pairing work on ARM64 Tegra" question be
answered without a radio fitted. Not tried yet.
