package com.localkeep.core.crypto

import android.os.Build
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

/** Local AndroidKeyStore metadata inspection. No attestation key or certificate chain. */
object HardwareBacking {
    fun keyInfo(key: SecretKey): KeyInfo =
        SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo

    @Suppress("DEPRECATION")
    fun isHardwareBacked(key: SecretKey): Boolean {
        val info = keyInfo(key)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            info.securityLevel == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT ||
                info.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX ||
                info.securityLevel == KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE
        } else {
            info.isInsideSecureHardware
        }
    }

    @Suppress("DEPRECATION")
    fun describe(key: SecretKey): String {
        val info = keyInfo(key)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            "hardwareBacked=${isHardwareSecurityLevel(info.securityLevel)}, " +
                "securityLevel=${securityLevelName(info.securityLevel)}"
        } else {
            "hardwareBacked=${info.isInsideSecureHardware}"
        }
    }

    private fun isHardwareSecurityLevel(level: Int): Boolean =
        level == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT ||
            level == KeyProperties.SECURITY_LEVEL_STRONGBOX ||
            level == KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE

    private fun securityLevelName(level: Int): String = when (level) {
        KeyProperties.SECURITY_LEVEL_SOFTWARE -> "software"
        KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "trusted-environment"
        KeyProperties.SECURITY_LEVEL_STRONGBOX -> "strongbox"
        KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE -> "unknown-secure"
        KeyProperties.SECURITY_LEVEL_UNKNOWN -> "unknown"
        else -> "unrecognized($level)"
    }

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
}
