# Automated locale sweep

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:installFossDebug
python3 tools/i18n-sweep.py --device emulator-5556 --password '<master password>'
```

The viewer flows (`file-viewer.yaml` and `file-settings-metadata.yaml`) are opt-in because they
need the test-only `MediaViewerFixtureSeeder` and a device PIN. Seed that fixture, then run them
with `LK_DEVICE_PIN=1234` (or pass `--device-pin`) and `--include-fixtures`.

One command walks every screen in five languages and writes `build/i18n-sweep.md`.

## The vault it needs — read this before blaming the harness

Two fixture facts, both learned the expensive way on 2026-08-11, when a whole run came back with
every flow reporting `FLOW FAILED` and the obvious conclusion — that the harness had rotted — was
wrong twice over.

**The password must actually open the vault.** Every flow starts with `subflows/unlock.yaml`, so a
stale password fails all eleven identically and the report says nothing about why. The one in the
local fixture notes had drifted from what the AVD held. Symptom to recognise: launching the app by
hand shows the unlock screen and rejects the password. Recovery is the documented one —
`adb shell pm clear com.localkeep.foss`, re-onboard with the same password, then put the device PIN
back (`adb shell locksettings set-pin 1234`), because clearing it makes three
`VaultLockManagerInstrumentedTest` cases fail as `BIOMETRIC_UNAVAILABLE` and look like a lock-manager
regression.

**The trash must not be empty.** `screens/trash-empty-dialog.yaml` taps `trash-empty`, and that
control does not exist when there is nothing in the trash — so a freshly re-onboarded vault fails
that one flow in all five languages while the other ten pass. It is a missing fixture, not a defect,
and it looks exactly like a defect in the report. Seed it before a run: create any item, long-press
its row, choose move-to-trash. One item is enough, and it survives across locales.

A run against a correctly seeded vault is 55 flows: 48 clean and 7 leads, all seven explicable
(see § Reading the report).

## What it is for

Two device passes in August 2026 found five i18n
defects. Every one was found by measuring pixel bounds, not by reading copy — and both of the
checks that did the finding are mechanical:

| check | what it catches | what it caught |
|---|---|---|
| **unaccented** | a string that renders in plain ASCII while everything around it is pseudolocalised — it was never a resource | seven `NewItemType` labels; `LkDialog`'s `dismissLabel = "Cancel"` default, one line reaching nine dialogs in four languages |
| **shared edge** | two or more *different-length* strings ending on the same pixel — they are all stopping at one constraint, not at their own last character | the settings summaries: seven truncated rows in German, one in English |

Neither is expressible as a Maestro assertion. An assertion has to name what it expects, and the
whole point is that nobody knew to expect either of these. Android lint cannot see them either:
`MissingTranslation` compares catalogues, and neither string was in one.

## Why the flows contain no English

Every previous pass drove the app by matching text on screen, which cannot be written once and
reused — the text differs in all five languages, and under `en-XA` it is not even ASCII ("Trash"
renders as `[Ţŕåšĥ one]`). So the same walk had to be re-driven by hand per language, which is why
run 13 closed with **seven screens never opened in any locale**.

The flows navigate by **test tag**, the one attribute of a control that is identical in every
language. Tags come from data the app already has — a nav route, an enum constant — so they are
applied in the handful of composables every instance passes through (`DrawerLink`, `BottomTab`,
`AddTypeRow`, `LkDialog`'s two buttons, the FAB, unlock) and no call site has to remember one.

`core/ui/LkTestTags.kt` has the mechanism. It is **debuggable builds only**: exposing a test tag to
the accessibility tree is what makes it visible to Maestro, and would equally expose the app's
internal control names to any accessibility service on a shipped device.

## Layout

```
.maestro/
  subflows/unlock.yaml       opens the vault; every screen flow starts here
  subflows/open-drawer.yaml  opens the drawer and scrolls a destination into view
  screens/*.yaml             one per screen — unlock, navigate, stop
tools/i18n-sweep.py          runs the flows per locale, snapshots, applies the checks
```

Adding a screen is one file. Adding a language is one entry in `--locales`.

## Reading the report

**Findings are leads, not work orders** — a person confirms each one before it is acted on, and
for good reason. Both checks have known false positives, and both have already produced one:

- A shared edge can be arithmetic. `vault-YYYYMMDD-HHMMSS.enc` filenames are the same length by
  construction, so they share both edges and are not clipped. The check only reports a group whose
  members have **different lengths**, which removes that case, but a coincidence is still possible —
  run 13 filed one and had to withdraw it.
- An unaccented string can be deliberate. The check exempts everything the app declares
  `translatable="false"`, read out of the resources rather than from a list here, so product names
  (Bitwarden, KeePass, Aegis, "Other / CSV") do not appear. User data does — pass `--ignore` with a
  regex for whatever the test vault happens to contain.
- The generator screen reports its own output (`v8}uK9EuW5GU=A[S%)9z`). A generated password is not
  a resource and never will be — it is the one standing false positive, and it is left visible
  rather than filtered, because a rule broad enough to hide it would hide real findings too.

A flow that fails is reported too, and means the screen was not reached — never treat a short
report as a clean one without checking that every screen ran. One flow failing on its own is
usually the environment: reinstalling the APK over a running process can deaden input until the
activity is restarted, and a sweep started immediately after an install has
lost exactly one flow to it. Re-run that flow alone before believing it.

Two sweeps must not share a device: the first full run had 22 flows fail because another was driving the same emulator, and the report
said so rather than calling them clean, which is the behaviour to preserve.

## Proving it still works

A check that never fires is broken, not clean — a verification step that rejects nothing has not
been shown to work. The sweep was proved against the defect that motivated it: with
`dismissLabel: String? = "Cancel"` put back in `LkDialog`, a single-locale run reports

```
- **trash-empty-dialog** — 1 lead(s)
  - unaccented `text` `Cancel` at `[488,1270][592,1311]` — never became a resource?
```

and reports nothing there with the fix in place. Re-run that check after changing either detector.

`trash-empty-dialog.yaml` exists for exactly this reason: every other flow stops on a *screen*, and
the defect lived in a dialog. A harness that only reaches screens would have missed the bug it was
built to find.

## What it does not do

It does not read copy. Semantics, glossary adherence, placeholder correctness and plural forms are
the catalogue verification's job, and security copy has its own review protocol. This sweep answers one question — *does the text fit, and did it get
translated at all* — which is exactly the question that needs a device.
