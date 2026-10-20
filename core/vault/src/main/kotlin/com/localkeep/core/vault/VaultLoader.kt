package com.localkeep.core.vault

import android.content.Context
import com.localkeep.core.common.AppError
import com.localkeep.core.common.Outcome
import com.localkeep.core.crypto.KdfParams
import java.io.File
import java.io.IOException

/** Loads a structurally valid vault container, falling back to the prior committed file. */
object VaultLoader {
    /**
     * How many same-second quarantine attempts are given a distinct name before the copy is allowed
     * to fail. Restoring twice is ordinary; restoring 99 times inside one second is not, and the
     * caller's refusal is the right answer to it.
     */
    private const val MAX_QUARANTINE_COLLISIONS = 99


    /**
     * Every committed container file, by the names [AtomicVaultWriter] writes them under.
     *
     * Public because those constants are `internal` and the names had been written out by hand in
     * three other places — `app/.../LocalKeepBackupAgent.kt`, `feature/unlock/.../PreUnlockRestore.kt`
     * and, before that, this module's own tests. A rename here compiles fine and silently breaks a
     * quarantine whose entire job is "never destroy the bytes you are replacing", which is the shape
     * of inert guard this repo keeps getting burned by. One list, imported rather than retyped, so a
     * rename is a compile error or a red test everywhere at once.
     *
     * Order is the load order [loadCandidates] uses: primary, the fsynced-but-unrenamed save, then
     * the previous generation. Callers that copy all three should keep it — it is the order of
     * "most likely to be the good one".
     */
    val CONTAINER_FILE_NAMES: List<String> = listOf(
        AtomicVaultWriter.VAULT_FILE,
        AtomicVaultWriter.TEMP_FILE,
        AtomicVaultWriter.BACKUP_FILE,
    )

    /** The live container. Named separately because the cloud-backup exclusion omits only this one. */
    const val PRIMARY_CONTAINER_FILE: String = AtomicVaultWriter.VAULT_FILE

    /**
     * Copies every container that exists into [directory] under `"<name>-<suffix>"`, and returns
     * the copies.
     *
     * **Copies, and does not move**, which is the whole design and not a detail. Moving is tidier
     * and leaves no duplicate; it is also wrong, because between the move and the replacement write
     * the app has *no* container at all, and a failure in that window leaves [hasVaultFiles] false —
     * which routes the next launch to onboarding, where creating a new vault writes over the space
     * the damaged one occupied. A copy leaves the original for [AtomicVaultWriter] to rotate
     * normally, and the caller simply aborts if this throws.
     *
     * **All three, not just the primary.** Which one is the recoverable remnant is not knowable
     * here: the `.bak` is a whole previous generation and the `.tmp` is an fsynced save that was
     * never renamed, and either can be the better copy when the primary is the file that got
     * damaged. `AtomicVaultWriter.write` rotates onto `.bak` with `REPLACE_EXISTING`, so the write
     * that follows destroys the old `.bak` outright — this is the only moment it can be kept.
     *
     * [directory] is the caller's, deliberately. Where the copies land is an Auto Backup question,
     * not a storage one: Android matches `<exclude domain="file">` by canonical path with no prefix
     * matching, so quarantined siblings in `filesDir` could never be excluded and every restore
     * would push dead ciphertext into the user's cloud backup set. One directory is one exclude
     * line, and the module that owns those XML rules is the one that must name it.
     *
     * Nothing to copy is not a failure — a vault can be unreadable because a file is missing, which
     * is one of the states a restore path exists for. An existing copy is **never overwritten**: it
     * is the older damage, and the older damage is the one worth keeping.
     *
     * That is why [suffix] alone is not enough to name the destination. The caller's stamp is
     * second-resolution, so two restore attempts inside one second collided — and because an
     * existing copy is never overwritten, the collision surfaced as "the vault file couldn't be set
     * aside", an error blaming the disk for a clock. A numeric discriminator is appended when the
     * name is taken, the same way `RollingBackupManager.uniqueSnapshotFile` handles two snapshots
     * in one second.
     *
     * @throws IOException if the directory cannot be created, or any container that exists could
     *   not be copied. The caller must treat that as "do not proceed with the replacement".
     */
    fun quarantineContainers(context: Context, directory: String, suffix: String): List<File> =
        quarantineContainers(context.filesDir, directory, suffix)

