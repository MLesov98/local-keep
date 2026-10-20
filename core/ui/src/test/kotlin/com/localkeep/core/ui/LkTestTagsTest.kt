package com.localkeep.core.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The sweep's test tags must not reach a shipped build, and must not change layout in a debug one.
 *
 * Two properties, both of which a green build would happily hide:
 *
 * 1. **Release exposure.** `testTagsAsResourceId` is what publishes a tag to the accessibility
 *    tree — which is exactly how Maestro and `uiautomator` see it, and exactly how any
 *    accessibility service on a user's phone would. There is no need to argue about whether the
 *    app's internal control names matter: the sweep runs on debug, so the flag belongs on debug.
 * 2. **No wrapper node.** The sweep's entire method is comparing pixel bounds across locales. An
 *    extra layout node present only in debug builds would shift those bounds and quietly
 *    invalidate every measurement taken with it — including the ones already in the ledger.
 *
 * Source-level, because neither property is observable from a JVM test: the first needs a release
 * APK on a device, the second needs the composition. What can be pinned is the shape.
 */
class LkTestTagsTest {

    private val source: String =
        java.io.File("src/main/kotlin/com/localkeep/core/ui/LkTestTags.kt").readText()

    @Test
    fun theFlagIsGatedOnTheInstalledPackageBeingDebuggable() {
        assertTrue(
            "ApplicationInfo.FLAG_DEBUGGABLE" in source,
            "the gate must read the installed package's own flag, so a release APK is false at " +
                "runtime however it was assembled — not a build-time constant",
        )
        assertTrue(
            Regex("""if \(debuggable\) semantics \{ testTagsAsResourceId = true \} else this""")
                .containsMatchIn(source),
            "testTagsAsResourceId must be set only on the debuggable branch",
        )
    }

    @Test
    fun theHelperAddsNoLayoutNode() {
        // Either branch returning a container would put a node in debug builds and not in release,
        // which is the one difference that would corrupt the cross-locale bounds comparison.
        listOf("Box(", "Column(", "Row(").forEach {
            assertFalse(
                it in source,
                "$it introduces a layout node — the helper must only add a Modifier, or every " +
                    "pixel measurement the sweep takes is measured against a different tree",
            )
        }
    }
}
