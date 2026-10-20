# Bitwarden JSON — provenance

| File | Holds | Password |
|---|---|---|
| `plain-export.json` | Full coverage: 2 folders, one item of each type (login/note/card/identity), a favourite, three custom-field types, one password-history entry, a TOTP URI | — |
| `nested-folders-export.json` | 9 logins, 6 folders whose names encode nesting with `/`, including a case-variant duplicate (`Test/Subfolder` vs `Test/SubFolder`) | — |
| `passkey-export.json` | One login with an attached WebAuthn/FIDO2 passkey (`login.fido2Credentials`) | — |
| `account-encrypted-pbkdf2.json` | Real account-encrypted export, PBKDF2 (`kdfType: 0`) | `a` |
| `account-encrypted-argon2id.json` | Same, Argon2id (`kdfType: 1`) | `a` |
| `empty-unencrypted.json` | Minimal valid shell — `encrypted:false` with empty `folders`/`items` | — |
| `unencrypted-with-cipher-key.json` | One login carrying a per-cipher `key` | — |
| `empty-account-encrypted.json` | Minimal `encrypted:true` shell | — |
| `cipher-with-collections.json` | Organisation export: one login plus a `collections` array, and `"passwordHistory": null` | — |
| `password-protected.json` | Real password-protected envelope (PBKDF2 100k, salt/data/MAC) | not recoverable from the source — structural sample only |

**Sources** — the first five and `cipher-with-collections.json` come from
`github.com/keepassxreboot/keepassxc`, `tests/data/` (the passwords are confirmed by that project's
own `TestImports::testBitwardenEncrypted`); the rest from `github.com/bitwarden/clients`,
`libs/importer/spec-data/bitwarden-json/`. **Licences: GPL-2.0-or-later (KeePassXC) and GPL-3.0
(Bitwarden)**, both compatible with LocalKeep's GPLv3.

**Why this set matters** — these are the files that showed Bitwarden writes JSON `null`, not `[]`,
for empty lists on an item. Four of them could not be parsed at all until WP11; keep any new fixture
with its nulls intact rather than tidying them into empty arrays.
