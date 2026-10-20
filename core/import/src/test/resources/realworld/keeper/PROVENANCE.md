# Keeper — provenance

| File | Holds |
|---|---|
| `export.csv` | **Header-less positional** CSV: `folder,title,username,password,url,notes` then repeating custom-field name/value pairs; includes the nested folder `Foo\Baz` and a `TFC:Keeper` otpauth custom field |
| `export-multi-collection.csv` | Multi-folder / shared-folder variant |
| `export-dedicated-item-types.csv` | Bank account, passport and driver's-licence rows with pipe-delimited multi-value cells |
| `export-cli.json` · `export-web.json` · `export-legacy.json` · `export-enterprise.json` | Keeper's native JSON export — `{shared_folders, records}` — in its four vault variants |

**Source** — `github.com/bitwarden/clients`, `libs/importer/spec-data/keeper-csv/` and `keeper-json/`.
**Licence: GPL-3.0.**

Neither shape has an importer. The CSV files are the corpus's test that a header-less file is
*refused* rather than mapped by column position into confident nonsense.
