#!/usr/bin/env bash
# Boots a named AVD, runs the instrumented suite against it, and shuts it down again.
#
# Exists because the per-push `instrumented` job runs against whatever device is already attached to
# the self-hosted Mac, which is one API level. The roadmap's exit criterion asks for the low and high
# ends of the supported range — minSdk 28 and compileSdk 36 — and behaviour genuinely differs there:
# 28 predates the scoped-storage and biometric-prompt changes this app's unlock path depends on, and
# it is the oldest device a user can install on.
#
# Deliberately NOT run on every push. The suite takes ~45 minutes on one API level, so three levels
# per push would put over two hours on a single self-hosted machine for every commit. This is the
# scheduled job; the per-push gate stays one device. Stated here rather than left implicit, because
# "runs on every PR" is what the roadmap originally asked for and this is a considered deviation.
#
# Usage: tools/emulator-run.sh <avd-name> <gradle-task>...
set -euo pipefail

cd "$(dirname "$0")/.."

AVD="${1:?usage: emulator-run.sh <avd-name> <gradle-task>...}"
shift

: "${ANDROID_HOME:?ANDROID_HOME must be set}"
EMULATOR="$ANDROID_HOME/emulator/emulator"
ADB="$ANDROID_HOME/platform-tools/adb"

if ! "$EMULATOR" -list-avds | grep -qx "$AVD"; then
    echo "no such AVD: $AVD" >&2
    echo "available:" >&2
    "$EMULATOR" -list-avds >&2
    exit 1
fi

# An explicit port, and every adb call pinned to the serial it implies.
#
# Without this the script talks to whatever `adb` picks: a developer with a device already plugged
# in, or a leftover emulator from a previous run, gets a suite that installs on one device and
# reports results for another. It passes, and it is about the wrong API level — the worst available
# outcome for a job whose entire purpose is which API level it ran on. 5560 to stay clear of the
# default 5554/5556 range a desktop AVD occupies.
#
# Derived from CI_NODE_INDEX when GitLab is running this as a `parallel:matrix`, so two matrix legs
# that do end up concurrent take different ports rather than fighting over one. `resource_group` in
# the pipeline is what normally prevents that; this is the belt to its braces, and it costs a line.
# Emulator ports must be even — adb claims the odd port above each for its own channel.
NODE_INDEX="${CI_NODE_INDEX:-1}"
PORT="${LOCALKEEP_EMULATOR_PORT:-$(( 5560 + 2 * (NODE_INDEX - 1) ))}"
export ANDROID_SERIAL="emulator-$PORT"

if "$ADB" devices | grep -q "^$ANDROID_SERIAL"; then
    echo "$ANDROID_SERIAL is already attached; refusing to guess whose it is" >&2
    exit 1
fi

# -no-snapshot-load and -wipe-data so every run starts from the same state. A snapshot carries
# whatever the last run left behind — including a vault file and a lock credential — and an
# instrumented suite that passes only against a warm device is a suite that is testing the snapshot.
"$EMULATOR" -avd "$AVD" -port "$PORT" -no-window -no-audio -no-boot-anim -no-snapshot-load \
    -wipe-data >"/tmp/emulator-$AVD.log" 2>&1 &
EMULATOR_PID=$!

# Kill the emulator however this script exits, including on a failing test run. Without this a
# failed job leaves a headless emulator holding the port, and the next run attaches to it and
# reports results for the wrong API level.
cleanup() {
    "$ADB" -s "$ANDROID_SERIAL" emu kill >/dev/null 2>&1 || true
    kill "$EMULATOR_PID" >/dev/null 2>&1 || true
    wait "$EMULATOR_PID" 2>/dev/null || true
}
trap cleanup EXIT

echo "=== booting $AVD on $ANDROID_SERIAL ==="
"$ADB" -s "$ANDROID_SERIAL" wait-for-device
# `wait-for-device` returns as soon as adb can talk to it, which is long before the framework is up.
# Installing an APK into a half-booted device fails in ways that read like a build problem.
until [ "$("$ADB" -s "$ANDROID_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
    sleep 2
done
echo "=== $AVD booted ==="

# Three VaultLockManagerInstrumentedTest cases report BIOMETRIC_UNAVAILABLE without a lock
# credential, which reads exactly like a vault lock-management regression and is purely
# environmental. No fingerprint enrolment is needed — only the PIN.
"$ADB" -s "$ANDROID_SERIAL" shell locksettings set-pin 1234

# ANDROID_SERIAL is exported, so AGP's `connected*` tasks install onto this emulator and no other.
./gradlew "$@"
