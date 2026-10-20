package com.localkeep.feature.health

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localkeep.core.health.SecurityDashboard
import com.localkeep.core.ui.AlwaysOn
import com.localkeep.core.ui.labelled
import com.localkeep.core.ui.lkTag
import com.localkeep.core.ui.theme.LkColors
import com.localkeep.core.ui.theme.LkDialog
import com.localkeep.core.ui.theme.LkFootnote
import com.localkeep.core.ui.theme.LkFrame
import com.localkeep.core.ui.theme.LkGroup
import com.localkeep.core.ui.theme.LkGroupRow
import com.localkeep.core.ui.theme.LkIconButton
import com.localkeep.core.ui.theme.LkIcons
import com.localkeep.core.ui.announce
import com.localkeep.core.ui.theme.LkInlineError
import com.localkeep.core.ui.theme.LkInlineNote
import com.localkeep.core.ui.theme.LkLiftedCard
import com.localkeep.core.ui.theme.LkProgressRing
import com.localkeep.core.ui.theme.LkPulseHalo
import com.localkeep.core.ui.theme.LkScreen
import com.localkeep.core.ui.theme.LkSearchField
import com.localkeep.core.ui.theme.LkLockButton
import com.localkeep.core.ui.theme.LkScreenHeader
import com.localkeep.core.ui.theme.LkShape
import com.localkeep.core.ui.theme.LkText
import com.localkeep.core.ui.theme.LkTheme
import com.localkeep.core.ui.theme.lkEnter
import com.localkeep.core.ui.theme.lkFlooredPercent
import com.localkeep.core.ui.theme.lkPressScale
import com.localkeep.core.ui.theme.lkScrollEdges
import com.localkeep.core.ui.theme.lkStaggerDelay
import com.localkeep.core.ui.theme.lkSurface

/**
 * The security dashboard.
 *
 * Two rules shape it. First, the score is a *summary*, never the answer — it sits beside a sentence
 * naming what to fix first, because "78" on its own tells nobody what to do. Second, every category
 * carries its count as a number inside the coloured plate, so the severity ordering survives for a
 * user who cannot distinguish coral from amber (spec §2).
 */
