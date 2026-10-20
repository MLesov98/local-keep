package com.localkeep.credential

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A login stored without a username still has to be nameable in the system credential sheet.
 *
 * `Login.username` defaults to `""` and nothing forces it non-blank — the item editor validates the
 * title alone, and a KDBX entry routinely carries a password with no user name. The password branch
 * used to hand that empty string straight to `PasswordCredentialEntry.Builder` as the row's primary
 * label, producing a nameless row in the one surface outside this app's UI, while the passkey
 * branch three functions above already fell back to the item title for exactly the same case.
 *
 * The second assertion is the more serious half. `passwordEntries` was the one branch of
 * [CredentialEntryFactory.response] not wrapped in `runCatching`, so a builder that rejected the
 * empty label took the whole `BeginGetCredentialResponse` with it — every passkey entry included —
 * and surfaced as "could not read the vault".
 *
 * Asserted on the source for the reason `PasswordProviderGateTest`'s neighbours give: building a
 * real entry needs a `Context` and a `PendingIntent`, and this module has no Robolectric.
 */
class CredentialEntryLabelTest {

    private val source =
        File("src/main/kotlin/com/localkeep/credential/CredentialEntryFactory.kt").readText()

    private fun passwordEntriesBody(): String =
        source.substringAfter("private fun passwordEntries(")
            .substringBefore("private fun itemPendingIntent(")

    @Test
    fun `a login with no username is labelled with its title`() {
        assertTrue(
            "passwordEntries must fall back to the item title, the way passkeyEntries does. " +
                "Found: ${passwordEntriesBody()}",
            "login.username.ifBlank { item.title }" in passwordEntriesBody(),
        )
    }

    @Test
    fun `the password branch cannot take the passkey entries down with it`() {
        val body = passwordEntriesBody()
        assertTrue(
            "passwordEntries must be wrapped the way passkeyEntries is, so one unbuildable row " +
                "cannot lose the whole response. Found: $body",
            "runCatching {" in body && ".getOrDefault(emptyList())" in body,
        )
    }
}
