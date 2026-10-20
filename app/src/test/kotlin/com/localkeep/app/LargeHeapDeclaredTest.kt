package com.localkeep.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The application declares `largeHeap`, and this is a decision rather than a default.
 *
 * Argon2id's memory cost is a Java-heap allocation here (BouncyCastle holds the whole cost as a
 * `Block[]`), and Android caps an ordinary app at `dalvik.vm.heapgrowthlimit` — 48 MiB on the
 * API 28 and API 29 emulator images and on phones of that era. Unlocking fits, because nothing
 * else is resident yet. Every derivation that runs *inside* the unlocked app does not: on
 * 2026-09-14 setting up the duress vault on API 28 died three times out of three with
 * `OutOfMemoryError` inside `Argon2BytesGenerator.init` at a 48 MiB footprint, and was reported to
 * the user as "That didn't go through. Try again." The same ceiling is behind the API 28 save-path
 * failure ("the API 28 heap ceiling"). Even the FLOOR rung (19 MiB) does
 * not fit beside a resident Compose UI on 48 MiB.
 *
 * `largeHeap` lifts the cap to `dalvik.vm.heapsize` (512 MiB on both emulator images) on every
 * device that is not `ro.config.low_ram`; genuinely low-RAM devices ignore it, which is why the
 * in-app operations still map an `OutOfMemoryError` to a message that names the cause. Removing
 * this attribute re-breaks duress setup, master-password change and KDF re-wrapping on 48 MiB
 * heaps — hence the pin.
 */
class LargeHeapDeclaredTest {
    @Test
    fun theApplicationDeclaresLargeHeap() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val application = manifest.substringAfter("<application").substringBefore(">")
        assertTrue("android:largeHeap=\"true\"" in application, "largeHeap must stay declared on <application>")
    }
}