@Composable
fun HealthDashboardScreen(
    viewModel: HealthDashboardViewModel,
    onItemClick: (String) -> Unit,
    /**
     * Raises the command drawer. Defaults to a no-op so the screen still stands alone in a preview
     * or a test.
     */
    onOpenDrawer: () -> Unit = {},
) {
    val c = LkTheme.colors
    val state by viewModel.state.collectAsStateWithLifecycle()

    // The same 480dp threshold the vault list and the unlock screen use, so the app agrees with
    // itself about what "short" means. Material's own compact-height breakpoint: no phone in
    // portrait and no tablet in any orientation crosses it.
    val shortWindow = LocalConfiguration.current.screenHeightDp < 480
    val scroll = rememberScrollState()

    LkScreen(accent = c.jade, auraAlpha = 0.08f, auraBias = 0.5f) {
        Column(Modifier.fillMaxSize()) {
            // Outside the scroller: the header carries Lock now, and the one control that has
            // to be reachable from anywhere in a password manager cannot be above the fold.
            LkScreenHeader(
                title = stringResource(R.string.health_screen_title),
                // Dropped in a short window, exactly as the vault list drops its count line. In
                // landscape at font_scale 2.0 the fixed chrome — header, bottom bar, system bars —
                // left this screen a viewport that held a clipped score ring and nothing else: no
                // verdict, no advice, no category rows, no footnote. The subtitle is the one line
                // here that restates what the score ring says in words, so it is the one that can
                // go. Found by the F07 UX persona, 2026-08-29.
                subtitle = stringResource(R.string.health_screen_subtitle).takeIf { !shortWindow },
                modifier = Modifier
                    .lkEnter()
                    .padding(horizontal = LkFrame.screenHorizontal)
                    .padding(top = LkFrame.screenTop),
                // Every top-level tab reaches the drawer from its own header. Only the vault
                // list carried this button, so from Generator and Health the drawer existed but
                // had no visible way in - an edge swipe is not an affordance, it is a secret.
                leading = {
                    LkIconButton(
                        icon = LkIcons.Menu,
                        label = stringResource(com.localkeep.core.ui.R.string.core_ui_menu_open),
                        onClick = onOpenDrawer,
                        modifier = Modifier.lkTag("nav-open-drawer"),
                        plateAlignment = Alignment.CenterStart,
                    )
                },
                trailing = {
                    // See GeneratorScreen: a tab destination has no back stack, and every
                    // top-level tab now carries the same lock control in the same place.
                    LkLockButton()
                },
            )

            Column(
                Modifier
                    // weight, not fillMaxSize: in a Column, fillMaxSize takes the *parent's* height
                    // rather than what is left after the header, so the header got squeezed and its
                    // subtitle rendered 17px of a 40px line.
                    .weight(1f)
                    .fillMaxWidth()
                    // The fold, made visible. Rotated at the largest text size the five category
                    // rows scrolled past between one swipe and the next with nothing on screen to
                    // say they had been there — the tree went from a clipped score ring straight to
                    // the Always-on list. Outside the scroll: the fade belongs to the viewport.
                    .lkScrollEdges(
                        moreAbove = scroll.canScrollBackward,
                        moreBelow = scroll.canScrollForward,
                        color = c.ground,
                    )
                    .verticalScroll(scroll)
                    .padding(horizontal = LkFrame.screenHorizontal)
                    // 20dp, matching the arrangement below: the header used to be this
                    // scroller's first child and took its top spacing from there.
                    .padding(top = 20.dp, bottom = LkFrame.gutterFab),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                when {
                    state.loading -> ScanningCard(state.scanProgress)
                    state.error != null -> {
                        val message = stringResource(state.error!!)
                        LkInlineError(message, Modifier.announce(message))
                    }
                    else -> {
                        val verdict = state.verdictRes?.let { stringResource(it) }
                            ?: pluralStringResource(
                                state.faultVerdictPluralRes,
                                state.affectedCount,
                                state.affectedCount,
                            )
                        ScoreCard(
                            // `passwordsChecked`, not `scannedCount`: a vault of password-less
                            // Logins is scanned but nothing in it was ever scored, and a ring
                            // reading 100 there is a clean bill of health nobody earned.
                            score = state.score.takeIf { state.passwordsChecked > 0 },
                            verdict = verdict,
                            advice = state.advicePluralRes
                                ?.let { pluralStringResource(it, state.breachedCount, state.breachedCount) }
                                ?: stringResource(requireNotNull(state.adviceRes)),
                            // The scan runs on screen entry and re-runs on every save, then swaps this
                            // card in for the ScanningCard with no announcement — the screen's whole
                            // deliverable arrived silently. A polite live region on the result fires
                            // when it appears, so a screen-reader user learns the scan finished and
                            // hears the verdict. (Verdict only: the ring already labels the score.)
                            modifier = Modifier
                                .lkEnter(delayMillis = 40)
                                .announce(verdict),
                        )

                        // The qualification the card above cannot carry. Its advice line reads
                        // "Every password here is unique, not weak, and not in the offline breach
                        // set", and with entries concealed that sentence is true of what was
                        // scanned and false of the vault — measured on a device with seven entries
                        // invisible, where the screen read 100 / "Nothing worth fixing" against 88
                        // with two reused, one weak and one compromised while visible (3A-F33).
                        // The Invisible-items screen discloses the trade going in; this is where
                        // the claim is made.
                        //
                        // Amber and not coral: nothing has gone wrong and the user asked for this.
                        // [LkIcons.Info] rather than [LkIcons.EyeOff], which is reserved for
                        // unmasking a secret. Below the card rather than inside it, so a full-width
                        // sentence does not come out of the prose column beside the ring.
                        if (state.excludedCount > 0) {
                            val excludedNote = pluralStringResource(
                                R.plurals.health_excluded_note,
                                state.excludedCount,
                                state.excludedCount,
                            )
                            LkInlineNote(
                                excludedNote,
                                Modifier.lkEnter(delayMillis = 60).announce(excludedNote),
                                accent = c.amber,
                                icon = LkIcons.Info,
                            )
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            val reusedLabel = stringResource(R.string.health_category_reused_label)
                            val reusedNote = stringResource(R.string.health_category_reused_note)
                            val weakLabel = stringResource(R.string.health_category_weak_label)
                            val weakNote = stringResource(R.string.health_category_weak_note)
                            val breachedLabel = stringResource(R.string.health_category_breached_label)
                            val breachedNote = stringResource(R.string.health_category_breached_note)
                            val oldLabel = stringResource(R.string.health_category_old_label)
                            val oldNote = stringResource(R.string.health_category_old_note)
                            val duplicateLabel = stringResource(R.string.health_category_duplicate_label)
                            val duplicateNote = stringResource(R.string.health_category_duplicate_note)
                            val categories = buildList {
                                add(
                                    HealthCategory(
                                        SecurityDashboard.Category.REUSED,
                                        reusedLabel,
                                        state.reusedCount,
                                        reusedNote,
                                        categoryTone(SecurityDashboard.Category.REUSED, c, state.reusedCount),
                                    ),
                                )
                                add(
                                    HealthCategory(
                                        SecurityDashboard.Category.WEAK,
                                        weakLabel,
                                        state.weakCount,
                                        weakNote,
                                        categoryTone(SecurityDashboard.Category.WEAK, c, state.weakCount),
                                    ),
                                )
                                // A corpus that failed to open or parse hides the card entirely, rather
                                // than showing a permanently-zero count that looks like a scan result.
                                if (state.breachDataAvailable) {
                                    add(
                                        HealthCategory(
                                            SecurityDashboard.Category.BREACHED,
                                            breachedLabel,
                                            state.breachedCount,
                                            breachedNote,
                                            categoryTone(SecurityDashboard.Category.BREACHED, c, state.breachedCount),
                                        ),
                                    )
                                }
                                add(
                                    HealthCategory(
                                        SecurityDashboard.Category.OLD,
                                        oldLabel,
                                        state.oldCount,
                                        oldNote,
                                        categoryTone(SecurityDashboard.Category.OLD, c, state.oldCount),
                                    ),
                                )
                                // Last, because nothing here is wrong with a password. Its place in the
                                // list is the claim: everything above is a security finding, this one is
                                // housekeeping that turns into a security finding later.
                                add(
                                    HealthCategory(
                                        SecurityDashboard.Category.DUPLICATE,
                                        duplicateLabel,
                                        state.duplicateCount,
                                        duplicateNote,
                                        categoryTone(SecurityDashboard.Category.DUPLICATE, c, state.duplicateCount),
                                    ),
                                )
                            }
                            categories.forEachIndexed { index, category ->
                                CategoryRow(
                                    category = category,
                                    modifier = Modifier.lkEnter(delayMillis = 80 + lkStaggerDelay(index)),
                                    onClick = { viewModel.selectCategory(category.key) },
                                )
                            }
                        }

                        // The false-positive rate, in the UI rather than only in a code comment (spec
                        // §12.7: "pick and disclose the FPR"). The filter is probabilistic by
                        // construction, and a user deciding whether a hit is worth acting on cannot
                        // weigh "potentially" without a number: across fifty passwords, roughly one
                        // flag is expected to be wrong.
                        val locale = LocalConfiguration.current.locales[0]
                        // Derived at composition, not in a handler — `LocalContextGetResourceValueCall`
                        // is an error in this project.
                        val corpusSize = remember(state.breachCorpusEntries, locale) {
                            BreachCorpusFootnote.formatCorpusSize(state.breachCorpusEntries, locale)
                        }
                        LkFootnote(
                            // The dated variant of this footnote is gone from the dashboard. It read
                            // "…9,995,050 known-breached passwords, from July 2026", and the month was
                            // working against the app twice over: it is the second machine-precision
                            // figure in a sentence that already opens with one, and it decays — the
                            // list is a static bundled asset, so someone installing next spring reads
                            // "July 2026" as a neglected feed rather than as a top-10M corpus whose
                            // value barely changes with age.
                            //
                            // Nothing is hidden by dropping it: Settings → Breach list still shows the
                            // full vintage verbatim (SettingsScreen's BreachVintage.Available row), so
                            // provenance stays one tap away for anyone who wants it, off the screen
                            // that is trying to answer "are my passwords OK?".
                            //
                            // Which of the three variants is state.footnoteRes's call, not this
                            // composable's — a loaded-but-unconsulted corpus (fresh vault, or Logins
                            // with no password) must say what the corpus IS, not that it ran. The
                            // unavailable variant takes no placeholder; the unused corpusSize argument
                            // is harmless (Resources.getString ignores a formatArg the string omits).
                            text = stringResource(state.footnoteRes, corpusSize),
                            modifier = Modifier.lkEnter(delayMillis = 200),
                        )

                        // Last here, first on the locked screen: with a scan to read, the findings are
                        // what the user came for. A Pro install should not be told *less* about how its
                        // vault is protected than a free one.
                        AlwaysOnList(modifier = Modifier.lkEnter(delayMillis = 240))
                    }
                }
            }
        }
    }

    val selected = state.selectedCategory
    if (selected != null) {
        // A query can narrow a non-empty category to zero rows, which is a different state from
        // the category itself being empty — the empty-category copy would tell the user nothing
        // is wrong here when the real story is "your search missed everything".
        val searching = state.categoryQuery.isNotBlank()
        LkDialog(
            onDismiss = { viewModel.clearSelection() },
            title = categoryLabel(selected),
            body = if (state.filteredItems.isEmpty()) {
                stringResource(
                    if (searching) R.string.health_dialog_no_matches else R.string.health_dialog_empty_category,
                )
            } else {
                null
            },
            icon = LkIcons.ShieldCheck,
            // The dialog opens on a category the user tapped, so it is showing that category's
            // members — the count that matters here is what is in the list, not the dashboard's
            // total. An empty list is the same "nothing found" case the rows use.
            accent = categoryTone(selected, c, state.filteredItems.size),
            dismissLabel = stringResource(R.string.health_dialog_close),
        ) {
            // Only once there is enough here to be worth narrowing; a search field over a
            // handful of rows is furniture. Gated on the unfiltered count, like TrashScreen's
            // SEARCH_THRESHOLD, so typing a query that matches nothing does not make the field
            // that would let the user fix the query disappear along with the list.
            if (state.categoryItems.size > SEARCH_THRESHOLD) {
                LkSearchField(
                    value = state.categoryQuery,
                    onValueChange = viewModel::onCategoryQueryChanged,
                    placeholder = stringResource(R.string.health_dialog_search_placeholder),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (state.filteredItems.isNotEmpty()) {
                // Capped rather than free-scrolling to the screen edge: the dialog has to stay a
                // dialog, and a list that fills the viewport should have been a screen.
                val untitled = stringResource(R.string.health_item_untitled)
                LkGroup {
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        itemsIndexed(state.filteredRows, key = { _, row -> row.item.id.toString() }) { index, row ->
                            LkGroupRow(
                                first = index == 0,
                                onClick = {
                                    viewModel.clearSelection()
                                    onItemClick(row.item.id.toString())
                                },
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(
                                        row.item.title.ifBlank { untitled },
                                        style = LkText.rowTitle,
                                        color = c.textPrimary,
                                    )
                                    // F-30: two rows routinely share a title with nothing to tell them
                                    // apart. The username disambiguates, or the saved date where the
                                    // entry has none; the shared-count note (Reused only) says which
                                    // password they have in common.
                                    val savedLine = row.savedAt?.let {
                                        stringResource(R.string.health_row_saved_on, formatSavedDate(it))
                                    }
                                    if (row.secondary != null || savedLine != null || row.sharedWithOthersCount != null) {
                                        Text(
                                            listOfNotNull(
                                                row.secondary,
                                                savedLine,
                                                row.sharedWithOthersCount?.let {
                                                    pluralStringResource(R.plurals.health_dialog_reused_shared_with, it, it)
                                                },
                                            ).joinToString(" · "),
                                            style = LkText.subtitle,
                                            color = c.textFaint,
                                        )
                                    }
                                }
                                Icon(
                                    LkIcons.ChevronRight,
                                    contentDescription = null,
                                    tint = c.textFaint,
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The device locale's medium date ("Oct 14, 2026"), in the device's zone. */
private fun formatSavedDate(instant: java.time.Instant): String =
    java.time.format.DateTimeFormatter
        .ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)
        .withZone(java.time.ZoneId.systemDefault())
        .format(instant)

/** Below this the category dialog fits without scrolling and a search box is just furniture. */
private const val SEARCH_THRESHOLD = 6

internal data class HealthCategory(
    val key: SecurityDashboard.Category,
    val label: String,
    val count: Int,
    val note: String,
    val tone: Color,
)

/**
 * The score card.
 *
 * The ring is gradient amber→jade rather than a single colour that changes with the value: a ring
 * that turns red at 40 and green at 80 makes the score feel like a verdict on the user. The
 * gradient reads as a scale you are somewhere on.
 */
@Composable
internal fun ScoreCard(
    /** Null when nothing has been scanned yet — see the placeholder below. */
    score: Int?,
    verdict: String,
    advice: String,
    modifier: Modifier = Modifier,
) {
    val c = LkTheme.colors
    // The ring is a dp-sized circle holding sp-sized text, which is a size that does not survive
    // the system font slider: at 2.0 the "100" and the word SCORE grew into the stroke, and the
    // gradient was drawn straight across the last letter of SCORE. A ring that contains text has to
    // be sized like text, so the diameter scales with the type and the numerals stay in exactly the
    // proportion the comps specify at every scale. Clamping the score instead was the alternative,
    // and it is the wrong one — the score is the single number a low-vision reader opened this
    // screen for.
    //
    // Growing costs width, so past [LkText.stackAboveFontScale] the card stacks rather than putting
    // a 190dp ring beside two paragraphs of prose. Same threshold, same reason, as the strength
    // meter. Font scale is the wrong proxy alone at the narrow end (SCL-021): at 308dp the card's
    // content is 228dp, and a 96dp ring beside the prose left it 112dp at 1.0 and a 68dp column at
    // 1.45, so the card also stacks on the width it is actually given — see [LkText.stacks].
    val fontScale = LocalDensity.current.fontScale
    val ringSize = 96.dp * fontScale
    val ring: @Composable () -> Unit = {
        // An empty vault used to render a full ring reading "100" beside the words "Nothing to
        // check yet" — a perfect security score awarded for having done nothing, which is the
        // same species of overclaim the rest of this screen's copy is careful to avoid. The
        // underlying `score` still returns 100 for an empty scan (it is a percentage of zero
        // findings over zero items, and every caller that arithmetics on it wants that), so the
        // honesty is applied here, at the point where a number is shown to a person.
        //
        // The screen-reader label reuses the verdict string rather than inventing one: "Nothing
        // to check yet" is exactly what a dash in a ring means, and it is already translated.
        val nothingScanned = score == null
        LkProgressRing(
            progress = if (nothingScanned) 0f else score!! / 100f,
            size = ringSize,
            strokeWidth = 6.dp,
            brush = Brush.linearGradient(listOf(c.amber, c.jade)),
            modifier = Modifier.labelled(
                if (nothingScanned) {
                    stringResource(R.string.health_verdict_nothing_to_check)
                } else {
                    stringResource(R.string.health_score_accessibility, score!!)
                },
            ),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (nothingScanned) {
                        stringResource(R.string.health_score_placeholder)
                    } else {
                        score.toString()
                    },
                    style = LkText.numeralMedium,
                    color = if (nothingScanned) c.textFaint else c.textStrong,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    stringResource(R.string.health_score_badge),
                    style = LkText.badge.copy(letterSpacing = LkText.sectionLabel.letterSpacing),
                    color = c.textFaint,
                )
            }
        }
    }
    val prose: @Composable () -> Unit = {
        Text(verdict, style = LkText.cardTitle, color = c.textPrimary)
        Text(advice, style = LkText.bodyQuiet, color = c.textMuted)
    }
    LkLiftedCard(modifier = modifier, shape = LkShape.dialog, padding = 20.dp) {
        // Measured inside the card's padding: this is the width the ring and the prose share, not
        // the window's (228dp at 308dp, 280dp at 360dp, the two numbers `stackBelowWidth` sits between).
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val stacked = LkText.stacks(fontScale, maxWidth)
            if (stacked) {
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    ring()
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { prose() }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    ring()
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { prose() }
                }
            }
        }
    }
}

@Composable
internal fun CategoryRow(
    category: HealthCategory,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LkTheme.colors
    val fontScale = LocalDensity.current.fontScale
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .lkPressScale(interaction, pressedScale = 0.99f)
            .lkSurface(LkShape.xxl, c.surface, c.ring)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp)
            .labelled(
                stringResource(R.string.health_category_accessibility, category.label, category.count, category.note),
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val badge: @Composable () -> Unit = {
            Box(
                modifier = Modifier
                    .size(categoryBadgeSize(fontScale))
                    // The count lives *inside* the coloured plate. Severity is then carried by a
                    // number as well as a hue, which is the whole requirement.
                    .lkSurface(LkShape.sm, category.tone.copy(alpha = 0.12f), Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                Text(category.count.toString(), style = LkText.monoCaption.copy(fontSize = LkText.body.fontSize), color = category.tone)
            }
        }
        val texts: @Composable () -> Unit = {
            // Hyphenating, like the note beneath it: the label is the longest unbroken word in the
            // row ("Potentially compromised", "Kompromittiert") and the style it had broke it
            // wherever the line ran out (S-L4).
            Text(
                category.label,
                style = LkText.rowTitle.copy(lineBreak = LineBreak.Heading, hyphens = Hyphens.Auto),
                color = c.textPrimary,
            )
            Text(category.note, style = LkText.subtitle, color = c.textFaint)
        }
        // The badge grows with the type (see [categoryBadgeSize]) and at 2.0 it is 76dp of a 308dp
        // window's row, which left the title about 110dp: "Potential/ly/compro/mised". From the same
        // font scale every other graphic-beside-text row stacks at, the badge goes above the text
        // and the title gets the whole row.
        if (fontScale >= LkText.stackAboveFontScale) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                badge()
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) { texts() }
            }
        } else {
            badge()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) { texts() }
        }
        Icon(
            LkIcons.ChevronRight,
            contentDescription = null,
            tint = c.textFaint,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * The count badge's diameter — same defect class, same fix, as `ScoreCard`'s health-score ring
 * above: a dp-sized plate holding sp-sized text has no floor once the text outgrows it. `lkSurface`
 * clips its *background* to its shape but not its content (`Modifier.lkSurface` is
 * `background(color, shape).lkRing(shape, ring)` — no `.clip()`), so a count too big for its plate
 * does not get cut off cleanly, it draws past the plate's edge into the row beside it.
 *
 * Two-digit counts survive at the default scale — the badge was sized for them. Three-digit counts
 * are real, not hypothetical: `SecurityDashboard`'s `count = itemIds.size` is uncapped, and this
 * repo's own D1 device pass ran at 10,000 items. 14sp monospace at fontScale 2.0 is ~28sp; a two-char
 * count fits inside 38dp there, a three-char count needs ~42-50dp and does not.
 *
 * No cap here, unlike the KDF ring in `LkDeriving.kt`, which has the same shape of problem but a
 * fixed-width overlay pinned 44dp from each screen edge to grow inside. This Box sits beside a
 * `weight(1f)` label column that simply absorbs whatever width the badge takes, and that column's
 * text already wraps rather than truncates — so there is no edge for an uncapped multiply to run
 * past.
 */
internal fun categoryBadgeSize(fontScale: Float, base: Dp = 38.dp): Dp = base * fontScale

/**
 * The protections that are on right now, for this install, whatever it paid.
 *
 * Deliberately plain rows and not a card each: this is the reassuring half of the screen and it
 * should read as settled, not as five things demanding attention. The wording comes from
 * [AlwaysOn] so it cannot say something different from the same claims in Settings.
 */
/**
 * An `AlwaysOn` summary with its own trailing full stop removed, so
 * `health_always_on_item_accessibility` can supply exactly one.
 *
 * Only `core_ui_always_on_leaves_device_summary` ends in a stop — it is two sentences where the
 * other five are fragments — and it is the one the device sweep caught reading
 * "Nothing else.. Always on." (2C-F11). Trimming rather than editing that string keeps the
 * security claim it makes untouched, and keeps the other five rows' sentence break.
 */
internal fun alwaysOnSummaryForSpeech(summary: String): String = summary.trimEnd('.')

@Composable
private fun AlwaysOnList(modifier: Modifier = Modifier) {
    val c = LkTheme.colors
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.health_always_on_heading), style = LkText.cardTitle, color = c.textStrong)
        LkGroup {
            AlwaysOn.facts.forEachIndexed { index, fact ->
                LkGroupRow(
                    first = index == 0,
                    modifier = Modifier.labelled(
                        // The format supplies the sentence break after the summary, so a summary
                        // that already ends in one produced a doubled stop:
                        // `…if you allow it. Nothing else.. Always on.` (2C-F11, on a device
                        // 2026-09-20). Five of the six `AlwaysOn` summaries end without a stop and
                        // need the one the format adds, so the normalisation belongs here rather
                        // than in the format string or in the one summary that differs.
                        stringResource(
                            R.string.health_always_on_item_accessibility,
                            fact.title,
                            alwaysOnSummaryForSpeech(fact.summary),
                        ),
                    ),
                ) {
                    Icon(
                        LkIcons.ShieldCheck,
                        contentDescription = null,
                        tint = c.jade,
                        modifier = Modifier.size(16.dp),
                    )
                    Column(
                        Modifier.weight(1f).padding(start = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(fact.title, style = LkText.body, color = c.textStrong)
                        Text(fact.summary, style = LkText.subtitle, color = c.textFaint)
                    }
                }
            }
        }
        Text(
            stringResource(R.string.health_always_on_footer),
            style = LkText.caption,
            color = c.textFaint,
        )
    }
}


/**
 * @param progress How far the strength pass has got, 0f..1f, or null. [HealthDashboardUiState]
 *   seeds it null for the whole start of a scan (F-40) — the breach-corpus load ahead of the first
 *   score is real, unreported time, and a fabricated 0f there read as a stuck ring rather than an
 *   honestly-unknown size. It flips to a real, monotonically increasing fraction the instant
 *   [SecurityDashboard.analyze] reports its first callback (it knows the true total before scoring
 *   starts, so from there the ring is determinate for the rest of the scan) and back to null once
 *   nothing is running.
 */
@Composable
private fun ScanningCard(progress: Float?) {
    val c = LkTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(top = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (progress == null) {
                LkPulseHalo(size = 108.dp)
                Icon(
                    LkIcons.ShieldCheck,
                    contentDescription = null,
                    tint = c.jade,
                    modifier = Modifier.size(34.dp),
                )
            } else {
                // No `.labelled()` here, unlike ScoreCard's ring: that one hides a bare number
                // behind a friendlier sentence on purpose (see its own comment), but a scan in
                // progress is exactly the case where the raw percentage is the useful thing to
                // read out, same as the KDF ring below it does not relabel its own percentage
                // either. The title `Text` below still carries the one description this card needs.
                //
                // animated = false, also mirroring the KDF ring (LkDeriving.kt): the arc jumps to
                // each checkpoint the instant it lands rather than tweening over real work, which
                // would put a lag between the number and the thing it is reporting on.
                LkProgressRing(
                    progress = progress,
                    size = 108.dp,
                    strokeWidth = 6.dp,
                    brush = Brush.linearGradient(listOf(c.amber, c.jade)),
                    animated = false,
                ) {
                    val locale = LocalConfiguration.current.locales[0]
                    Text(
                        // Floored, never rounded up, for the same reason as the KDF ring: 100% must
                        // not appear before the scan that earns it has actually finished.
                        // Locale-aware, not a hardcoded "N%" concatenation, for the same reason as
                        // LkDeriving.kt's ring — this is that same shared helper.
                        remember(progress, locale) { lkFlooredPercent(progress, locale) },
                        style = LkText.numeralMedium,
                        color = c.textStrong,
                    )
                }
            }
        }
        Text(
            stringResource(R.string.health_scanning_title),
            style = LkText.cardTitle,
            color = c.textSecondary,
            modifier = Modifier.labelled(stringResource(R.string.health_scanning_accessibility)),
        )
    }
}

