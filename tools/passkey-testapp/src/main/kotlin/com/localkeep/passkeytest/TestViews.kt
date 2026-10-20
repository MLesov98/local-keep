package com.localkeep.passkeytest

import android.app.Activity
import android.graphics.Typeface
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Mirrors `tools/autofill-testapp`'s TestViews: code-only views, no resources, no theme work. */
internal fun Activity.column(title: String): LinearLayout =
    LinearLayout(this).apply {
        fitsSystemWindows = true
        orientation = LinearLayout.VERTICAL
        setPadding(48, 48, 48, 48)
        addView(TextView(this@column).apply {
            text = title
            textSize = 22f
        })
    }

internal fun Activity.action(label: String, block: () -> Unit): Button =
    Button(this).apply {
        text = label
        setOnClickListener { block() }
    }

/**
 * The on-screen transcript.
 *
 * Every result this app produces is printed here rather than logged, because the only reader is an
 * agent driving the device through `uiautomator dump`, which sees the view tree and not logcat.
 * Monospace so a base64url blob stays readable when it wraps.
 *
 * Deliberately carries no `contentDescription`: a literal one is what `checkNoHardcodedUiStrings`
 * catches, and routing around that with a constant would be dodging the gate rather than passing
 * it. The agent's handle is structural instead — this is the one TextView inside the one ScrollView
 * on the screen — and the README says so.
 */
internal class ResultLog(activity: Activity) {
    private val lines = StringBuilder()

    val view: TextView = TextView(activity).apply {
        typeface = Typeface.MONOSPACE
        textSize = 12f
        setTextIsSelectable(true)
    }

    val scroller: ScrollView = ScrollView(activity).apply { addView(view) }

    fun clear() {
        lines.setLength(0)
        view.text = ""
    }

    fun line(text: String) {
        if (lines.isNotEmpty()) lines.append('\n')
        lines.append(text)
        view.text = lines
        scroller.post { scroller.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
