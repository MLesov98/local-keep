package com.localkeep.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localkeep.core.vault.TrustedApp
import com.localkeep.core.ui.theme.LkActionEmphasis
import com.localkeep.core.ui.theme.LkDialog
import com.localkeep.core.ui.theme.LkDialogAction
import com.localkeep.core.ui.theme.LkEmptyState
import com.localkeep.core.ui.theme.LkFrame
import com.localkeep.core.ui.theme.LkGroup
import com.localkeep.core.ui.theme.LkGroupRow
import com.localkeep.core.ui.theme.LkIconButton
import com.localkeep.core.ui.theme.LkIcons
import com.localkeep.core.ui.theme.LkScreen
import com.localkeep.core.ui.theme.LkScreenHeader
import com.localkeep.core.ui.theme.LkText
import com.localkeep.core.ui.theme.LkTheme
import androidx.compose.material3.Text

/**
 * Trusted-apps management (UI/UX spec §10.1): the apps and sites the user has allowed autofill to
 * fill, each removable, plus the apps whose signature autofill has remembered without being allowed
 * anything (see [trustedAppCaptionRes]). A grant removed here is re-confirmed the next time autofill offers it, so
 * removal is reversible-by-use rather than destructive of anything the user cannot get back — which
 * is why a single site row removes without a prompt, and only forgetting a whole app (which also
 * drops its pinned signing identity) asks first.
 */
/**
 * Names the Settings row that opens this screen, for the device sweeps.
 *
 * Mirrors `HIDDEN_ITEMS_TEST_TAG`, and exists for the same reason: this row is the only way in, it
 * sits well down a long data-driven list, and a sweep hunting for its visible label has to scroll
 * speculatively — which cost minutes per pass and still missed it, leaving the screen unmeasured at
 * every font scale. Read only by `tools/fontscale_walk.py`, and exposed only on debuggable builds
 * (see `lkExposeTestTags`).
 */
const val TRUSTED_APPS_TEST_TAG = "settings-trusted-apps"

@Composable
fun TrustedAppsScreen(
    viewModel: TrustedAppsViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val c = LkTheme.colors
    // The package name, not the object: `rememberSaveable` writes to the instance-state Bundle and
    // `TrustedApp` is not parcelable. Saveable rather than `remember` because this dialog was the
    // odd one out — the item "Move to trash?" and "Restore this snapshot?" confirmations both
    // survive a rotation and this one was silently dismissed by it (device, 2026-09-20).
    var pendingForgetPackage by rememberSaveable { mutableStateOf<String?>(null) }

    // The store is read as a snapshot, so a grant made in a browser while this screen sat in the
    // background was never picked up. This is when the user comes back from that browser.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshOnResume()
        onPauseOrDispose {}
    }

    LkScreen(accent = c.jade, auraAlpha = 0.07f, auraBias = 0.5f) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LkFrame.screenHorizontal)
                .padding(top = LkFrame.screenTop, bottom = LkFrame.gutterPlain),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LkScreenHeader(
                title = stringResource(R.string.settings_trusted_title),
                subtitle = stringResource(R.string.trusted_apps_subtitle),
                // Trailing, matching the eight other LkScreenHeader back chevrons (Trash,
                // Settings, Backup, Import, Recovery Kit, item edit, file edit, attachments).
                // This screen put it in the leading slot, so Back physically swapped sides
                // depending on which screen you were leaving. Visual sweep, 2026-09-01.
                trailing = {
                    LkIconButton(
                        icon = LkIcons.ChevronLeft,
                        label = stringResource(R.string.trusted_apps_back),
                        onClick = onBack,
                        plateAlignment = Alignment.CenterEnd,
                    )
                },
            )

            if (!state.loading && state.apps.isEmpty()) {
                LkEmptyState(
                    icon = LkIcons.Shield,
                    title = stringResource(R.string.trusted_apps_empty_title),
                    body = stringResource(R.string.trusted_apps_empty_body),
                    modifier = Modifier.padding(top = 48.dp),
                )
            } else {
                state.apps.forEach { app ->
                    TrustedAppCard(
                        app = app,
                        onRemoveDomain = { domain ->
                            // Removing an app's *last* site removes the app, pinned signing
                            // identity and all — the same act the trash button asks about, and it
                            // used to happen on one tap with no prompt at all. Same question,
                            // whichever control asks it.
                            if (app.domains.size == 1) {
                                pendingForgetPackage = app.packageName
                            } else {
                                viewModel.forgetDomain(app.packageName, domain)
                            }
                        },
                        onForgetApp = { pendingForgetPackage = app.packageName },
                    )
                }
            }
        }
    }

    val forgetting = state.apps.firstOrNull { it.packageName == pendingForgetPackage }
    if (forgetting != null) {
        LkDialog(
            onDismiss = { pendingForgetPackage = null },
            title = stringResource(R.string.trusted_apps_forget_title, forgetting.label),
            body = stringResource(R.string.trusted_apps_forget_body),
            icon = LkIcons.Warning,
            accent = c.coral,
            confirm = LkDialogAction(
                label = stringResource(R.string.trusted_apps_forget_action),
                onClick = {
                    viewModel.forgetApp(forgetting.packageName)
                    pendingForgetPackage = null
                },
                emphasis = LkActionEmphasis.Destructive,
            ),
        )
    }
}

