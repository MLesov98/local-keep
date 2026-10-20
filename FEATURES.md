# LocalKeep — what it is, what it does, and why

An offline password manager for Android. No account, no sync, no server. Your vault is one
encrypted file on your phone, and the app has no way to send it anywhere.

Everything here is checked against the code rather than against the design documents — where the two
disagree, this file says so out loud. It used to open with a "written against commit `0610688`,
2026-07-31" stamp, which turned out to be worse than no stamp at all: a snapshot date reads as a
freshness guarantee while quietly ageing, and by 2026-09-07 the Location item type and both free-tier
caps had moved underneath it without a word here changing. A date cannot notice that. So the stamp is
gone and the checks are named instead — `FreeTierDocsMatchCodeTest` for the free-tier numbers, and
`checkFlavorPermissions`, which enforces the permission allowlist the build table below describes.

For how these features compare against other Android password managers, see
[`COMPARISON.md`](COMPARISON.md).

---

## Contents

- [The one-paragraph version](#the-one-paragraph-version)
- [Why it exists](#why-it-exists)
- [What you can store](#what-you-can-store)
- [How your data is protected](#how-your-data-is-protected)
- [Getting your passwords in](#getting-your-passwords-in)
- [Using it day to day](#using-it-day-to-day)
- [Security tools](#security-tools)
- [Backup, export and recovery](#backup-export-and-recovery)
- [The duress vault](#the-duress-vault)
- [Languages](#languages)
- [Accessibility](#accessibility)
- [Free and Pro](#free-and-pro)
- [The two builds](#the-two-builds)
- [What it deliberately does not do](#what-it-deliberately-does-not-do)
- [Edges, limits and honest caveats](#edges-limits-and-honest-caveats)
- [How it is built](#how-it-is-built)
- [How quality is checked](#how-quality-is-checked)

---

## The one-paragraph version

LocalKeep keeps passwords, two-factor codes, cards, identity documents, secure notes and file
attachments in a single encrypted file called `vault.enc`, protected by a master password you
choose and that is never stored. It fills logins into other apps and browsers, generates passwords,
and tells you which of yours are weak, reused, old or known to have appeared in a breach — all of
that computed on the phone, with no lookup service involved. The free build has no networking
permission whatsoever.

## Why it exists

Most password managers are a service with an app attached. That is a reasonable design, and it
means your vault sits on someone else's computer, protected by their operational security and
reachable by their subpoenas. LocalKeep is the other choice: the vault is on your device, the app
cannot phone home because it has no permission to, and there is nothing to breach centrally because
there is no centre.

The cost of that choice is real and the app does not hide it. Nobody can reset your master password.
If you lose it and your Recovery Kit, the vault is gone — not "gone until support restores it".
That is the trade, stated at onboarding rather than buried.

## What you can store

Nine item types:

| Type | What it holds |
|---|---|
| **Login** | Username, password, websites, an optional built-in 2FA code, notes, custom fields, attachments |
| **2FA code** | A standalone authenticator entry — issuer, account, secret, algorithm, digits, period, Steam support |
| **Secure note** | Free-form encrypted text |
| **Card** | Cardholder, number, expiry, CVV, PIN, notes |
| **Identity** | Name, company, national ID number, passport, licence, email, phone, full address |
| **File** | Encrypted documents, photos and videos, viewable in-app |
| **Location** | A saved coordinate — latitude, longitude, when it was taken and how accurate it was. Captured from the phone's GPS on an explicit tap. There is no way to type one in by hand; the editor's only control is "Find my location", and saving without one is refused |
| **Seed phrase** | A recovery phrase — the words, plus the optional BIP-39 passphrase |
| **Passkey** | A WebAuthn credential — relying party, credential and user handle, the DEK-wrapped private key, algorithm and signature counter. Created and used through Android's credential provider, not typed in |

Across all of them: **folders**, **tags**, **favourites**, **custom fields** including hidden ones,
and a per-item **reprompt** flag that demands re-authentication before that item's secrets are shown.

**Folders nest**, up to eight levels. The vault list browses them: a breadcrumb, folder rows with
counts over the whole subtree, and the folder's own items listed above an "In subfolders" section
holding everything further down — so a parent never reads as empty while holding forty items one
level below it. Folder names are searchable, and searching inside a folder is scoped to its subtree.

**Hold an item or a folder to pick it up and drag it** — onto a folder row, onto a breadcrumb (rest
on one and it opens with what you are carrying still in hand), up a level, out to the top level, or
into the bin. Every move that writes is one tap to undo. Everything the drag can do also has a
non-gesture route: each row carries the same actions for a screen reader, and a "Move to folder…"
picker does the same work without a gesture at all. The drag never leaves the app's own surface —
Android's system drag would draw what you are carrying in a window outside `FLAG_SECURE`, so this
one is in-process, and a build check keeps it that way.

**Deleting a folder is recoverable.** It goes to the Trash with its contents rather than scattering
them, and restoring it puts the subtree back where it was.

Videos play **inside the app**, never handed to the gallery. The blob format is seekable, so the
decoder is fed one decrypted 64 KiB frame at a time — peak memory is two frames regardless of file
size, and no plaintext copy of the file ever exists on disk for another app to find.

## How your data is protected

**At rest.** Your master password derives a key encryption key via **Argon2id** (RFC 9106,
version `0x13`) — 32 MiB memory, 3 iterations, parallelism 4 by default, with a hard floor enforced
in code and an on-device calibration ladder that steps *down* rather than crashing on a weak phone.
Cost parameters are stored per unlock slot with an independent bounds check, so a tampered file
cannot force a cheap key derivation.

The vault payload is encrypted with **AES-256-GCM-SIV**, which is nonce-misuse-resistant — a
repeated nonce degrades gracefully instead of catastrophically. The data encryption key is wrapped
per slot with **AES-256-GCM**. Sub-keys come from **HKDF-SHA256** with frozen purpose labels, so
the DEK is never used directly as an encryption or authentication key.

There is also a **key commitment**: an HMAC-SHA256 over a fixed message, checked in constant time.
This defeats the "invisible salamander" class of attack, where one ciphertext decrypts successfully
under two different keys.

The core primitives are pinned to **published reference vectors** — RFC 9106, RFC 8452, NIST CAVP,
RFC 5869 — not merely round-tripped against themselves. The streaming format used for file
attachments is the exception: it has no published vector to pin it to. Round-tripping proves you can undo what you
did; vectors prove you implemented the actual algorithm.

**On screen.** Every window sets `FLAG_SECURE`, so screenshots and the app-switcher preview are
blank. Secrets are masked by default. Revealing one cross-fades rather than snapping, and switches
to a monospace face — the change of *shape* is what makes "this is exposed right now" hard to miss.

**Re-authentication** is required before both revealing **and** copying high-value fields: a TOTP
seed, a card's CVV and PIN, and a location's coordinates. Those are gated on every item
regardless of its reprompt flag, because each is enough on its own to clone or use a credential,
or to reveal a place. Password-history entries are re-auth gated to reveal and cannot be copied.
The live password and the rolling 2FA code are deliberately *not* — a fingerprint every thirty
seconds is a second factor nobody keeps using.

**Clipboard** entries auto-clear. **Auto-lock** runs on both an idle timer and a background timer,
configurable, with a Relaxed setting for long operations. **Panic lock** — a long press on Lock —
drops everything immediately.

**Password history** keeps the last **5** passwords per login. They are shown on the item's detail
screen with the date each was replaced, every one re-auth gated, and deletable in one action. They
are deliberately *not* copyable: nobody signs in with a password they have stopped using, and
copying would put it on a clipboard every app can read.

## Getting your passwords in

Eleven guided import sources, each with step-by-step instructions written for that specific app and
honest caveats about what it will and will not carry across:

**Google / Chrome** · **Apple Passwords** · **Bitwarden** · **1Password** (`.1pux`) ·
**KeePass** (`.kdbx`) · **Aegis** · **Keeper** · **Enpass** · **Proton Pass** · **Dashlane** ·
**Other / CSV**

The wizard shows you what will be imported before it commits, lets you deselect items, and merges
rather than replaces — an existing item keeps its attachments, tags, folder and notes when an import
collides with it, and the *newer* password wins even if the older copy was edited more recently.
(The folder and notes halves of that sentence were untrue for Bitwarden imports until 2026-09-11:
Bitwarden exports carry folders, so a colliding item was silently re-filed and its note replaced.
`ItemMergePreserveStructureTest` now fails if either regresses.)

Imported data is never capped. Free-tier limits restrict **creation only**; a vault you brought
over is fully readable and editable however large it is.

## Using it day to day

**Autofill** fills logins into other apps and browsers, matching on web domain and Android package.
It offers to save new credentials and to update changed ones. Credentials bind to their web domain
rather than to the browser's package, so a password saved in Chrome works in Firefox.

**Passkeys** — the app is a credential provider, creating and using WebAuthn passkeys. This needs
Android 14 or later; on Android 9–13 passkeys are not available.

**Generator** — passwords and passphrases, built with the system's secure random generator, with a
strength meter.

**Search** covers titles, usernames, sites, notes and folder names, and is diacritic-insensitive.

**Haptics** — seven distinct signatures for different kinds of event, with a Reduce Haptics setting.
There are deliberately **no custom UI sounds**: audio feedback on a master-password or PIN field is
an acoustic side-channel, and the app relies on Android's own touch-sound convention instead.

## Security tools

A dedicated Security screen, scanning **entirely on the device**:

- **Reused passwords** — the same secret across multiple entries
- **Weak passwords** — scored with zxcvbn
- **Potentially compromised** — matched against a bundled breach corpus
- **Old passwords** — 90 days or more since the password itself changed, not since the item was edited
- **Duplicate entries**

The breach corpus is a **13.8 MB** Golomb-Rice-coded filter derived from SecLists
(`Pwdb_top-10000000`, MIT-licensed), shipped **inside the app**. Checking a password against it
involves no network request and no partial-hash lookup, because there is no service to ask. Both
builds have it; it is not a paid feature.

An **Always-on** panel states the protections that are in force at all times, including an honest
statement of what the app's root/tamper detection can and cannot see.

## Backup, export and recovery

- **Rolling local snapshots** — the newest **5**, written atomically, kept on the device
- **Manual export** of the encrypted vault
- **Restore** validates a snapshot before overwriting anything; a corrupt file is rejected, never written
- **Recovery Kit** — 24 words, shown once, the only way back in if you forget your master password
- **"Include vault in Android backup" toggle** — see the caveat below, because this one matters

## The duress vault — Pro

**Setting one up requires Pro**, and in the `foss` build Pro is permanently unavailable, so the
duress vault is a Play-build feature. This section carried no tier marker until 2026-09-13, when a
sweep checked it against `SettingsScreen.kt`'s `ProGate` and found the free build advertising a
screen it cannot reach. A decoy configured on a Play install stays readable in every build — the
gate is on creating one, as with every other quota here.

A second master password that opens a *different*, decoy vault. Both unlock slots carry
**identical KDF parameters**, deliberately, so someone reading the vault file cannot tell which slot
is real or that a second one is even in use. The decoy is seeded with plausible content rather than
left empty — an empty vault advertises that a real one is being withheld.

The Play In-App Review prompt is suppressed during a duress session. The decoy exists to convince an
observer that there is nothing here, and a review card is a conversation about the app itself.

## Languages

**English, French, German, Spanish, Portuguese** — base languages, no regional variants.

Translation is machine-first and verified second. Every string is a resource; plurals use proper
CLDR plural forms per language; format arguments are positional. Lint treats missing translations,
mismatched format specifiers and hardcoded strings as **errors**, so an untranslated string fails
the build rather than shipping in English.

Security-critical copy — irreversible-action confirmations, the Recovery Kit, reveal gates, the
duress vault, purchase claims, user-acted numbers, and any claim about where your data lives — goes
through back-translation diffing and a three-vote adversarial check, plus a per-language
banned-phrase regression test for the strongest tier.

**Stated plainly:** no native speaker of French, German, Spanish or Portuguese has reviewed this
copy. That was decided in the open and the substitute is documented, not pretended equivalent.

## Accessibility

48dp minimum touch targets. Content descriptions on every icon-only control. Reduced-motion
fallbacks throughout, and no looping attention animations — continuous motion is reserved for
loading indicators, and every other animation expresses a cause. State changes are announced to
screen readers. Buttons scale with the system type size. Colour is never the sole carrier of meaning.

The UI is **dark only**, by decision rather than omission. This caps the app's own UX score below
100 and that cap is recorded rather than quietly dropped.

## Free and Pro

Pro is a **one-time €9.99 purchase**, Play build only. No subscription. Everything you already have
keeps working whether or not you buy it.

Free forever, and not negotiable for revenue reasons: **unlimited passwords and notes**, **autofill**
and **the generator**. A password manager that cannot fill a login is not being evaluated, it is
being uninstalled.

Paid: unlimited file attachments (free tier: **7**), plus a Card and an Identity beyond the one of
each the free tier gets.

The three free-tier numbers above are the constants the app enforces, not a separate copy of them:
**7** is `AttachmentQuota.FREE_LIMIT` and the **one Card / one Identity** allowance is
`ProTypeQuota.FREE_LIMIT`. `FreeTierDocsMatchCodeTest` fails the build if this file, `COMPARISON.md`
and those constants stop agreeing — the earlier version of this paragraph said 5 attachments for
four days after the cap moved to 7, and nothing noticed.

The caps limit **creation only**. A vault that arrives from an import carrying thirty attachments or
twenty cards keeps every one of them readable, searchable and editable on the free tier; it simply
cannot add one more. Nothing is ever hidden or dropped to fit a cap.

> **Resolved 2026-09-03.** This paragraph used to flag a discrepancy: the 2026-07-29 tier decision
> specified Cards capped at 3, Identity
> at 1 and TOTP at 3, while the shipped code gated Cards and Identities on Pro outright. The code
> moved, not the document: `ProTypeQuota` now grants one Card and one identity document to a free
> vault, so neither type is a binary gate any more. TOTP remains deliberately ungated — a
> two-factor code the user cannot store is a login they cannot complete.

The price shown in the app is always **Play's own localised price**, never a hardcoded euro
figure — and where Play has not answered yet, the copy states no amount at all rather than guessing.

## The two builds

|  | `foss` | `play` |
|---|---|---|
| App id | `com.localkeep.foss` | `com.localkeep` |
| Permissions | `USE_BIOMETRIC`, `USE_FINGERPRINT`, `CAMERA` (offline QR scanning for 2FA setup), `ACCESS_COARSE_LOCATION` and `ACCESS_FINE_LOCATION` (the Location item type). **No `INTERNET`.** | The same five, plus `INTERNET`, `ACCESS_NETWORK_STATE`, `BILLING` — used only by Google Play Billing and the in-app review card |
| Billing | None — Pro is permanently unavailable | Google Play Billing |
| In-App Review | Absent | Present, heavily gated |
| Breach checking | Yes | Yes |
| Distribution | Self-hosted APK, open-source repo | Google Play |

The free build is self-distributed. **F-Droid is not a distribution target** — that was decided on
2026-07-29.

Both location permissions are foreground-only, and both builds carry them for the same single
feature: one GPS reading, taken at the moment you tap "Find my location" and never at launch.
`ACCESS_BACKGROUND_LOCATION` and `FOREGROUND_SERVICE` are deliberately absent — there is no
tracking, no history and no scheduled work behind the Location item type — and `checkFlavorPermissions`
fails the build if either ever appears. The coordinate goes into the encrypted vault like every other
item and is sent nowhere; the `foss` build still has no way to send it anywhere at all.

Two Gradle tasks enforce the split on every build: `checkNoNetworkingDeps` blocks networking
artifacts from reaching the air-gapped modules, and `checkFlavorPermissions` fails the build if the
merged `foss` manifest ever asks for anything outside its allowlist.

## What it deliberately does not do

- **No sync.** No cross-device anything.
- **No account, no email, no telemetry, no analytics, no crash reporting.**
- **No password recovery by the vendor.** There is no vendor-side anything.
- **No browser extension, no desktop app, no web vault.**
- **No emergency access / dead-man's switch.** Listed in the tier table; does not exist yet.
- **No custom UI sounds** — an acoustic side-channel on a PIN field.
- **No light theme.**

## Edges, limits and honest caveats

These are the things a careful reader should know, and the app says them in its own UI too.

**Google Auto Backup takes your vault by default.** `vault.enc` is in Android's backup set. Turning
the **Include vault in Android backup** switch off genuinely removes it — this was verified on hardware
(Galaxy S24 Ultra, 2026-07-29) — but the default is on, and the OS transport performs the upload, so
`foss` having no `INTERNET` permission does not change that. Three separate files used to claim the
vault never leaves the device. All three were wrong and all three were corrected.
**Attachments are not in that backup set.** `full_backup_content.xml` excludes `blobs/` — the bytes
of every File and photo item — because they would blow the ~25 MB payload cap on their own, so a
restore from Google brings the items back without their files. The Backup screen says so too.

**A trash delete is not a secure erase.** Flash storage and wear levelling mean the app cannot
promise a byte is unrecoverable. The UI says so rather than implying otherwise.

**Copying puts plaintext on the system clipboard,** where every app on the phone can read it until
it clears. That is why the app's own claim is the narrow one — "no network, no sync" — rather than
"never leaves your device".

**Root defeats it.** A root-level OS compromise defeats any userspace password manager. The threat
model puts that tier out of scope in writing, and the app's root detection screen states what it can
and cannot actually see rather than implying it is a defence.

**The security scan takes about 20 seconds on a 10,000-item vault**, measured on a device. It is not
a hang — the scanning card renders — but completion is not currently announced, which matters for
screen-reader users. Open, and known.

**The low-memory path at Android 9 (API 28) is unverified.** Measurement was done on API 36. The
48 MiB heap cap at minSdk is exactly the case nothing has exercised.

**Nothing here has had an external security audit.** Review is tools-only and free: guardrails,
fuzzing, coverage floors and structured QA reviews, with the automated checks re-run on every commit. That caps the
project's own release-readiness score on purpose, and the cap is stated rather than rounded away.

## How it is built

Kotlin and Jetpack Compose. **29 Gradle modules** — `core/*` for crypto, vault, model, import,
health, generator, TOTP, location, UI and billing; `feature/*` for each screen; `autofill/` and
`credential/`; plus two test apps under `tools/` and the fuzz and device-test modules under `test/`.
minSdk 28 (Android 9), targetSdk 36. Argon2id via Bouncy Castle. Hilt for injection.
Protobuf-shaped serialisation with frozen field numbers, so the vault format can grow without
breaking older files — and any change to it requires a version bump and a migration path.

## How quality is checked

- **2,241 unit tests** (counted 2026-09-12), plus an instrumented suite covering vault key management, the keystore, the
  air gap and no-plaintext-to-disk
- **Measured coverage floors** on `core/crypto` and `core/vault` — 84.4% and 88.4% against a floor
  of 80% (measured 2026-08-12), computed by the build from unit and instrumented data combined,
  not asserted
- **Guardrail tasks** that fail the build: no networking dependencies, no JVM-only XML APIs, no
  permissions outside the flavour allowlist, no hardcoded user-facing strings, no placeholder breach
  corpus
- **Fuzzing** — Jazzer targets over every parser of untrusted input, with crashers committed as
  regression tests
- **Structured QA reviews** across a catalogue of 59 named review angles, each finding independently
  verified by an adversarial pass that rejects 25–50% of them, followed by a hands-on device walk —
  because a read-only audit cannot see an interaction dead-end, and seven launch-blockers survived
  all 59 lenses before someone simply used the app
- **CI** runs in the maintainers' private pipeline, with instrumented tests on a self-hosted macOS
  runner; this public repository has no CI of its own

Every fix carries a regression test that fails without it.
