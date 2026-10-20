# Proton Pass JSON — provenance

| File | Holds |
|---|---|
| `export.json` | `v1.21.2`, two vaults — "Personal" (5 items: logins, a note, a credit card) and "Test" (1 login) |
| `export-bitwarden-fixture.json` | Same top-level shape with synthetic ids |
| `export-dedicated-types.json` | Proton Pass's "dedicated" item-type field shapes |

**Sources** — `export.json` from `github.com/keepassxreboot/keepassxc`, `tests/data/`
(**GPL-2.0-or-later**); the other two from `github.com/bitwarden/clients`,
`libs/importer/spec-data/protonpass-json/` (**GPL-3.0**).

Proton Pass's **CSV** export is supported (`ProtonPassCsvUsernameTest`); this JSON shape is not.
All three files carry a top-level `"encrypted": false`, which is also a `BitwardenExport` key — the
test here pins that recognition does not rest on it.
