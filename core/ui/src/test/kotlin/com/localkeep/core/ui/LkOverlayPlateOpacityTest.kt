package com.localkeep.core.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two floating overlays — [LkNoticeHost] and [ClipboardCountdown] — draw on an **opaque** plate.
 *
 * F-11, visual pass 2026-09-15. Both drew on `cardTop.copy(alpha = 0.96f)`, which looks like a
 * rounding error and is not one on a palette this dark. Decoded from `shots/041-quickcopy.png`:
 * the plate measured #11161A and the row underneath bled through at #1B2023 — a 1.10:1 separation,
 * numerically nothing and plainly legible on the device. The screenshot has a vault row's title and
 * a green Copy button readable *inside* "Password copied / Clipboard clears in 28s", which is a
 * confirmation you cannot read and a row you cannot tap, at the same time.
 *
 * A source scan rather than a render, for the reason [com.localkeep.core.ui.theme
 * .LkSearchFieldKeyboardTypeSourceScanTest] gives: the defect is a composited pixel, and proving it
 * from the composition tree means capturing a bitmap of two stacked screens, which needs a device.
 * What can be pinned here is the thing that was actually wrong — the alpha — and the fact that the
 * plate is now a colour [com.localkeep.core.ui.theme.LkContrastTest] already audits as a surface.
 *
 * The bottom bar's own `cardTop.copy(alpha = 0.92f)` in `AppShell` is deliberately *not* covered:
 * list content scrolling under it and fading into the ground is what makes it read as a floating
 * control, and nothing is ever legible through it because the gradient above it has already taken
 * the content to the ground colour. These two sit directly on top of a settled list instead.
 */
class LkOverlayPlateOpacityTest {

    private val plates = listOf(
        "LkNoticeHost.kt" to File("src/main/kotlin/com/localkeep/core/ui/LkNoticeHost.kt"),
        "ClipboardCountdown.kt" to File("src/main/kotlin/com/localkeep/core/ui/ClipboardCountdown.kt"),
    )

    @Test
    fun `neither floating overlay tints its plate with an alpha`() {
        plates.forEach { (name, file) ->
            assertTrue(file.exists(), "$name not found at ${file.absolutePath}")
            assertFalse(
                "c.cardTop.copy(alpha" in file.readText(),
                "$name draws its plate on a translucent cardTop. At 0.96 the row underneath " +
                    "composites to #1B2023 against a #12171A plate and reads straight through " +
                    "(F-11) — an overlay that announces something has to be the only thing legible " +
                    "where it is.",
            )
        }
    }

    @Test
    fun `both floating overlays draw on the audited surface colour`() {
        plates.forEach { (name, file) ->
            assertTrue(
                ".background(c.cardTop, LkShape.xxl)" in file.readText(),
                "$name no longer draws its plate on c.cardTop. The alpha check above is only " +
                    "half the statement: the point is that the plate is a colour LkContrastTest " +
                    "audits (textPrimary 15.87:1, textFaint 4.78:1), not merely that it is opaque.",
            )
        }
    }
}
