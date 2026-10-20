package com.localkeep.autofill

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.service.autofill.Dataset
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.localkeep.core.common.sortedByDisplayName
import com.localkeep.core.generator.PasswordGenerator
import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import com.localkeep.core.ui.SecureActivity
import com.localkeep.core.vault.AppSignatureCache
import com.localkeep.core.vault.LockState
import com.localkeep.core.vault.UnlockResult
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultLockedException
import com.localkeep.core.vault.VaultRepository
import dagger.hilt.android.AndroidEntryPoint
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Matches the vault list's own search debounce. Same reason, and it is the interval the onboarding
 * strength meter already uses: one keystroke is a frame of a word, not a decision.
 */
private const val PICKER_SEARCH_DEBOUNCE_MS = 250L

/**
 * The two suggestions LocalKeep offers of its own accord, once the user has made it their autofill
 * service: generate a password for a form that is creating an account, and search the vault by hand
 * for a form whose site or package matched nothing stored.
 *
 * Both exist because a strict match returns nothing far more often than a user expects. Before this
 * screen, tapping a field on any site not already in the vault produced an empty autofill menu,
 * which is indistinguishable from the service not running at all — and the vault's own answer, one
 * domain spelling away, was unreachable without leaving the form.
 *
 * Reached only through a dataset authentication, so the platform, not this app, decides it may
 * appear over the requesting window — the same route [AuthActivity] and [SaveConfirmActivity] take,
 * and for the same reason: a service is a background context and cannot start an activity itself.
 */
@AndroidEntryPoint
class PickerActivity : SecureActivity() {

    /** The in-flight search pass, so the next keystroke can cancel it. */
    private var searchJob: Job? = null

    @Inject
    lateinit var lockManager: VaultLockManager

    @Inject
    lateinit var repository: VaultRepository

    @Inject
    lateinit var appSignatureCache: AppSignatureCache

    private lateinit var mode: String
    private lateinit var fields: List<FillableField>
    private var callerWebDomain: String? = null
    private var callerPackageName: String? = null

    /** Held so Regenerate can replace it and "Use password" can read the one on screen. */
    private var generated: String? = null

