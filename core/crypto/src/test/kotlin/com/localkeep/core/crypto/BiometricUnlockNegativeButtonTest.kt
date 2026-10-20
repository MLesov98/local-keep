package com.localkeep.core.crypto

import java.lang.reflect.Method
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression guard for the biometric negative-button i18n fix: `authenticate` used to build its
 * `BiometricPrompt.PromptInfo` with a hardcoded English literal, `.setNegativeButtonText("Use
 * master password")`, baked into this pure-JVM-ish core/crypto class — while title and subtitle
 * were already caller-supplied so every caller could localize them. That literal cannot be
 * exercised from a JVM unit test (it only shows up in the real BiometricPrompt system dialog), so
 * this asserts the fix at the signature level instead: the negative-button text must now be a
 * caller-supplied parameter, the same way title/subtitle already are.
 *
 * `authenticate` is a `suspend fun`, so the compiler appends a trailing
 * `kotlin.coroutines.Continuation` parameter — the counts below subtract it back out.
 *
 * RED before the fix: `authenticate(activity, cipher, title, subtitle)` declares 4 parameters (2
 * of them String) and compiles to 5 Java parameters, so both assertions below fail.
 * GREEN after the fix: `authenticate(activity, cipher, title, subtitle, negativeButtonText)`
 * declares 5 parameters (3 of them String) and compiles to 6 Java parameters.
 */
class BiometricUnlockNegativeButtonTest {
    @Test
    fun `authenticate requires a caller-supplied negative button text parameter`() {
        val method = biometricUnlockAuthenticateMethod()
        val declaredParamCount = method.parameterTypes.size - 1 // drop the synthesized Continuation

        assertTrue(
            declaredParamCount == 5,
            "expected authenticate(activity, cipher, title, subtitle, negativeButtonText) with " +
                "5 declared parameters; found $declaredParamCount. Was the negative-button " +
                "parameter removed, renamed, or never added?",
        )

        val stringParamCount = method.parameterTypes.count { it == String::class.java }
        assertTrue(
            stringParamCount == 3,
            "expected 3 String parameters (title, subtitle, negativeButtonText); found " +
                "$stringParamCount. A hardcoded negative-button literal would leave only 2.",
        )
    }

    private fun biometricUnlockAuthenticateMethod(): Method =
        BiometricUnlock::class.java.declaredMethods.single { it.name == "authenticate" }
}
