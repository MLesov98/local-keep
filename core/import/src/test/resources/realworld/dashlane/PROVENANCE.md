# Dashlane CSV — provenance

Dashlane does not export one file; it exports one per record kind.

| File | Holds |
|---|---|
| `credentials.csv` | Logins, header ends `...,category,otpSecret` (a bare seed) |
| `credentials-otpurl.csv` | Same schema with `otpUrl` (a full `otpauth://` URI) instead |
| `id.csv` | ID / passport / licence / SSN / tax-number rows |
| `payments.csv` | Bank account and credit card rows in one file |
| `personal-info.csv` | Identity / personal-info, one row |
| `multiple-personal-info.csv` | Same schema, several rows |
| `securenotes.csv` | `title,note` |

**Source** — `github.com/bitwarden/clients`, `libs/importer/spec-data/dashlane-csv/`.
**Licence: GPL-3.0.**

Only the two credential files have a password column, so only those are recognised by
`CsvImporter`. The `otpUrl` spelling was unrecognised until WP11 and every seed in such a file was
silently dropped.
