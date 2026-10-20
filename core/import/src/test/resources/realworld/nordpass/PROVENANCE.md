# NordPass CSV — provenance

NordPass writes one unified 22-column schema for every record kind and distinguishes them with a
`type` cell.

| File | `type` | Holds |
|---|---|---|
| `login.csv` | `password` | Login with folder, note and a `custom_fields` JSON array (one text, one hidden) |
| `login-with-additinal-urls.csv` | `password` | Login whose `additional_urls` cell is a JSON array (filename misspelling is upstream's) |
| `card.csv` | `credit_card` | Cardholder, number, CVC, expiry, ZIP — no login columns filled |
| `identity.csv` | `identity` | Name, phone, email, postal address |
| `secure-note.csv` | `note` | A bare folder-declaration row followed by the note itself |

**Source** — `github.com/bitwarden/clients`, `libs/importer/spec-data/nordpass-csv/`.
**Licence: GPL-3.0.**
