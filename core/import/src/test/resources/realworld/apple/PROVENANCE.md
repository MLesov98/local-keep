# Apple Passwords / iCloud Keychain (and Safari) CSV — provenance

| File | Holds | Password / key file |
|---|---|---|
| `safari-modern-header.csv` | Header `Title,URL,Username,Password,Notes,OTPAuth`; one row with a quoted multi-line note and an `otpauth://totp/...` seed | — |
| `safari-old-header.csv` | Older four-column header `Title,Url,Username,Password` | — |

**Source** — `github.com/bitwarden/clients`, `libs/importer/spec-data/safari-csv/`. **Licence: GPL-3.0.**

Both header variants are kept on purpose: Apple changed the shape when Passwords became its own app,
and an installed base exports the old one. The seed in the modern file is the upstream suite's
placeholder `examplesecret`, which is not decodable Base32 — so the test asserts the *rescue* path,
not a `Totp`.
