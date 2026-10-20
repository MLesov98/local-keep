package com.localkeep.autofill

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.service.autofill.Dataset
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.InlinePresentation
import android.view.autofill.AutofillId
import android.widget.RemoteViews
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.RequiresApi
import androidx.autofill.inline.v1.InlineSuggestionUi

/**
 * Every API-30 inline-suggestion type this module touches, and nothing else.
 *
 * The isolation is the point, not a tidiness preference. `InlinePresentationSpec` and
 * `InlinePresentation` do not exist below API 30, and `minSdk` is 28. A `Build.VERSION.SDK_INT`
 * guard around the *call* is not enough on its own: ART resolves a class when an instruction names
 * it, and a method that merely declares one of these types in its signature can be made to resolve
 * it far away from any guard. That is exactly what shipped — `FillResponseFactory` took the spec as
 * a typed parameter, and because it is a `suspend` fun Kotlin spilled that parameter into the
 * continuation as `Object` and check-cast it back on resume. The cast is generated code; no guard
 * the author writes sits in front of it. On API 29 a form with a *stored* credential threw
 * `NoClassDefFoundError` and the user was offered nothing at all, while the same build served the
 * no-match path fine because it returned before the second suspension point.
 *
 * So the rule this file exists to enforce: outside this object the spec travels as `Any?`, and the
 * only code that names an inline type is code that cannot run below API 30. `checkNoApi30Signatures`
 * fails the build if that stops being true.
 */
@RequiresApi(Build.VERSION_CODES.R)
internal object InlineSuggestions {
    /** The spec the platform offered for the first suggestion slot, as an opaque handle. */
    fun firstSpec(request: FillRequest): Any? =
        request.inlineSuggestionsRequest?.inlinePresentationSpecs?.firstOrNull()

    /**
     * Attach an inline presentation to a dataset. Silent no-op if one cannot be built.
     *
     * [attributionIntent] is what a long-press on the chip launches, never what a tap does — the
     * tap goes through the dataset's own authentication. Pass `DatasetBuilder.attributionIntent`,
     * never a gate (2026-10-02).
     */
    fun applyTo(
        builder: Dataset.Builder,
        title: String,
        subtitle: String?,
        attributionIntent: PendingIntent,
        spec: Any,
    ) {
        presentation(title, subtitle, attributionIntent, spec)
            ?.let(builder::setInlinePresentation)
    }

    /**
     * Carries [spec] in [intent] under [key], for a gate that rebuilds the response on the far side
     * of a challenge and has no fill request of its own to read one from.
     */
    fun putSpec(intent: Intent, key: String, spec: Any) {
        intent.putExtra(key, spec as InlinePresentationSpec)
    }

    /** The spec [putSpec] carried, as the same opaque handle, or null. */
    fun specFrom(intent: Intent, key: String): Any? = intent.parcelableExtraCompat<InlinePresentationSpec>(key)

    /**
     * Response-level authentication with an inline presentation.
     *
     * Returns false when no inline presentation could be built, so the caller can fall back to the
     * three-argument `setAuthentication` rather than declaring none at all.
     */
    fun applyAuthentication(
        builder: FillResponse.Builder,
        ids: Array<AutofillId>,
        intentSender: IntentSender,
        remoteViews: RemoteViews,
        title: String,
        subtitle: String?,
        attributionIntent: PendingIntent,
        spec: Any,
    ): Boolean {
        val inline = presentation(title, subtitle, attributionIntent, spec) ?: return false
        builder.setAuthentication(ids, intentSender, remoteViews, inline)
        return true
    }

    private fun presentation(
        title: String,
        subtitle: String?,
        attributionIntent: PendingIntent,
        spec: Any,
    ): InlinePresentation? = runCatching {
        val content = InlineSuggestionUi.newContentBuilder(attributionIntent)
            .setTitle(title)
            .setSubtitle(subtitle.orEmpty())
            .build()
        InlinePresentation(content.slice, spec as InlinePresentationSpec, false)
    }.getOrNull()
}
