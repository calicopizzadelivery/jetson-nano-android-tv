#!/usr/bin/env bash
#
# Attach a virtual Bluetooth A2DP speaker to a running Android emulator, so
# audio routing can be tested without hardware. See docs/emulator-bluetooth.md.
#
# Run the emulator first. This talks to the netsim daemon the emulator starts.
#
set -euo pipefail

VENV="${BUMBLE_VENV:-${HOME}/.cache/jetson-tv/bumble-venv}"
NETSIM_LOG="${NETSIM_LOG:-/tmp/android-${USER}/netsimd/netsim_stderr.log}"
CONFIG="${BUMBLE_CONFIG:-}"

die() { printf '\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# One-time: a venv with bumble. Ubuntu splits python3-venv out of python3 and
# installing it needs root, so bootstrap pip rather than rely on ensurepip.
# grpcio is not a bumble dependency but the android-netsim transport needs it.
# ---------------------------------------------------------------------------
if [[ ! -x ${VENV}/bin/bumble-speaker ]]; then
    echo "==> creating ${VENV}"
    mkdir -p "$(dirname "${VENV}")"
    python3 -m venv --without-pip "${VENV}"
    curl -fsSL https://bootstrap.pypa.io/get-pip.py | "${VENV}/bin/python" - -q
    "${VENV}/bin/pip" install -q bumble grpcio protobuf
fi

# ---------------------------------------------------------------------------
# netsim picks its gRPC port at random each run and writes no .ini, so bumble's
# auto-discovery finds nothing. Read the port out of the daemon's log.
# ---------------------------------------------------------------------------
[[ -f ${NETSIM_LOG} ]] || die "no netsim log at ${NETSIM_LOG} — is the emulator running?"
PORT="$(grep -oP 'Grpc server listening on localhost: \K[0-9]+' "${NETSIM_LOG}" | tail -1)"
[[ -n ${PORT} ]] || die "no gRPC port in ${NETSIM_LOG} — is the emulator running?"

if [[ -z ${CONFIG} ]]; then
    CONFIG="$(mktemp -t bumble-speaker-XXXX.json)"
    trap 'rm -f "${CONFIG}"' EXIT
    # class_of_device 0x240404 is Audio/Video : Loudspeaker, which is what makes
    # Android offer it as an audio output rather than a generic accessory.
    cat > "${CONFIG}" <<'JSON'
{
    "name": "Bumble Speaker",
    "address": "F0:F1:F2:F3:F4:F5",
    "class_of_device": 2360324,
    "keystore": "JsonKeyStore"
}
JSON
fi

echo "==> netsim gRPC on ${PORT}; starting speaker (ctrl-c to stop)"
echo "    pair from the device: Settings > Remotes & Accessories > Pair accessory"
echo "    then answer the 'Bluetooth pairing request' prompt on screen"
exec "${VENV}/bin/bumble-speaker" \
     --codec sbc --device-config "${CONFIG}" "android-netsim:localhost:${PORT}" "$@"
