package com.localkeep.credential

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Regression test for F4: the Credential Manager password path used to match a native-app login by
 * bare package name with no signing-certificate check, unlike the autofill path and the passkey
 * path. [PasswordProvider.passwordEntriesAllowed] is the gate that closes it — a native caller is
 * only served a password once its certificate has verified against the pinned identity.
 */
class PasswordProviderGateTest {

    @Test
    fun `a native caller whose signature did not verify is refused`() {
        // origin == null (native), signature not verified → no password entries.
        assertFalse(PasswordProvider.passwordEntriesAllowed(hasWebOrigin = false, signatureVerified = false))
    }

    @Test
    fun `a native caller whose signature verified is allowed`() {
        assertTrue(PasswordProvider.passwordEntriesAllowed(hasWebOrigin = false, signatureVerified = true))
    }

    @Test
    fun `a web caller is allowed without a signature check`() {
        // A web caller is matched by its resolver-verified domain, not by package, so the signing
        // pin does not apply — it must still be served.
        assertTrue(PasswordProvider.passwordEntriesAllowed(hasWebOrigin = true, signatureVerified = false))
    }
}
