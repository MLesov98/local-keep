#!/usr/bin/env bash
# Runs every Jazzer target in the repo, one Gradle invocation each.
#
# The loop is not a style choice. Jazzer fuzzes AT MOST ONE TARGET PER JVM: with JAZZER_FUZZ set,
# the first @FuzzTest method runs a campaign and every later one in the same JVM is **skipped**.
# Skipped, not failed — so `./gradlew :test:fuzz:test` under JAZZER_FUZZ reports BUILD SUCCESSFUL
# having fuzzed one target out of seven, which is the exact shape of green-and-useless this
# harness exists to avoid. Measured 2026-07-28: one invocation fuzzed `onepux` and skipped
# bitwarden, aegis, csv and kdbx.
#
# Two Gradle tasks, because one target does not live with the others: `VaultHeaderCodec.decode` is
# in `:core:vault`, an Android library, which a pure-JVM `:test:fuzz` cannot depend on. See the
# KDoc on VaultHeaderCodecFuzzTest.
#
# The per-target budget is `maxDuration` on each @FuzzTest annotation, not an argument here.
# Jazzer's JUnit integration takes it from the annotation and offers no runtime override, so a
# --duration flag would be a flag that silently does nothing. Change the annotations to change the
# budget.
#
# Findings: Jazzer writes the reproducing input straight into the seed corpus under
# src/test/resources/<pkg>/<Class>Inputs/<method>/crash-<sha>, so a crasher becomes a committed
# regression the moment it is found — `./gradlew test` replays it with no env var set.
#
# `checkFuzzTargetsCovered` in the root build file fails if a @FuzzTest is missing from the list
# below, so narrowing or forgetting cannot silently drop a target.
set -euo pipefail

cd "$(dirname "$0")/.."

# "<gradle task>|<test filter>"
TARGETS=(
    ":test:fuzz:test|ImporterFuzzTest.bitwarden"
    ":test:fuzz:test|ImporterFuzzTest.aegis"
    ":test:fuzz:test|ImporterFuzzTest.csv"
    ":test:fuzz:test|ImporterFuzzTest.onepux"
    ":test:fuzz:test|ImporterFuzzTest.kdbx"
    ":test:fuzz:test|ImporterFuzzTest.keeper"
    ":test:fuzz:test|ImporterFuzzTest.enpass"
    ":test:fuzz:test|ImporterFuzzTest.protonpass"
    ":test:fuzz:test|ImporterFuzzTest.dashlane"
    ":test:fuzz:test|BreachFilterFuzzTest.load"
    ":test:fuzz:test|BreachFilterFuzzTest.ensureLoadedNeverThrows"
    ":core:vault:testDebugUnitTest|VaultHeaderCodecFuzzTest.decode"
    ":core:vault:testDebugUnitTest|FolderTreeFuzzTest.normalize"
)

# A crasher is a NEW `crash-*` file under a seed directory. Counting them is how this script tells
# a finding from a build problem.
#
# It used to treat any non-zero Gradle exit as "FINDING — reproducer saved into the seed corpus",
# and on 2026-07-29 that reported a finding for a campaign that had just printed `DONE` after
# 429,618,989 clean runs: Gradle had failed *writing the JUnit XML*, because another build was
# running against the same project directory. No reproducer existed, and the message said one did.
# Announcing a security finding that is really an infrastructure failure is the same defect class
# this harness exists to catch, and it cuts both ways — it also teaches you to ignore the signal.
crashers() {
    find test/fuzz/src/test/resources core/vault/src/test/resources \
        -name 'crash-*' 2>/dev/null | sort
}

findings=()
errored=()
for entry in "${TARGETS[@]}"; do
    task="${entry%%|*}"
    filter="${entry##*|}"
    echo "=== fuzzing ${filter} ==="
    before="$(crashers)"
    if JAZZER_FUZZ=1 ./gradlew --quiet "${task}" "--tests" "*${filter}"; then
        echo "    clean"
        continue
    fi
    new="$(comm -13 <(echo "$before") <(crashers))"
    if [ -n "$new" ]; then
        # A new `crash-*` file is a CANDIDATE, not yet a finding. libFuzzer also dumps the in-flight
        # input when the campaign is interrupted, and it uses the same `crash-` prefix — on
        # 2026-07-29 killing a run mid-target produced a 130-byte `crash-a3908a…` under the onepux
        # seeds that replayed perfectly green. A file whose name asserts a crash it cannot reproduce
        # is worse than no file: it is a permanent committed claim that something was once broken.
        #
        # So: replay it. Replay mode is the no-JAZZER_FUZZ path, it runs every seed in the
        # directory, and it takes seconds.
        echo "    candidate reproducer(s) — replaying to confirm:"
        echo "$new" | sed 's/^/        /'
        if ./gradlew --quiet "${task}" "--tests" "*${filter}"; then
            echo "    NOT A FINDING — the candidate replays clean; it is an interrupt artifact."
            echo "$new" | while read -r f; do [ -n "$f" ] && rm -f "$f"; done
            errored+=("${filter}")
        else
            echo "    FINDING — confirmed reproducer, now a committed seed"
            findings+=("${filter}")
        fi
    else
        # Still a failure, and still red — but named for what it is.
        echo "    ERROR — the campaign did not complete, and left no reproducer."
        echo "        This is a build or environment failure, not a finding. A concurrent Gradle"
        echo "        build against this project directory is the known cause; check for one."
        errored+=("${filter}")
    fi
done

if [ ${#findings[@]} -gt 0 ]; then
    echo
    echo "targets with findings: ${findings[*]}"
    echo "the reproducers are now committed seeds; ./gradlew test replays them"
fi

if [ ${#errored[@]} -gt 0 ]; then
    echo
    echo "targets that errored without a reproducer: ${errored[*]}"
fi

if [ ${#findings[@]} -gt 0 ] || [ ${#errored[@]} -gt 0 ]; then
    exit 1
fi

echo
echo "all ${#TARGETS[@]} targets clean"
