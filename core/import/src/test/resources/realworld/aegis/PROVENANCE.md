# Aegis Authenticator JSON — provenance

| File | Holds | Password |
|---|---|---|
| `plain.json` | `db.version 1`, 7 entries: 3 TOTP (SHA1/6/30, SHA256/7/20, SHA512/8/50), 3 HOTP, 1 Steam | — |
| `plain-grouped-v2.json` | `db.version 2`, the same 7 entries with `group` populated (`group1`/`group2`/null) | — |
| `encrypted.json` | Real encrypted vault, one scrypt key slot, ciphertext `db` as a JSON string | `test` (confirmed from `DatabaseImporterTest.java`) |

**Source** — `github.com/beemdevelopment/Aegis`, the project's own test vectors. **Licence: GPL-3.0.**

Known upstream gap: no fixture has a populated `favorite` flag or a non-null icon, so
`AegisEntry.favorite` is exercised only by the hand-written tests beside this corpus.
