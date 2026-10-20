package com.localkeep.credential

import kotlin.test.assertEquals
import org.junit.Test

/**
 * First tests for [RpIdValidator], written 2026-09-13.
 * The validator is the offline anti-phishing guard for every passkey ceremony and had no test at
 * all until this file, which was worth more than the finding that prompted it.
 *
 * The finding: `registrableRpId` accepted any dotted string as a registrable relying-party id,
 * including an IP-address literal. Measured on the JDK the same day — `IDN.toASCII` under
 * `USE_STD3_ASCII_RULES` accepts all-digit LDH labels, so `canonicalRpId("192.168.1.50")` returns
 * it unchanged and `it.contains('.')` was satisfied. WebAuthn's RP ID must be a valid domain
 * string, which excludes addresses.
 *
 * The loopback control is the load-bearing one. `127.0.0.1` is an IPv4 literal too, and
 * [RpIdValidator.isSameSiteOrigin]'s loopback arm accepts exactly that literal over http, so a fix
 * written as a blanket all-numeric rejection would silently break local-development WebAuthn. That
 * test goes red on the over-broad fix and green on the shipped one.
 *
 * `CallerIdentity` is constructed directly: it is a plain data class with no Android dependency,
 * while `CallerIdentityResolver` needs a `Context` and must not be involved in a JVM unit test.
 */
class RpIdValidatorTest {

    private fun webCaller(origin: String) =
        CallerIdentity(
            packageName = "com.example.browser",
            origin = origin,
            currentCertHashes = setOf("cert-a"),
            certHistoryHashes = setOf("cert-a"),
        )

    private fun nativeCaller() =
        CallerIdentity(
            packageName = "com.example.app",
            origin = null,
            currentCertHashes = setOf("cert-a"),
            certHistoryHashes = setOf("cert-a"),
        )

    // --- RED without the fix -------------------------------------------------------------------

    @Test
    fun `an IPv4 literal is not a registrable relying party`() {
        assertEquals(
            RpIdValidator.CreateValidation.Rejected,
            RpIdValidator.validateForCreate(webCaller("https://192.168.1.50"), "192.168.1.50"),
            "An IP address has no registrable structure; WebAuthn's domain-string rule excludes it.",
        )
    }

    @Test
    fun `a numeric partial suffix cannot span two unrelated addresses`() {
        // The wider case: "1.50" carries an internal dot, and both "192.168.1.50" and "10.0.1.50"
        // end with ".1.50" — so isSameSiteOrigin's suffix arm would have matched across hosts that
        // share nothing. A fix that only rejected four-octet shapes would leave this open.
        assertEquals(
            RpIdValidator.CreateValidation.Rejected,
            RpIdValidator.validateForCreate(webCaller("https://192.168.1.50"), "1.50"),
        )
    }

    @Test
    fun `a native caller cannot state an IPv4 literal as its rpId either`() {
        // Reached NativeUnconfirmed before the fix — the rpId is shown to the user in the consent
        // dialog, so it has to be a name the user can judge.
        assertEquals(
            RpIdValidator.CreateValidation.Rejected,
            RpIdValidator.validateForCreate(nativeCaller(), "192.168.1.50"),
        )
    }

    // --- CONTROLS: must stay green ------------------------------------------------------------

    @Test
    fun `loopback development still registers`() {
        // The control that turns red if the fix is written as a blanket all-numeric rejection.
        assertEquals(
            RpIdValidator.CreateValidation.WebOk,
            RpIdValidator.validateForCreate(webCaller("http://127.0.0.1"), "127.0.0.1"),
            "127.0.0.1 is the one IP literal isSameSiteOrigin's loopback arm accepts.",
        )
    }

    @Test
    fun `an ordinary domain still registers`() {
        assertEquals(
            RpIdValidator.CreateValidation.WebOk,
            RpIdValidator.validateForCreate(webCaller("https://example.com"), "example.com"),
        )
    }

    @Test
    fun `the registrable-suffix arm survives`() {
        assertEquals(
            RpIdValidator.CreateValidation.WebOk,
            RpIdValidator.validateForCreate(webCaller("https://app.example.com"), "example.com"),
        )
    }

    @Test
    fun `an internationalised domain still registers`() {
        // Punycode output ends in a non-numeric label, so the new rule cannot touch it: measured
        // 2026-09-13, canonicalRpId("bücher.de") is "xn--bcher-kva.de", rightmost label "de".
        assertEquals(
            RpIdValidator.CreateValidation.WebOk,
            RpIdValidator.validateForCreate(webCaller("https://xn--bcher-kva.de"), "bücher.de"),
        )
    }

    @Test
    fun `a bare public suffix is still refused`() {
        assertEquals(
            RpIdValidator.CreateValidation.Rejected,
            RpIdValidator.validateForCreate(webCaller("https://example.com"), "com"),
        )
    }

    @Test
    fun `a bare localhost rpId is still refused`() {
        // Pre-existing behaviour, pinned here because the fix reasons about the loopback arm:
        // "localhost" has no internal dot, so it never was a registrable rpId.
        assertEquals(
            RpIdValidator.CreateValidation.Rejected,
            RpIdValidator.validateForCreate(webCaller("http://localhost"), "localhost"),
        )
    }

    @Test
    fun `an IPv6 loopback rpId fails closed`() {
        // canonicalRpId returns null for "::1" — IDN.toASCII throws on ':' under
        // USE_STD3_ASCII_RULES — which is why isSameSiteOrigin's `host == "::1"` arm is dead code.
        assertEquals(
            RpIdValidator.CreateValidation.Rejected,
            RpIdValidator.validateForCreate(webCaller("http://[::1]"), "::1"),
        )
    }
}
