# R8/ProGuard rules — Phase 16 release configuration.

# --- kotlinx.serialization (models are (de)serialized reflectively via generated serializers) ---
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keep,includedescriptorclasses class com.localkeep.core.model.**$$serializer { *; }
-keepclassmembers class com.localkeep.core.model.** { *** Companion; }
-keepclasseswithmembers class com.localkeep.core.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Bouncy Castle (low-level API used for Argon2id / GCM-SIV) ---
# Also carried as a consumer rule in :core:crypto/consumer-rules.pro so any other consumer keeps
# it too; kept here as well per the plan (belt-and-suspenders, harmless duplication).
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# --- Credential Manager + biometric (passkeys, WebAuthn CredentialProviderService) ---
-keep class androidx.credentials.** { *; }
-dontwarn androidx.credentials.**

# --- CBOR (com.upokecenter:cbor, used by :credential for WebAuthn attestation/assertion encoding) ---
-dontwarn com.upokecenter.**

# --- CameraX (play flavor only — TOTP QR scan + capture-to-vault) ---
-dontwarn androidx.camera.**

# --- zxing (play flavor only — QR decode) ---
-dontwarn com.google.zxing.**

# --- Play Billing (play flavor only) ---
-dontwarn com.android.billingclient.**

# --- Play In-App Review (play flavor only) ---
# `review-ktx:2.0.2` is compiled against a newer play-services-basement than the one on this
# classpath, so `ReviewManagerKtxKt`'s generated OnSuccessListener carries an annotation type that
# is genuinely absent: com.google.android.gms.common.annotation.NoNullnessRewrite. R8 fails the
# build on it rather than warning.
#
# Silencing is sound here in a way it usually is not: the missing type is an *annotation*, it is
# not runtime-retained, and nothing reads it reflectively — a class file referencing an absent
# annotation loads fine on Android. Narrowed to the single class rather than the package, so a
# genuinely missing GMS *class* would still fail the build instead of being swallowed by a wildcard.
-dontwarn com.google.android.gms.common.annotation.NoNullnessRewrite

# --- zxcvbn (password-strength scoring, :core:health) — ships no consumer rules of its own ---
-keep class com.nulabinc.zxcvbn.** { *; }
-dontwarn com.nulabinc.zxcvbn.**

# --- Strip debug/verbose/info logs from release (keep wtf/error for crash diagnostics) ---
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
}

# KeePassJava2 (:core:import) — first reachable from :app with the B4 migration wizard.
# Its `simple` flavour is backed by simple-xml, which compiles against the StAX API
# (javax.xml.stream). Android has never shipped StAX, so these classes are genuinely absent.
# Silencing is only sound because nothing reachable calls them: the KeePass source is disabled
# on-device for exactly this reason (see ImportSource.KEEPASS). Re-enabling KDBX on Android needs
# a StAX-free KeePassJava2 flavour (jackson/dom) — not just the deletion of these lines.
-dontwarn javax.xml.stream.**
-dontwarn org.codehaus.stax2.**
-dontwarn com.fasterxml.aalto.**
# Guava (a KeePassJava2 transitive) carries j2objc annotations that exist only in Google's
# Objective-C transpiler toolchain; compile-time only, never loaded at runtime.
-dontwarn com.google.j2objc.annotations.**
