package com.localkeep.credential

import com.localkeep.core.model.CustomField
import com.localkeep.core.model.CustomFieldType
import com.localkeep.core.model.VaultItem
import java.net.IDN
import java.net.URI
import java.util.Locale

/**
 * Offline anti-phishing checks.
 *
 * Related Origin Requests and public-suffix validation need maintained RP metadata that LocalKeep
 * cannot fetch at ceremony time. This implementation therefore fails closed to same-site origins.
 */
internal object RpIdValidator {
    const val BOUND_PACKAGE_FIELD = "credential.boundPackage"
    const val BOUND_CERT_FIELD = "credential.boundCertSha256"

    sealed interface CreateValidation {
        data object WebOk : CreateValidation
        data class NativeUnconfirmed(val packageName: String) : CreateValidation
        data object Rejected : CreateValidation
    }

    fun isSameSiteOrigin(
        origin: String,
        rpId: String,
    ): Boolean {
        val uri = runCatching { URI(origin) }.getOrNull() ?: return false
        val host = normalizeHost(uri.host) ?: return false
        val rp = normalizeHost(rpId) ?: return false
        val secureScheme = uri.scheme.equals("https", ignoreCase = true)
        val localHttp =
            uri.scheme.equals("http", ignoreCase = true) &&
                (host == "localhost" || host == "127.0.0.1" || host == "::1")
        return (secureScheme || localHttp) && (host == rp || host.endsWith(".$rp"))
    }

    fun validateForCreate(
        caller: CallerIdentity,
        rpId: String,
    ): CreateValidation =
        when {
            registrableRpId(rpId) == null -> CreateValidation.Rejected
            caller.origin == null -> CreateValidation.NativeUnconfirmed(caller.packageName)
            isSameSiteOrigin(caller.origin, rpId) -> CreateValidation.WebOk
            else -> CreateValidation.Rejected
        }

    fun validateForAssertion(
        caller: CallerIdentity,
        item: VaultItem,
        rpId: String,
    ): Boolean =
        if (caller.origin != null) {
            isSameSiteOrigin(caller.origin, rpId)
        } else {
            val fields = item.customFields.associate { it.name to it.value }
            val boundPackage = fields[BOUND_PACKAGE_FIELD]?.lowercase(Locale.ROOT)
            val boundCerts = fields[BOUND_CERT_FIELD]
                ?.split(',')
                ?.filter(String::isNotBlank)
                ?.toSet()
                .orEmpty()
            boundPackage == caller.packageName &&
                boundCerts.isNotEmpty() &&
                when {
                    caller.currentCertHashes.size > 1 -> caller.currentCertHashes == boundCerts
                    else -> boundCerts.any(caller.certHistoryHashes::contains)
                }
        }

    fun nativeBindingFields(caller: CallerIdentity): List<CustomField> =
        listOf(
            CustomField(
                name = BOUND_PACKAGE_FIELD,
                value = caller.packageName,
                type = CustomFieldType.HIDDEN,
            ),
            CustomField(
                name = BOUND_CERT_FIELD,
                value = caller.currentCertHashes.sorted().joinToString(","),
                type = CustomFieldType.HIDDEN,
            ),
        )

    /**
     * The effective domain of [origin] — what WebAuthn uses as the relying-party id when a request
     * leaves `rpId` out, which the spec explicitly allows and callers regularly do.
     *
     * Null for a native caller, which has no origin: such a request must still state its `rpId`,
     * because there is nothing to derive one from. Defaulting to the caller's own domain grants
     * nothing extra — a site may always assert for itself, and [isSameSiteOrigin] is still what
     * decides that.
     */
    fun effectiveDomain(origin: String?): String? {
        val uri = runCatching { URI(origin ?: return null) }.getOrNull() ?: return null
        return canonicalRpId(uri.host)
    }

    fun canonicalRpId(value: String?): String? {
        val candidate = value
            ?.trim()
            ?.trimEnd('.')
            ?.takeIf { it.isNotEmpty() && !it.startsWith('.') }
            ?: return null
        return runCatching {
            IDN.toASCII(candidate, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
        }.getOrNull()
    }

    private fun normalizeHost(value: String?): String? = canonicalRpId(value)

    /**
     * A canonical rpId that is also *registrable* — it has an internal dot, and its rightmost label
     * is not all-numeric.
     *
     * "com" is not a relying party, it is a public suffix, and a credential scoped to one would be
     * offered to every site beneath it. The header above records the deliberate decision not to
     * ship a Public Suffix List; this is the half of what a PSL would catch that needs no list,
     * because a registrable domain has an internal dot by construction.
     *
     * The all-numeric-label half is the IP-literal rule, added 2026-09-13.
     * Measured on the JDK that day: `IDN.toASCII` under
     * `USE_STD3_ASCII_RULES` accepts all-digit LDH labels, so `canonicalRpId("192.168.1.50")`
     * returns it unchanged and the internal-dot test alone let it through — an address has no
     * registrable structure at all, and WebAuthn's domain-string rule excludes one. The rule is
     * written on the *rightmost label* rather than on four-octet shape because the wider case is
     * the one that actually over-scopes: rpId "1.50" also carries an internal dot, and both
     * "192.168.1.50" and "10.0.1.50" end with ".1.50" (measured), so [isSameSiteOrigin]'s suffix
     * arm would span two unrelated addresses. No real TLD is all-numeric, so nothing registrable
     * is lost — punycode output is unaffected, `bücher.de` still canonicalises to a `.de`.
     *
     * `127.0.0.1` is spared by name because [isSameSiteOrigin]'s loopback arm accepts exactly that
     * literal over http; a blanket numeric rejection would break local-development WebAuthn, which
     * is the failure the paragraph below warns about, one case over from the one it names.
     *
     * Deliberately NOT folded into [canonicalRpId]. That function also normalises the *host* side
     * of [isSameSiteOrigin], and the loopback branch there compares the already-normalised host
     * against `localhost` and `::1` — neither of which contains a dot. Rejecting bare labels in the
     * shared helper therefore looks like a tightening and is really a break of local-development
     * WebAuthn, which is why the rule lives here, on the one input it is about.
     *
     * Correctness note from the same 2026-09-13 measurement, no behaviour change: that `::1`
     * comparison is dead. `URI("http://[::1]").host` keeps the brackets, `IDN.toASCII` throws on
     * `[` and `:` under `USE_STD3_ASCII_RULES`, so [normalizeHost] returns null and
     * [isSameSiteOrigin] has already returned false before the arm is reached (`http://::1` parses
     * with a null host, same outcome). It fails closed — IPv6 loopback simply has no WebAuthn
     * support here — but half the rationale above defends a comparison that cannot fire.
     */
    private fun registrableRpId(value: String?): String? =
        canonicalRpId(value)?.takeIf { candidate ->
            candidate == "127.0.0.1" ||
                (candidate.contains('.') && !candidate.substringAfterLast('.').all(Char::isDigit))
        }
}
