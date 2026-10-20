# LocalKeep threat model

This document states what LocalKeep defends against and, just as important, what it does not. Any
security claim the app, its store listing or its website makes has to trace back to a tier here.
If you find a claim that does not, or a behaviour that contradicts this document, please report it
(see [`SECURITY.md`](../SECURITY.md)).

It describes the Android app. An iOS port is in progress and is not covered until it is released.

## What is being protected

| Asset | Where it lives |
|---|---|
| Vault contents: passwords, 2FA seeds, cards, identities, notes, passkeys, locations, attachments | `vault.enc`, plus `vault.enc.bak` and up to five rolling snapshots under `backups/`; file and media attachments as separately encrypted blobs |
| The master password | Never stored. It derives the key-encryption key via Argon2id and is wiped after use |
| The data encryption key (DEK) | Random 256-bit, wrapped once per unlock slot; held in memory only while the vault is unlocked |
| The Recovery Kit mnemonic | Shown once at creation, never persisted |
| Autofill trust decisions | The app-to-domain pairs the user has approved |
| The *existence* of a duress vault | Itself a secret (see T4) |

## What defends them

- **Argon2id**, RFC 9106, version `0x13`, default 32 MiB / 3 iterations / parallelism 4, with a hard
  floor enforced in code (`KdfParams.kt`) and an on-device calibration ladder that steps down
  rather than crashing on a low-memory phone. Cost parameters are stored per slot with an
  independent bounds check, so a tampered header cannot force a cheap derivation.
- **AES-256-GCM-SIV** (nonce-misuse-resistant) over the vault payload; **AES-256-GCM** for the
  per-slot DEK wrap.
- **HKDF-SHA256** with fixed purpose labels, so the DEK is never used directly as an encryption or
  MAC key.
- **Key commitment**: an HMAC-SHA256 over a fixed message, verified in constant time. This defeats
  the partitioning ("invisible salamander") class of attack against GCM-SIV.
- **Published reference vectors** pin every primitive (RFC 9106, RFC 8452, NIST CAVP, RFC 5869), so
  an accidental algorithm or parameter substitution fails the build rather than round-tripping
  quietly.
- **Both unlock slots carry identical KDF parameters**, so the header does not reveal which slot is
  real or whether a duress slot is in use.
- **No network in the `foss` build.** Its merged manifest has no `INTERNET` or
  `ACCESS_NETWORK_STATE` permission; `checkFlavorPermissions` fails the build if any permission
  outside its allowlist appears, and `checkNoNetworkingDeps` blocks networking libraries from the
  modules that handle vault data.
- **`FLAG_SECURE`** on every window in release builds, clipboard auto-clear, and re-authentication
  before revealing *or* copying high-value fields.

## Attacker tiers

### T0: remote or network attacker

**In scope. Defended by construction.**

There is no server, no account and no sync. The `foss` build cannot open a network connection, so
vault data cannot leave the device over the network even if code tried.

**Residual.** The `play` build holds `INTERNET` and `ACCESS_NETWORK_STATE`, for Google Play Billing
only. `checkNoNetworkingDeps` proves that no networking *library* reaches the scanned modules; it
does not inspect first-party bytecode, and `:app`, `:core:billing`, `:feature:vaultlist`,
`:feature:itemedit` and `:feature:settings` are not in its scan list.

### T1: someone briefly holds the unlocked phone

*Shoulder-surfing, a grabbed handset, "can I borrow your phone".*

**In scope. This is the tier where the app's UX decisions are security decisions.**

Defended by: secrets masked by default; re-authentication before revealing *and* copying
high-value fields (2FA secrets, card CVV and PIN, saved locations, recovery phrases and their
passphrase, national ID, passport and licence numbers, and password history); a per-item reprompt
flag for everything else; clipboard auto-clear; auto-lock on idle and on leaving the app; a panic
lock; `FLAG_SECURE` blanking the app-switcher preview; and a duress vault whose existence the
error messages never disclose.

**Known gaps.**

- Custom fields are not in the always-re-authenticate set. On an item without the reprompt flag, a
  hidden custom field is masked but one tap away.
- Every surface that can display a protected field must consult the re-authentication gate.
  Twice, a new surface for an existing field (the item editor, and search indexing of hidden
  values, which allowed a value to be reconstructed one character at a time) released it without
  the gate. Both are fixed; treat the class as not exhausted.
- Copy protection on secret text fields depends on Compose internals. A Compose upgrade once
  silently bypassed it (the new text context menu and hardware Ctrl+C ignored the old guard); it is
  now enforced by a guard with a behavioural test. Re-test after every Compose upgrade.

### T2: a malicious app on the device, without root

**In scope.**

Defended by: no exported component surrenders vault data; autofill callers are identified by
signing certificate, with a narrow `<queries>` declaration instead of `QUERY_ALL_PACKAGES`, so an
unidentifiable caller fails closed; `FLAG_SECURE` blocks screen capture by other apps.

