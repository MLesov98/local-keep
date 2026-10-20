package com.localkeep.autofill

/**
 * Recognises the OS's own credential screens, whose "password" fields are the device lock itself
 * rather than an account we should offer to store.
 *
 * Observed on-device: setting a lock-screen PIN in Settings made LocalKeep pop "Save username and
 * password?". Storing a device PIN in the vault is noise at best, and at worst it lands the one
 * secret that guards the vault *inside* the vault.
 *
 * Matching is on package identity, not on labels or field hints — those are localised and
 * app-controlled, so they aren't a sound basis for a security decision.
 */
internal object SystemCredentialUi {

    /**
     * Exact package matches. Deliberately a small, explicit list: over-blocking would silently
     * swallow real saves, which is the failure mode we just finished fixing.
     */
    private val PACKAGES = setOf(
        "com.android.settings",
        "com.android.systemui",              // keyguard / bouncer
        "com.android.credentialmanager",     // platform credential chooser (API 34+)
        "com.android.settings.intelligence",
        "com.google.android.settings.intelligence",
        "com.android.managedprovisioning",   // work-profile setup asks for a device credential
    )

    /**
     * True when a save proposal originating from [packageName] should be dropped.
     *
     * [webDomain] is the escape hatch: a page rendered inside a WebView still deserves a save even
     * if the host package is on the list, because the credential then belongs to the site and not
     * to the device.
     */
    fun shouldSkipSave(packageName: String?, webDomain: String?): Boolean {
        if (webDomain != null) return false
        val pkg = packageName ?: return false
        return pkg in PACKAGES
    }
}
