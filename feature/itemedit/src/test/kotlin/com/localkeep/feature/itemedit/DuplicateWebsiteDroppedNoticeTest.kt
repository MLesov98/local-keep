package com.localkeep.feature.itemedit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A website that already exists on a Login must not vanish from Save with nothing said.
 *
 * Found in the 2026-09-09 functional sweep: `ItemEditUiState.toContent()`'s LOGIN branch builds
 * `Login.uris` with `.distinct()` after trimming and blank-filtering — added, per the comment beside
 * it, so a repeated URI does not produce two identical rows. That call is correct; the silence
 * around it is not. Type (or paste) a website already in the list, press Save, and the duplicate is
 * gone with no sign it was ever there — indistinguishable, from the user's side, from the save
 * having dropped it by mistake.
 *
 * The fix computes the same count `save()` needs (trimmed, non-blank, minus its own `.distinct()`)
 * ahead of the write, and posts it through [LkNotices] — the same channel `itemedit_folder_absent
 * _notice` and `itemedit_saved_into_trash_notice` use for exactly this reason: information the
 * Saved screen that follows a successful save does not itself carry. Posted only in `onSuccess`,
 * after the write has actually landed, never at `toContent()` time — a save that fails for some
 * other reason must not claim a drop that was never written.
 *
 * Source scan, not a unit test on `save()` directly: this module has no Robolectric wiring and
 * `ItemEditViewModel` cannot be constructed on a JVM at all (its `VaultRepository` needs a
 * `VaultLockManager` whose `KeystoreManager` opens AndroidKeyStore in its constructor) — the same
 * reason `IdentityPhotoRouteEntitlementTest` is one. Each claim is paired with a control that held
 * before the fix.
 */
class DuplicateWebsiteDroppedNoticeTest {

    private fun saveBody(): String {
        val src = File("src/main/kotlin/com/localkeep/feature/itemedit/ItemEditViewModel.kt")
        assertTrue(src.exists(), "ItemEditViewModel.kt not found at ${src.absolutePath}")
        val text = src.readText()
        val start = text.indexOf("fun save() {")
        assertTrue(start > 0, "save() not found — did it get renamed?")
        val end = text.indexOf("fun seedFolderFromPending()", start)
        assertTrue(end > start, "seedFolderFromPending( not found after save() — region unbounded")
        return text.substring(start, end)
    }

    @Test
    fun `save counts the websites its own dedup will drop`() {
        val body = saveBody()
        assertTrue(
            "duplicateWebsitesDropped" in body,
            "save() no longer computes a duplicate-website count — a repeated URI is dropped by " +
                "toContent()'s .distinct() with nothing said about it: $body",
        )
        assertTrue(
            "trimmed.distinct()" in body,
            "the duplicate count is no longer derived from the same trim-then-distinct toContent() " +
                "itself applies — a count computed a different way could disagree with what was " +
                "actually written",
        )
    }

    @Test
    fun `a positive count posts a counted notice`() {
        val body = saveBody()
        assertTrue(
            "R.plurals.itemedit_notice_duplicate_website_dropped" in body,
            "save() no longer posts itemedit_notice_duplicate_website_dropped for a positive count",
        )
        assertTrue(
            "LkNotices.Message.Plural(" in body,
            "the duplicate-website notice is not posted as a counted LkNotices.Message.Plural — " +
                "the dropped count would not be a true, localized claim",
        )
    }

    @Test
    fun `the notice is posted only after the write actually succeeds`() {
        val body = saveBody()
        val computedAt = body.indexOf("val duplicateWebsitesDropped")
        val launchAt = body.indexOf("viewModelScope.launch")
        val onSuccessAt = body.indexOf(".onSuccess {")
        val postedAt = body.indexOf("R.plurals.itemedit_notice_duplicate_website_dropped")
        assertTrue(computedAt > 0, "duplicateWebsitesDropped is not computed in save()")
        assertTrue(launchAt > computedAt, "viewModelScope.launch not found after the count — region wrong?")
        assertTrue(onSuccessAt > launchAt, ".onSuccess { not found after the launch — region wrong?")
        assertTrue(
            postedAt > onSuccessAt,
            "the duplicate-website notice is posted before onSuccess — a save that fails after " +
                "toContent() would then claim a drop that was never written to the vault",
        )
    }

    /**
     * The control: `toContent()`'s own dedup — the thing actually doing the dropping — must still
     * be there and still apply to the LOGIN uris. True before this fix and must stay true after it;
     * a change that removed the dedup itself (rather than merely leaving it unannounced) would make
     * this notice describe a drop that no longer happens.
     */
    @Test
    fun `toContent still deduplicates a Login's uris`() {
        val src = File("src/main/kotlin/com/localkeep/feature/itemedit/ItemEditViewModel.kt")
        val text = src.readText()
        val start = text.indexOf("private fun ItemEditUiState.toContent()")
        assertTrue(start > 0, "toContent() not found — did it get renamed?")
        val end = text.indexOf("ItemEditUiState.ItemType.NOTE ->", start)
        assertTrue(end > start, "the NOTE branch was not found after toContent's LOGIN branch — region unbounded")
        val loginBranch = text.substring(start, end)
        assertTrue(
            ".filter(String::isNotEmpty)" in loginBranch && ".distinct()" in loginBranch,
            "toContent's LOGIN branch no longer trims, filters and deduplicates uris: $loginBranch",
        )
    }
}
