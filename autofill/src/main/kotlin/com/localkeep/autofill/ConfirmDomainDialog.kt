package com.localkeep.autofill

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.localkeep.core.ui.theme.LkDialog
import com.localkeep.core.ui.theme.LkDialogAction
import com.localkeep.core.ui.theme.LkIcons

/**
 * "Allow <app> to fill saved logins for <site>?" — the one-time browser-package/domain trust gate.
 *
 * This is the app's most phishing-sensitive prompt, and it was the one that looked least like the
 * app. Until 2026-09-20 it was a platform `LinearLayout` of two `TextView`s and two `Button`s: no
 * card, no icon, no accent, ALLOW and DENY full-bleed grey, and every node dumping as
 * `bounds="[0,…][1080,…]"` with the text touching both screen edges — the 24dp padding the source
 * set never reached the glass. The passkey binding gate two screens away had already been rebuilt
 * as an `LkDialog` in wave 3 (`NativeBindingDialog`), so the two consents that decide who may speak
 * for a site did not look like they came from the same app. 2C-F12.
 *
 * Same treatment, same reasoning: its own composable rather than an expression inside
 * [ConfirmDomainActivity.onCreate], so what it offers can be rendered and read without an autofill
 * request, a browser, or a pinned signing certificate. See `ConfirmDomainDialogTest`.
 *
 * [onDeny] is every answer that is not Allow — the Deny button, Back, and a tap outside. It was one
 * answer when this was two platform buttons over a `finish()`, and it stays one here: a trust
 * decision nobody made is a trust decision refused.
 *
 * [LkIcons.Globe] is the glyph because it already means "a website" on every login row, which is
 * exactly what the second half of this pairing is.
 *
 * The strings are untouched — both carry `SECURITY-COPY: tier C` and have been through that
 * protocol; this changes what draws them, not what they say.
 */
@Composable
internal fun ConfirmDomainDialog(
    packageName: String,
    webDomain: String,
    working: Boolean,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
) {
    LkDialog(
        onDismiss = onDeny,
        title = stringResource(R.string.autofill_confirm_domain_message, packageName, webDomain),
        body = stringResource(R.string.autofill_confirm_domain_explanation),
        icon = LkIcons.Globe,
        confirm = LkDialogAction(
            label = stringResource(R.string.autofill_confirm_domain_allow),
            onClick = onAllow,
            // The old Allow button disabled itself on the first tap, because everything behind it —
            // pinning the signing certificate, confirming the domain, rebuilding the response —
            // happens in a coroutine and a second tap would start it again.
            enabled = !working,
        ),
        dismissLabel = stringResource(R.string.autofill_confirm_domain_deny),
    )
}
