# No consumer rules required — kotlin-bip39 (recovery kit) and the vault codec are plain Kotlin
# with no reflection; :core:crypto/consumer-rules.pro carries the one real requirement (Bouncy
# Castle) for this module's transitive dependency chain (Phase 16 audit).
