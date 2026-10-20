package com.localkeep.core.vault

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.localkeep.core.common.sortedByDisplayName
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

// Store name kept as "autofill_trust" — unchanged from when this class lived in the autofill module,
// so the pins a user has already accumulated survive the move. The credential provider now shares
// this same store, so an app trusted (or a spoofer rejected) for autofill is treated identically
// when it asks for a password through Credential Manager.
private val Context.autofillTrustStore: DataStore<Preferences> by preferencesDataStore(
    name = "autofill_trust",
)

/** An app and the domains autofill has been allowed to fill in it. See [AppSignatureCache.trustedApps]. */
data class TrustedApp(
    val packageName: String,
    val label: String,
    val domains: List<String>,
)

/**
 * Offline app-trust: persisted signing-certificate pins plus explicit browser/domain grants.
 *
 * Shared by both the autofill service and the Credential Manager provider, which is the whole point
 * of it living in `:core:vault` rather than either of those modules: a native-package match must be
 * gated by the same signing-certificate pin whether the credential is served through autofill or
 * through Credential Manager, or the weaker path becomes the way in.
 */
@Singleton
class AppSignatureCache @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    /**
     * First sighting pins current signers. Later sightings must be signed by that pinned identity.
     * Android signing-certificate rotation remains valid because the old pin stays in the lineage.
     */
    suspend fun verifyOrPin(packageName: String): Boolean = runCatching {
        val identity = signingIdentity(packageName) ?: return@runCatching false
        val key = pinKey(packageName)
        val pinned = context.autofillTrustStore.data.first()[key]
        if (pinned == null) {
            context.autofillTrustStore.edit { it[key] = identity.current }
            true
        } else {
            pinned.isNotEmpty() && pinned.all(identity.lineage::contains)
        }
    }.getOrDefault(false)

    suspend fun isDomainConfirmed(
        packageName: String,
        webDomain: String,
    ): Boolean = runCatching {
        pairKey(packageName, webDomain) in
            context.autofillTrustStore.data.first()[confirmedPairsKey].orEmpty()
    }.getOrDefault(false)

    suspend fun confirmDomain(
        packageName: String,
        webDomain: String,
    ): Boolean = runCatching {
        context.autofillTrustStore.edit { preferences ->
            preferences[confirmedPairsKey] =
                preferences[confirmedPairsKey].orEmpty() + pairKey(packageName, webDomain)
        }
        true
    }.getOrDefault(false)

    /**
     * Every app the trust store holds anything about, grouped for a management surface
     * (UI/UX spec §10.1). The label is resolved from the installed package; an app uninstalled
     * since the grant falls back to its package name, so a stale grant is still nameable and
     * removable rather than an opaque row.
     *
     * **Pinned-but-ungranted apps are rows too.** The store holds two independent things: the
     * `pkg|domain` grants below, and a signing-certificate pin written by [verifyOrPin] on first
     * sighting. A native app has no web domain, so filling a login in one pins its certificate and
     * records no pair — and this list, built from pairs alone, had no row for it. [forgetApp] is
     * reachable only from a row, so that pin could never be reset from anywhere in the UI, and the
     * `trusted_apps_no_sites` string written for exactly this case was unreachable (release sweep
     * 2026-09-20, 3B-F5). A pin *is* trust the user granted, so it gets a row whether or not a
     * domain came with it.
     */
    suspend fun trustedApps(): List<TrustedApp> = runCatching {
        val stored = context.autofillTrustStore.data.first()
        groupPairs(
            pairs = stored[confirmedPairsKey].orEmpty(),
            pinnedPackages = pinnedPackages(stored),
            label = ::appLabel,
        )
    }.getOrDefault(emptyList())

    /**
     * The packages [verifyOrPin] has pinned a signing certificate for.
     *
     * Read back out of the key names because that is where the package is: [pinKey] encodes it into
     * the preference key rather than keeping a second index, and adding one now would be a store
     * migration for a set this small.
     */
    private fun pinnedPackages(stored: Preferences): Set<String> =
        stored.asMap().keys
            .mapNotNull { key ->
                key.name.takeIf { it.startsWith(PIN_KEY_PREFIX) }?.removePrefix(PIN_KEY_PREFIX)
            }
            .filter { it.isNotEmpty() }
            .toSet()

    /** Forgets a single app↔domain grant. Autofill will confirm again next time it is offered. */
    suspend fun forgetPair(
        packageName: String,
        webDomain: String,
    ) = runCatching {
        context.autofillTrustStore.edit { preferences ->
            preferences[confirmedPairsKey] =
                preferences[confirmedPairsKey].orEmpty() - pairKey(packageName, webDomain)
        }
        Unit
    }.getOrDefault(Unit)

    /**
     * Forgets an app entirely: every domain grant and the signing-certificate pin. Dropping the pin
     * too means the app is treated as first-seen again — the stronger reset, which is what a user
     * removing an app they no longer trust expects.
     */
    suspend fun forgetApp(packageName: String) = runCatching {
        context.autofillTrustStore.edit { preferences ->
            preferences.remove(pinKey(packageName))
            preferences[confirmedPairsKey] = preferences[confirmedPairsKey].orEmpty()
                .filterNot { it.substringBefore('|') == packageName.trim() }
                .toSet()
        }
        Unit
    }.getOrDefault(Unit)

    private fun appLabel(packageName: String): String? = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrNull()

    internal companion object {
        val confirmedPairsKey = stringSetPreferencesKey("confirmed_domain_package_pairs")

        /** Names every signing-certificate pin. Read back by [pinnedPackages] to recover the package. */
        const val PIN_KEY_PREFIX: String = "pin::"

        /**
         * Pure grouping of the stored trust into per-app rows, sorted by label.
         *
         * Two inputs, because the store keeps two kinds of trust: [pairs] are the `pkg|domain`
         * grants, and [pinnedPackages] are the apps whose signing certificate has been pinned.
         * Every package in either gets a row — a pinned app with no domain grant renders with an
         * empty [TrustedApp.domains], which is what the screen's `trusted_apps_no_sites` line is
         * for, and what makes its Forget control the only route to clearing that pin.
         *
         * Malformed pair entries (no separator, empty package) are dropped rather than shown as
         * opaque rows; an empty domain is filtered so it cannot render as a blank line. Separated
         * from the DataStore read so it can be tested on the plain JVM this module's tests run on.
         */
        fun groupPairs(
            pairs: Set<String>,
            pinnedPackages: Set<String> = emptySet(),
            label: (String) -> String?,
        ): List<TrustedApp> {
            val granted = pairs
                .mapNotNull { pair ->
                    val sep = pair.indexOf('|')
                    if (sep <= 0) return@mapNotNull null
                    pair.substring(0, sep) to pair.substring(sep + 1)
                }
                .groupBy({ it.first }, { it.second })
            return (granted.keys + pinnedPackages.filter { it.isNotEmpty() })
                .map { packageName ->
                    TrustedApp(
                        packageName = packageName,
                        label = label(packageName) ?: packageName,
                        domains = granted[packageName]
                            .orEmpty()
                            .filter { it.isNotEmpty() }
                            .distinct()
                            .sorted(),
                    )
                }
                .sortedByDisplayName { it.label }
        }
    }

    private fun signingIdentity(packageName: String): SigningIdentity? = runCatching {
        val info = packageInfo(packageName)
        val signingInfo = info.signingInfo ?: return null
        val current = signingInfo.apkContentsSigners
            .map { sha256(it.toByteArray()) }
            .toSet()
        if (current.isEmpty()) return null
        val lineage = if (signingInfo.hasMultipleSigners()) {
            current
        } else {
            signingInfo.signingCertificateHistory
                .map { sha256(it.toByteArray()) }
                .toSet()
        }
        SigningIdentity(current = current, lineage = lineage)
    }.getOrNull()

    private fun packageInfo(packageName: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(
                    PackageManager.GET_SIGNING_CERTIFICATES.toLong(),
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { String.format(Locale.ROOT, "%02x", it) }

    private fun pinKey(packageName: String): Preferences.Key<Set<String>> =
        stringSetPreferencesKey("$PIN_KEY_PREFIX$packageName")

    private fun pairKey(packageName: String, webDomain: String): String =
        "${packageName.trim()}|${webDomain.trim().lowercase()}"

    private data class SigningIdentity(
        val current: Set<String>,
        val lineage: Set<String>,
    )
}
