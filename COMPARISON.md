# LocalKeep vs. the competition

A feature comparison between LocalKeep and the password managers an Android user would
realistically pick instead. LocalKeep's column is drawn from [`FEATURES.md`](FEATURES.md),
which is itself checked against the code. Every other column is drawn from vendor
documentation and reviews gathered on 2026-08-01 by parallel web research.

**How to read this.** Competitor rows are as good as their public documentation, which is
not the same standard as the LocalKeep column. Anything the research could not confirm from
a source is written `?` rather than guessed — an unconfirmed `FLAG_SECURE` row means nobody
published it, not that the app lacks it. Prices move; the ones here are what vendors listed
in mid-2026. Where a well-reviewed site and a vendor disagreed, the vendor wins.

---

## Contents

- [Who is actually in this table](#who-is-actually-in-this-table)
- [The short version](#the-short-version)
- [Architecture and trust model](#architecture-and-trust-model)
- [Cryptography](#cryptography)
- [Price](#price)
- [What you can store](#what-you-can-store)
- [Daily use](#daily-use)
- [Security features](#security-features)
- [Import and lock-in](#import-and-lock-in)
- [Distribution, audits and reach](#distribution-audits-and-reach)
- [Where LocalKeep wins](#where-localkeep-wins)
- [Where LocalKeep loses](#where-localkeep-loses)
- [Adjacent apps that are not really competitors](#adjacent-apps-that-are-not-really-competitors)
- [Sources](#sources)

---

## Who is actually in this table

Seventeen products across four categories. The three adjacent apps get their own section at the
bottom, and not every product appears in every table. Apple Passwords has no Android app; it is
included alongside Google Password Manager as the other OS built-in.

| Category | Products |
|---|---|
| **Local-first / offline** | **LocalKeep**, KeePassDX, Enpass, SafeInCloud, RoboForm (local-only mode) |
| **Cloud-first commercial** | Bitwarden, 1Password, Proton Pass, Dashlane, NordPass, Keeper, mSecure |
| **OS built-in** | Google Password Manager, Apple Passwords |
| **Adjacent (not password managers)** | Aegis, Ente Auth, Android Password Store |

**Dropped from the table:** **Strongbox** — iOS/macOS only, no Android build, so it cannot
compete for the same user. **Aegis**, **Ente Auth** and **Password Store** are covered in
their own section at the bottom rather than in the main table, because comparing a TOTP-only
authenticator against a full vault produces a row of misleading dashes.

Legend: **✓** yes · **✗** no · **?** not confirmed by any source found · **$** paid tier only

---

## The short version

LocalKeep and KeePassDX are the only products in this comparison whose *free* build has no
networking permission at all, and LocalKeep is the only one that runs a breach check without a
network request. It is also the only one that documents a duress vault. It is the smallest in reach by a wide margin:
one platform, five languages, no sync, no audit.

If the question is "which password manager can I use without trusting anyone", LocalKeep and
KeePassDX are the two serious answers, and LocalKeep is the one that ships breach checking,
password health, guided imports and a duress vault on top. If the question is "which password
manager should most people use", the honest answer is still Bitwarden or 1Password, because
most people want their passwords on their laptop too.

---

## Architecture and trust model

| | LocalKeep | Bitwarden | 1Password | Proton Pass | KeePassDX | Enpass | Dashlane | NordPass | Keeper | RoboForm | Google PM | Apple Passwords |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Works fully offline | **✓** | read-only | ✓ after login | $ only | **✓** | ✓ | limited | ? | ✓ after sync | ✓ | ✗ | cached only |
| Vendor server involved at all | **✗** | ✓ | ✓ | ✓ | ✗ | optional | ✓ | ✓ | ✓ | optional | ✓ | ✓ |
| Account required | **✗** | ✓ | ✓ | ✓ | ✗ | ✗ | ✓ | ✓ | ✓ | ✗ (local mode) | ✓ | ✓ |
| Cross-device sync | ✗ | ✓ | ✓ | ✓ | manual | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Open source | **✓** | ✓ | ✗ | ✓ | ✓ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ |
| No `INTERNET` permission in free build | **✓** | ✗ | ✗ | ✗ | ✓ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ |
| Telemetry / analytics | **✗** | ✓ | ✓ | ✓ | ✗ | ? | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |

Only LocalKeep and KeePassDX have no vendor in the trust model at all. Enpass and RoboForm
come close — both can run without cloud — but both are proprietary, so "no server" is a claim
you take on faith rather than one you can read.

---

## Cryptography

| | KDF | Cipher | Notes |
|---|---|---|---|
| **LocalKeep** | **Argon2id**, 32 MiB / 3 iter / p4 | **AES-256-GCM-SIV** | Nonce-misuse-resistant; HKDF-SHA256 sub-keys; HMAC key commitment; pinned to RFC 9106 / RFC 8452 / NIST CAVP / RFC 5869 vectors |
| Bitwarden | PBKDF2 600k **or** Argon2id | AES-256-CBC + HMAC-SHA256 | Argon2id opt-in, PBKDF2 still the default |
| 1Password | PBKDF2-HMAC-SHA256, 100k | AES-256-GCM | Low iteration count, offset by the 128-bit Secret Key |
| Proton Pass | bcrypt + SRP (account); Argon2 (local) | AES-256-GCM | Per-vault key wrapped with Curve25519 OpenPGP |
| KeePassDX | Argon2d / Argon2id / AES-KDF | AES-256, Twofish, ChaCha20 | User-selectable; KDBX format |
| Enpass | PBKDF2-SHA256 | AES-256 (SQLCipher) | |
| Dashlane | Argon2 (PBKDF2 legacy) | AES-256 | |
| NordPass | Argon2id | XChaCha20-Poly1305 | |
| Keeper | PBKDF2-HMAC-SHA512, 1,000,000 | AES-256-GCM | |
| RoboForm | PBKDF2-SHA256, 8,388,608 | AES-256 | Highest published iteration count here |
| SafeInCloud | PBKDF2-HMAC-SHA512 | AES-256 | |
| Google PM | ? | AES-256 | Not zero-knowledge unless a sync passphrase is set |
| Apple Passwords | ? | AES-256 | E2EE via Secure Enclave; Apple holds keys unless ADP is on |

Two things stand out. **Memory-hard KDF as the default** is not universal — Bitwarden ships
PBKDF2 by default, and 1Password's 100k PBKDF2 is the weakest number in the table taken
alone. And **key commitment** appears nowhere else in this list; it defeats a real, published
attack class, and no competitor documents having it.

Cipher choice is a wash otherwise. AES-256 in an authenticated mode is the correct answer and
almost everyone gives it. LocalKeep's GCM-SIV and NordPass's XChaCha20-Poly1305 are the two
that pick a mode with a better failure story rather than the default one.

---

## Price

| | Free tier | Paid | Model |
|---|---|---|---|
| **LocalKeep** | Unlimited passwords, notes, autofill, generator, breach checks; 7 attachments, 1 card, 1 identity | **€9.99 once** | **One-time, Play build only** |
| Bitwarden | Unlimited passwords + devices | $19.80/yr | Subscription (doubled Jan 2026) |
| 1Password | None — 14-day trial | $47.88/yr | Subscription (+33% Mar 2026) |
| Proton Pass | Unlimited logins/notes/cards | $35.88/yr | Subscription |
| KeePassDX | Everything | themes only | Donation-shaped |
| Enpass | 25 items (mobile) | $17.99/yr, lifetime option | Sub or one-time |
| Dashlane | **None** — discontinued Sept 2025 | $59.88/yr | Subscription |
| NordPass | Unlimited passwords, 1 device | ~$35.76/2yr | Subscription |
| Keeper | trial | ~$21.48/yr | Subscription |
| RoboForm | 1 device | $11.90/yr | Subscription |
| SafeInCloud | — | **$4.99 once** (mobile) | One-time |
| mSecure | trial | $30–60 once, or sub | Either |
| Google PM / Apple Passwords | Everything | — | Free, bundled |

LocalKeep and SafeInCloud are the only paid products here with a genuine one-time price and
no subscription path. That is a real differentiator in a category where **two major vendors
raised prices in the first quarter of 2026** and one deleted its free tier outright.

LocalKeep's free tier is also unusually generous at the point that matters: autofill and the
generator are free forever, where Enpass caps free users at 25 items and NordPass at one
device. A password manager that cannot fill a login does not get evaluated.

---

## What you can store

| | Logins | Notes | Cards | Identity | TOTP | Files | Custom fields | Folders |
|---|---|---|---|---|---|---|---|---|
| **LocalKeep** | ✓ | ✓ | $ (1 free) | $ (1 free) | ✓ | $ (7 free) | ✓ + hidden | ✓ |
| Bitwarden | ✓ | ✓ | ✓ | ✓ | $ | $ 5 GB | ✓ | ✓ |
| 1Password | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ 1 GB | ✓ | ✓ |
| Proton Pass | ✓ | ✓ | ✓ | ✓ | $ | $ 100 MB/file | ✓ | ✓ |
| KeePassDX | ✓ | ✓ | ✓ | ✓ | ✓ | ? | ✓ | ✓ |
| Enpass | ✓ | ✓ | ✓ | ✓ | ✓ | $ | ✓ | ✓ |
| Dashlane | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ 1 GB | ✓ | ✓ |
| NordPass | ✓ | ✓ | ✓ | ✓ | **✗** | $ 40 MB/file | ✓ | ✓ |
| Keeper | ✓ | ✓ | ✓ | ? | ✓ | ✓ unlimited | ✓ | ✓ |
| RoboForm | ✓ | ✓ | ✓ | ✓ | ✓ | ? | ✓ | ✓ |
| Google PM | ✓ | partial | via Chrome | ✗ | **✗** | ✗ | ✗ | ✗ |
| Apple Passwords | ✓ | **✗** | **✗** | ✗ | ✓ | ✗ | ✗ | groups |

Item-type coverage is a solved problem in the paid tier of every commercial product; nobody
wins here. The interesting rows are the built-ins — **neither Google nor Apple gives you a
full vault**, and Apple Passwords has no secure notes and no card storage at all.

LocalKeep's one genuinely unusual capability in this section is **in-app encrypted video
playback** — a seekable blob format decoded a 64 KiB frame at a time, so peak memory is two
frames and no plaintext copy of the file ever lands on disk. Every other product with
attachments hands large media to the OS or caps it (NordPass at 40 MB/file).

> **Resolved 2026-09-03**, and the table above reflects the shipped code. Cards and Identity
> documents were Pro-only; a free vault now creates one of each (`ProTypeQuota.FREE_LIMIT`), and
> the free attachment allowance went from five to seven (`AttachmentQuota.FREE_LIMIT`). TOTP is
> ungated, as are logins, notes, coordinates, seed phrases, folders and tags. Every cap limits
> **creation only** — a vault already above one keeps every existing record readable and editable,
> which is what makes losing Pro non-destructive.
>
> Every number above is pinned to those two constants by `FreeTierDocsMatchCodeTest`, in both the
> price table and the storage grid, so this cannot drift from the code again without failing the
> build. It said "unresolved, not a typo" until 2026-09-07, four days after the code stopped
> agreeing with it — and the grid two sections up still said five for the same four days, because
> the guard test pinned only the price table. Both are pinned now.

---

## Daily use

| | Autofill | Passkeys | Generator | Biometric | Search quality |
|---|---|---|---|---|---|
| **LocalKeep** | ✓ domain-bound | ✓ provider (Android 14+) | ✓ + passphrases | ✓ | diacritic-insensitive, incl. folders |
| Bitwarden | ✓ | ✓ | ✓ | ✓ | ✓ |
| 1Password | ✓ | ✓ (Android 14+) | ✓ | ✓ (Android 12+) | ✓ |
| Proton Pass | ⚠ reported unreliable | ✓ | ✓ | ✓ | ✓ |
| KeePassDX | ✓ + Magikeyboard | ✓ | ✓ | ✓ | ✓ |
| Enpass | ✓ | ✓ | ✓ | ✓ | ✓ |
| Dashlane | ⚠ reported unreliable | ✓ | ✓ | ? | ✓ |
| NordPass | ✓ | ✓ | ✓ | ✓ | ✓ |
| Keeper | ✓ | ✓ (Android 14+) | ✓ | ✓ | ✓ |
| RoboForm | ✓ | ✓ | ✓ | ✓ | ✓ |
| Google PM | ✓ best-in-class | ✓ | ✓ | ✓ | ✓ |
| Apple Passwords | ✓ (Apple only) | ✓ | ✓ | ✓ | ✓ |

Autofill is table stakes and everyone has it, but **two well-funded products have publicly
documented autofill reliability complaints on Android** (Proton Pass, Dashlane) — which is
worth knowing, because autofill quality is the single thing a user notices daily and the one
place where "the vendor is bigger" does not predict "the app works better".

LocalKeep's domain-binding detail matters more than it sounds: credentials bind to the web
domain rather than the browser's package, so a password saved in Chrome fills in Firefox.

---

## Security features

| | Breach check | Breach check **offline** | Weak/reused audit | Duress vault | `FLAG_SECURE` | Re-auth to reveal | Clipboard auto-clear | Password history |
|---|---|---|---|---|---|---|---|---|
| **LocalKeep** | ✓ free | **✓ bundled corpus** | ✓ zxcvbn, local | **$** | **✓ every window** | **✓ reveal *and* copy** | ✓ | ✓ last 5, reveal gated, not copyable |
| Bitwarden | ✓ free (HIBP) | ✗ | $ local | ✗ | ✓ | partial | ✓ | ✓ |
| 1Password | ✓ (HIBP) | ✗ | ✓ | ✗ | ✓ | partial | ✓ | ✓ |
| Proton Pass | $ dark web | ✗ | ✓ local | ✗ | ? | ? | ✓ | ✓ |
| KeePassDX | ✗ | — | ✗ | ? | ? | ? | ✓ | ✓ (KDBX) |
| Enpass | $ (HIBP) | ✗ | ✓ | ✗ | ? | ? | ✓ | ✓ |
| Dashlane | ✓ dark web | ✗ | ✓ | ? | ? | ? | ✓ | ✓ |
| NordPass | $ | ✗ | ✓ | ? | ? | ? | ✓ | ✓ |
| Keeper | $ BreachWatch | ✗ | ✓ | ? | ? | ? | ✓ | ✓ |
| RoboForm | ✓ (HIBP, 5 emails) | ✗ | ✓ | ? | ✓ toggle | ? | ✓ | ✓ |
| Google PM | ✓ (k-anonymity) | ✗ | ✓ | ✗ | ✓ | ✓ | n/a | ✓ |
| Apple Passwords | ✓ (k-anonymity) | ✗ | ✓ | ✗ | ✓ | ✓ | n/a | ✓ |

**This is the table where LocalKeep is actually differentiated**, on three rows:

1. **Offline breach checking.** Everyone else asks a service. Even the privacy-careful
   implementations (HIBP k-anonymity, Google's multi-party computation) still send a hash
   prefix over the network and still tell a third party that you are checking *something*.
   LocalKeep ships a 13.8 MB Golomb-Rice filter derived from SecLists inside the APK and asks
   nobody. Both builds have it and it is not a paid feature — where Proton Pass, NordPass,
   Enpass and Keeper all put breach monitoring behind the paywall.

2. **Duress vault.** No other product in this comparison documents one; the table marks the
   ones nobody could confirm either way as `?`. Proton Pass has it as an open community feature
   request. Both LocalKeep unlock slots carry identical KDF parameters deliberately, so the vault
   file does not reveal that a second slot exists, and the decoy is seeded with plausible content
   because an empty vault advertises a withheld real one. That holds until the decoy itself has
   been written to under coercion: someone comparing several generations of the file can then tell
   it was used (see [`docs/THREAT-MODEL.md`](docs/THREAT-MODEL.md)). It is Pro, so Play build only.

3. **Re-auth gating copy, not just reveal.** Most products gate revealing a secret. LocalKeep
   gates *copying* it too, for TOTP seeds, card CVV/PIN and GPS coordinates — because each of
   those is enough on its own to clone a credential or reveal a place, and copy-without-reveal is
   otherwise a trivial bypass of the reveal gate. Password-history entries go further: revealing one needs
   re-auth, and they cannot be copied at all.

---

## Import and lock-in

| | Guided import sources | Export your data | Free-tier import cap |
|---|---|---|---|
| **LocalKeep** | **11, each with app-specific instructions** — Google/Chrome, Apple Passwords, Bitwarden, 1Password `.1pux`, KeePass `.kdbx`, Aegis, Keeper, Enpass, Proton Pass, Dashlane, Other/CSV | ✓ encrypted vault | **none — imports are never capped** |
| Bitwarden | LastPass, 1Password, KeePass, Kaspersky, NordPass | ✓ | n/a |
| 1Password | CSV, KeePass, Bitwarden, LastPass, Dashlane, RoboForm, browsers | ✓ `.1pux` | n/a |
| Proton Pass | 16 sources | ✓ | n/a |
| KeePassDX | KDBX/KDB, CSV | ✓ | n/a |
| Enpass | KeePass, LastPass, CSV — **desktop app required** | ✓ | 25-item vault cap applies |
| Dashlane | browsers, CXP | ✓ | n/a |
| NordPass | browsers, CSV | **desktop only** | n/a |
| Google PM | CSV (3,000/file) | ✓ CSV | n/a |
| Apple Passwords | CSV | ✓ CSV (partial) | n/a |

Two things worth naming. LocalKeep's **merge-rather-than-replace** import keeps an existing
item's attachments, tags and folder on collision and lets the *newer password* win — most
competitors document import but not their collision rule. And the **imports-are-never-capped**
policy is the opposite of Enpass, where the 25-item free cap applies to a vault you brought
with you; LocalKeep's free limits restrict creation only.

Against that: **NordPass cannot export from mobile at all**, which is the lock-in behaviour
worth watching for in this category.

---

## Distribution, audits and reach

| | Play | F-Droid | Direct APK | Platforms | Languages | External audit |
|---|---|---|---|---|---|---|
| **LocalKeep** | ✓ (`play`) | **✗ ruled out** | ✓ (`foss`) | **Android only** | **5** | **✗ none** |
| Bitwarden | ✓ | ✓ own repo | ✓ | all | 50+ | ✓ Cure53, annual |
| 1Password | ✓ | ✗ | ✗ | all | 11 | ✓ + ISO 27001, SOC 2 |
| Proton Pass | ✓ | ✓ | ✓ | all | **78** | ✓ Cure53 2023, Recurity 2026 |
| KeePassDX | ✓ | ✓ | ✓ | Android | community | ✗ |
| Enpass | ✓ | ✗ | ? | all | 35+ | ✓ Cure53, VerSprite |
| Dashlane | ✓ | ✗ | ✗ | all | 10 | ? |
| NordPass | ✓ | ✗ | ✗ | all | 6+ | ✓ Cure53 2024, ISO 27001 |
| Keeper | ✓ | ✗ | ✗ | all | 12–21 | ? |
| RoboForm | ✓ | ✗ | ✗ | all | 30 | ✓ Secfault 2025 |
| Google PM | built-in | ✗ | ✗ | Android/Chrome | ? | ✗ |
| Apple Passwords | ✗ | ✗ | ✗ | **Apple only** | 37 | ✗ |

**This is the table where LocalKeep loses, and it loses badly.** One platform against
everyone else's five. Five languages against Proton Pass's 78 and Bitwarden's 50. And no
external security audit, in a category where five competitors have published one.

Two of those are deliberate and stated: no sync means no desktop client is even coherent, and
the audit gap is recorded in the project's own release-readiness score rather than rounded
away. The language count is simply small. F-Droid was ruled out on 2026-07-29 — the free
build self-distributes instead.

Worth noting for context: **an audit is not a clean bill of health**. Bitwarden's 2024 Cure53
mobile audit found 8 issues, 4 resolved after the assessment. The value is the process, not a
verdict — and LocalKeep's substitute (guardrail Gradle tasks, Jazzer fuzzing with committed
crashers, measured coverage floors of 84.4% and 88.4%, 2,241 unit tests, and structured QA
reviews with adversarial verification) is a real process that runs on every
commit. It is not equivalent to an audit and `FEATURES.md` does not claim it is.

---

## Where LocalKeep wins

1. **No network is a property, not a setting.** The `foss` build declares no `INTERNET`
   permission and two Gradle tasks fail the build if that ever changes. Every cloud
   competitor asks you to trust a policy; this is enforced by the merged manifest.
2. **Breach checking with no service to ask.** Unique in this comparison, free in both
   builds, where four competitors paywall the network version of the same feature.
3. **Duress vault (Pro).** No other product here documents one. Setting one up is a paid feature, and
   `foss` cannot buy Pro — so this is a Play-build advantage, not a free-tier one.
4. **One-time €9.99.** Against a market that raised prices twice and killed a free tier in
   the first quarter of 2026.
5. **Argon2id by default plus key commitment.** The strongest default KDF posture in the
   table, and the only documented defence against the invisible-salamander attack class.
6. **Re-auth on copy, not only on reveal** — closes a bypass most competitors leave open.
7. **Imports are never capped**, unlike Enpass's free tier.
8. **Encrypted media plays in-app** with two-frame peak memory and no plaintext on disk.

## Where LocalKeep loses

1. **No sync, no desktop, no browser extension, no web vault.** For most people this is
   disqualifying, and it is the deliberate cost of the architecture.
2. **Android only.** Every commercial competitor is cross-platform.
3. **Five languages.** Proton Pass has 78, Bitwarden 50+, RoboForm 30.
4. **No external security audit** — five competitors have one.
5. **No emergency access / dead-man's switch.** 1Password, Keeper and RoboForm all ship it.
6. **Dark only.** No competitor forces this.
7. **No vault or item sharing.** Standard everywhere else, including free tiers.
8. **The security scan takes ~20 s on a 10,000-item vault**, and completion is not announced
   to screen readers. Open and known.
9. **Google Auto Backup takes `vault.enc` by default.** The opt-out works and was verified on
   hardware, but the default is on and the OS performs the upload — no `INTERNET` permission
   does not prevent it. Cloud competitors have no equivalent surprise because uploading is
   the stated design.

---

## Adjacent apps that are not really competitors

Included because they come up in the same searches, excluded from the table because comparing
them feature-by-feature against a vault is misleading.

| | Aegis | Ente Auth | Android Password Store |
|---|---|---|---|
| What it is | TOTP authenticator | TOTP authenticator | GPG-file password manager |
| Stores passwords | ✗ | ✗ | ✓ (text files) |
| Offline | ✓ | ✓ | ✓ |
| Open source | ✓ GPL-3.0 | ✓ | ✓ |
| Encryption | AES-256-GCM, scrypt | AES-256 | OpenPGP per file |
| Audit | ✗ | ✓ Cure53, Symbolic Software, Fallible | ✗ |
| `FLAG_SECURE` | ✓ | ? | ? |
| Distribution | Play + F-Droid | Play + F-Droid | F-Droid only |

**Aegis and Ente Auth are companions to a password manager, not replacements** — they hold
2FA codes and nothing else. LocalKeep imports from Aegis directly, which is the actual
relationship. **Password Store** is a real offline password manager but requires OpenKeyChain
and GPG key management, which puts it out of reach of anyone who is not already comfortable
with `gpg`.

---

## Sources

Research gathered 2026-08-01. Primary vendor documentation preferred; review sites used only
where vendors publish nothing.

**Bitwarden** — [open source](https://bitwarden.com/open-source/) ·
[encryption](https://bitwarden.com/help/what-encryption-is-used/) ·
[KDF](https://bitwarden.com/help/kdf-algorithms/) ·
[offline](https://bitwarden.com/help/using-bitwarden-offline/) ·
[reports](https://bitwarden.com/help/reports/) ·
[audits](https://bitwarden.com/help/is-bitwarden-audited/) ·
[F-Droid](https://contributing.bitwarden.com/getting-started/mobile/android/f-droid/)

**1Password** — [PBKDF2](https://support.1password.com/pbkdf2/) ·
[white paper](https://1passwordstatic.com/files/security/1password-white-paper.pdf) ·
[item categories](https://support.1password.com/item-categories/) ·
[Watchtower privacy](https://support.1password.com/watchtower-privacy/) ·
[import](https://support.1password.com/import/) ·
[assessments](https://support.1password.com/security-assessments/)

**Proton Pass** — [pricing](https://proton.me/pass/pricing) ·
[security model](https://proton.me/blog/proton-pass-security-model) ·
[attachments](https://proton.me/pass/file-attachment) ·
[Pass Monitor](https://proton.me/blog/proton-pass-monitor) ·
[import](https://proton.me/support/pass-import) ·
[2023 audit](https://proton.me/blog/pass-open-source-security-audit) ·
[2026 audit](https://proton.me/business/blog/proton-pass-audit-2026) ·
[F-Droid](https://f-droid.org/en/packages/proton.android.pass.fdroid/)

**KeePassDX** — [GitHub](https://github.com/Kunzisoft/KeePassDX) ·
[F-Droid](https://f-droid.org/packages/com.kunzisoft.keepass.libre/)

**Enpass** — [security](https://www.enpass.io/security/) ·
[offline](https://www.enpass.io/offline/) · [sync](https://www.enpass.io/sync/) ·
[password audit](https://www.enpass.io/password-audit/) ·
[audit report](https://dl.enpass.io/docs/EnpassSecurityAssessmentReport.pdf)

**Dashlane** — [security](https://www.dashlane.com/security) ·
[white paper](https://www.dashlane.com/download/whitepaper-en.pdf) ·
[languages](https://support.dashlane.com/hc/en-us/articles/4424359405586-Supported-languages)

**NordPass** — [features](https://nordpass.com/features/) ·
[attachments](https://nordpass.com/blog/file-attachments/) ·
[breach scanner](https://support.nordpass.com/hc/en-us/articles/360012509998-How-to-use-the-Data-Breach-Scanner-in-NordPass) ·
[audits](https://nordsecurity.com/press-area/nord-security-business-suite-products-have-passed-multiple-security-audits)

**Keeper** — [security](https://www.keepersecurity.com/security.html) ·
[encryption model](https://docs.keeper.io/enterprise-guide/keeper-encryption-model) ·
[Android guide](https://docs.keeper.io/user-guides/android)

**RoboForm** — [Android manual](https://www.roboform.com/manual-android) ·
[Secfault audit](https://www.roboform.com/pdf/SecfaultSecurity_Summary_Pentesting_2023_v1_0.pdf) ·
[audit FAQ](https://help.roboform.com/hc/en-us/articles/15888502834061-Has-RoboForm-undergone-a-third-party-security-audit)

**SafeInCloud** — [official](https://www.safe-in-cloud.com/en/) ·
[encryption](https://www.safe-in-cloud.com/en/features/encryption/) ·
**mSecure** — [security model](https://support.msecure.com/en/support/solutions/articles/36000064305-msecure-s-security-model-secure-by-design/)

**Google Password Manager** — [import/export](https://support.google.com/chrome/answer/13068232) ·
[breach check protocol](https://docs.cloud.google.com/recaptcha/docs/check-passwords) ·
[Password Checkup design](https://elie.net/blog/security/password-checkup-from-0-to-650-000-users-in-20-days)

**Apple Passwords** — [app guide](https://support.apple.com/en-us/120758) ·
[iCloud Keychain security](https://support.apple.com/guide/security/icloud-keychain-security-overview-sec1c89c6f3b/web) ·
[Advanced Data Protection](https://support.apple.com/en-us/108756)

**Aegis** — [GitHub](https://github.com/beemdevelopment/Aegis) ·
[vault format](https://github.com/beemdevelopment/Aegis/blob/master/docs/vault.md) ·
**Ente Auth** — [FAQ](https://help.ente.io/auth/faq/) ·
**Password Store** — [GitHub](https://github.com/android-password-store/Android-Password-Store)

**Strongbox** (excluded, iOS/macOS only) — [GitHub](https://github.com/strongbox-password-safe/Strongbox)
