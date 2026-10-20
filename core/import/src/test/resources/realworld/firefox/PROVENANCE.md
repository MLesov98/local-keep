# Firefox `logins.csv` — provenance

| File | Holds |
|---|---|
| `simple-password.csv` | Header `url,username,password,httpRealm,formActionOrigin,guid,timeCreated,timeLastUsed,timePasswordChanged`; one ordinary row |
| `firefox-accounts.csv` | The same header, plus Firefox's own `chrome://FirefoxAccounts` row whose "password" is a quote-escaped JSON blob of Sync key material |

**Source** — `github.com/bitwarden/clients`, `libs/importer/spec-data/firefox-csv/`.
**Licence: GPL-3.0.**

The header is the reason these are here: it has no title column at all and two columns (`url`,
`formActionOrigin`) that both match `URL_HEADERS`.
