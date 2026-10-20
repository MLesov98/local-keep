package com.localkeep.credential

import android.content.Context
import androidx.annotation.RawRes
import androidx.credentials.provider.CallingAppInfo
import com.localkeep.credential.webauthn.WebAuthnCodec
import java.util.Locale

internal data class CallerIdentity(
    val packageName: String,
    val origin: String?,
    val currentCertHashes: Set<String>,
    val certHistoryHashes: Set<String>,
) {
    init {
        require(packageName.isNotBlank())
        require(currentCertHashes.isNotEmpty())
    }

    val nativeOrigin: String
        get() = "android:apk-key-hash:${currentCertHashes.sorted().first()}"
}

/** Resolves only origins asserted by a browser in the bundled trusted-caller snapshot. */
internal object CallerIdentityResolver {
    @Volatile
    private var allowlist: String? = null

    fun resolve(
        context: Context,
        callingAppInfo: CallingAppInfo,
    ): CallerIdentity {
        val signingInfo = callingAppInfo.signingInfo
        val current = signingInfo.apkContentsSigners
            .map { WebAuthnCodec.base64Url(WebAuthnCodec.sha256(it.toByteArray())) }
            .toSet()
        val history = signingInfo.signingCertificateHistory
            ?.map { WebAuthnCodec.base64Url(WebAuthnCodec.sha256(it.toByteArray())) }
            ?.toSet()
            .orEmpty()
        val origin = if (callingAppInfo.isOriginPopulated()) {
            callingAppInfo.getOrigin(privilegedAllowlist(context))
        } else {
            null
        }
        return CallerIdentity(
            packageName = callingAppInfo.packageName.lowercase(Locale.ROOT),
            origin = origin,
            currentCertHashes = current,
            certHistoryHashes = history.ifEmpty { current },
        )
    }

    private fun privilegedAllowlist(context: Context): String =
        allowlist ?: synchronized(this) {
            allowlist ?: context.readRawText(R.raw.privileged_apps).also { allowlist = it }
        }

    private fun Context.readRawText(@RawRes resourceId: Int): String =
        resources.openRawResource(resourceId).bufferedReader().use { it.readText() }
}
