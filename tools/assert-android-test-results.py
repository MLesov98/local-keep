#!/usr/bin/env python3
"""Assert that the connected-test job actually *ran* the tests it claims to cover.

A green Gradle task is not evidence. `connectedAndroidTest` succeeds when a module has no
instrumented tests at all, when every test in it is `@Ignore`d, and when a filter typo means the
runner matched nothing — three states indistinguishable from "everything passed" in the build log,
and the middle one is how 23 test files in this repo went unrun for months.

So this reads the JUnit XML the runs left behind and requires, per module, that a *named* class is
present with at least one executed, non-skipped test. Nothing is inferred from the absence of
failures.

Usage:
    python3 tools/assert-android-test-results.py --results-root . \
        [--require-nonzero-new-feature-tests]

Exit status is 0 only when every required module produced results and every required class ran.
"""
from __future__ import annotations

import argparse
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

# module path fragment -> the class that must have executed in it.
#
# Deliberately a hand-maintained table rather than "whatever we find": the failure this exists to
# catch is a suite that quietly stopped running, and a self-derived list would simply stop expecting
# it. Adding a row is a reviewed edit, like `config/i18n-allowlist.txt` and `HiddenChokepointTest`.
# A module maps to a LIST, not a single class. It was a single class until a review pointed out
# that `core/vault`'s newest and most destructive suite — the one proving Empty Trash no longer
# destroys concealed records — could stop running entirely and the gate would still pass, because
# the one class it did check was a different one in the same module.
NEW_FEATURE_TESTS = {
    "feature/health": ["HealthVisibilityInstrumentedTest"],
    "autofill": ["AutofillHiddenLookupInstrumentedTest"],
    "credential": ["CredentialHiddenEntryInstrumentedTest"],
    "feature/itemedit": ["ItemEditVisibilityInstrumentedTest"],
    "feature/files": ["HiddenBlobRetentionInstrumentedTest"],
    "core/vault": ["HiddenRepositoryInstrumentedTest", "VisibleTrashPurgeInstrumentedTest"],
    "feature/vaultlist": ["VaultListHiddenVisibilityInstrumentedTest"],
    "app": ["AppHiddenNavigationInstrumentedTest"],
}


def result_files(root: Path, module: str) -> list[Path]:
    """Every JUnit XML AGP wrote for one module's connected run, across flavours."""
    base = root / module / "build" / "outputs" / "androidTest-results"
    if not base.is_dir():
        return []
    return sorted(base.rglob("*.xml"))


def executed_tests(path: Path) -> list[tuple[str, str, bool]]:
    """(classname, testname, skipped) for every case in one XML file."""
    try:
        tree = ET.parse(path)
    except ET.ParseError:
        return []
    out = []
    for case in tree.getroot().iter("testcase"):
        skipped = case.find("skipped") is not None
        out.append((case.get("classname", ""), case.get("name", ""), skipped))
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--results-root", default=".", help="repo root the modules sit under")
    parser.add_argument(
        "--require-nonzero-new-feature-tests",
        action="store_true",
        help="fail unless every module in the table above ran its named class",
    )
    args = parser.parse_args()
    root = Path(args.results_root).resolve()

    if not args.require_nonzero_new_feature_tests:
        print("nothing asserted: pass --require-nonzero-new-feature-tests")
        return 0

    failures: list[str] = []
    required_total = 0
    for module, required_classes in sorted(NEW_FEATURE_TESTS.items()):
        required_total += len(required_classes)
        files = result_files(root, module)
        if not files:
            failures.append(
                f"{module}: no androidTest-results directory. The connected task did not run, or "
                f"its output was not archived — either way nothing about this module was measured."
            )
            continue

        cases = [c for f in files for c in executed_tests(f)]
        for required_class in required_classes:
            matching = [c for c in cases if c[0].endswith(required_class)]
            if not matching:
                seen = sorted({c[0].rsplit(".", 1)[-1] for c in cases})
                failures.append(
                    f"{module}: {required_class} produced no results. Classes that did run: "
                    f"{', '.join(seen) or '(none)'}."
                )
                continue

            ran = [c for c in matching if not c[2]]
            if not ran:
                failures.append(
                    f"{module}: every test in {required_class} was skipped. A skipped suite is not "
                    f"a passing suite."
                )
                continue

            print(f"ok  {module}: {required_class} — {len(ran)} executed, {len(matching) - len(ran)} skipped")

    if failures:
        print("\nFAILED — the connected run did not cover what it claims to:\n", file=sys.stderr)
        for line in failures:
            print(f"  - {line}", file=sys.stderr)
        return 1

    print(f"\nall {required_total} required suites executed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
