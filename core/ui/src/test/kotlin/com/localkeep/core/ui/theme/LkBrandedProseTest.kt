package com.localkeep.core.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.AnnotatedString
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Wherever a shared component prints a sentence that names the product, "Keep" is jade.
 *
 * Reported from a phone twice: 2026-08-20 (*"LocalKeep -> Keep is not green like everywhere else in
 * the app"*, which added `lkBrandedText` and put it behind the dialog masthead) and again
 * 2026-10-12, on the Files & media note that says *"LocalKeep does not remove location or camera
 * data from them"*. The first fix made the masthead consistent and left every other component
 * rendering the same name in flat grey — one product, two treatments, decided by which composable
 * happened to draw the sentence.
 *
 * The three components here are the shared ones that take a bare string and draw it as prose. Doing
 * it in the component rather than at each call site is the point: a call site cannot forget.
 *
 * Read from the semantics tree rather than a screenshot — a colour span is data, and what the test
 * needs is that the span exists, covers exactly the word "Keep", and is the same jade the wordmark
 * uses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LkBrandedProseTest {

    @get:Rule
    val compose = createComposeRule()

    private var jade by mutableStateOf(Color.Unspecified)

    private fun spansOf(sentence: String): AnnotatedString {
        val node = compose.onNodeWithText(sentence, substring = true).fetchSemanticsNode()
        return node.config.getOrNull(SemanticsProperties.Text)!!.first()
    }

    private fun assertKeepIsJade(sentence: String) {
        val text = spansOf(sentence)
        val coloured = text.spanStyles.filter { it.item.color == jade }
        assertEquals(1, coloured.size, "expected one jade span in \"${text.text}\", found ${text.spanStyles}")
        assertEquals("Keep", text.text.substring(coloured.single().start, coloured.single().end))
    }

    @Test
    fun anInlineNoteBrandsTheProductName() {
        compose.setContent {
            LocalKeepTheme {
                jade = LkTheme.colors.jade
                LkInlineNote(SENTENCE)
            }
        }
        assertKeepIsJade(SENTENCE)
    }

    @Test
    fun anInlineErrorBrandsTheProductName() {
        compose.setContent {
            LocalKeepTheme {
                jade = LkTheme.colors.jade
                LkInlineError(SENTENCE)
            }
        }
        assertKeepIsJade(SENTENCE)
    }

    @Test
    fun anEmptyStateBodyBrandsTheProductName() {
        compose.setContent {
            LocalKeepTheme {
                jade = LkTheme.colors.jade
                LkEmptyState(icon = LkIcons.Info, title = "Nothing here", body = SENTENCE)
            }
        }
        assertKeepIsJade(SENTENCE)
    }

    /** Control: a sentence that does not name the product comes back untouched, spans and all. */
    @Test
    fun aSentenceWithoutTheNameIsLeftAlone() {
        compose.setContent {
            LocalKeepTheme {
                jade = LkTheme.colors.jade
                LkInlineNote(PLAIN)
            }
        }
        val text = spansOf(PLAIN)
        assertTrue(text.spanStyles.none { it.item.color == jade }, "unexpected jade in \"${text.text}\"")
    }

    private companion object {
        const val SENTENCE = "Videos are kept as imported — LocalKeep does not remove location or camera data from them."
        const val PLAIN = "Metadata removed."
    }
}