    /** See the [Context] overload. Split out so it can be proven without Robolectric. */
    internal fun quarantineContainers(filesDir: File, directory: String, suffix: String): List<File> {
        val quarantine = File(filesDir, directory)
        if (!quarantine.isDirectory && !quarantine.mkdirs()) {
            // No path in the message: this can become an `AppError` cause, and a cause is as
            // renderable as a message.
            throw IOException("could not create the quarantine directory")
        }
        return CONTAINER_FILE_NAMES.mapNotNull { name ->
            val source = File(filesDir, name)
            if (!source.isFile) return@mapNotNull null
            try {
                source.copyTo(unusedName(quarantine, "$name-$suffix"), overwrite = false)
            } catch (e: Exception) {
                throw IOException("could not preserve a container before replacing it", e)
            }
        }
    }

    /**
     * [base], or `base-2`, `base-3`, … — the first name in [directory] that nothing holds.
     *
     * Bounded rather than unbounded: a directory that somehow holds every name in the range is a
     * condition the caller must hear about, and returning the taken `base` makes `copyTo` throw,
     * which is the refusal the caller already knows how to render. Silently overwriting would be
     * the one outcome this whole function exists to prevent.
     */
    private fun unusedName(directory: File, base: String): File {
        val first = File(directory, base)
        if (!first.exists()) return first
        for (n in 2..MAX_QUARANTINE_COLLISIONS) {
            val candidate = File(directory, "$base-$n")
            if (!candidate.exists()) return candidate
        }
        return first
    }
    /** True even when the remaining container is corrupt, preventing accidental overwrite. */
    fun hasVaultFiles(context: Context): Boolean =
        File(context.filesDir, AtomicVaultWriter.VAULT_FILE).exists() ||
            File(context.filesDir, AtomicVaultWriter.BACKUP_FILE).exists() ||
            readValid(File(context.filesDir, AtomicVaultWriter.TEMP_FILE)) != null

    fun loadFileBytes(context: Context): Outcome<ByteArray> = loadFileBytes(context.filesDir)

    /**
     * See the [Context] overload. Split out so it can be proven without Robolectric.
     *
     * Stops at the first container that parses, in [loadCandidates]' order. It used to *be*
     * `loadCandidates(...).firstOrNull()`, which reads all three files whole and keeps one — so
     * every save, snapshot and re-authentication also read the `.bak` in full, ~32 MiB at 10,000
     * items, ahead of the save's only `OutOfMemoryError` translation (2026-10-02). Unlock still
     * asks for the whole list; it needs the fallback.
     */
    internal fun loadFileBytes(filesDir: File): Outcome<ByteArray> =
        CONTAINER_FILE_NAMES.firstNotNullOfOrNull { readValid(File(filesDir, it)) }
            ?.let { Outcome.Success(it) }
            ?: Outcome.Failure(
                AppError.Storage("vault file missing or structurally corrupt; backup recovery failed"),
            )

