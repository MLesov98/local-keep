# Bouncy Castle (low-level API used for Argon2id / GCM-SIV) — kept for any consumer of :core:crypto,
# not just :app, so the rule travels with the module rather than relying on app/proguard-rules.pro alone.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
