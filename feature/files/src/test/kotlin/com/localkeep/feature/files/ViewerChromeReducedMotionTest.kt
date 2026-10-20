package com.localkeep.feature.files

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression for 16-1: the media viewer's chrome did not honour reduced motion.
 *
 * `lkTween` exists to collapse a duration to zero when `LocalLkReduceMotion` is set, and Settings
 * tells the user in as many words that motion follows their system "remove animations" setting.
 * Every animated surface in the app routes through it — `core/ui`'s `ReducedMotionTest` says so in
 * its own KDoc — except this file, whose four `AnimatedVisibility` specs called the raw `tween`
 * underneath it. So the top bar, the bottom action bar, the details sheet and the hint pill slid
 * and faded on every open of every photo and attachment, on a setting the rest of the app keeps.
 *
 * Source-scanning rather than a Compose test, for the reason [PlaybackScreenAwakeTest] gives: this
 * module's unit-test classpath carries no Compose UI test artifact, and the property is a claim
 * about which spec a call site passes, which the source answers exactly. The elapsed-time
 * measurement belongs with `ReducedMotionTest`, which has a device.
 *
 * **Replace any one `lkTween(` in `SecureMediaViewer.kt` with `tween(` and the first test goes
 * red.** The second is the control: `AttachmentPicker`'s encrypting spinner is a *loading*
 * indicator, the one category of continuous motion this project allows, and collapsing its
 * duration to zero would spin it infinitely fast rather than stop it. A blanket sweep of `tween(`
 * → `lkTween(` across the module is therefore wrong, and this pins the exception.
 */
class ViewerChromeReducedMotionTest {

    private fun source(path: String): String = File("src/main/kotlin/com/localkeep/feature/files/$path").readText()

    @Test
    fun `every chrome animation in the media viewer collapses under reduced motion`() {
        val viewer = source("SecureMediaViewer.kt")

        val raw = Regex("""(?<![A-Za-z])tween\(LkMotion""").findAll(viewer).map { it.value }.toList()
        assertEquals(
            emptyList(), raw,
            "the viewer's chrome must animate through lkTween, which reads LocalLkReduceMotion; " +
                "raw tween ignores it: $raw",
        )

        // The control on the control: an assertion that only forbids `tween(` would also pass on a
        // file whose animations had been deleted outright.
        assertEquals(
            16,
            Regex(Regex.escape("lkTween(LkMotion")).findAll(viewer).count(),
            "three chrome/sheet surfaces animating a fade and a slide in each direction, plus the " +
                "hint pill's fade in and out, plus an image's top and bottom inset following the " +
                "chrome on and off (2026-10-09)",
        )
        assertTrue(
            "import com.localkeep.core.ui.theme.lkTween" in viewer,
            "and it has to be the shared helper, not a local one",
        )
    }

    @Test
    fun `the encrypting spinner is deliberately not collapsed`() {
        val picker = source("AttachmentPicker.kt")
        assertTrue(
            Regex("""infiniteRepeatable\(\s*tween\(LkMotion\.Draw""").containsMatchIn(picker),
            "a loading indicator is the one continuous motion this project allows, and a zero-length " +
                "tween inside infiniteRepeatable spins without bound rather than stopping",
        )
    }
}
