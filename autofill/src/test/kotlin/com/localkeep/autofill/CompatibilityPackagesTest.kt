package com.localkeep.autofill

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmlpull.v1.XmlPullParser

/**
 * `res/xml/autofill_service.xml` declares compatibility mode for the browsers that need it.
 *
 * Compatibility mode is the only way this service sees inside a browser that does not hand its page
 * content to the autofill framework, and the platform enables it solely on the strength of these
 * tags — `AutofillServiceInfo` parses them out of this file and `AutofillManagerService` intersects
 * the result with the device's own allowlist. Declaring none, which is what shipped, meant a login
 * typed into a web page in Chrome was offered nothing and captured nothing, silently and on every
 * device.
 *
 * Read back through the real resource pipeline rather than off the source text, so a file that
 * compiles to something other than what it reads as cannot pass. The set is asserted whole: an
 * entry quietly dropped in an unrelated edit is the regression this exists to catch, and a test
 * that only checked for "at least one" would not see it.
 */
@RunWith(RobolectricTestRunner::class)
class CompatibilityPackagesTest {

    private fun declaredCompatibilityPackages(): List<String> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val parser = context.resources.getXml(R.xml.autofill_service)
        val packages = mutableListOf<String>()
        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "compatibility-package") {
                // The platform reads this through R.styleable.AutofillService_CompatibilityPackage;
                // by attribute name here, because that styleable is not public API.
                val name = parser.getAttributeValue(ANDROID_NS, "name")
                if (name != null) packages += name
            }
            event = parser.next()
        }
        parser.close()
        return packages
    }

    @Test
    fun `every browser that needs compatibility mode is declared`() {
        assertEquals(
            listOf(
                "com.android.chrome",
                "org.mozilla.firefox",
                "com.brave.browser",
                "com.microsoft.emmx",
                "com.sec.android.app.sbrowser",
                "com.opera.browser",
                "com.duckduckgo.mobile.android",
            ),
            declaredCompatibilityPackages(),
        )
    }

    /**
     * `AutofillServiceInfo.parseCompatibilityPackages` reads `maxLongVersionCode` as a string and
     * defaults it to `Long.MAX_VALUE` when it is absent, which is what this file wants: compat mode
     * for every version, because no verified per-browser version exists at which native autofill
     * support begins. A cut-off invented to look thorough would turn filling off in a browser that
     * still needs it, and the failure would be invisible.
     */
    @Test
    fun `no invented version cut-off is declared`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val parser = context.resources.getXml(R.xml.autofill_service)
        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "compatibility-package") {
                assertTrue(
                    parser.getAttributeValue(ANDROID_NS, "maxLongVersionCode") == null,
                    "maxLongVersionCode must stay absent unless someone can cite the version at " +
                        "which that browser supports autofill natively",
                )
            }
            event = parser.next()
        }
        parser.close()
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
