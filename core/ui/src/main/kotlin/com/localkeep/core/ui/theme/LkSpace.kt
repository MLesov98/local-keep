package com.localkeep.core.ui.theme

import androidx.compose.ui.unit.dp

/**
 * The spacing scale.
 *
 * The last of the design tokens to exist, and its absence was measurable. `LkColors`, `LkType`,
 * `LkMotion`, `LkShape` and `LkFields` all shipped from the start, so every colour and every
 * corner in the app came from one place — while every *gap* was typed at the call site. On
 * 2026-07-29 `ItemEditScreen.kt` alone contained `spacedBy` at 6, 8, 9, 10, 11, 12 and 16dp, and
 * the vertical distance between two adjacent fields in the login form measured 18dp in one place
 * and 42dp in another. Nobody chose that; it accumulated.
 *
 * Five steps, and deliberately only five. The point of a scale is not that it covers every gap
 * someone might want — it is that 9dp and 11dp become unspellable, so the next person adjusting a
 * form picks a rung instead of a number that looked right on their screen.
 *
 * Rungs, and what each one separates:
 *
 * - [xs] — parts of one control: an icon from its text, a chip from a chip.
 * - [sm] — a label from the thing it labels. Nothing else.
 * - [md] — one field from the next field. The form's default rhythm.
 * - [lg] — one section from the next section.
 * - [xl] — a primary action from the content it acts on.
 *
 * A gap that fits none of these is usually a sign the layout wants a section boundary rather than a
 * bigger number.
 */
object LkSpace {
    /** Inside a single control — icon to label, chip to chip. */
    val xs = 4.dp

    /** A label to its own field. */
    val sm = 8.dp

    /** Field to field: the default rhythm of a form. */
    val md = 16.dp

    /** Section to section. */
    val lg = 24.dp

    /** Above a primary action. */
    val xl = 32.dp
}

/**
 * The frame around a full-screen, one-decision-at-a-time screen.
 *
 * Not rungs of [LkSpace], and deliberately not bent onto them. These values already recur
 * identically across every hero-style screen in the app — onboarding's welcome, done and step
 * frames, and the unlock screen — so they are consistent, deliberate structure that simply had no
 * name. Replacing them with `lg`/`xl` would silently change the rendered padding of every one of
 * those screens, which is re-opening a density decision that was measured in July, not a cleanup.
 *
 * Note the horizontal margin is two values, not one: [horizontal] (26dp) frames the item flows,
 * [heroHorizontal] (30dp) frames onboarding and unlock. This KDoc claimed the unlock screen used
 * the 26dp value until 2026-09-01; it never did, and reading the claim rather than the call site is
 * how the unlock screen's action column ended up on a third value again.
 *
 * The rule the two objects encode together: [LkSpace] is the rhythm *inside* a screen, and this is
 * the margin *of* one. A value belongs here only when it frames the whole screen.
 */
object LkFrame {
    /** Left and right margin of a wizard/item-flow screen — the item editor and the folder picker. */
    val horizontal = 26.dp

    /**
     * Left and right margin of a *hero* screen — onboarding's welcome, password and done frames,
     * the unlock screen, and the full-size empty state.
     *
     * Four points wider than [horizontal], and that difference is deliberate: those screens carry
     * one sentence and one decision, where an item flow carries wells that want the width. The
     * value was already unanimous across all four call sites before it had a name — naming it is
     * what stopped the unlock screen's pinned action column, which took the 20dp
     * [screenHorizontal] default, from sitting 4dp wider on each side than the field it submits
     * (found by the visual sweep of 2026-09-01).
     */
    val heroHorizontal = 30.dp

    /** Above the first element — the progress rail, or the headline where there is none. */
    val top = 46.dp

    /** Below the pinned actions. */
    val bottom = 32.dp

    /**
     * Left and right margin of an ordinary screen — a list, a form, a settings page.
     *
     * Tighter than [horizontal] because these screens carry rows and wells that want the width,
     * where a hero screen carries one sentence that does not. The value was already unanimous
     * across the vault list, both item screens, the generator, settings, backup, trash, health,
     * folders, the import wizard, files and trusted apps before it had a name; naming it is what
     * makes the next screen inherit the frame instead of re-deciding it.
     */
    val screenHorizontal = 20.dp

    /** Above the first element on an ordinary screen — usually its header row. */
    val screenTop = 12.dp

    /**
     * Bottom of a scrolling list on a screen with a FAB. Clears the button so the last row is
     * reachable rather than sitting under it — the vault list, the generator and health.
     */
    val gutterFab = 120.dp

    /** Bottom of a scrolling column on a screen with no FAB. */
    val gutterPlain = 40.dp
}
