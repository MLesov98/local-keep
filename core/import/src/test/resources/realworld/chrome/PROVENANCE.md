# Chrome / Google Password Manager CSV — provenance

| File | Holds | Password / key file |
|---|---|---|
| `simple-password.csv` | Header `name,url,username,password`; one ordinary login row | — |
| `android-app-credential.csv` | Same header; one row whose `name` is empty and whose URL is an `android://<hash>@<package>/` app-linked credential | — |

**Source** — `github.com/bitwarden/clients`, `libs/importer/spec-data/chrome-csv/`, extracted from the
`.ts` fixtures that suite feeds its own Chrome importer. **Licence: GPL-3.0**, the same licence
LocalKeep ships under, so these are redistributable here as test data.

**Adding a file** — Chrome's export is `Google Passwords.csv` from
`chrome://password-manager/settings`. Keep the header verbatim; that is the part under test.
