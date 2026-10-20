# LastPass CSV — provenance

| File | Holds |
|---|---|
| `login-with-totp.csv` | 8-column header `url,username,password,totp,extra,name,grouping,fav`; one login with a real Base32 TOTP secret, `fav=0`, empty `grouping` |
| `secure-note-bank-account.csv` | Secure note — `NoteType:Bank Account` blob in `extra`, `grouping=Tools Test Items` |
| `secure-note-passport.csv` | `NoteType:Passport` blob, same grouping |
| `secure-note-drivers-license.csv` | `NoteType:Driver's License` blob, including the literal `4'0""` RFC 4180 escaped-quote edge case |
| `secure-note-credit-card.csv` | `NoteType:Credit Card` blob on the older 7-column header (no `totp`) |

**Source** — real LastPass-shaped rows embedded in `github.com/bitwarden/clients`'s
`lastpass-csv-importer.spec.ts`. **Licence: GPL-3.0.**

Every secure-note row writes `http://sn` in the `url` column — LastPass's sentinel for "this is a
note". LocalKeep did not know that sentinel when this fixture was added; `LastPassSecureNote`
now handles it.
