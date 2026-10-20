package com.localkeep.feature.onboarding

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

/**
 * The Recovery Kit PDF footer used to stamp `LocalDate.now().format(DateTimeFormatter.ISO_DATE)`
 * — always `YYYY-MM-DD`, regardless of the locale the rest of the document's strings were resolved
 * in. [recoveryKitFooterDate] formats through `DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)`
 * instead, which is pure `java.time` — no Android runtime needed, so this is a plain JVM
 * `kotlin.test`, unlike `RecoveryKitWriteModeTest`'s source-scanning tests for the two view
 * models that need a real `Context`.
 */
class RecoveryKitFooterDateFormatTest {

    @Test
    fun `no longer stamps the fixed ISO-8601 date`() {
        val date = recoveryKitFooterDate(Locale.US)
        assertFalse(
            Regex("""^\d{4}-\d{2}-\d{2}$""").matches(date),
            "recoveryKitFooterDate reverted to a fixed YYYY-MM-DD, unfamiliar outside en-US's own " +
                "conventions: $date",
        )
    }

    @Test
    fun `formats differently across locales`() {
        val us = recoveryKitFooterDate(Locale.US)
        val fr = recoveryKitFooterDate(Locale.FRANCE)
        assertNotEquals(
            us,
            fr,
            "the footer date rendered identically in en-US and fr-FR — MEDIUM localized dates " +
                "spell the month, so the two should differ (\"Sep 7, 2026\" vs \"7 sept. 2026\")",
        )
    }
}