/**
 * The line a card shows in place of a site list, or null when it lists sites instead.
 *
 * Two kinds of entry share this screen, and only one of them is trust the user gave. A site row
 * exists because the user tapped Allow on `ConfirmDomainActivity` for that browser and site. A card
 * with no sites is a signing-certificate pin and nothing more: autofill writes it the moment an app
 * with a matching saved login asks to be filled, before the user has chosen anything, so that the
 * signer is fixed before any credential can reach it. The store keeps no record of whether a fill
 * then happened, so this line says what is known (the signature is remembered) and never calls the
 * app trusted. Separated from the composable so the choice can be tested without a Compose harness.
 */
internal fun trustedAppCaptionRes(app: TrustedApp): Int? =
    if (app.domains.isEmpty()) R.string.trusted_apps_no_sites else null

@Composable
private fun TrustedAppCard(
    app: TrustedApp,
    onRemoveDomain: (String) -> Unit,
    onForgetApp: () -> Unit,
) {
    val c = LkTheme.colors
    LkGroup {
        LkGroupRow(first = true) {
            // The package beneath the label, because the label is the app's own claim about
            // itself: two installed apps can both call themselves "Chrome", and this is the one
            // screen built to review which of them autofill trusts. The grant prompt already
            // names the package; the list that reviews those grants did not.
            Column(Modifier.weight(1f)) {
                Text(
                    app.label,
                    style = LkText.body,
                    color = c.textStrong,
                )
                Text(
                    app.packageName,
                    style = LkText.caption,
                    color = c.textFaint,
                )
            }
            LkIconButton(
                icon = LkIcons.Trash,
                label = stringResource(R.string.trusted_apps_forget_app_label, app.label),
                onClick = onForgetApp,
                tint = c.coral,
            )
        }
        val caption = trustedAppCaptionRes(app)
        if (caption != null) {
            LkGroupRow(first = false) {
                Text(
                    stringResource(caption),
                    style = LkText.bodyQuiet,
                    color = c.textMuted,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            app.domains.forEach { domain ->
                LkGroupRow(first = false) {
                    Text(
                        domain,
                        style = LkText.bodyQuiet,
                        color = c.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    LkIconButton(
                        icon = LkIcons.Close,
                        label = stringResource(R.string.trusted_apps_remove_site, domain),
                        onClick = { onRemoveDomain(domain) },
                    )
                }
            }
        }
    }
}