    private var prompt: MasterPasswordPrompt? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val requestedMode = intent.getStringExtra(EXTRA_MODE)
        if (requestedMode != MODE_GENERATE && requestedMode != MODE_SEARCH) {
            cancel()
            return
        }
        mode = requestedMode
        fields = fieldsFromExtras(intent)
        if (fields.isEmpty()) {
            cancel()
            return
        }
        callerWebDomain = intent.getStringExtra(EXTRA_WEB_DOMAIN)
        callerPackageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)

        when {
            // Generation reads nothing from the vault, so a lock is no reason to refuse it — the
            // user is signing up, and sending them to a master-password prompt to obtain a string
            // of random characters would be a gate protecting nothing.
            mode == MODE_GENERATE -> showGenerator()
            lockManager.lockState.value is LockState.Unlocked -> showSearch()
            // The vault can lock between the suggestion row being built and the user tapping it —
            // the auto-lock timer runs on wall clock while the row sits in another app's menu.
            else -> showUnlock()
        }
    }

    // ------------------------------------------------------------------ generate

    private fun showGenerator() {
        val density = resources.displayMetrics.density
        val pad = (24 * density).toInt()
        val gap = (12 * density).toInt()

        val valueView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 18f
        }
        regenerate(valueView)

        setContentView(
            generatorLayout(
                context = this,
                pad = pad,
                gap = gap,
                heading = TextView(this).apply {
                    text = getString(R.string.autofill_generate_heading)
                    textSize = 18f
                },
                value = valueView,
                explanation = TextView(this).apply {
                    text = getString(R.string.autofill_generate_explanation)
                    textSize = 13f
                },
                use = Button(this).apply {
                    text = getString(R.string.autofill_generate_use)
                    setOnClickListener { useGenerated() }
                },
                regenerate = Button(this).apply {
                    text = getString(R.string.autofill_generate_regenerate)
                    setOnClickListener { regenerate(valueView) }
                },
                cancel = Button(this).apply {
                    text = getString(R.string.autofill_generate_cancel)
                    setOnClickListener { cancel() }
                },
            ),
        )
    }

    private fun regenerate(valueView: TextView) {
        // The generator's own defaults, deliberately — the same 20-character, four-class,
        // no-ambiguous-characters password the in-app generator produces. A weaker "more
        // compatible" variant here would mean the password a user gets by accepting the suggestion
        // is worse than the one they get by opening the app, with nothing on screen saying so.
        val password = runCatching { PasswordGenerator.generateRandom() }.getOrNull()
        generated = password?.value
        valueView.text = password?.value.orEmpty()
        if (password == null) toast(getString(R.string.autofill_generate_failed))
    }

    private fun useGenerated() {
        val password = generated
        if (password.isNullOrEmpty()) {
            cancel()
            return
        }
        // Every password field, not just the first: a sign-up form's second box is the confirmation,
        // and filling one of the two leaves the user to retype a 20-character random string by hand.
        // Except a change-password form's Current box (2026-10-02) — see generatedPasswordTargets.
        val passwordFields = SuggestionPolicy.generatedPasswordTargets(fields)
        if (passwordFields.isEmpty()) {
            cancel()
            return
        }
        val presentation = DatasetBuilder.rowPresentation(
            this,
            getString(R.string.autofill_generate_heading),
            getString(R.string.autofill_dataset_password_mask),
        )
        val dataset = Dataset.Builder().apply {
            passwordFields.forEach { setValue(it.id, AutofillValue.forText(password), presentation) }
        }.build()
        finishWith(dataset)
    }

    // ------------------------------------------------------------------ search

    private fun showUnlock() {
        val unlockPrompt = MasterPasswordPrompt(
            context = this,
            title = getString(R.string.autofill_unlock_title),
            subtitle = getString(R.string.autofill_picker_unlock_subtitle),
        ) { password -> unlock(password) }
        prompt = unlockPrompt
        setContentView(unlockPrompt.view)
    }

    private fun unlock(password: CharArray) {
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.Default) {
                try {
                    lockManager.unlock(password).let { result ->
                        if (result.opened) AuthOutcome.Success else AuthOutcome.Failed(result)
                    }
                } catch (locked: VaultLockedException) {
                    AuthOutcome.Throttled(locked.retryAfterMs)
                } catch (_: Exception) {
                    // Anything else is a fault in this gate, not a claim about the password — the
                    // same mapping AuthActivity uses, for the same reason.
                    AuthOutcome.Failed(UnlockResult.VAULT_UNREADABLE)
                }
            }
            password.fill('\u0000')
            if (outcome is AuthOutcome.Success) {
                showSearch()
            } else {
                prompt?.showError(authPromptMessage(outcome).resolve(this@PickerActivity))
            }
        }
    }

    private fun showSearch() {
        // The list this builds is a snapshot of plaintext logins held by a live Activity, and
        // locking the vault cannot reach into it — `Login.password` is an immutable String, so
        // there is nothing for `Zeroizer` to wipe. Re-resolving at fill time (see `fill`) stops a
        // password leaving after a lock; this stops the titles and usernames staying on screen
        // after one, which is the same list a shoulder-surfer was being kept away from.
        lifecycleScope.launch {
            lockManager.lockState.collect { state ->
                // Qualified: the innermost implicit receiver here is the CoroutineScope, and a bare
                // `cancel()` would resolve to `CoroutineScope.cancel()` — killing the collector
                // instead of the picker, which is silently the opposite of what this is for.
                if (state !is LockState.Unlocked) {
                    // Closing is the control; closing in silence is not. Nothing here extends the
                    // idle timer — `extendAutoLock` is called only from MainActivity.onUserInteraction,
                    // so typing into the search box above does not count — and this window sits over
                    // another app, possibly seconds after the master password was typed into it. A
                    // disappearance with nothing said reads as a crashed or missing service.
                    toast(getString(R.string.autofill_auth_error_interrupted))
                    this@PickerActivity.cancel()
                }
            }
        }
        // Through the visibility projection, never the raw item list: a concealed or duress-hidden
        // item is absent from every Android credential surface for as long as its lease runs, and a
        // hand-search inside another app is exactly the surface someone looking over a shoulder
        // would ask for.
        val logins = runCatching {
            repository.visibilitySnapshot().liveItems
                .filter { it.content is Login }
                .sortedByDisplayName { it.title }
        }.getOrNull().orEmpty()

        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val adapter = LoginAdapter(logins)
        val emptyView = TextView(this).apply {
            text = getString(
                if (logins.isEmpty()) {
                    R.string.autofill_picker_empty
                } else {
                    R.string.autofill_picker_no_results
                },
            )
            textSize = 14f
            visibility = if (logins.isEmpty()) View.VISIBLE else View.GONE
        }
        val listView = ListView(this).apply {
            this.adapter = adapter
            setOnItemClickListener { _, _, position, _ -> adapter.itemAt(position)?.let(::fill) }
        }
        val searchField = EditText(this).apply {
            hint = getString(R.string.autofill_picker_search_hint)
            imeOptions =
                EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            // This box is not a credential field, and the vault must never be offered to it.
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            addTextChangedListener(
                object : TextWatcher {
                    override fun afterTextChanged(s: Editable?) {
                        val query = s?.toString().orEmpty()
                        // Same shape as the vault list's search: cancel the in-flight pass, wait
                        // out the keystroke, then scan off the main thread. A keystroke is one
                        // frame of a word being typed, not a decision, and ten characters used to
                        // mean ten whole-vault passes on the UI thread to show the result of the
                        // tenth - on the one search box that renders on top of another app.
                        searchJob?.cancel()
                        searchJob = lifecycleScope.launch {
                            delay(PICKER_SEARCH_DEBOUNCE_MS)
                            val results = withContext(Dispatchers.Default) {
                                adapter.computeVisible(query)
                            }
                            val remaining = adapter.publish(results)
                            emptyView.text = getString(
                                if (logins.isEmpty()) {
                                    R.string.autofill_picker_empty
                                } else {
                                    R.string.autofill_picker_no_results
                                },
                            )
                            emptyView.visibility = if (remaining == 0) View.VISIBLE else View.GONE
                        }
                    }

                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit

                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                },
            )
        }

        setContentView(
            pickerLayout(
                context = this,
                pad = pad,
                heading = TextView(this).apply {
                    text = getString(R.string.autofill_picker_target, callerLabel())
                    textSize = 16f
                },
                search = searchField,
                empty = emptyView,
                list = listView,
            ),
        )
    }

    /**
     * Who the credential is about to go to, in the user's terms: the site, or the app's own label.
     *
     * Shared with [SaveConfirmActivity] since 2026-08-30 — see [callerLabel] for why the two
     * drifting apart mattered.
     */
    private fun callerLabel(): String = callerLabel(callerWebDomain, callerPackageName)

    private fun fill(item: VaultItem) {
        lifecycleScope.launch {
            val packageName = callerPackageName
            // The same signing-certificate check the automatic path runs before it offers anything.
            // An explicit pick is consent to fill *this* item here; it is not consent to fill an app
            // whose signer no longer matches the one first seen under that package name.
            //
            // The app-to-domain pairing gate is deliberately NOT applied, and deliberately not
            // recorded either: that gate asks whether a browser may be trusted to fill saved logins
            // for a site on its own, and the answer here is the pick itself. Granting the pair as a
            // side effect would turn one deliberate choice into a standing permission the user was
            // never shown.
            //
            // A null package refuses outright rather than falling through the check. Reaching here
            // with no package means the both-null caller: `FillResponseFactory` returns
            // `saveOnly(...)` before either suggestion site when a web domain is present without
            // one, so a domain-only request never opens this picker. That is the same deduction,
            // and the same treatment, that the card rows already got - a request the platform could
            // not attribute is one this app declines to fill, not one it fills unchecked because
            // there is no name to check against. `verifyOrPin` is signer-continuity for a *named*
            // package, so with no name there is nothing for it to be continuous with.
            if (packageName == null || !appSignatureCache.verifyOrPin(packageName)) {
                toast(getString(R.string.autofill_picker_untrusted))
                cancel()
                return@launch
            }
            // Re-resolved through the projection rather than filled from the object captured at
            // onCreate. `showSearch` snapshots every login once, and `Login.password` is an
            // immutable String — so locking the vault zeroizes the DEK but leaves that captured
            // plaintext fully readable in this Activity. Nothing in the picker extends the
            // auto-lock timer either (only `MainActivity.onUserInteraction` calls `extendAutoLock`,
            // and this is a different Activity), so a user reading down the list crosses the idle
            // timeout with the screen on and the row still filled a password out of a locked vault.
            // `requireVault()` fails closed once locked, so this yields null and the picker
            // cancels. Same reasoning as `reauthenticate` below and `AuthActivity.finishWithDataset`.
            val current = runCatching {
                repository.visibilitySnapshot().liveItems.firstOrNull { it.id == item.id }
            }.getOrNull()
            if (current == null) {
                cancel()
                return@launch
            }
            // Read off the re-resolved item, not the captured one: a reprompt flag raised since the
            // picker opened has to be honoured.
            if (current.reprompt) {
                showReprompt(current)
                return@launch
            }
            finishWithItem(current)
        }
    }

    private fun showReprompt(item: VaultItem) {
        val repromptPrompt = MasterPasswordPrompt(
            context = this,
            title = getString(R.string.autofill_auth_reprompt_title),
        ) { password -> reauthenticate(item, password) }
        prompt = repromptPrompt
        setContentView(repromptPrompt.view)
    }

    private fun reauthenticate(item: VaultItem, password: CharArray) {
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.Default) {
                try {
                    if (lockManager.reauthenticate(password)) {
                        AuthOutcome.Success
                    } else {
                        AuthOutcome.Failed(UnlockResult.WRONG_CREDENTIAL)
                    }
                } catch (locked: VaultLockedException) {
                    AuthOutcome.Throttled(locked.retryAfterMs)
                } catch (_: Exception) {
                    AuthOutcome.Failed(UnlockResult.VAULT_UNREADABLE)
                }
            }
            password.fill('\u0000')
            if (outcome !is AuthOutcome.Success) {
                prompt?.showError(authPromptMessage(outcome).resolve(this@PickerActivity))
                return@launch
            }
            // Re-resolved through the projection rather than filled from the captured object: a
            // lease can expire, and a lock or a conceal can land while the challenge is on screen.
            // Same reasoning as AuthActivity.finishWithDataset.
            val current = runCatching {
                repository.visibilitySnapshot().liveItems.firstOrNull { it.id == item.id }
            }.getOrNull()
            if (current == null) cancel() else finishWithItem(current)
        }
    }

    private fun finishWithItem(item: VaultItem) {
        val dataset = DatasetBuilder.buildSingleDataset(this, item, fields)
        if (dataset == null) cancel() else finishWith(dataset)
    }

    // ------------------------------------------------------------------ plumbing

    private fun finishWith(dataset: Dataset) {
        setResult(
            Activity.RESULT_OK,
            Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset),
        )
        finish()
    }

    private fun cancel() {
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    /**
     * A Toast rather than a dialog, for the reason SaveConfirmActivity gives: this window can be
     * sitting over another app's sign-in form, and a modal there is an interruption the user did
     * not ask for. Carries no site, no package and no item title.
     */
    private fun toast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
    }

    private fun spacing(top: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = top }

    /** Two-line rows over a filtered view of the vault's logins. */
    private inner class LoginAdapter(private val all: List<VaultItem>) : BaseAdapter() {
        private var visible = all

        /**
         * The whole-vault scan, run OFF the main thread by the caller.
         *
         * `VaultRepository.search` is the pass this repo has already measured at 51ms per keystroke
         * on a 10,000-item vault *with* the index cached, and it used to run here synchronously on
         * the UI thread of a window that sits over another app's sign-in form. The vault list and
         * the trash both moved their own search boxes off the main thread and behind a debounce;
         * this one, the only search that renders over somebody else's app, never got either.
         */
        fun computeVisible(query: String): List<VaultItem> =
            pickerSearchResults(all, query) { needle ->
                // A lock can land between the collector above closing this window and the next
                // keystroke being delivered. `search` throws when it does; the picker is already
                // finishing, and an empty list is the only honest answer left.
                runCatching { repository.search(needle) }.getOrDefault(emptyList())
            }

        /** Publishes a result computed by [computeVisible]. Main thread. */
        fun publish(results: List<VaultItem>): Int {
            visible = results
            notifyDataSetChanged()
            return visible.size
        }

        fun itemAt(position: Int): VaultItem? = visible.getOrNull(position)

        override fun getCount(): Int = visible.size

        override fun getItem(position: Int): Any? = visible.getOrNull(position)

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val density = resources.displayMetrics.density
            val row = convertView as? LinearLayout ?: LinearLayout(this@PickerActivity).apply {
                orientation = LinearLayout.VERTICAL
                // 48dp is the touch floor this project holds everywhere; a list row inside another
                // app's window is no place to go under it.
                minimumHeight = (48 * density).toInt()
                val inset = (8 * density).toInt()
                setPadding(inset, inset, inset, inset)
                // Both lines are capped and ellipsised. Neither was, and an imported item whose
                // title is 300 characters — which Bitwarden and 1Password exports both permit —
                // wrapped to seven full lines and ate a third of a picker shown over another app's
                // window. The vault list truncates the same item to one line; this is that rule.
                addView(
                    TextView(this@PickerActivity).apply {
                        id = TITLE_ID
                        textSize = 16f
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    },
                )
                addView(
                    TextView(this@PickerActivity).apply {
                        id = SUBTITLE_ID
                        textSize = 13f
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    },
                )
            }
            val item = visible.getOrNull(position)
            row.findViewById<TextView>(TITLE_ID).text = item?.title.orEmpty()
            row.findViewById<TextView>(SUBTITLE_ID).text =
                (item?.content as? Login)?.username.orEmpty()
            return row
        }
    }

    companion object {
        const val MODE_GENERATE = "generate"
        const val MODE_SEARCH = "search"

        // Arbitrary but stable view ids for the two TextViews in a recycled row. Not resources:
        // this module builds its views in code (see MasterPasswordPrompt) and findViewById needs
        // something to find.
        private const val TITLE_ID = 0x10c8ea01
        private const val SUBTITLE_ID = 0x10c8ea02

        private const val EXTRA_MODE = "com.localkeep.autofill.PICKER_MODE"
        private const val EXTRA_USERNAME_ID = "com.localkeep.autofill.PICKER_USERNAME_ID"
        private const val EXTRA_PASSWORD_IDS = "com.localkeep.autofill.PICKER_PASSWORD_IDS"
        private const val EXTRA_TOTP_ID = "com.localkeep.autofill.PICKER_TOTP_ID"
        private const val EXTRA_WEB_DOMAIN = "com.localkeep.autofill.PICKER_WEB_DOMAIN"
        private const val EXTRA_PACKAGE_NAME = "com.localkeep.autofill.PICKER_PACKAGE_NAME"

        fun intent(
            context: Context,
            mode: String,
            fields: List<FillableField>,
            webDomain: String?,
            packageName: String?,
        ): Intent = Intent(context, PickerActivity::class.java).apply {
            // One PendingIntent per mode per form, so FLAG_UPDATE_CURRENT cannot rewrite the
            // generate row's intent underneath the search row's.
            data = Uri.parse("localkeep-autofill://picker/$mode/${UUID.randomUUID()}")
            putExtra(EXTRA_MODE, mode)
            putExtra(EXTRA_USERNAME_ID, fields.firstOrNull { it.type == FieldType.Username }?.id)
            // An array, unlike AuthActivity's single id: the generator has to reach a sign-up form's
            // confirmation box as well as its password box.
            putExtra(
                EXTRA_PASSWORD_IDS,
                fields.filter { it.type == FieldType.Password }.map { it.id }.toTypedArray(),
            )
            putCurrentPasswordIds(fields)
            putExtra(EXTRA_TOTP_ID, fields.firstOrNull { it.type == FieldType.Totp }?.id)
            putExtra(EXTRA_WEB_DOMAIN, webDomain)
            putExtra(EXTRA_PACKAGE_NAME, packageName)
        }

        internal fun fieldsFromExtras(intent: Intent): List<FillableField> = buildList {
            intent.parcelableExtraCompat<AutofillId>(EXTRA_USERNAME_ID)
                ?.let { add(FillableField(it, FieldType.Username)) }
            addAll(intent.passwordFields(EXTRA_PASSWORD_IDS))
            intent.parcelableExtraCompat<AutofillId>(EXTRA_TOTP_ID)
                ?.let { add(FillableField(it, FieldType.Totp)) }
        }
    }
}

