# Enpass — provenance

| File | Holds |
|---|---|
| `login.csv` | **Header-less, label/value interleaved**: `Booking,Username,,E-mail,…,*Password,…` — two rows |
| `login-quoted.csv` | Every cell individually quoted, four rows |
| `login-no-username.csv` · `login-one-time-code.csv` · `login-sensitive-custom-field.csv` · `login-starred-only.csv` · `login-trailing-note.csv` | Single-row variants of the same shape |
| `card.csv` | Card as label/value pairs |
| `secure-note.csv` | `<Title>,<note>` |
| `json-login.json` · `json-login-android-url.json` · `json-credit-card.json` · `json-note.json` · `json-folders.json` | Native Enpass JSON — `{folders, items}` with typed `fields` and a `parent_uuid` folder hierarchy |

**Source** — `github.com/bitwarden/clients`, `libs/importer/spec-data/enpass-csv/` and `enpass-json/`.
**Licence: GPL-3.0.**

`login-quoted.csv` is the important one: its first row carries the *labels* `Username` and `Website`
in positions a header parser would bind, so it is the file that proves `CsvImporter.detect`'s refusal
is what keeps labels from importing as data.
