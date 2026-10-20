# KeePass KDBX — provenance

Three sub-corpora with three different jobs.

## `pykeepass/` — the primary, fully documented set

Every password, KDF, cipher and key file below is copied from `pykeepass`'s own passing test suite
(`tests/tests.py`), so they are confirmed working rather than guessed.
Repo `github.com/libkeepass/pykeepass`, path `tests/`. **Licence: MIT.**

| File | KDBX | Cipher | KDF | Password | Key file |
|---|---|---|---|---|---|
| `test3.kdbx` | 3.1 | AES256 | AES-KDF | `password` | `test3.key` |
| `test3_transformed.kdbx` | 3.1 | AES256 | AES-KDF | raw transformed key | — |
| `test4.kdbx` | 4.0 | AES256 | Argon2d | `password` | `test4.key` |
| `test4_aes.kdbx` | 4.0 | AES256 | Argon2d | `password` | `test4.key` |
| `test4_aeskdf.kdbx` | 4.0 | AES256 | AES-KDF | `password` | `test4.key` |
| `test4_chacha20.kdbx` | 4.0 | ChaCha20 | Argon2d | `password` | `test4.key` |
| `test4_twofish.kdbx` | 4.0 | Twofish | Argon2d | `password` | `test4.key` |
| `test4_blankpass.kdbx` | 4.0 | AES256 | Argon2d | `""` | `test4.key` |
| `test4_hex.kdbx` | 4.0 | ChaCha20 | Argon2d | `password` | `test4_hex.key` (legacy 64-hex) |
| `test4_keyx.kdbx` | 4.0 | — | — | `password` | `test4_keyx.keyx` (XML keyfile v2) |
| `test4_transformed.kdbx` | 4.0 | ChaCha20 | Argon2d | raw transformed key | — |
| `test4_argon2id.kdbx` | 4.0 | AES256 | **Argon2id** | `password` | none |
| `test4_aes_uncompressed.kdbx` | 4.0 | AES256 | Argon2d | `password` | none |
| `test4_chacha20_uncompressed.kdbx` | 4.0 | ChaCha20 | Argon2d | `password` | none |
| `test4_twofish_uncompressed.kdbx` | 4.0 | Twofish | Argon2d | `password` | none |
| `extra_content.kdbx` | 4.0 | — | — | `password` | none |
| `change.key` | — | — | — | — | key file for in-place key-change tests |

All twelve with a password and/or a key file now open: key files were built on 2026-09-16 and
`RealWorldKeePassImportTest.a database protected by a password and a key file opens` runs eight of
them. Two rows were **measured** that day rather than read off pykeepass's suite, and the table
above is corrected accordingly: `test4_keyx.kdbx` needs the password `password` *as well as* its
`.keyx` (this table said it had none), and `test4_blankpass.kdbx` opens on an **empty** password
plus `test4.key` — SHA-256(SHA-256("") ‖ keyFileKey), i.e. with the empty password as a real
component, and on no other composition tried (key-file-only, `password`, or any of the other four
key files).

## `keepassxc/` — richer databases, most without a confirmed password

Repo `github.com/keepassxreboot/keepassxc`, path `tests/data/`. **Licence: GPL-2.0-or-later.**

`NewDatabase.kdbx`, `NewDatabase2.kdbx`, `MergeDatabase.kdbx` and `SyncDatabase.kdbx` open with
password **`a`** (confirmed from `tests/TestDatabase.cpp`); `Compressed.kdbx` opens with a **blank**
password (measured here). The rest — `RecycleBinWithData`, `RecycleBinEmpty`, `KeyFileProtected`,
`FileKeyXmlV2`, `FileKeyHex`, `NonAscii`, `ProtectedStrings`, `Format200/300/400`, `CompositeKey.kdb`
— have no password confirmable from publicly fetchable source, and are used only for
container-level checks: is it recognised as a KDBX, and does it refuse with a *named* reason.

`NewDatabase.xml` is a plaintext KeePass2 XML export from the same repo. It is **not** the same
revision as `NewDatabase.kdbx` (4 entries and a populated Recycle Bin, against the container's 2
entries), so it is not used as a count oracle — `RealWorldKdbxOracle` reads the decrypted container
instead.

## `keepass2xml/` — KeePass2 "Generic XML" exports

Repo `github.com/bitwarden/clients`, `libs/importer/spec-data/keepass2-xml/`. **Licence: GPL-3.0.**
`TestData1.xml` has no `<Root>` and `TestData2.xml` no `<KeePassFile>` — upstream's deliberately
truncated variants. Kept to prove LocalKeep does not mistake a plaintext XML export for a database.
