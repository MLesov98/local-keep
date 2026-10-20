# Changelog

Notable changes to LocalKeep. Format loosely follows [Keep a Changelog](https://keepachangelog.com);
versions follow [Semantic Versioning](https://semver.org).

## [Unreleased]

### Organising the vault by hand

Folders nest, and you can now move things between them by holding and dragging: an item or a whole
folder, onto a folder row, onto a breadcrumb, up a level, out to the top level, or into the bin.
Resting on a folder opens it with what you are carrying still in hand, so you can walk down into a
nested destination in one gesture. Every move that writes anything is one tap to undo.

None of it is gesture-only. Each row carries the same actions for a screen reader, and a "Move to
folder…" picker does the same work without a drag. Folder rows and the folder chips also answer a
long press with Rename, Move and Move to trash.

Inside a folder, the list now shows that folder's own items first and puts everything living further
down under an **In subfolders** heading, with each of those rows naming the path to it. A folder list
has always shown its whole subtree — a parent that read "nothing here" while holding forty items one
level down sends people looking for data they still have — but it never said which rows were which.

**Deleting a folder is now recoverable.** It goes to the Trash carrying its contents instead of
scattering them, and restoring it puts the subtree back where it was. This is a vault-format change
(schema 4); older vaults open unchanged, and a vault written by this version cannot be opened by an
older build.

The drag stays inside the app's own window. Android's system drag-and-drop draws what you are
carrying in a separate system window, outside the `FLAG_SECURE` surface that keeps this app out of
screenshots and the recents list — so it is not used here, and a build check fails if it ever is.

### Backup — read this before you rely on it

LocalKeep can hand an **encrypted** copy of your vault to Android's Auto Backup, which stores it in
your Google account. Setup asks you to choose, with two buttons and no pre-selected answer: losing
the phone is by a wide margin the commonest way people lose a vault, and keeping nothing off the
device is the stronger privacy position. Neither is dressed up as the safe one. You can change your
mind any time in **Settings → Backup**.

What Google receives is the same `vault.enc` that sits on your device: ciphertext, plus the
key-derivation parameters needed to open it. Your master password never leaves the phone, is not
derivable from anything in the backup, and cannot be recovered by us or by Google. That is the
protection and it is also the catch — **a backup you cannot decrypt is not a backup.** If you lose
your master password, the Recovery Kit issued during setup is the only way back in. Google's copy
will not help you.

If you would rather nothing at all leaves the device, turn backup off. Everything else about the app
works identically; the app requests no network permission in the `foss` build and never sends vault
data anywhere itself.

Under the hood this release also **fixes Auto Backup, which had never actually run.** The app
declared a backup agent but was classified for key/value backup, so Android never invoked it and no
vault ever reached a backup set — including in local testing, where the operation reported success.
Now verified end to end: agent invoked, encrypted vault written, restored byte-for-byte identical.
An instrumented test asserts the classification so it cannot silently regress.

### Minimum-version fix

Creating a vault or exporting a backup could crash on devices with a small heap (Android 9 and
some low-memory devices on later versions). Key derivation sizes itself to the phone's memory
budget and now degrades when memory runs out rather than failing. Where memory is genuinely
insufficient to open an existing vault, the app says so plainly instead of reporting a wrong
password.
