package com.localkeep.credential

import androidx.credentials.PasswordCredential
import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import com.localkeep.core.vault.VaultRepository
import java.net.URI

internal object PasswordProvider {
    /**
     * Whether a password match may be offered to this caller.
     *
     * A web caller is matched by domain through a privileged, resolver-verified origin, so it is
     * allowed. A native caller is matched by bare package name — which any app that acquires that
     * name reports truthfully — so it is only allowed when its signing certificate has verified
     * against the pinned identity ([AppSignatureCache.verifyOrPin]). This closes the gap that let a
     * native-package login be handed to an app the login was never saved against, the same check the
     * autofill path and the passkey path already make.
     */
    fun passwordEntriesAllowed(hasWebOrigin: Boolean, signatureVerified: Boolean): Boolean =
        hasWebOrigin || signatureVerified

    fun matchingLogins(
        repository: VaultRepository,
        caller: CallerIdentity,
    ): List<VaultItem> {
        val domain = caller.origin?.let { runCatching { URI(it).host }.getOrNull() }
        val packageName = caller.packageName.takeIf { caller.origin == null }
        return repository.findMatching(domain, packageName).filter { it.content is Login }
    }

    fun credentialFor(item: VaultItem): PasswordCredential {
        val login = item.content as? Login ?: error("selected item is not a login")
        return PasswordCredential(login.username, login.password)
    }
}
