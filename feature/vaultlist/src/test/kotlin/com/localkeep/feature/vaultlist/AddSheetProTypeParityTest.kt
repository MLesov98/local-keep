package com.localkeep.feature.vaultlist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A cell is marked when, and only when, selecting it will refuse — and every refusable type is
 * marked on that one condition, so no two of them can render as different kinds of offer.
 *
 * This file exists because of a defect it must still catch after being rewritten twice. Between
 * 2026-09-03 and 2026-09-04 the sheet marked a row only once that row's slot was spent, but drew
 * that mark from `requiresPro`. A free vault holding one identity and no card therefore showed
 * Identity plated brass and badged PRO beside a plain jade Card, and the sheet read as *identities
 * are a paid feature and cards are not*. Reported: "Identity says that it's pro only but not card?"
 *
 * The first repair marked both metered types always, badging an unspent allowance "1 FREE". That
 * was withdrawn on 2026-09-04: brass means "this costs money" (`LkColors`), so the badge fought its
 * own plate; in a `foss` release `isPro` is permanently false, so two cells sat brass from install
 * for ever in a build that sells nothing; and a badge cannot teach a cap the refusal dialog does not
 * teach better a second later, with the real count.
 *
 * So the invariant is no longer "both metered rows always mark". It is the narrower, truer one:
 * **marking follows refusal, and refusal alone.** Two types on one quota rule cannot diverge,
 * because neither's mark consults its type at all.
 *
 * The third type is the reason that phrasing matters. File is capped at seven
 * (`AttachmentQuota.FREE_LIMIT`) with `requiresPro = false`, so any rule keyed on `requiresPro`
 * marks two of the three refusable types and leaves the third — the one a free vault is most likely
 * to exhaust — looking unconditionally available.
 */
class AddSheetProTypeParityTest {

    /**
     * Every type a free vault can be refused for. Not `entries.filter { it.requiresPro }`: that
     * property answers "may this tier create this type at all", which has been false for File since
     * files stopped being gated, while File's quota went on refusing people.
     *
     * Hard-coded rather than derived, deliberately, because deriving it from the same property the
     * production code uses would make this test agree with any bug that property causes.
     */
    private val refusable = listOf(NewItemType.CARD, NewItemType.IDENTITY, NewItemType.FILE)

    @Test
    fun `nothing is marked while it still works`() {
        // The withdrawn "1 FREE" state. An allowance in hand is a row that works, and a row that
        // works is jade and silent — there is nothing to warn anyone about.
        NewItemType.entries.forEach { type ->
            assertEquals(
                ProTypeMark.None,
                proTypeMark(isPro = false, spent = false),
                "$type has its allowance in hand, so its cell must be indistinguishable from any " +
                    "other working cell",
            )
        }
    }

    @Test
    fun `a spent allowance is marked, whichever type spent it`() {
        refusable.forEach { type ->
            assertEquals(
                ProTypeMark.Spent,
                proTypeMark(isPro = false, spent = true),
                "$type refuses on selection when its quota is full, so its cell must say so",
            )
        }
    }

    @Test
    fun `the mark cannot depend on which type is asking`() {
        // The structural guard, and the one that survives a future category. The reported defect was
        // two types on one rule rendering differently; that becomes unrepresentable if the mark
        // never sees the type. If someone reintroduces a `type` parameter here, this stops compiling
        // — which is the intended way to be told.
        val marks = listOf(true, false).flatMap { spent ->
            listOf(true, false).map { pro -> proTypeMark(isPro = pro, spent = spent) }
        }
        assertEquals(
            4,
            marks.size,
            "proTypeMark must be answerable from (isPro, spent) alone — a per-type answer is how " +
                "Card and Identity came to look like different offers",
        )
    }

    @Test
    fun `a Pro vault marks nothing, spent or not`() {
        // The mirror defect, and the reason the mark cannot simply be "this type is metered": an
        // entitled user seeing Card and Identity plated brass among eight jade rows reads a
        // distinction that is not there, drawn on the two types they have actually paid for.
        //
        // `spent = true` with `isPro = true` should be unreachable — every quota flag in
        // VaultListUiState comes from an `exceedsFreeLimit(used, isPro)` that is false for Pro — but
        // it is asserted anyway, because "unreachable" is a claim about a caller and this is a
        // property of the function.
        listOf(true, false).forEach { spent ->
            assertEquals(
                ProTypeMark.None,
                proTypeMark(isPro = true, spent = spent),
                "a Pro vault must carry no marking at all (spent=$spent)",
            )
        }
    }

    @Test
    fun `File is one of the types the sheet can mark`() {
        // Guards the half of the fix that lives in VaultListScreen's `spentAllowances`. The rule above
        // is type-blind, so nothing in this file can notice File being omitted from the set that
        // feeds it — this at least fails if File stops being a refusable type, which is the premise
        // that omission would be hiding behind.
        assertTrue(
            NewItemType.FILE in refusable,
            "File is capped by AttachmentQuota and refuses on selection like Card and Identity; " +
                "if that has changed, VaultListScreen's spentAllowances must change with it",
        )
        assertTrue(
            !NewItemType.FILE.requiresPro,
            "File is quota-limited rather than gated — this is exactly why a marking rule keyed " +
                "on requiresPro left it looking unconditionally available",
        )
    }

    @Test
    fun `the two gated types are still Card and Identity`() {
        // The premise of the reported defect. A third `requiresPro` type is not necessarily wrong,
        // but it has to be looked at here, and added to `refusable` and to spentAllowances with it.
        assertEquals(
            listOf(NewItemType.CARD, NewItemType.IDENTITY),
            NewItemType.entries.filter { it.requiresPro },
            "the set of gated add-sheet types changed; ProTypeQuota.Kind and this file have to " +
                "agree about which types are metered",
        )
    }
}