/**
 * The picker's visible rows for [query] — the vault's definition of a match, narrowed to what the
 * picker was already showing.
 *
 * This box used to run a matcher of its own over `title` and `Login.username` only, so a login
 * titled "Shopping" whose only URI was `amazon.co.uk` answered "No logins match that" to the one
 * query the user had every reason to type: the domain of the form they were standing on. The
 * in-app list found it, through `VaultRepository.search`, which also matches URIs, notes, tags, the
 * folder path and unmasked custom fields, pins `Locale.ROOT`, and folds diacritics — so "cafe"
 * found "Café" there and not here, and a Turkish dotted-I was mis-cased here alone.
 *
 * [search] is `VaultRepository.search` rather than the matcher itself because `matchesSearchQuery`
 * and `searchHaystack` are `internal` to `:core:vault` and unreachable from this module — the same
 * reason `searchTrash` exists there as a second entry point instead of a copied predicate. Nothing
 * is re-derived here, so the two cannot drift on what "matches" means.
 *
 * The intersection is the security control, not an optimisation. [search] runs over
 * `getAllItems()`, which still contains concealed and duress-hidden items; [all] is already
 * `visibilitySnapshot().liveItems` filtered to logins. Filtering [all] rather than [search]'s
 * answer means widening the query can never surface a row the projection had removed, and the
 * display-name order `showSearch` established survives.
 *
 * Deliberately does not lowercase or fold [query]: normalising is [search]'s job, and a second
 * normalisation here is exactly how the divergence this removes would grow back.
 */
