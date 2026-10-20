package com.localkeep.core.ui

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Transient confirmations for actions that have already happened.
 *
 * Mutations across the app were silent on success: every repository call is wrapped in a
 * `runCatching { }.onSuccess { refresh() }.onFailure { error }`, which means failure has a channel
 * and success has none. Trashing an item, restoring one, saving an edit, tagging a hundred rows —
 * all of them just made the list change shape, and the user had to infer what happened from what
 * disappeared.
 *
 * A singleton rather than per-screen state, for the same reason [ClipboardHelper] is one: the
 * action that most needs confirming is the one that navigates away as it completes, and an inline
 * note on a screen the user is leaving is a note nobody reads. [LkNoticeHost] is mounted once, in
 * the app shell, and outlives any single screen.
 *
 * Deliberately Context-free so a ViewModel can post without holding one, and deliberately
 * single-slot: two confirmations stacked on top of each other is noise, and the newest is the one
 * the user just caused.
 */
object LkNotices {

    /** How long a plain confirmation stays up. */
    const val SHORT_MS = 4_000L

    /** Longer when there is something to do about it — an undo the user cannot read in time is not an undo. */
    const val ACTION_MS = 8_000L

    /**
     * For a notice posted the moment the vault opens, reporting something the lock lost or replaced
     * — an edit discarded, a snapshot restored over the vault. See [postAtUnlock].
     *
     * Longer than [SHORT_MS] because of when it lands, not because of what it says. It arrives with
     * the unlock itself, while the user is still watching the unlock screen give way to the list and
     * looking for the item they came for, and four seconds of that is spent before anyone reads a
     * line at the bottom of the screen. Three device testers in one pass (2026-10-05) unlocked
     * straight past "your unsaved changes were discarded" and reported the loss as silent. These
     * sentences are the only record that something is gone, so a reader who misses one has no way
     * back to it.
     */
    const val UNLOCK_MS = 10_000L

    /**
     * What a notice says — either text the caller already resolved, or the resource id of it.
     *
     * The second case is why this type exists. Posting used to take a `String` and nothing else,
     * and a ViewModel holds no Context to resolve a resource with, so four files posted English
     * literals — "Moved to trash", "Item saved", "Custom field removed", "Undo" — and sat on
     * `config/i18n-allowlist.txt` as BLOCKED with that reason written out. They shipped untranslated
     * in every language. [LkNoticeHost] is a composable and can resolve an id perfectly well; the
     * only thing missing was a way to hand it one.
     *
     * [LkNotices] stays Context-free, which was the property worth keeping.
     */
    sealed interface Message {
        @JvmInline
        value class Text(val value: String) : Message

        @JvmInline
        value class Res(@get:StringRes val id: Int) : Message

        /**
         * A counted message — the `<plurals>` id plus the number, which is both the quantity that
         * selects the form and the value substituted into it.
         *
         * Added 2026-07-31 for the same reason [Res] exists. The bulk actions were the one path
         * left building their own English: `VaultListViewModel` carried
         * `if (n == 1) "1 item" else "$n items"` and composed "%s moved to trash" around it, so
         * selecting three rows and trashing them announced English in every language. A `<plurals>`
         * cannot be resolved without a Context, so [Res] alone could not carry it.
         */
        data class Plural(@get:PluralsRes val id: Int, val count: Int) : Message
    }

    /**
     * What a notice means, which decides how [LkNoticeHost] looks and feels when it appears.
     *
     * [Success] is the jade check and the platform's "it worked" buzz, and is what every [post]
     * produces. [Refusal] is for an action the app declined to carry out and wants to say so — a
     * folder name already taken, a ninth level of nesting. It is drawn coral with a warning glyph
     * and plays [Haptic.ERROR]: the old host drew those with a tick and [Haptic.SUCCESS], which told
     * the user the opposite of what the sentence beside it said.
     */
    enum class Tone(internal val haptic: Haptic) {
        Success(Haptic.SUCCESS),
        Refusal(Haptic.ERROR),
    }

