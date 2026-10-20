# 1Password — provenance

| File | Holds | Password |
|---|---|---|
| `1PasswordExport.1pux` | A real unencrypted `.1pux` ZIP: `export.data`, `export.attributes`, two image attachments. Personal vault (login with TOTP + extra URL + tags, credit card, identity, document with a `keepassxc.png` attachment, an archived login, a standalone password) and a Shared vault with a Bank Account — 9 items | none (1PUX is an "Unencrypted Export") |
| `export.data-sanitized.json` | Large sanitized `export.data` fragment, many categories | — |
| `export.data-example.json` | One account/vault ("Personal"), one item | — |
| `export.data-login.json` | Login-category `export.data` fragment | — |
| `export.data-identity.json` | Identity-category fragment | — |
| `legacy-login.1pif` | Legacy 1PIF text: `***uuid***\n{json}\n` login entry | — |
| `legacy-windows-opvault.1pif` | 1PIF Windows OPVault variant | — |
| `legacy-identity.1pif` | 1PIF identity entry | — |

**Sources** — the archive from `github.com/keepassxreboot/keepassxc`, `tests/data/`
(**GPL-2.0-or-later**); the loose fragments and the `.1pif` files from `github.com/bitwarden/clients`,
`libs/importer/spec-data/onepassword-1pux/` and `-1pif/` (**GPL-3.0**).

The `export.data-*.json` files are the JSON *inside* a `.1pux`, not archives, and the `.1pif` files
are the pre-1PUX format — both are kept so the corpus records what LocalKeep answers when the user
picks the wrong file, which is `OnePuxMissingExportData`.
