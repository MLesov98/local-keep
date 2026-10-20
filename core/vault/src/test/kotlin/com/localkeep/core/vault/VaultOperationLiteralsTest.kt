package com.localkeep.core.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Regression test: every operation literal this class names has a localised phrase waiting for it.
 *
 * `realSessionSnapshot(operation)` takes a deliberately-English literal that never reaches the UI
 * as-is. When an auto-lock lands mid-operation, `ensureSessionUnchanged` throws
 * `VaultSessionEndedException(operation)` and `:feature:settings` looks the literal up in
 * `vaultOperationRes` to build "The vault locked while trying to <phrase>." A literal with no row
 * there falls back to the generic "while an operation was running" — correct, but stripped of the
 * one detail the mapping exists to provide.
 *
 * `setMasterPasswordWithRecoveryKit` shipped with `"set a new master password"`, which no row
 * matched. That is the Recovery-Kit reset: the user has already forgotten their master password and
 * is retyping 24 words off paper, and the Aggressive preset's five-second background grace expires
 * during three key derivations. The one screen where knowing which operation was interrupted
 * decides whether they retype the words or start over was the one screen that did not say.
 *
 * `VaultOperationResTest` in `:feature:settings` pins the map-to-phrase direction. This pins the
 * other one — that the manager never invents a literal the map has never heard of — which is the
 * direction that actually broke. The two lists must move together; a new literal needs a
 * `vaultOperationRes` row and a `settings_op_*` string in all five catalogues before it appears
 * here.
 */
class VaultOperationLiteralsTest {

    private val source = java.io.File("src/main/kotlin/com/localkeep/core/vault/VaultLockManager.kt")

    /** Mirrors the keys of `vaultOperationRes` (feature/settings/.../SettingsViewModel.kt). */
    private val mappedOperations = setOf(
        "prepare the Recovery Kit",
        "save the Recovery Kit",
        "save your changes",
        "set up duress",
        "remove duress",
        "inspect duress",
        "update the encryption settings",
        "change the master password",
        "rotate the encryption key",
        "enable biometric unlock",
    )

    @Test
    fun `no session snapshot names an operation the settings mapping cannot translate`() {
        if (!source.isFile) fail("cannot find ${source.absolutePath} — this test reads the source it guards")

        val literal = Regex("""realSessionSnapshot\("([^"]+)"\)""")
        val used = literal.findAll(source.readText()).map { it.groupValues[1] }.toSet()
        assertTrue(
            used.isNotEmpty(),
            "found no realSessionSnapshot(\"…\") call sites — this test is no longer checking anything",
        )

        val unmapped = used - mappedOperations
        assertEquals(
            emptySet(),
            unmapped,
            "VaultLockManager names operation literal(s) $unmapped that vaultOperationRes has no " +
                "row for, so an auto-lock during them shows the generic sentence instead of the " +
                "named one. Add a row plus a settings_op_* string in all five catalogues, then add " +
                "the literal here and to VaultOperationResTest.",
        )
    }
}