@Composable
private fun categoryLabel(category: SecurityDashboard.Category): String = when (category) {
    SecurityDashboard.Category.WEAK -> stringResource(R.string.health_category_weak_label)
    SecurityDashboard.Category.REUSED -> stringResource(R.string.health_category_reused_label)
    SecurityDashboard.Category.OLD -> stringResource(R.string.health_category_old_dialog_title)
    SecurityDashboard.Category.BREACHED -> stringResource(R.string.health_category_breached_label)
    SecurityDashboard.Category.DUPLICATE -> stringResource(R.string.health_category_duplicate_label)
}

/**
 * A category's severity colour, in one place so the row and the dialog it opens cannot disagree.
 *
 * Coral for the two that mean an account is exposed right now, amber for weak (bad, not breached),
 * and the plain text ramp for age and duplicates — a 90-day-old strong password is a housekeeping
 * note, an account saved twice is tidying, and colouring either as a warning is how a dashboard
 * teaches people to ignore its colours.
 */
private fun categoryTone(
    category: SecurityDashboard.Category,
    c: LkColors,
    count: Int,
): Color = when {
    // Nothing found is not a warning, whatever the category would be if something had been.
    //
    // The severity below is a property of the CATEGORY, and until 2026-09-09 it was applied
    // regardless of the count — so a clean vault drew coral on "Reused passwords 0" and on
    // "Potentially compromised 0", and amber on "Weak passwords 0", directly beneath a headline
    // reading "Nothing worth fixing". Three alarm-coloured chips under a reassurance is a screen
    // arguing with itself, and it is the exact reading a user takes at a glance: three things are
    // wrong. `LkColors` already states the test — coral asks "is the user's data less safe than
    // they believe", and at zero the answer is no.
    count == 0 -> c.textMuted
    else -> when (category) {
        SecurityDashboard.Category.REUSED, SecurityDashboard.Category.BREACHED -> c.coral
        SecurityDashboard.Category.WEAK -> c.amber
        SecurityDashboard.Category.OLD, SecurityDashboard.Category.DUPLICATE -> c.textMuted
    }
}