    data class Notice(
        val message: Message,
        val actionLabel: Message? = null,
        val onAction: (() -> Unit)? = null,
        /** Distinguishes two identical messages posted in a row, so the second re-arms the timer. */
        internal val id: Long,
        /** Set by [postAtUnlock] only. */
        internal val atUnlock: Boolean = false,
        /** How [LkNoticeHost] dresses it. [Tone.Success] unless posted with [postRefusal]. */
        val tone: Tone = Tone.Success,
    ) {
        val durationMs: Long get() = when {
            atUnlock -> UNLOCK_MS
            onAction != null -> ACTION_MS
            else -> SHORT_MS
        }
    }

    private val nextId = AtomicLong(0)
    private val _current = MutableStateFlow<Notice?>(null)

    /** The confirmation currently worth showing, or null. */
    val current: StateFlow<Notice?> = _current.asStateFlow()

    /**
     * Announces something that has *already succeeded*, drawn with the jade check and a success
     * haptic. Never use this to report a refusal or a failure — a refusal goes through
     * [postRefusal], and an error that can be retried belongs on the screen that can retry it,
     * where it persists until dealt with.
     */
    fun post(
        message: String,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null,
    ) = post(
        message = Message.Text(message),
        actionLabel = actionLabel?.let(Message::Text),
        onAction = onAction,
    )

    /**
     * The same, for a caller with no Context — every ViewModel. The host resolves the ids.
     */
    fun post(
        @StringRes messageRes: Int,
        @StringRes actionLabelRes: Int? = null,
        onAction: (() -> Unit)? = null,
    ) = post(
        message = Message.Res(messageRes),
        actionLabel = actionLabelRes?.let(Message::Res),
        onAction = onAction,
    )

    fun post(
        message: Message,
        actionLabel: Message? = null,
        onAction: (() -> Unit)? = null,
    ) {
        _current.value = Notice(
            message = message,
            actionLabel = actionLabel?.takeIf { onAction != null },
            onAction = onAction,
            id = nextId.incrementAndGet(),
        )
    }

    /**
     * Announces that the app declined to do what was asked. Same single slot and timing as [post],
     * but the host draws it as a refusal (see [Tone.Refusal]) instead of a success. No action: a
     * refusal has nothing to undo.
     */
    fun postRefusal(message: Message) {
        _current.value = Notice(message = message, id = nextId.incrementAndGet(), tone = Tone.Refusal)
    }

    fun postRefusal(message: String) = postRefusal(Message.Text(message))

    /** The same, for a caller with no Context. */
    fun postRefusal(@StringRes messageRes: Int) = postRefusal(Message.Res(messageRes))

    /**
     * Posts a notice that reports, at unlock, something the lock lost or replaced. It stays up for
     * [UNLOCK_MS]; everything else a caller posts at unlock goes through [post] as usual.
     *
     * Drawn as a [Tone.Refusal] unless [tone] says otherwise: what the lock lost or replaced is
     * normally a loss (an unsaved draft discarded), and the jade check plus the success buzz told
     * the user the opposite of what the sentence said. A caller reporting a good outcome at unlock
     * (a snapshot restored in full) passes [Tone.Success].
     */
    fun postAtUnlock(message: Message, tone: Tone = Tone.Refusal) {
        _current.value = Notice(
            message = message,
            id = nextId.incrementAndGet(),
            atUnlock = true,
            tone = tone,
        )
    }

    /** The same, for a caller with no Context. */
    fun postAtUnlock(@StringRes messageRes: Int, tone: Tone = Tone.Refusal) =
        postAtUnlock(Message.Res(messageRes), tone)

    /** Retires [notice] only if it is still the one showing, so a newer post is never cut short. */
    fun dismiss(notice: Notice) {
        _current.update { if (it?.id == notice.id) null else it }
    }

    /**
     * Drops any pending confirmation. Called when the vault locks: a notice naming what was just
     * trashed or saved has no business surviving on top of the unlock screen.
     */
    fun clear() {
        _current.value = null
    }
}
