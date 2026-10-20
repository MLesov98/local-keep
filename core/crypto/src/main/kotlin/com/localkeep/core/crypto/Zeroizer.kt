package com.localkeep.core.crypto

/** Best-effort secret wiping; runtime copies cannot be guaranteed on ART/JVM. */
object Zeroizer {
    fun wipe(bytes: ByteArray?) {
        bytes?.fill(0)
    }

    fun wipe(chars: CharArray?) {
        chars?.fill('\u0000')
    }
}
