package com.localkeep.feature.itemedit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The three screens that pin a Save/Continue stack draw it through the frames that obey the app's
 * keyboard-up rule. The behaviour is measured in [WizardFrameKeyboardTest] and
 * [ItemEditFrameKeyboardTest]; this holds the wiring, because `ItemEditViewModel` cannot be built on
 * a JVM and so the screens themselves cannot be composed here.
 */
class KeyboardRuleIsWiredTest {

    private fun source(name: String): String {
        val f = File("src/main/kotlin/com/localkeep/feature/itemedit/$name")
        assertTrue(f.exists(), "$name not found at ${f.absolutePath}")
        return f.readText()
    }

    @Test
    fun `the item editor draws through ItemEditFrame`() {
        val s = source("ItemEditScreen.kt")
        assertTrue("ItemEditFrame(" in s.substringAfter("fun ItemEditScreen("), "the editor no longer uses ItemEditFrame")
    }

    @Test
    fun `both wizards draw through WizardFrame`() {
        assertTrue("WizardFrame(" in source("CardWizard.kt"), "the card wizard no longer uses WizardFrame")
        assertTrue("WizardFrame(" in source("IdentityWizard.kt"), "the identity wizard no longer uses WizardFrame")
    }

    @Test
    fun `the frames read the shared rule`() {
        assertTrue("rememberLkKeyboardUnpinsChrome()" in source("WizardFrame.kt"))
        assertTrue("LocalLkChromeUnpinned.current" in source("ItemEditScreen.kt"))
    }
}
