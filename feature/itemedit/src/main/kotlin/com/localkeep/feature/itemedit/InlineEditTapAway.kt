package com.localkeep.feature.itemedit

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputEventHandler
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.TraversableNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.findNearestAncestor
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.platform.LocalFocusManager

/**
 * Ends the item view screen's inline edit when the user taps anywhere outside it.
 *
 * `ReadField` saves on "IME Done or focus loss" and discards on Back, and a tap elsewhere was meant
 * to be a focus loss. It never was: in Compose a tap on anything that is not a text field — blank
 * space, a section label, a button — takes no focus, so the field kept it, the keyboard stayed up,
 * and nothing was saved. The user's natural next move is Back, which is the discard path, so the
 * edit was lost (device pass 2026-10-04, A3 ITM-04).
 *
 * [endInlineEditOnTapAway] goes on the screen and [inlineEditArea] on the open edit. A completed tap
 * — down and up without travelling past touch slop, so a scroll is not one — that lands outside the
 * area clears focus, and the field's own focus-loss handler then commits exactly as Done does. The
 * tap is watched on its way *down* the tree and never consumed, so a button tapped to leave the edit
 * still receives it and still does its own job.
 *
 * Modifier nodes rather than a CompositionLocal: the editable rows sit at several depths inside one
 * screen-long Column, and the layout tree already links each of them to that Column — which is also
 * the coordinate space the tap arrives in.
 */
internal fun Modifier.endInlineEditOnTapAway(): Modifier = this then EndInlineEditOnTapAwayElement

/** Marks the open inline edit — label and well — as the one place a tap does not end it. */
internal fun Modifier.inlineEditArea(): Modifier = this then InlineEditAreaElement

private object TapAwayTraverseKey

private object EndInlineEditOnTapAwayElement : ModifierNodeElement<EndInlineEditOnTapAwayNode>() {
    override fun create() = EndInlineEditOnTapAwayNode()
    override fun update(node: EndInlineEditOnTapAwayNode) = Unit
    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = "endInlineEditOnTapAway".hashCode()
}

private object InlineEditAreaElement : ModifierNodeElement<InlineEditAreaNode>() {
    override fun create() = InlineEditAreaNode()
    override fun update(node: InlineEditAreaNode) = Unit
    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = "inlineEditArea".hashCode()
}

private class EndInlineEditOnTapAwayNode :
    DelegatingNode(), TraversableNode, CompositionLocalConsumerModifierNode {

    override val traverseKey: Any get() = TapAwayTraverseKey

    /** The edit currently open below this screen, or null while there is none. */
    var openEdit: InlineEditAreaNode? = null

    init {
        delegate(
            SuspendingPointerInputModifierNode(
                PointerInputEventHandler {
                    awaitEachGesture {
                        // Initial pass: ahead of every child, so nothing below has consumed it yet,
                        // and nothing here consumes it either.
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val edit = openEdit ?: return@awaitEachGesture
                        while (true) {
                            val change = awaitPointerEvent(PointerEventPass.Initial).changes
                                .firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                            // A drag is the user scrolling to look at something, not leaving the field.
                            if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                return@awaitEachGesture
                            }
                            if (change.changedToUpIgnoreConsumed()) break
                        }
                        val screen = this@EndInlineEditOnTapAwayNode
                        if (edit.isAttached && !edit.contains(screen.requireLayoutCoordinates(), down.position)) {
                            screen.currentValueOf(LocalFocusManager).clearFocus()
                        }
                    }
                },
            ),
        )
    }
}

private class InlineEditAreaNode : Modifier.Node() {
    private var screen: EndInlineEditOnTapAwayNode? = null

    override fun onAttach() {
        screen = findNearestAncestor(TapAwayTraverseKey) as? EndInlineEditOnTapAwayNode
        screen?.openEdit = this
    }

    override fun onDetach() {
        // Compared, because opening another row's edit attaches its area before this one leaves.
        screen?.let { if (it.openEdit === this) it.openEdit = null }
        screen = null
    }

    /** Whether [position], in [from]'s coordinates, lands inside this edit. */
    fun contains(from: LayoutCoordinates, position: Offset): Boolean {
        val area = requireLayoutCoordinates()
        val local = area.localPositionOf(from, position)
        return local.x in 0f..area.size.width.toFloat() && local.y in 0f..area.size.height.toFloat()
    }
}