    /**
     * Every structurally valid container on disk, primary first, then the `.bak`.
     *
     * [loadFileBytes] answers "give me *a* vault file" and stops at the first one that parses. That
     * is the wrong question during unlock, because "parses" here means the *header* parses and the
     * declared region offsets fit the file — nothing about it says the encrypted payload will still
     * authenticate. A file whose ciphertext was damaged in place keeps a perfectly valid header, so
     * it wins over a healthy `.bak` that would have opened, and the user is told their password is
     * wrong for a vault they can still recover.
     *
     * The unlock path therefore asks for the whole list and falls through to the backup when the
     * primary turns out to be authenticated-but-corrupt. Callers that merely need the current
     * container keep using [loadFileBytes].
     *
     * The `.tmp` sits between them, and it is there for one specific crash. [AtomicVaultWriter]
     * commits a save with two renames — `vault.enc` to `.bak`, then `.tmp` to `vault.enc` — and a
     * process death in the gap leaves no `vault.enc` at all: the only copy of the just-saved data
     * is the fsynced `.tmp`, and the only file this list used to see was the `.bak` holding the
     * state from *before* that save. The user reopened their vault to find the last edit gone, with
     * nothing reporting a failure, and the next save overwrote the `.tmp` that still had it.
     *
     * Ordering is the whole safety argument. `vault.enc` stays first, so a committed file always
     * wins and a `.tmp` left behind by a crash *during* the write can never shadow it — that one is
     * short, so [readValid]'s container check rejects it anyway, but preferring the committed file
     * means the question never has to be asked. The `.tmp` comes before the `.bak` because when
     * both are readable the `.tmp` is by construction the newer of the two.
     */
    /**
     * Whether `vault.enc` itself is a readable container right now.
     *
     * [loadCandidates] returns bytes without saying which file they came from, and callers cannot
     * infer it from position: with the primary missing, the `.tmp` is candidate 0 and looks exactly
     * like a healthy primary. That gap is why an unlock after a process death in
     * [AtomicVaultWriter]'s rename window opened from the `.tmp` and then left it there — the
     * promotion that repairs a `.bak` open is guarded on position, so it never fired for the one
     * case where no `vault.enc` exists at all. Every backup set taken until the next save then
     * carried no vault, because `.tmp` and `.bak` are both excluded from Auto Backup by design.
     */
    fun hasValidPrimary(context: Context): Boolean =
        readValid(File(context.filesDir, AtomicVaultWriter.VAULT_FILE)) != null

    fun loadCandidates(context: Context): List<ByteArray> = loadCandidates(context.filesDir)

    /** See the [Context] overload. Split out so it can be proven without Robolectric. */
    internal fun loadCandidates(filesDir: File): List<ByteArray> =
        listOfNotNull(
            readValid(File(filesDir, AtomicVaultWriter.VAULT_FILE)),
            readValid(File(filesDir, AtomicVaultWriter.TEMP_FILE)),
            readValid(File(filesDir, AtomicVaultWriter.BACKUP_FILE)),
        )

    /**
     * The Argon2id parameters this vault was sealed with, or null if the header cannot be read.
     *
     * Exposed so the unlock screen can *name* the work it is doing — "Argon2id · 3 passes · 256
     * MiB" — while the derivation runs. That is the difference between a spinner, which teaches
     * the user that the wait is arbitrary, and a statement of what their phone is actually
     * grinding through. Reads only the header, so it costs nothing and needs no key.
     */
    fun kdfParams(context: Context): KdfParams? {
        val bytes = (loadFileBytes(context) as? Outcome.Success)?.value ?: return null
        return try {
            VaultHeaderCodec.decode(bytes).slots.firstOrNull()?.kdfParams
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /**
     * The format version of a container this build is too old to read, or null.
     *
     * [readValid] deliberately returns null for anything it cannot parse, which collapses "damaged"
     * and "written by a newer LocalKeep" into the same silence — and the unlock screen then offers
     * the advice for the first: restore a backup. Doing that over a vault that was merely *newer*
     * replaces good data with old data, permanently. So the question is asked separately, and only
     * when there is otherwise nothing to open.
     */
    fun unsupportedFormatVersion(context: Context): Int? = listOf(
        AtomicVaultWriter.VAULT_FILE,
        AtomicVaultWriter.TEMP_FILE,
        AtomicVaultWriter.BACKUP_FILE,
    ).firstNotNullOfOrNull { name ->
        val file = File(context.filesDir, name)
        if (!file.isFile) return@firstNotNullOfOrNull null
        try {
            VaultHeaderCodec.decode(file.readBytes())
            null
        } catch (e: UnsupportedVaultFormatException) {
            e.formatVersion
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun readValid(file: File): ByteArray? {
        if (!file.isFile) return null
        return try {
            val bytes = file.readBytes()
            val header = VaultHeaderCodec.decode(bytes)
            VaultFileCodec.validateContainer(header, bytes.size)
            bytes
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
