package com.localkeep.core.vault

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A restore must never destroy the bytes it is replacing.
 *
 * [VaultLoader.quarantineContainers] is the one moment `vault.enc.bak` can be kept: the write that
 * follows rotates the primary onto it with `REPLACE_EXISTING`. These pin the two properties the
 * pre-unlock restore path (F34) depends on and the one that makes the whole thing a guard rather
 * than a gesture — that the list of container names comes from `AtomicVaultWriter` rather than being
 * retyped, so a rename cannot silently make the quarantine miss a file.
 */
class QuarantineContainersTest {
    private val dir: File = Files.createTempDirectory("quarantine").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun `the names come from the writer, not from a copy of them`() {
        // The duplication this API exists to end. Three places had spelled these out by hand.
        assertContentEquals(
            listOf("vault.enc", "vault.enc.tmp", "vault.enc.bak"),
            VaultLoader.CONTAINER_FILE_NAMES,
            "the load order is primary, the unrenamed save, then the previous generation",
        )
        assertEquals(AtomicVaultWriter.VAULT_FILE, VaultLoader.PRIMARY_CONTAINER_FILE)
        assertTrue(AtomicVaultWriter.TEMP_FILE in VaultLoader.CONTAINER_FILE_NAMES)
        assertTrue(AtomicVaultWriter.BACKUP_FILE in VaultLoader.CONTAINER_FILE_NAMES)
    }

    @Test
    fun `every container is copied aside and the originals stay where they were`() {
        write("vault.enc", "primary")
        write("vault.enc.tmp", "unrenamed save")
        write("vault.enc.bak", "previous generation")

        val kept = VaultLoader.quarantineContainers(dir, "damaged", "20260913-141502")

        assertEquals(3, kept.size, "all three are the recoverable remnant until one is opened")
        assertContentEquals(
            listOf("primary", "unrenamed save", "previous generation"),
            kept.map { it.readText() },
        )
        // The half that makes it a copy rather than a move: leaving the originals is what keeps
        // `hasVaultFiles` true, so a failure in the next step cannot route the launch to onboarding
        // and let a fresh vault be written over the damaged one's space.
        assertTrue(File(dir, "vault.enc").isFile, "the original must stay for the writer to rotate")
        assertTrue(File(dir, "vault.enc.tmp").isFile)
        assertTrue(File(dir, "vault.enc.bak").isFile)
    }

    @Test
    fun `nothing to keep is not a failure`() {
        // A vault can be unreadable because a file is missing, which is one of the states the
        // restore path exists for.
        assertEquals(emptyList(), VaultLoader.quarantineContainers(dir, "damaged", "stamp"))
    }

    @Test
    fun `an existing quarantine copy is never overwritten`() {
        write("vault.enc", "the damaged one")
        File(dir, "damaged").mkdirs()
        write("damaged/vault.enc-stamp", "an earlier quarantine")

        val kept = VaultLoader.quarantineContainers(dir, "damaged", "stamp")

        assertEquals(
            "an earlier quarantine",
            File(dir, "damaged/vault.enc-stamp").readText(),
            "a second quarantine under a colliding suffix must not destroy the first — that is " +
                "the one thing this function exists not to do",
        )
        assertEquals("the damaged one", File(dir, "vault.enc").readText())
        assertEquals(
            "the damaged one",
            File(dir, "damaged/vault.enc-stamp-2").readText(),
            "the colliding copy belongs beside the first, not in place of it and not nowhere",
        )
        assertEquals(listOf("vault.enc-stamp-2"), kept.map { it.name })
    }

    /**
     * The collision is ordinary, so it must not be reported as a disk failure.
     *
     * The caller's stamp is second-resolution, and two restore attempts inside one second is a
     * thing a user does — reach the confirmation, cancel, try the next backup. Until 2026-09-13
     * that threw, and the caller renders a thrown quarantine as "The vault file couldn't be set
     * aside, so nothing was changed": an error blaming the disk for a clock, on the screen a user
     * reaches only when their vault will not open.
     */
    @Test
    fun `two quarantines in the same second both succeed and both survive`() {
        write("vault.enc", "first damage")
        VaultLoader.quarantineContainers(dir, "damaged", "stamp")
        write("vault.enc", "second damage")

        val kept = VaultLoader.quarantineContainers(dir, "damaged", "stamp")

        assertEquals(listOf("vault.enc-stamp-2"), kept.map { it.name })
        assertEquals("first damage", File(dir, "damaged/vault.enc-stamp").readText())
        assertEquals("second damage", File(dir, "damaged/vault.enc-stamp-2").readText())
    }

    private fun write(name: String, text: String) {
        File(dir, name).also { it.parentFile?.mkdirs() }.writeText(text)
    }
}