**Known gaps.**

- **The clipboard.** Any app can read it while a copy is live. LocalKeep clears it on a timer, with
  an alarm-based backstop for when Android freezes the app in the background (observed clearing at
  roughly 50 s rather than 30 s). If the process is killed rather than frozen, the clip is left for
  the next launch to clear, because clearing blind could erase something another app copied since.
- **Accessibility services** the user has granted can read screen content regardless of
  `FLAG_SECURE`. The app does not currently warn about this.
- **Autofill sessions on LocalKeep's own fields.** The app excludes its view tree from autofill, so
  an autofill service does not receive its fields or their contents. But Jetpack Compose still
  reports focus on a text field to the system autofill manager, so a bound autofill service learns
  that a fillable field in LocalKeep was focused, and its bounds. No value is disclosed. Compose
  offers no opt-out today; the only available workaround would strip screen-reader semantics from
  every secret field, which is a worse trade.

### T3: root, a hostile OS, or a compromised ROM

**Out of scope.**

An attacker who controls the operating system can read the screen as it is drawn, log the master
password as it is typed, and copy the DEK out of process memory while the vault is unlocked. Secret
wiping on the Android runtime is best-effort; copies made by the runtime cannot be guaranteed to be
erased. No userspace password manager defends this tier, and LocalKeep does not claim to. Root or
integrity detection is defeated by an attacker who already owns the kernel, so the app reports what
it can observe and does not present that as a defence.

### T4: physical possession of a locked device; forensic extraction

**In scope. This is where the cryptography does the work.**

An attacker imaging storage obtains `vault.enc`, `vault.enc.bak` and the snapshots. Recovering
anything requires brute-forcing the master password at each container's stored Argon2id cost.
After a master-password change, no container under the app's files directory still opens with the
old password; this is checked directly by trying the old key against everything left on disk.

**Residual: the master password.** The app enforces a minimum strength score, which raises the bar
but cannot make a guessable password safe.

**Residual: the duress vault across file generations.** An attacker holding several generations of
the vault file (the current file, its `.bak` and the snapshots) can compare them. Every save,
with or without a decoy configured, rewrites the same region shape, so comparison does not reveal
whether a decoy *exists*. It can reveal that a decoy was *opened and written to*: a save made inside
a decoy session touches a region that a vault with no decoy never touches. **A duress vault that is
configured and never used is indistinguishable from none; one that has been written to under
coercion is not.**

**Residual: the duress vault's missing settings.** Inside a decoy session some rows are hidden
(the duress and Recovery Kit entries, and biometric unlock), because each would have to modify
real-vault state to work there. Someone who knows the app well can infer a decoy from their
absence. The duress vault is designed against a coercer who does not know the app and against
one-off inspection, not against an informed observer, and no copy may claim otherwise.

### T5: the user's Google account is compromised

**In scope.**

Android Auto Backup is enabled, and **`vault.enc` is in the backup set by default.** The encrypted
vault therefore reaches the user's Google account unless the user turns cloud backup off, which
onboarding asks about and Settings can change at any time. The OS transport performs the upload, so
the `foss` build's lack of `INTERNET` does not prevent it. Device-bound key material
(`device_keys.bin`, used for biometric unlock) is excluded from every backup set. File and media
attachments are excluded too, so a restore from Google brings items back without their files.

Even then the attacker obtains ciphertext, at the same Argon2id cost as T4. Account compromise
alone does not yield passwords.

**Narrow window.** The vault file is created before onboarding reaches the backup question, so an
Auto Backup run in between could upload it before the user has chosen.

Manual export and device-to-device transfer can also move the encrypted vault off the device, at
the user's request.

## Summary

| Tier | In scope | Assessment |
|---|---|---|
| T0: remote / network | Yes | Defended by construction; no network in `foss` |
| T1: brief access to the unlocked phone | Yes | Strong; the most productive place to keep investing |
| T2: malicious app, no root | Yes | Good; clipboard and accessibility services are the soft edges |
| **T3: root / hostile OS** | **No** | **Out of scope, and stated as such** |
| T4: forensic extraction of a locked device | Yes | Strong; rests on Argon2id and the master password |
| T5: Google account compromised | Yes | Ciphertext only; the vault is in Auto Backup unless cloud backup is turned off |

## What LocalKeep does not defend against

1. A compromised operating system (T3): root, a hostile ROM, a kernel implant.
2. A weak master password. Argon2id raises the cost of each guess; it does not rescue a guessable
   password.
3. Coercion to unlock. The duress vault is a mitigation, not a defence, with the limits in T4.
4. Losing both the master password and the Recovery Kit. There is no reset and no backdoor.
5. A screen-reading accessibility service the user has granted.

Nothing in LocalKeep has had an external security audit yet.
