package com.localkeep.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * One protection that is on for every install, in the two lengths the app needs to state it.
 *
 * [summary] is the scannable line; [detail] is the paragraph behind a disclosure. Both live here
 * because two screens now make these claims — Settings' "How this works" and the Security tab — and
 * they are claims about cryptography and about what leaves the device. Two copies drift, and the
 * drift is not cosmetic: one screen would end up naming a cipher the app no longer uses.
 */
data class AlwaysOnFact(
    val title: String,
    val summary: String,
    val detail: String,
    /** Search terms for the Settings list — the words people use, not the ones we filed it under. */
    val keywords: String,
)

/**
 * The always-on protections, in the order they answer "is my vault actually safe here".
 *
 * Not settings: nothing here can be switched off, which is the point of it. Free and Pro installs
 * get all of it — no protection in this app has ever been behind the paywall, and the Security tab
 * needs to be able to say that without repeating the wording by hand.
 */
object AlwaysOn {
    // A composable property getter, not an eager `val`: the facts are now string resources, and
    // resolving them needs a composition. The access syntax at both call sites (`AlwaysOn.facts`)
    // is unchanged, so this stays inside core/ui — neither caller needed to change.
    val facts: List<AlwaysOnFact>
        @Composable get() = listOf(
            AlwaysOnFact(
                title = stringResource(R.string.core_ui_always_on_encryption_title),
                // Leads with what the reader wants to know and keeps the primitives named. The row
                // was just "Argon2id, then AES-256-GCM-SIV", which answers "which algorithms?" for
                // the few who can read it and nothing at all for everyone else — and the collapsed
                // summary is the line people actually scan. Dropping the names would have been the
                // wrong trade in the other direction: in a local-first security app they are the
                // evidence.
                summary = stringResource(R.string.core_ui_always_on_encryption_summary),
                detail = stringResource(R.string.core_ui_always_on_encryption_detail),
                keywords = "crypto argon2 aes gcm siv key derivation algorithm disclosure",
            ),
            AlwaysOnFact(
                title = stringResource(R.string.core_ui_always_on_throttling_title),
                summary = stringResource(R.string.core_ui_always_on_throttling_summary),
                detail = stringResource(R.string.core_ui_always_on_throttling_detail),
                keywords = "brute force attempts delay guessing lockout",
            ),
            AlwaysOnFact(
                title = stringResource(R.string.core_ui_always_on_phishing_title),
                summary = stringResource(R.string.core_ui_always_on_phishing_summary),
                detail = stringResource(R.string.core_ui_always_on_phishing_detail),
                keywords = "phishing domain mismatch confirm untrusted app signature",
            ),
            AlwaysOnFact(
                title = stringResource(R.string.core_ui_always_on_screenblock_title),
                summary = stringResource(R.string.core_ui_always_on_screenblock_summary),
                detail = stringResource(R.string.core_ui_always_on_screenblock_detail),
                keywords = "screenshot screen record recents preview flag secure privacy",
            ),
            AlwaysOnFact(
                title = stringResource(R.string.core_ui_always_on_leaves_device_title),
                summary = stringResource(R.string.core_ui_always_on_leaves_device_summary),
                detail = stringResource(R.string.core_ui_always_on_leaves_device_detail),
                keywords = "privacy network telemetry clipboard crash reports data",
            ),
            // Last, and deliberately so: the only entry here that describes a limit rather than a
            // protection, and it answers the question the five above raise — what if the phone
            // itself is not trustworthy?
            //
            // It exists because the app already ships a root check ([RootCheck], surfaced as a
            // one-time banner by MainActivity) and said nothing about it anywhere a user could go
            // looking. That is a gap in the opposite direction from the usual one: the detector is
            // four `su` paths and a build tag, which any root-hiding tool defeats, so someone who
            // never saw the banner could reasonably conclude the app had checked and found nothing.
            // `THREAT-MODEL.md` puts a root-level compromise (T3) out of scope in writing; this is
            // that boundary said out loud, on the screen where the user asks what protects them.
            //
            // "Always on" is still the honest heading for it. The check runs on every install, is
            // not a setting, and is not behind Pro — the same three things the heading asserts of
            // everything else in this list.
            AlwaysOnFact(
                title = stringResource(R.string.core_ui_always_on_root_title),
                summary = stringResource(R.string.core_ui_always_on_root_summary),
                detail = stringResource(R.string.core_ui_always_on_root_detail),
                keywords = "root rooted jailbreak magisk tamper modified rom integrity attestation",
            ),
        )
}
