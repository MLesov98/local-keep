package com.localkeep.feature.itemedit

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.localkeep.core.vault.AttachmentQuota
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * On `foss` the identity-photo quota refusal must not tell the reader to buy something that cannot
 * be bought. `Entitlement.canPurchase` is permanently false there, yet the sentence ended "or unlock
 * Pro for unlimited" with no branch on it. The vault list's dialog already chooses its last clause
 * on the same flag; this is the same decision for the camera route's refusal. Resolves the real
 * catalogues in every language through the same id list the screen renders.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IdentityPhotoQuotaWithoutPurchaseTest {

    private val locales = listOf("en", "fr", "de", "es", "pt")
    private val mustRemove = 3

    private fun rendered(tag: String, canPurchase: Boolean): String {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        val context = base.createConfigurationContext(config)
        return identityPhotoQuotaStringIds(canPurchase).joinToString("") {
            context.getString(it, AttachmentQuota.FREE_LIMIT, mustRemove)
        }
    }

    private val unlimitedClause = mapOf(
        "en" to "for unlimited", "fr" to "accès illimité", "de" to "unbegrenzt",
        "es" to "acceso ilimitado", "pt" to "acesso ilimitado",
    )
    private val wherePro = mapOf(
        "en" to "Play version", "fr" to "version Play", "de" to "Play-Version",
        "es" to "versión de Play", "pt" to "versão Play",
    )

    @Test
    fun `without a purchase path the refusal never offers one, in any language`() {
        locales.forEach { tag ->
            val text = rendered(tag, canPurchase = false)
            assertFalse(unlimitedClause.getValue(tag) in text, "$tag still offers an unlimited unlock: \"$text\"")
            assertTrue(wherePro.getValue(tag) in text, "$tag must say where Pro can be bought: \"$text\"")
            assertTrue(AttachmentQuota.FREE_LIMIT.toString() in text, "$tag lost the limit: \"$text\"")
            assertTrue(mustRemove.toString() in text, "$tag lost the remove count: \"$text\"")
        }
    }

    @Test
    fun `with a purchase path the refusal keeps its purchase clause, in any language`() {
        locales.forEach { tag ->
            val text = rendered(tag, canPurchase = true)
            assertTrue(unlimitedClause.getValue(tag) in text, "$tag lost the purchase clause: \"$text\"")
            assertTrue(mustRemove.toString() in text, "$tag lost the remove count: \"$text\"")
        }
    }
}