internal fun pickerSearchResults(
    all: List<VaultItem>,
    query: String,
    search: (String) -> List<VaultItem>,
): List<VaultItem> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return all
    val matched = search(trimmed).mapTo(HashSet(), VaultItem::id)
    return all.filter { it.id in matched }
}

/**
 * The picker's window content: heading, search box, empty note, then the list filling the rest.
 *
 * The padding sits on an inner container, not on the root. The root is `fitsSystemWindows`, and for
 * a view with that flag the framework *replaces* its padding with the system-bar insets when they
 * are dispatched, so a `setPadding` on the root is silently thrown away. That left the heading
 * flush against the left edge while the list rows (which pad themselves) sat inset.
 */
internal fun pickerLayout(
    context: Context,
    pad: Int,
    heading: View,
    search: View,
    empty: View,
    list: View,
): View = insetRootWithPadding(context, pad) {
    addView(heading)
    addView(search)
    addView(empty)
    addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
}

/**
 * A `fitsSystemWindows` root holding one padded column, which [content] fills.
 *
 * The padding lives on the inner column because the root's would be replaced by the system-bar
 * insets (see [pickerLayout]). Shared so the picker and the generator prompt cannot disagree about
 * where the margin goes.
 */
private fun insetRootWithPadding(context: Context, pad: Int, content: LinearLayout.() -> Unit): View =
    LinearLayout(context).apply {
        fitsSystemWindows = true
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        orientation = LinearLayout.VERTICAL
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
                content()
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }

/**
 * The generator prompt's window content: heading, the password, a note, then Use / Regenerate / Cancel.
 * Padded on an inner column for the same reason as [pickerLayout].
 */
internal fun generatorLayout(
    context: Context,
    pad: Int,
    gap: Int,
    heading: View,
    value: View,
    explanation: View,
    use: View,
    regenerate: View,
    cancel: View,
): View {
    fun spacing() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = gap }
    return insetRootWithPadding(context, pad) {
        addView(heading)
        addView(value, spacing())
        addView(explanation, spacing())
        addView(use, spacing())
        addView(regenerate)
        addView(cancel)
    }
}
