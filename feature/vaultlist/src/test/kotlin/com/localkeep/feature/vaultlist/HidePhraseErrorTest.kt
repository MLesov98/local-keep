package com.localkeep.feature.vaultlist

import com.localkeep.core.vault.HiddenTarget
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * A collision verdict belongs to the phrase that produced it.
 *
 * Left up after the user typed something else, the coral ring and "that phrase is taken" sat under a
 * phrase nobody had checked yet, until the next submit.
 */
class HidePhraseErrorTest {

    private val flow = HideFlowState(
        target = HiddenTarget.Item(UUID.randomUUID()),
        label = "Bank",
        needsPhrase = true,
        pendingUntil = Instant.EPOCH,
        error = HideFlowError.PhraseCollision,
    )

    @Test
    fun editingThePhraseClearsTheCollision() {
        assertNull(flow.afterPhraseEdited().error)
    }

    @Test
    fun theRestOfTheFlowIsLeftAlone() {
        val edited = flow.afterPhraseEdited()
        assertEquals(flow.copy(error = null), edited, "only the error may change: the chosen duration is not re-asked")
    }

    @Test
    fun aFlowWithNoErrorIsReturnedUnchanged() {
        val clean = flow.copy(error = null)
        assertSame(clean, clean.afterPhraseEdited())
    }
}
