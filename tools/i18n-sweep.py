#!/usr/bin/env python3
"""Drive every screen in every language and check two things no human should have to.

    tools/i18n-sweep.py --device emulator-5556

Why this exists
---------------
Two device passes (August 2026) found five i18n defects, and every one of them was
found by *looking at pixel bounds*, not by reading copy. Both of the checks that did the finding are
mechanical, and neither is expressible as a Maestro assertion, because an assertion has to name what
it expects and the whole point is that nobody knew what to expect:

  unaccented   Under the `en-XA` pseudolocale every string the app owns comes back accented. One
               that does not was never a resource. This is what caught the seven `NewItemType`
               labels, and later `LkDialog`'s `dismissLabel = "Cancel"` default — a single line that
               reached nine dialogs in four languages. Android lint cannot see either: it compares
               catalogues, and neither string was in one.

  shared-edge  A dump reports text in full even when the screen ellipsises it, so length proves
               nothing. Width does: ellipsised text fills exactly the space it was given, intact
               text stops short. Two or more *different-length* strings ending on the same pixel are
               all stopping at one shared constraint. This caught the settings summaries (seven
               truncated rows in German, one in English).

Both are locale-invariant, so they cost nothing to run on all five languages — which is the part a
person will not do by hand, and did not: run 13 shipped with seven screens never opened at all.

The flows navigate entirely by test tag (see `core/ui/LkTestTags.kt`), so one flow file runs in
every language. That is the only reason this is cheaper than driving it by hand.

Findings are leads. A shared edge can be a coincidence — `vault-YYYYMMDD-HHMMSS.enc` filenames are
the same length by construction — and an unaccented string can be a product name. The report says
what was measured and leaves the verdict to a person.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import unicodedata
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
FLOWS = REPO / ".maestro" / "screens"
PKG = "com.localkeep.foss"

# The pseudolocale accents letters and leaves everything else alone, so a string made only of
# digits, punctuation and spacing tells us nothing either way.
NO_LETTERS = re.compile(r"^[^A-Za-z]*$")


def sh(*cmd: str, **kw) -> subprocess.CompletedProcess:
    env = {**os.environ}
    env.setdefault("JAVA_HOME", "/Applications/Android Studio.app/Contents/jbr/Contents/Home")
    env["PATH"] = "/opt/homebrew/opt/maestro/bin:" + env.get("PATH", "")
    return subprocess.run(cmd, capture_output=True, text=True, env=env, **kw)


def untranslatable() -> set[str]:
    """Every string the app declares `translatable="false"`, read from the resources themselves.

    Hardcoding a list of product names here would rot the first time one was added. The app already
    states which strings are deliberately English — Bitwarden, KeePass, Aegis, "Other / CSV" — and
    it states it in the only place that cannot drift from what ships.
    """
    out: set[str] = set()
    for f in REPO.glob("**/src/main/res/values/strings.xml"):
        if "build/" in str(f):
            continue
        for m in re.finditer(r'<string name="[^"]+"[^>]*translatable="false"[^>]*>(.*?)</string>',
                             f.read_text(encoding="utf-8"), re.DOTALL):
            out.add(m.group(1).replace("&amp;", "&").strip())
    return out


# Maestro's hierarchy carries no package name, so foreign windows are identified by the only thing
# that does name them: the resource-id prefix on their root. Pruning the whole subtree rather than
# filtering leaf by leaf, because the leaves themselves are anonymous — the status bar's clock and
# "Battery 100 percent." have no id at all, and the first run of this sweep duly reported them as
# untranslated app strings on all ten screens.
FOREIGN = ("com.android.systemui:", "com.google.android.")


def nodes(tree: dict):
    """Flatten the hierarchy, keeping only what belongs to the app under test."""
    stack, out = [tree], []
    while stack:
        n = stack.pop()
        if not isinstance(n, dict):
            continue
        a = n.get("attributes", {})
        rid = str(a.get("resource-id", ""))
        if rid.startswith(FOREIGN):
            continue  # drop the window, not just this node
        if a.get("text") or a.get("accessibilityText"):
            out.append(a)
        stack.extend(n.get("children") or [])
    return out


def bounds(a: dict):
    b = a.get("bounds", "")
    m = re.match(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", b)
    return tuple(int(g) for g in m.groups()) if m else None


def check_unaccented(attrs, allowed: set[str], ignore: re.Pattern | None):
    """Anything the app renders under en-XA that never went through the pseudolocalizer."""
    hits = []
    for a in attrs:
        for key in ("text", "accessibilityText"):
            v = (a.get(key) or "").strip()
            if not v or v in allowed or NO_LETTERS.match(v):
                continue
            if ignore and ignore.search(v):
                continue
            # The pseudolocalizer marks what it processed by accenting the letters in it. A string
            # with letters and no combining/extended characters at all did not go through it.
            if any(unicodedata.combining(c) or ord(c) > 0x7F for c in v):
                continue
            hits.append((v, a.get("bounds", ""), key))
    return hits


def prose(attrs):
    """Single-line strings long and wide enough for their right edge to mean anything."""
    out = []
    for a in attrs:
        t = (a.get("text") or "").strip()
        bb = bounds(a)
        if not t or len(t) < 20 or not bb:
            continue
        x1, y1, x2, y2 = bb
        if (y2 - y1) > 46 or (x2 - x1) < 200:  # single line, wide enough to be prose
            continue
        out.append((t, x1, x2, y2 - y1))
    return out


def check_shared_edge(attrs):
    """Different-length strings ending on the same pixel — the ellipsis signature."""
    by_edge: dict[tuple[int, int], set[str]] = {}
    for t, x1, x2, _ in prose(attrs):
        by_edge.setdefault((x1, x2), set()).add(t)
    out = []
    for (x1, x2), texts in sorted(by_edge.items()):
        # Same-length strings share an edge for arithmetic reasons — timestamps, generated
        # filenames. Only a *spread* of lengths landing on one pixel is evidence of a constraint.
        if len(texts) >= 2 and len({len(t) for t in texts}) >= 2:
            out.append((x1, x2, sorted(texts)))
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--device", required=True)
    ap.add_argument("--locales", default="en-XA,de,fr,es,pt")
    ap.add_argument("--password", default=os.environ.get("LK_MASTER_PASSWORD", ""))
    ap.add_argument("--device-pin", default=os.environ.get("LK_DEVICE_PIN", ""),
                    help="credential for fixture viewer flows")
    ap.add_argument("--include-fixtures", action="store_true",
                    help="include flows that require MediaViewerFixtureSeeder")
    ap.add_argument("--out", default=str(REPO / "build" / "i18n-sweep.md"))
    ap.add_argument("--ignore", default="", help="regex for text this vault happens to contain")
    ap.add_argument("--only", default="", help="regex on screen names, to re-run a subset")
    args = ap.parse_args()
    if not args.password:
        print("--password (or LK_MASTER_PASSWORD) is required: the flows unlock a real vault",
              file=sys.stderr)
        return 2

    allowed = untranslatable()
    ignore = re.compile(args.ignore) if args.ignore else None
    flows = sorted(FLOWS.glob("*.yaml"))
    if not args.include_fixtures:
        flows = [flow for flow in flows if flow.stem not in {"file-viewer", "file-settings-metadata"}]
    if args.only:
        flows = [flow for flow in flows if re.search(args.only, flow.stem)]
    report: list[str] = [
        "# i18n sweep",
        "",
        f"{len(flows)} screens x {len(args.locales.split(','))} locales, "
        f"driven by test tag so one flow file runs in every language.",
        "",
        "Findings are leads, not work orders. A shared edge can be arithmetic; an unaccented",
        "string can be a product name the app deliberately does not translate.",
        "",
    ]
    total = 0
    # A flow that never reached its screen is not a lead, it is a broken sweep: every check below
    # runs against a hierarchy that was never captured, so the report reads "no findings" for a
    # screen nobody looked at. This exits non-zero so CI cannot treat that as a pass -- the leads
    # themselves stay advisory, and still do not fail the run.
    flow_failed = False
    # Collected across every locale first, then written out, so a lead found in the first language
    # can be judged against what the last one turned out to render.
    found: list = []
    widest: dict[str, int] = {}

    for locale in args.locales.split(","):
        sh("adb", "-s", args.device, "shell", "cmd", "locale", "set-app-locales", PKG,
           "--locales", locale)
        for flow in flows:
            sh("adb", "-s", args.device, "shell", "am", "force-stop", PKG)
            maestro_args = ["maestro", "--device", args.device, "test",
                            "-e", f"MASTER_PASSWORD={args.password}"]
            if args.device_pin:
                maestro_args += ["-e", f"DEVICE_PIN={args.device_pin}"]
            r = sh(*maestro_args, str(flow))
            if r.returncode != 0:
                found.append((locale, flow.stem,
                              [f"  - flow failed, screen not reached "
                               f"(`{(r.stdout or r.stderr).strip().splitlines()[-1][:120]}`)"]))
                total += 1
                flow_failed = True
                print(f"[{locale}] {flow.stem}: FLOW FAILED", flush=True)
                continue
            h = sh("maestro", "--device", args.device, "hierarchy")
            try:
                attrs = nodes(json.loads(h.stdout))
            except json.JSONDecodeError:
                found.append((locale, flow.stem, ["  - hierarchy unreadable"]))
                total += 1
                continue

            lines = []
            if locale == "en-XA":
                for v, b, key in check_unaccented(attrs, allowed, ignore):
                    lines.append(f"  - unaccented `{key}` `{v}` at `{b}` — never became a resource?")
            for x1, x2, texts in check_shared_edge(attrs):
                joined = ", ".join(f"`{t}`" for t in texts[:4])
                lines.append((x2, f"  - shared edge x=[{x1},{x2}] across {len(texts)} strings: {joined}"))
            # How far prose on this screen is allowed to reach, learned from every locale that has
            # run it. See the annotation below.
            widest[flow.stem] = max([widest.get(flow.stem, 0)] + [x2 for _, _, x2, _ in prose(attrs)])
            found.append((locale, flow.stem, lines))
            n = len(lines)
            total += n
            verdict = "clean" if not n else f"{n} lead(s)"
            # Streamed, because a full sweep is ~55 flow runs and a silent half hour is
            # indistinguishable from a hang.
            print(f"[{locale}] {flow.stem}: {verdict}", flush=True)

    for locale in args.locales.split(","):
        report += [f"## {locale}", ""]
        for loc, screen, lines in found:
            if loc != locale:
                continue
            report.append(f"- **{screen}** — {'clean' if not lines else f'{len(lines)} lead(s)'}")
            for line in lines:
                if isinstance(line, str):
                    report.append(line)
                    continue
                x2, text = line
                report.append(text)
                # The refutation this sweep is uniquely placed to make, because it has already
                # rendered the same screen in five languages: if some *other* language wraps prose
                # on this screen further right than the suspected edge, that further point is the
                # real constraint and the shared edge is under it — a coincidence, not a clip. Two
                # strings of 44 and 41 characters ending on the same pixel is exactly how run 13
                # filed a German finding it then had to withdraw.
                if widest.get(screen, 0) > x2:
                    report.append(
                        f"    - likely coincidence: prose on this screen reaches "
                        f"x={widest[screen]} in another locale, so {x2} is not the constraint"
                    )
                else:
                    report.append(
                        f"    - no locale renders prose past x={x2} on this screen, "
                        f"so this is where the constraint is"
                    )
        report.append("")

    report += ["", f"**{total} lead(s) total.**", ""]
    if flow_failed:
        report += [
            "**At least one flow did not reach its screen.** Everything this report says about "
            "those screens is the absence of a measurement, not the absence of a problem.",
            "",
        ]
    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    Path(args.out).write_text("\n".join(report), encoding="utf-8")
    print("\n".join(report))
    return 1 if flow_failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
