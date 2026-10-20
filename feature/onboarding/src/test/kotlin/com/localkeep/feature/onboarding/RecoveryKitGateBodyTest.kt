package com.localkeep.feature.onboarding

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Recovery Kit gate does not invite a fingerprint it will not accept.
 *
 * After a panic lock the gate hides "Use biometrics" (the same hands that were forced to unlock the
 * phone could otherwise open the 24 words), but its body still said "Confirm with biometrics or
 * your vault password". The password-only body is chosen whenever the biometric door is not on
 * screen, which also covers a phone with no strong sensor.
 */
class RecoveryKitGateBodyTest {

    private val languages = listOf("values", "values-fr", "values-de", "values-es", "values-pt")

    private fun string(dir: String, key: String): String =
        Regex("""<string name="$key">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(File("src/main/res/$dir/strings.xml").readText())?.groupValues?.get(1)
            ?: error("$key missing from $dir")

    @Test
    fun `with the biometric door the shared body is shown`() {
        assertEquals(R.string.onboarding_recoverykit_gate_body, recoveryKitGateBody(biometricAvailable = true))
    }

    @Test
    fun `without the biometric door the password-only body is shown`() {
        assertEquals(
            R.string.onboarding_recoverykit_gate_body_password_only,
            recoveryKitGateBody(biometricAvailable = false),
        )
    }

    /** Every language has the variant, and it differs from the shared body by dropping the offer. */
    @Test
    fun `every language carries a password-only variant that is not the shared body`() {
        languages.forEach { dir ->
            val shared = string(dir, "onboarding_recoverykit_gate_body")
            val only = string(dir, "onboarding_recoverykit_gate_body_password_only")
            assertTrue(only.isNotBlank(), "$dir variant is empty")
            assertFalse(only == shared, "$dir variant is the shared body")
            assertTrue(only.length < shared.length, "$dir variant should be shorter: it drops the biometric clause")
        }
    }

    @Test
    fun `the english variant names no biometrics`() {
        assertFalse(
            "biometric" in string("values", "onboarding_recoverykit_gate_body_password_only").lowercase(),
            "the variant exists to stop inviting biometrics",
        )
    }
}
