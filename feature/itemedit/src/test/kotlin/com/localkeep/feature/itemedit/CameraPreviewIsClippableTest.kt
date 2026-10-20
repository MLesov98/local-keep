package com.localkeep.feature.itemedit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every camera preview in the app must be one an ancestor can clip.
 *
 * `PreviewView` defaults to `ImplementationMode.PERFORMANCE`, which draws the frames onto a
 * `SurfaceView` — composited by the system in a layer of its own, outside the app's view drawing,
 * where no ancestor clip reaches it. A preview put inside a rounded card then draws as a
 * hard-cornered rectangle spilling past it, and no amount of `Modifier.clip` on the Compose side
 * changes that. Reported from a phone on 2026-08-21 with a screenshot of the QR dialog, the frames
 * visibly wider than the surface holding them.
 *
 * `COMPATIBLE` draws onto a `TextureView`, an ordinary view inside the app's hierarchy that clips
 * like anything else. It is one line, it is easy to leave out of the next viewfinder, and leaving it
 * out fails only on a real camera — never in a unit test, never in a preview, and not visibly in a
 * screenshot of a full-screen viewfinder. So the assertion is made against the source itself.
 *
 * Read as text rather than by composing: constructing a `PreviewView` needs an Android context and a
 * camera provider, and this is a statement about what the code says, which is exactly the thing that
 * regresses.
 */
class CameraPreviewIsClippableTest {

    /**
     * Anchored on `settings.gradle.kts` rather than a fixed number of `..` segments, because
     * Gradle's default test working directory is the *module* directory and this repo has modules
     * at more than one depth (`feature/itemedit`, `core/ui`, `app`). A depth-counting relative path
     * silently stops finding files the day a module moves; this does not.
     */
    private fun repoRoot(): File {
        var dir = File(".").absoluteFile
        while (true) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile ?: fail("cannot locate the repo root (no settings.gradle.kts above ${File(".").absoluteFile})")
        }
    }

    private fun sourceOf(relativePath: String): String {
        val file = File(repoRoot(), relativePath)
        if (!file.exists()) fail("cannot find $relativePath — looked at ${file.absolutePath}")
        return file.readText()
    }

    private fun assertClippable(label: String, source: String) {
        assertTrue(
            "PreviewView.ImplementationMode.COMPATIBLE" in source,
            "$label builds a PreviewView without ImplementationMode.COMPATIBLE, so its frames land " +
                "on a SurfaceView and no parent can clip them",
        )
        assertTrue(
            "PreviewView.ScaleType.FILL_CENTER" in source,
            "$label does not state FILL_CENTER, so the preview's fit depends on the sensor's " +
                "aspect ratio rather than on the frame it was given",
        )
    }

    @Test
    fun `the QR scanner's preview can be clipped by its card`() {
        // Moved from :feature:itemedit to :core:ui on 2026-09-10 so the Recovery Kit unlock flow
        // could reuse it — see QrScanner.kt's own header comment.
        assertClippable("QrScanner", sourceOf("core/ui/src/main/kotlin/com/localkeep/core/ui/QrScanner.kt"))
    }

    @Test
    fun `the file capture's preview can be clipped by its card`() {
        assertClippable(
            "CameraCapture",
            sourceOf("feature/files/src/main/kotlin/com/localkeep/feature/files/CameraCapture.kt"),
        )
    }

    @Test
    fun `no camera preview anywhere is left on the default implementation mode`() {
        // The two above are the previews that exist today. This catches a third being added without
        // the line, which is the actual way this comes back. Walks the whole repo, not just
        // `feature/`, since the QR scanner itself now lives under `core/ui`.
        val offenders = repoRoot().walkTopDown()
            // Pruned, not just filtered: a build/ or .git directory here is large enough that
            // merely filtering its contents out afterward — rather than never descending into it —
            // is the difference between this test taking a second and taking minutes.
            .onEnter { it.name != "build" && it.name != ".git" }
            .filter { it.isFile && it.extension == "kt" }
            .filter { "PreviewView(" in it.readText() }
            .filterNot { "PreviewView.ImplementationMode.COMPATIBLE" in it.readText() }
            .map { it.path }
            .toList()
        assertTrue(
            offenders.isEmpty(),
            "these build a PreviewView on the unclippable default mode: $offenders",
        )
    }
}
