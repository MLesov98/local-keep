package com.localkeep.feature.itemedit

import android.app.Activity
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localkeep.core.billing.Entitlement
import com.localkeep.core.billing.PurchaseOutcome
import com.localkeep.core.common.PendingSecret
import com.localkeep.core.common.fold
import com.localkeep.core.common.sortedByDisplayName
import com.localkeep.core.generator.PasswordGenerator
import com.localkeep.core.location.CaptureConvergence
import com.localkeep.core.location.CurrentLocationProvider
import com.localkeep.core.location.FixOutcome
import com.localkeep.core.location.FixRequest
import com.localkeep.core.location.LocationFailure
import com.localkeep.core.crypto.Zeroizer
import com.localkeep.core.model.FolderPickerState
import com.localkeep.core.model.Attachment
import com.localkeep.core.model.Address
import com.localkeep.core.model.Card
import com.localkeep.core.model.CardBrand
import com.localkeep.core.model.CardBrands
import com.localkeep.core.model.Coordinate
import com.localkeep.core.model.CustomField
import com.localkeep.core.model.CustomFieldType
import com.localkeep.core.model.Folder
import com.localkeep.core.model.Identity
import com.localkeep.core.model.ItemContent
import com.localkeep.core.model.ALWAYS_REAUTH_FIELDS
import com.localkeep.core.model.COORDINATE_FIELD
import com.localkeep.core.model.Login
import com.localkeep.core.model.NOTE_BODY_FIELD
import com.localkeep.core.model.PHRASE_FIELD
import com.localkeep.core.model.PHRASE_PASSPHRASE_FIELD
import com.localkeep.core.model.isValid
import com.localkeep.core.model.Note
import com.localkeep.core.model.Passkey
import com.localkeep.core.model.PasswordHistory
import com.localkeep.core.model.PasswordHistoryEntry
import com.localkeep.core.model.SeedPhrase
import com.localkeep.core.model.Totp
import com.localkeep.core.model.TotpAlgorithm
import com.localkeep.core.model.UriMatch
import com.localkeep.core.model.VaultItem
import com.localkeep.core.totp.OtpAuthParser
import com.localkeep.core.totp.TotpGenerator
import com.localkeep.feature.files.ExifStripper
import com.localkeep.feature.files.FileBlobStore
import com.localkeep.feature.files.SanitizerResult
import com.localkeep.core.ui.LkNotices
import com.localkeep.core.ui.LkUnlockNotices
import com.localkeep.core.vault.AttachmentQuota
import com.localkeep.core.vault.LockState
import com.localkeep.core.vault.ProTypeQuota
import com.localkeep.core.vault.SeedPhraseStatus
import com.localkeep.core.vault.SeedPhraseWordList
import com.localkeep.core.vault.UpdateOutcome
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultPresentationState
import com.localkeep.core.vault.VaultRepository
import com.localkeep.core.vault.VaultBucketExhaustedException
import com.localkeep.core.vault.VaultTooLargeForDeviceException
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.util.Base64
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * How far ahead a card may be dated before the value is a typo rather than an expiry.
 *
 * Payment networks issue at most about ten years out, so twenty is deliberately loose: the job is
 * to catch a slipped digit, not to argue with an unusual issuer.
 */
internal const val MAX_CARD_YEARS_AHEAD = 20

/**
 * Whether a typed expiry year cannot be one.
 *
 * The rule used to be `year <= 0`, which accepted every positive integer the two-digit well could
 * hold. Reported from hands-on use, 2026-08-11: "entered expiry 05/55 and it bugged, lets me
 * proceed" — 55 read as 2055, twenty-nine years out, and nothing said a word. A wizard that has
 * already decided a month of 13 is worth refusing has no consistent reason to accept that year.
 *
 * **Two digits mean 20YY**, which is what the `MM/YY` well prints and therefore what the user
 * typed; four digits are taken as written, so the flat form's four-digit field still works. Three
 * digits are neither: a year still being typed, which this does not mark and save refuses — see
 * [cardExpiryYearUnfinished].
 *
 * A year in the *past* stays valid, and that is not an oversight. Storing a card that has already
 * expired is an ordinary thing to want — the number is still what a refund or a dispute needs —
 * and refusing it would break a real use to catch a typo this rule already catches at the other
 * end. Blank stays valid for the reason given on [ItemEditUiState.cardExpMonthInvalid].
 *
 * [currentYear] is a parameter rather than a call to the clock so the test can state a year and
 * still be reading this function in 2047.
 */
internal fun cardExpiryYearInvalid(
    raw: String,
    currentYear: Int = java.time.Year.now().value,
): Boolean {
    if (raw.isBlank()) return false
    // Three digits only. A single digit is unfinished too, but it was always judged here — "0" is
    // marked, "1"-"9" read as 2001-2009 and pass — and it still is; save refuses it either way.
    if (raw.trim().length == 3) return false
    val full = cardExpiryYearFull(raw) ?: return true
    return full > currentYear + MAX_CARD_YEARS_AHEAD
}

/**
 * Whether [raw] is one or three digits — a two- or four-digit year part-way through being typed.
 *
 * Not marked inline, for the reason `CardExpiryValidationTest` gives for the wizard's single digit:
 * "2030" passes through "203", and a red ring under the caret at the third digit would be an error
 * about something the user has not finished typing. But never saved (S3-09, 2026-10-02): taken
 * as written, "202" was the year 202 — stored, shown as ".../02" by autofill, and too small for
 * the detail screen's expired warning to recognise as a year at all.
 *
 * One digit joined it on 2026-10-04 (C1 device pass): the flat form saved year "1" as the integer 1,
 * and the card face read "expires 12 / 1". The wizard never could — its expiry step waits for all
 * four MM/YY digits — so this is the flat form refusing what the wizard already refuses. Inline, a
 * single digit is judged as it always was ([cardExpiryYearInvalid] marks "0" and nothing else).
 */
internal fun cardExpiryYearUnfinished(raw: String): Boolean = raw.trim().length.let { it == 1 || it == 3 }

/**
 * The four-digit year [raw] means, or null when it does not mean one at all.
 *
 * Two digits are read as `20YY` — see [cardExpiryYearInvalid] for why that is the user's own
 * reading and not an assumption. Four are taken as written, but not under 1000: "0202" is the
 * year-202 slip of [cardExpiryYearUnfinished] with a leading zero (S3-09, 2026-10-02).
 */
internal fun cardExpiryYearFull(raw: String): Int? {
    val trimmed = raw.trim()
    val typed = trimmed.toIntOrNull() ?: return null
    if (typed <= 0) return null
    return when {
        trimmed.length <= 2 -> 2000 + typed
        trimmed.length == 4 && typed >= 1000 -> typed
        else -> null
    }
}

/**
 * Whether the year parses fine and is simply too far ahead to be an expiry.
 *
 * Split out from [cardExpiryYearInvalid] so the field can name the actual fault. Both cases used to
 * print "Year is the four digits printed on the card", which is a true sentence and the wrong
 * answer: someone who typed `55` and read that would type `2055` next, which fails for the same
 * reason and produces the same message. An error that sends the user round the loop it just caught
 * them in is worse than no error, and the repo's rule is that a message states the real cause.
 */
internal fun cardExpiryYearTooFarAhead(
    raw: String,
    currentYear: Int = java.time.Year.now().value,
): Boolean {
    if (raw.isBlank()) return false
    val full = cardExpiryYearFull(raw) ?: return false
    return full > currentYear + MAX_CARD_YEARS_AHEAD
}

/**
 * The network a saved card records. [carried] is the brand the card was loaded with, and
 * [carriedFor] the number it was stored against — null for a new card.
 *
 * A recognised number names its own network. An unrecognised one keeps [carried] only while it is
 * still the number [carried] was recorded for: that is the imported loyalty/co-brand case the carry
 * exists for. Once the number itself is edited the old brand describes a card that is no longer
 * there — device pass 2026-10-04 saved a Visa as `9999999999999995` and the detail screen went on
 * calling it "Visa card". Compared as digits, because a typed number is stored digits-only while an
 * imported one may still carry its spaces.
 */
internal fun cardBrandToStore(number: String, carried: CardBrand, carriedFor: String?): CardBrand =
    CardBrands.detect(number).takeIf { it != CardBrand.UNKNOWN }
        ?: carried.takeIf { carriedFor != null && number.filter(Char::isDigit) == carriedFor.filter(Char::isDigit) }
        ?: CardBrand.UNKNOWN

data class ItemEditUiState(
    val isNew: Boolean = true,
    val itemId: UUID = UUID.randomUUID(),
    /** Whether this item already carries files, which decides whether the attachments screen is
     * reachable without Pro — see the gate in `ItemEditScreen`. */
    val hasAttachments: Boolean = false,
    /**
     * How many files this item carries, and how many of those are images.
     *
     * The detail screen's attachments row used to be the bare word "Attachments" with a chevron,
     * identical whether the item held nothing, one PDF, or the two photographs of a passport that
     * are the whole point of an Identity. Reported 2026-09-03: *"I open an Identity and I see
     * Attachments, that is too broad — if somebody has taken a picture isn't it reasonable for him
     * to see View photos"*. Counting here rather than in the composable keeps the rule about
     * ViewModels holding `@StringRes` and arguments, never display strings.
     */
    val attachmentCount: Int = 0,
    val attachmentImageCount: Int = 0,
    /**
     * Document photographs taken in the Identity capture flow but not yet written to the item.
     *
     * The bytes are already encrypted into the blob store — a blob needs no item to exist, which is
     * what lets the camera come *before* the form instead of after it. Only the reference waits
     * here until Save. An abandoned flow therefore leaves an unreferenced blob, which is exactly
     * what `BlobGarbageCollector` reclaims on the next unlock; the alternative, deleting eagerly,
     * is the destructive-first ordering `AttachmentPickerViewModel.remove` already had to unlearn.
     */
    val identityPhotos: List<Attachment> = emptyList(),
    /** True while a captured frame is being stripped and encrypted. */
    val identityPhotoSaving: Boolean = false,
    /**
     * Set when the last shutter press was refused for want of a free attachment slot: how many
     * stored files have to go before another page will fit. Null when it was not refused.
     *
     * Its own field rather than [error], because the capture step already draws a failure line of
     * its own and this must not be mistaken for the generic "that picture did not save" — a quota
     * refusal has a cause and a way forward, and a retry is not it. The number comes from
     * `AttachmentQuota.mustRemove` for the reason that function exists: "remove one" is false for
     * a vault sitting far above the cap after an import, which never charges against it.
     */
    /**
     * A capture that failed, held where a rotation cannot lose it.
     *
     * The screen already had a `captureFailed` flag, and `rememberSaveable` made its *value*
     * survive — but the callback `captureIdentityPhoto` invokes closes over the state object of the
     * composition that started the capture. An activity recreate builds a new one, so a failure
     * resolving after the rotation wrote `true` into an object nothing renders any more: no
     * message, and the user free to Continue past a page whose photo never reached the vault.
     * A ViewModel outlives the recreate, which is the whole reason this lives here.
     */
    val identityPhotoFailed: Boolean = false,
    val identityPhotoQuotaMustRemove: Int? = null,
    val title: String = "",
    val type: ItemType = ItemType.LOGIN,
    val username: String = "",
    val password: String = "",
    /**
     * The entropy of [password] in bits when this app produced the value itself, null when the user
     * typed or pasted it.
     *
     * The generator has always scored entropy and named the result from it; the form scores a typed
     * password with zxcvbn (`LkStrength.ofScore`). Those are the right measures for their own inputs
     * and they disagree loudly on the same string — "Reasonable · 77 bits" on the Generator became
     * "Excellent" one tap later in the editor (F-29, 2026-09-15 visual pass). Carrying the bits with
     * the value lets the form keep the *generator's* verdict for a generated password instead of
     * re-deriving a second opinion from the characters.
     *
     * Carried, never recomputed: entropy is a property of how a value was drawn, not of the string,
     * and the only place that knows it is the draw. Cleared the moment the user edits the field —
     * one keystroke and this is no longer a description of what is in the well.
     */
    val generatedEntropyBits: Double? = null,
    /**
     * A login's websites, one entry per row.
     *
     * A list rather than the newline-joined String the form used to hold. The model has stored a
     * list all along ([Login.uris]); flattening it for the editor meant the one field whose label
     * had to explain its own convention — "Websites (one per line)" — and no way to remove a single
     * entry, validate one, or see how many there were without counting lines.
     *
     * Blank entries are legal *here* and dropped at save: an empty row is what the user is about to
     * type into, and deleting it out from under them as they tab away is worse than saving nothing.
     */
    val uris: List<String> = emptyList(),
    val noteBody: String = "",
    /**
     * The snapshot this item holds, or null if none has been taken yet.
     *
     * One nullable value rather than three parallel fields, because latitude without a capture time
     * is not a partial coordinate — it is a nonsense one. Save is refused until this is non-null,
     * and nothing else in the editor can put a value here: it arrives from a capture or from a
     * loaded item, never from typing.
     */
    val coordinateFix: Coordinate? = null,
    /** A capture is running. The action is disabled and the preview shows the searching state. */
    val coordinateCapturing: Boolean = false,
    /**
     * `SystemClock.elapsedRealtime()` at the tap, or null when nothing is running.
     *
     * Here rather than in the composable so the progress ring survives a rotation. Held in the
     * ViewModel for the same reason the capture itself is: the search continues across a
     * configuration change, and a ring that restarted from zero would say it had not.
     *
     * Monotonic, not wall-clock — an NTP correction mid-capture must not make the ring jump.
     */
    val coordinateCaptureStartedElapsedMillis: Long? = null,
    /** How long the running capture will wait before giving up. Pairs with the field above. */
    val coordinateCaptureTimeoutMillis: Long = FixRequest().timeoutMillis,
    /**
     * Whether the running capture is still getting better.
     *
     * The one thing the user is actually deciding on, and until this existed the screen could not
     * say it: the provider computed the answer to stop the capture and then threw it away, so the
     * UI showed a metre figure and left "has that number finished moving?" to be inferred from
     * watching it. Indoors it finishes moving almost immediately and stays put for the rest of the
     * window, which reads as a stall rather than as a reading that is ready.
     *
     * In-flight state, not content — stripped by [editableOnly], with the same argument as
     * [coordinateCapturing] beside it.
     */
    val coordinateConvergence: CaptureConvergence = CaptureConvergence.SEARCHING,
    /**
     * Whether [coordinateFix] was taken during this editing session rather than loaded from disk.
     *
     * What decides whether the editor shows the position or masks it. Hiding a measurement behind a
     * fingerprint prompt from the person who took it two seconds ago is theatre — they were holding
     * the phone. A fix that was already in the vault is a different thing: it is stored data, and it
     * is gated exactly as it is on the detail screen.
     */
    val coordinateJustCaptured: Boolean = false,
    /**
     * Why the last capture produced nothing, or null.
     *
     * Structured, not a message: each case is a different sentence *and* a different way out
     * (settings, turn location on, try again outdoors), and the screen resolves it.
     */
    val coordinateFailure: LocationFailure? = null,
    val totpIssuer: String = "",
    val totpAccount: String = "",
    /**
     * Previously-used passwords for this login, newest first and capped at
     * [PasswordHistory.MAX_ENTRIES].
     *
     * Read-only to the user — they can look at an entry or delete the lot, never edit one — but it
     * lives in the state rather than being read off `originalItem` at save time so that clearing it
     * takes effect on the screen the moment it is cleared.
     */
    val passwordHistory: List<PasswordHistoryEntry> = emptyList(),
    /**
     * When the loaded item was first written, and when it was last changed. Null on a new item,
     * which has not been written yet.
     *
     * Read-only: nothing on these screens sets them — [ItemEditViewModel.save] stamps the model — so
     * they exist here only so the detail screen has them to show. It had nothing: the whole app
     * showed no created or updated date anywhere, while the health dashboard shipped an "Old
     * passwords (90+ days)" category whose entire basis is a date the user could not see (F-36,
     * 2026-09-15 visual pass).
     */
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
    /**
     * When a Login's password was last replaced, or null when the vault has never recorded one —
     * an item written before the field existed, or one that arrived through an importer (none of
     * them set it; see `ItemMerge`).
     */
    val passwordChangedAt: Instant? = null,
    val totpSecret: String = "",
    val totpAlgorithm: TotpAlgorithm = TotpAlgorithm.SHA1,
    val totpDigits: String = "6",
    val totpPeriodSec: String = "30",
    val totpSteam: Boolean = false,
    val showQrScanner: Boolean = false,
    val totpCurrentCode: String = "",
    val totpRemainingSeconds: Int = 0,
    val cardHolder: String = "",
    val cardNumber: String = "",
    val cardExpMonth: String = "",
    val cardExpYear: String = "",
    val cardCvv: String = "",
    val cardPin: String = "",
    val cardNotes: String = "",
    /**
     * A Login's free-text notes.
     *
     * Every importer writes `Login.notes` — KeePass's Notes field, Bitwarden's, 1Password's, a
     * CSV notes column — and `VaultRepository.searchHaystack` has always indexed it, so a query
     * matching only a login's note returned a row whose every visible field lacked the term.
     * Nothing rendered it, and nothing let the user delete it: a KeePass migration looked
     * complete while quietly dropping one of that format's most-used fields from view. The
     * UI/UX blueprint lists notes in the Login read view; this is that field, given the same
     * treatment Card already had.
     */
    val loginNotes: String = "",
    // The card's stored brand, carried so an imported one survives an edit. It used to be
    // write-only — set from the PAN at save, read nowhere — so an importer that named a brand the
    // IIN ranges don't recognise (a loyalty/co-brand card) had it silently overwritten to UNKNOWN
    // on the first save. Kept here and preferred at save when detection comes back UNKNOWN.
    val cardBrand: CardBrand = CardBrand.UNKNOWN,
    val identityFirstName: String = "",
    val identityLastName: String = "",
    val identityCompany: String = "",
    val identitySsn: String = "",
    val identityPassport: String = "",
    val identityLicense: String = "",
    val identityEmail: String = "",
    val identityPhone: String = "",
    val identityAddressLine1: String = "",
    val identityAddressLine2: String = "",
    val identityAddressCity: String = "",
    val identityAddressState: String = "",
    val identityAddressZip: String = "",
    val identityAddressCountry: String = "",
    /**
     * The Recovery-phrase grid: one entry per numbered slot, sized to the chosen word count. The
     * word count is [phraseWords].size on save (the model stores no separate count), but it is held
     * here too so the count row can resize the grid before every slot is filled.
     */
    val phraseWords: List<String> = emptyList(),
    val phraseWordCount: Int = 12,
    /** The optional BIP-39 25th word. Masked with the words; never checked against them. */
    val phrasePassphrase: String = "",
    /** The slot the entry field is editing, or -1 for none (the view/detail state, and after save). */
    val phraseFocus: Int = 0,
    /** What is being typed into the focused slot — lowercased [a-z] only, resolved on commit. */
    val phraseDraft: String = "",
    /**
     * Words entered past the current count, held so that picking a larger count again puts them
     * back — see [withPhraseWordCount]. Never saved: [toSeedPhrase] reads [phraseWords] alone.
     */
    val phraseWordsSetAside: List<String> = emptyList(),
    /**
     * True once this editor is showing a phrase that came out of the vault rather than one the user
     * is typing in, which is what decides whether the grid is gated.
     *
     * Entering a secret is not disclosing a stored one, so a capture opens ungated. An *edit* of a
     * saved item is a disclosure — [ItemEditViewModel.load] puts the stored words straight into
     * [phraseWords], and `item/{id}/edit` is one ungated tap from the detail screen — so it goes
     * through the same §5.2 prompt the detail screen raises. Set by [ItemEditViewModel.load]; never
     * by anything the user does, and stripped by [editableOnly] so it cannot read as an edit.
     */
    val phraseGateRequired: Boolean = false,
    /**
     * The slot [ItemEditViewModel.focusPhraseSlot] was asked for while the gate was still shut, held
     * until the prompt passes so the tap lands where it was aimed instead of being swallowed. -1
     * when nothing is waiting.
     */
    val phrasePendingFocus: Int = -1,
    /**
     * Set once a checksum-valid grid has been proven to be *this vault's own* Recovery Kit — the
     * fifth banner state. Computed asynchronously (it needs key material the state cannot reach; the
     * ViewModel derives the recovery KEK and tests the vault's recovery block), so it lands a beat
     * after the last word does. Composed into [phraseStatus] as [SeedPhraseStatus.VAULTKIT].
     */
    val phraseIsVaultKit: Boolean = false,
    val folders: List<Folder> = emptyList(),
    /**
     * How many live items each folder holds, for the chooser's row names.
     *
     * `Item Flows.dc.html` names its rows "Personal (12)" — the count is part of the row, not a
     * detail behind it, because "which folder" is usually answered by how full one is.
     */
    val folderItemCounts: Map<UUID, Int> = emptyMap(),
    /**
     * The titles of the live logins in the vault, trimmed, as plain data for [suggestedTitle] to
     * pick the smallest free "Login N" against. Plain strings rather than a resolved name: this
     * ViewModel never holds display text, so the screen formats the label and does the comparing.
     * Presentation, not content - stripped by [editableOnly] like [folderItemCounts].
     */
    val existingLoginTitles: Set<String> = emptySet(),
    val folderId: UUID? = null,
    val newFolderName: String = "",
    val showNewFolderInput: Boolean = false,
    /** Where a folder created from the picker lands — the folder currently expanded, or the root. */
    val newFolderParentId: UUID? = null,
    /** Non-null while the delete-folder confirmation is showing for that folder. */
    val tags: List<String> = emptyList(),
    val tagInput: String = "",
    val customFields: List<CustomField> = emptyList(),
    val revealedCustomFieldIndices: Set<Int> = emptySet(),
    val revealedFields: Set<String> = emptySet(),
    /** Field awaiting a successful re-auth before it may be shown (§5.2); null when none. */
    val pendingRevealField: String? = null,
    /** True when [pendingRevealField] was raised by Copy rather than by Show, so the copy can be
     * completed once the prompt passes instead of leaving the user to tap again. */
    val copyAfterReveal: Boolean = false,
    /** Set for exactly one field once copying it is allowed; the row that owns that field performs
     * the copy and calls back to clear it. */
    val copyGrantedField: String? = null,
    /**
     * Whether this item is a favourite.
     *
     * Content, not in-flight state, so it counts as an unsaved edit and travels through
     * [editableOnly] untouched. Until 2026-09-20 (sweep 2B-6 / 4A-F5) the flag existed only on the
     * vault list's row menu: a full dump of the editor with its "Folder, tags and more" panel open
     * matched no node containing `favourite` or `star`, and the detail screen had neither a control
     * nor an indicator — so an item the list announced as a favourite could be opened, read and
     * edited with nothing on either screen saying so.
     */
    val favorite: Boolean = false,
    val reprompt: Boolean = false,
    val isSaving: Boolean = false,
    val saved: Boolean = false,
    /** Set once the item has actually reached the trash, which is what sends the detail screen back. */
    val trashed: Boolean = false,
    /**
     * Set when the open item stopped being visible under us, which sends both screens back.
     *
     * Separate from [trashed] because the cause is different and the copy must not claim a deletion:
     * a reveal session ended — by Hide again now, by a lock, or by the background grace expiring —
     * while this screen was holding a concealed item's decrypted fields. Lease *expiry* is not this
     * case: expiry makes the record ordinary and visible, so an open detail screen stays open.
     */
    val concealed: Boolean = false,
    /**
     * True until [ItemEditViewModel.loadItem] has run for an existing item.
     *
     * Without it the detail screen rendered its default state — a blank title, no fields —
     * for the frame between navigating in and the lookup returning, which is exactly what a
     * genuinely empty item looks like. On a large vault the lookup is a linear scan, so this
     * is not always one frame.
     */
    val isLoading: Boolean = false,
    @param:androidx.annotation.StringRes val error: Int? = null,
    /** The one format arg [error] takes — currently only the duplicate-tag name. Null for every
     * other error. */
    val errorArg: String? = null,
    /**
     * The title field is what the save refused over. A sentence at the bottom of a scrolling form
     * is not an answer to "why did nothing happen" — the field itself has to say so, which means
     * an error ring here and the focus that scrolls it into view.
     */
    val titleError: Boolean = false,
    /**
     * The card number field is what a new card's save refused over — same job as [titleError], and
     * for the same reason.
     *
     * The refusal has been correct since 2026-09-06 (a card that is nothing but a title cannot be
     * created) and reported itself as the form-wide `itemedit_error_check_fields` banner with no
     * field marked, at the foot of a seven-field form. `CardFields` already draws this distinction
     * for the expiry pair, whose comment records the identical complaint: a single failure message
     * at the bottom leaves the user to work out which box it means.
     *
     * Setting it also moves this refusal into [shouldRevealErrorBanner]'s `fieldOwnsError` arm, so
     * the banner no longer asks to be scrolled to while the field is asking the same — the pair of
     * opposing reveal requests that pinned the scroll on 2026-07-29.
     */
    val cardNumberError: Boolean = false,
    /**
     * The loaded item carries content this editor cannot map, so it may be shown but never written
     * back. Set by [loadItem]'s fallback branch; read by [save] and by the detail screen's Edit
     * affordance.
     */
    val readOnly: Boolean = false,
    /**
     * Set when a save landed but something else had written the item first and its password was
     * newer, so that password was kept instead of this editor's stale copy.
     *
     * Deliberately keeps the screen open — [saved] stays false — where every other successful save
     * navigates away. The user is about to walk off believing the password they were looking at is
     * the one in the vault, and it is not; a message that scrolls past during a screen transition
     * is not a message.
     */
    @param:androidx.annotation.StringRes val conflictNotice: Int? = null,
    val isPro: Boolean = false, // 14b — cards/identities/folders/tags/custom-fields gates
    val canPurchase: Boolean = false,
    /**
     * Play's own localised price, or null when this build cannot know it — see
     * [com.localkeep.core.billing.Entitlement.displayPrice]. Null selects the no-price wording;
     * it must never be replaced by a literal amount.
     */
    val proPrice: String? = null,
    val showProPrompt: Boolean = false,
    /**
     * The relying-party id a stored passkey was created for — a domain, shown verbatim.
     *
     * Read-only display data, populated by [withPasskeyContent]. There is no editable counterpart:
     * a passkey's `rpId` is bound into the credential itself at creation and cannot be retyped
     * without producing a different credential entirely.
     */
    val passkeyRpId: String = "",
    /**
     * What identifies the account to a human: [Passkey.userDisplayName], falling back to
     * [Passkey.userName], falling back to a base64url readout of the opaque [Passkey.userHandle]
     * when the relying party supplied neither. Computed once at load, in [withPasskeyContent], so
     * this screen has one string to show rather than three fields to fall back through itself.
     */
    val passkeyUsername: String = "",
    val passkeyCreatedAt: Instant? = null,
    val passkeySignCounter: Long = 0L,
) {
    /**
     * Redacted, like every secret-bearing type in `core/model`. This one holds every secret the editor holds — password, TOTP secret, card CVV and PIN, the identity
     * numbers, the seed phrase and the password history, and the
     * compiler-generated `toString()` would print all of it — into a crash report, a `Log.d` added
     * during a debugging session, or an exception message that interpolates the state. Nothing
     * interpolates it today; this is the guard for the next person who does (V27, 2026-09-12).
     */
    override fun toString(): String = "ItemEditUiState(redacted)"

    /**
     * Card expiry, checked as the user types.
     *
     * One definition, read by both the form (which marks the box) and `toContent` (which refuses
     * the save). They were always going to be the same rule; written twice they would have been
     * the same rule only until one of them was edited.
     *
     * Blank is valid — a card recorded without an expiry is an ordinary thing to want.
     */
    val cardExpMonthInvalid: Boolean
        get() = cardExpMonth.isNotBlank() && cardExpMonth.trim().toIntOrNull().let { it == null || it !in 1..12 }

    val cardExpYearInvalid: Boolean
        get() = cardExpiryYearInvalid(cardExpYear)

    /** One or three digits: not marked while typing, refused at save — see [cardExpiryYearUnfinished]. */
    val cardExpYearUnfinished: Boolean
        get() = cardExpiryYearUnfinished(cardExpYear)

    /** True for the one invalid year that has its own explanation — see [cardExpiryYearTooFarAhead]. */
    val cardExpYearTooFarAhead: Boolean
        get() = cardExpiryYearTooFarAhead(cardExpYear)

    /**
     * The BIP-39 verdict for the current grid — drives the banner, the grid tint and the save gate.
     *
     * Computed rather than stored, the same way [cardExpMonthInvalid] is: one rule, read by the form
     * (which colours the banner) and by the save gate (which refuses), and written once so the two
     * cannot drift.
     *
     * The fifth state — the entered words being *this* vault's own Recovery Kit — cannot be a pure
     * check: the vault never stores its mnemonic, so proving it means deriving the recovery KEK from
     * these words and testing whether it unwraps this vault's recovery block (see
     * [com.localkeep.core.vault.VaultKitMatch]). That is done asynchronously by the ViewModel into
     * [phraseIsVaultKit]; here it composes *on top* of the pure verdict — a vault-kit phrase is
     * checksum-valid, so [SeedPhraseStatus.VAULTKIT] ranks above [SeedPhraseStatus.VALID] and blocks
     * the save.
     */
    val phraseStatus: SeedPhraseStatus
        get() = phraseStatusOf(phraseWords)

    private fun phraseStatusOf(words: List<String>): SeedPhraseStatus {
        val base = SeedPhraseWordList.validator.status(words)
        return if (base == SeedPhraseStatus.VALID && phraseIsVaultKit) {
            SeedPhraseStatus.VAULTKIT
        } else {
            base
        }
    }

    /** Slots whose entered word is not a BIP-39 word — coral at its own slot before the grid fills. */
    val phraseBadIndices: List<Int>
        get() = SeedPhraseWordList.validator.badIndices(phraseWords)

    val phraseFilledCount: Int
        get() = phraseWords.count { it.isNotBlank() }

    /**
     * Whether the grid and the 25th word are uncovered.
     *
     * Derived from [revealedFields], not held beside it. It used to be its own boolean that
     * [ItemEditViewModel.togglePhraseReveal] flipped directly, which is how a stored phrase — the
     * one secret in this app that cannot be changed after it leaks — became the only
     * [ALWAYS_REAUTH_FIELDS] member the editor handed over on a tap. One field means the gate, the
     * auto-hide timer and the display can no longer disagree about whether it is showing.
     */
    val phraseRevealAll: Boolean
        get() = PHRASE_FIELD in revealedFields

    /**
     * Whether *how many words* this phrase has is still behind the reveal gate.
     *
     * The count is content, not chrome. A stored phrase carried a `REPROMPT` badge, a field reading
     * "Recovery phrase, hidden" and a copy button saying "unlocks first" — and, beside all three,
     * "12 words" in clear the instant the screen opened (F-26, 2026-09-15 visual pass). A screen
     * that publishes a fact about a secret it is simultaneously refusing to show is teaching the
     * user that the gate is decorative.
     *
     * The same rule on both screens, from one property, because that is the failure it is fixing:
     * the detail view and the editor each drew the count their own way and only one of them thought
     * about the gate. It follows [phraseGateRequired] rather than the item's `reprompt` flag, which
     * is what makes it true for a *stored* phrase and false for one being typed in — entering a
     * secret is not disclosing one, and a capture screen that hid its own word count would be
     * hiding a number the user is choosing.
     */
    val phraseCountHidden: Boolean
        get() = phraseGateRequired && !phraseRevealAll

    /**
     * A phrase may be saved once every slot holds a real BIP-39 word. Short-circuits on [type] so
     * the BIP-39 check never runs for a login or a note.
     *
     * **[SeedPhraseStatus.CHECKSUM] is a warning, not a block (4C-14, 2026-09-20).** A failed
     * checksum means the words are all real and one of them is wrong or two are swapped — and the
     * user transcribing a phrase off a piece of steel they can no longer read is exactly the person
     * who needs somewhere to put what they have. Refusing the save left them with nothing stored at
     * all, which is worse than storing a phrase the app has told them, in coral, does not verify;
     * the detail screen repeats that warning for as long as the item exists
     * ([ItemDetailScreen]'s `PhraseDetail`). The three states that still block are the ones where
     * saving would store something the user did not mean: an [SeedPhraseStatus.INCOMPLETE] grid
     * (blank slots), a [SeedPhraseStatus.BADWORD] slot (a word no wallet will ever accept), and
     * [SeedPhraseStatus.VAULTKIT] (this vault's own Recovery Kit, which must not go inside it).
     *
     * **Judged on the grid Save would write**, which is the grid with the focused slot's pending word
     * in it — `save()` flushes that word before it reads anything ([withPendingPhraseDraftCommitted]).
     * Judged on the committed grid alone, "zzzz" typed over a word of a checksum-failing phrase left
     * Save enabled — the grid still held real words — and the press then refused (C1, 2026-10-04).
     */
    val phraseSaveBlocked: Boolean
        get() {
            if (type != ItemType.PHRASE) return false
            val words = withPendingPhraseDraftCommitted().phraseWords
            return when (phraseStatusOf(words)) {
                SeedPhraseStatus.VALID -> false
                // Only at a length BIP-39 actually defines. `SeedPhraseValidator.status` folds "wrong
                // word count" into CHECKSUM along with a genuine checksum failure — its own comment
                // says reaching that branch "means checksum", which is true for every grid the UI can
                // produce and not for one built directly. An eleven-word grid is an unfinished phrase,
                // not a phrase whose last word disagrees with the others, and it stays refused.
                SeedPhraseStatus.CHECKSUM -> words.size !in PHRASE_COUNTS
                else -> true
            }
        }

    /**
     * Why Save cannot be pressed for a Recovery-phrase item right now, or null — F49, 2026-09-13
     * (device walk found the button carried no reason in its accessibility name).
     *
     * Keyed on the *status*, never on the button's `enabled`, which also folds in
     * `isSaving`/`readOnly`: hanging the reason on `enabled` would announce "the phrase isn't valid"
     * over a phrase that already passed its checksum, while an ordinary save is running. The banner
     * above the grid ([PhraseBanner] in ItemEditScreen.kt) gives sighted users the specific reason
     * (incomplete / bad word / failed checksum / this vault's own kit); this is what a screen reader
     * gets instead.
     *
     * **[SeedPhraseStatus.VAULTKIT] needs its own sentence, and that is why this is not one string.**
     * A Recovery-Kit phrase *is* checksum-valid — [phraseStatus] layers VAULTKIT on top of a passing
     * BIP-39 check — so "available once the recovery phrase is valid" told a screen-reader user to
     * repair something that is not broken, and named no action that could ever satisfy it.
     *
     * Both arms name the control first. `Modifier.labelled` *replaces* a node's accessible name
     * rather than adding to it, so a reason that opens with the vault-kit clause would leave the
     * button with no "Save" in its name at all — reason without identity, which is a different
     * accessibility defect from the identity-without-reason F49 set out to fix, not an improvement
     * on it. The vault-kit arm is therefore its own key, written whole in each of the five locales
     * and never assembled at runtime from this one plus the banner title: the two clauses join with
     * different word order and punctuation per language. Its vault-kit clause is taken verbatim from
     * `itemedit_phrase_banner_vaultkit_title`, so the sentence a screen reader hears and the banner
     * a sighted user sees say the same thing in the same words.
     */
    val phraseSaveDisabledReasonRes: Int?
        @androidx.annotation.StringRes
        get() = when {
            !phraseSaveBlocked -> null
            phraseStatus == SeedPhraseStatus.VAULTKIT ->
                R.string.itemedit_phrase_save_disabled_vaultkit_a11y
            else -> R.string.itemedit_phrase_save_disabled_a11y
        }

    /**
     * Commit a pending phrase-word edit into [phraseWords] before a save reads them. [focusPhraseSlot]
     * loads a slot's word into [phraseDraft] to edit, but only [commitPhraseWord] (the autocomplete
     * chip) writes it back — so a correction typed and then saved without tapping the chip would be
     * dropped and the old word kept, silently. It is the pending-folder-name hazard [save] already
     * guards, in a field where the lost edit is a seed word. Non-blank drafts only, mirroring that
     * guard: a cleared draft is left to the checksum gate rather than blanking a slot on the way out.
     */
    /**
     * Move the phrase grid's focus to [index], keeping whatever was typed in the slot being left.
     *
     * A pure function on the state for the reason the other pure helpers here are: the bug it fixes
     * lived in a ViewModel method that a JVM test cannot reach, and the module's tests would not
     * have caught it.
     *
     * What it fixes: focusing a slot used to overwrite [ItemEditUiState.phraseDraft] with the new
     * slot's stored word, so a word typed into the previous slot was simply dropped. The only thing
     * that ever wrote into [ItemEditUiState.phraseWords] was the autocomplete chip, and a user
     * tapping through twelve slots is not looking at it. Every cue said the word had landed — the
     * field bordered green, the hint read "One match" — and the loss only surfaced later as a Save
     * button that never enabled, naming no slot.
     *
     * Commits through [withPendingPhraseDraftCommitted], the same helper save uses, so navigating
     * and saving can never disagree about what the grid holds.
     */
    fun withPhraseSlotFocused(index: Int): ItemEditUiState {
        if (index !in phraseWords.indices) return this
        val committed = withPendingPhraseDraftCommitted()
        return committed.copy(phraseFocus = index, phraseDraft = committed.phraseWords[index])
    }

    /**
     * The draft stays in the field after the flush (C1, 2026-10-04). Both the focused slot and the
     * entry field draw [phraseDraft], so emptying it here emptied the screen: a save refused over
     * "zzzz" in slot 12 named slot 12 as the fault and showed it blank, and the next keystroke
     * started from nothing. Left equal to the slot's word, it is by this function's own test no
     * longer pending, so a second flush writes nothing.
     */
    fun withPendingPhraseDraftCommitted(): ItemEditUiState {
        if (type != ItemType.PHRASE) return this
        val i = phraseFocus
        if (i !in phraseWords.indices || phraseDraft.isBlank() || phraseWords[i] == phraseDraft) return this
        return copy(phraseWords = phraseWords.toMutableList().also { it[i] = phraseDraft })
    }

    /**
     * The grid resized to [count] slots, focused on its first gap — see
     * [ItemEditViewModel.onPhraseWordCountChanged].
     *
     * Words past the new size are set aside, not dropped, and a larger count takes them back in
     * order. Device pass 2026-10-04 (C1): 24 words entered, a tap on 15 or 12 — the chips are 48dp
     * plates side by side, so a near-miss is the ordinary route — and words 13–24 were gone, with
     * tapping 24 again returning blank slots. What is saved is still only the grid on screen. A word
     * half-typed into the focused slot is flushed in first, for the same reason save flushes it.
     */
    fun withPhraseWordCount(count: Int): ItemEditUiState {
        val committed = withPendingPhraseDraftCommitted()
        val all = committed.phraseWords + committed.phraseWordsSetAside
        val resized = List(count) { all.getOrElse(it) { "" } }
        return committed.copy(
            phraseWordCount = count,
            phraseWords = resized,
            phraseWordsSetAside = all.drop(count).dropLastWhile { it.isBlank() },
            phraseDraft = "",
            phraseFocus = resized.indexOfFirst { it.isBlank() },
        )
    }

    /**
     * The grid replaced by [filled], a paste already cut to the current count by
     * [ItemEditViewModel.pastePhrase]. A paste replaces the phrase, so words set aside from the one
     * it replaced are dropped with it rather than reappearing after the pasted ones.
     */
    fun withPastedPhrase(filled: List<String>): ItemEditUiState {
        return copy(
            phraseWords = filled,
            phraseWordsSetAside = emptyList(),
            phraseDraft = "",
            phraseFocus = filled.indexOfFirst { it.isBlank() },
        )
    }

    /**
     * The state after a QR code or `otpauth://` link has been read successfully.
     *
     * A pure function on the state so the title behaviour below can be tested without a ViewModel —
     * it shipped broken once and the reason nothing caught it is that it lived inside a lambda
     * inside a `fold` inside a suspendable class.
     *
     * The QR already said whose code this is, so asking again was asking the user to retype
     * something the app had just read. Until 2026-07-29 a successful scan of a GitHub code left
     * [title] empty — and title is required — so the scan appeared to work while Save stayed
     * blocked, with the issuer sitting visibly in the next field down.
     *
     * Filled only when blank: re-scanning to correct a mistyped seed must not overwrite a name the
     * user chose. [Totp.account] is the fallback for issuers that omit the issuer parameter.
     */
    fun withScannedTotp(totp: Totp): ItemEditUiState {
        val derived = totp.issuer.ifBlank { totp.account }.trim()
        val newTitle = title.ifBlank { derived }
        return copy(
            // A Login carries its own second factor (`Login.totp`), and the fields written below
            // are the very ones `withLoginTotp` fills — so adding a factor to a login must leave
            // the type alone. It did not until 2026-09-20: a scan, or an `otpauth://` link pasted
            // into the login's own Two-factor card, flipped the editor to a standalone 2FA item,
            // and Save then wrote a `Totp` content — silently dropping the username, password,
            // websites and notes, with no confirmation and no way back. Only the standalone 2FA
            // wizard reaches here on a non-Login state (`presetType` has already set TOTP by the
            // time its chooser renders), so the type it wants is the one set here.
            type = if (type == ItemType.LOGIN) type else ItemType.TOTP,
            title = newTitle,
            // Clearing the error matters as much as filling the field: the caption is what tells the
            // user Save is refusing, and leaving it up beside a now-populated Title reads as the
            // app failing to notice its own answer.
            titleError = if (newTitle.isNotBlank()) false else titleError,
            totpIssuer = totp.issuer,
            totpAccount = totp.account,
            totpSecret = totp.secretBase32,
            totpAlgorithm = totp.algorithm,
            totpDigits = totp.digits.toString(),
            totpPeriodSec = totp.periodSec.toString(),
            totpSteam = totp.steam,
        )
    }

    /**
     * The state for viewing a stored passkey — a pure function on the state, same reasoning as
     * [withScannedTotp]: this is what [loadItem] maps a [Passkey] onto, and it needs to be testable
     * without constructing the ViewModel and its repository/lock-manager/entitlement dependencies.
     *
     * [readOnly] is set here, not left to the caller: every path that produces a passkey's detail
     * state must come out non-editable, because [toContent] has no form fields to rebuild a
     * `Passkey` from — a save reaching it would either destroy `privateKeyRef`, `credentialId` and
     * `signCounter` by overwriting them with defaults, or (in the defended branch) do nothing
     * silently. Read-only is what makes the second, safe outcome the only reachable one.
     */
    fun withPasskeyContent(passkey: Passkey): ItemEditUiState = copy(
        type = ItemType.PASSKEY,
        passkeyRpId = passkey.rpId,
        passkeyUsername = passkey.userDisplayName.ifBlank { passkey.userName }
            .ifBlank { Base64.getUrlEncoder().withoutPadding().encodeToString(passkey.userHandle) },
        passkeyCreatedAt = passkey.createdAt,
        passkeySignCounter = passkey.signCounter,
        readOnly = true,
    )

    enum class ItemType {
        LOGIN,
        NOTE,
        TOTP,
        CARD,
        IDENTITY,
        COORDINATE,
        PHRASE,
        PASSKEY,
    }
}

// ALWAYS_REAUTH_FIELDS, COORDINATE_FIELD, PHRASE_FIELD and PHRASE_PASSPHRASE_FIELD now live in
// `:core:model` (ReauthFields.kt) and are re-exported by the imports at the top of this file.
// They moved because `internal` is module-scoped: the autofill service and the credential
// provider — the two surfaces that hand a secret to another app entirely — could not see this
// list and gated on the per-item `reprompt` flag alone, which defaults to false.

/**
 * The reveal-gate key for the live TOTP code — the six digits, not the seed.
 *
 * Deliberately *not* in [ALWAYS_REAUTH_FIELDS]: §5.2 gates the seed on every item and lets the
 * one-shot code through, because a code that demanded a fingerprint every thirty seconds is a code
 * nobody would use. The per-item reprompt flag is the part that was missing — on a flagged item the
 * card rendered the current second factor in the clear on open and copied it on one tap, while
 * `VaultItemRow` and the autofill dataset both withheld that same value from that same item.
 */
internal const val TOTP_CODE_FIELD = "totpCode"

/**
 * The reveal-gate key for the hidden custom field at [index].
 *
 * One spelling, shared by the detail screen and the editor, because they reveal the same secret
 * through two different pieces of state and had drifted apart: the detail screen already routed
 * `custom:N` through the gate, while the editor's own toggle flipped
 * [ItemEditUiState.revealedCustomFieldIndices] directly. On a reprompt item that meant the password
 * demanded a fingerprint and the "recovery codes" field beside it opened on a tap.
 */
internal fun customFieldRevealKey(index: Int): String = "custom:$index"

/**
 * The reveal-gate key for the password-history entry at [index].
 *
 * Always re-auth gated (see [requiresReauthFor]), on every item, regardless of its reprompt flag —
 * a deliberately stricter rule than the *current* password on the same screen gets. A password the
 * user has stopped using is very often one they still use somewhere else, and unlike the live
 * password there is no routine reason to look at one: nobody needs a former password to sign in.
 * A secret with no everyday use has nothing to lose from asking every time.
 */
internal fun passwordHistoryRevealKey(index: Int): String = "$PASSWORD_HISTORY_FIELD_PREFIX$index"

internal const val PASSWORD_HISTORY_FIELD_PREFIX = "passwordHistory:"

/** The custom-field index [field] names, or null if it is not a custom-field key. */
internal fun customFieldIndexOf(field: String): Int? =
    field.removePrefix("custom:").takeIf { it != field }?.toIntOrNull()

/**
 * Re-masks the custom field at [index] in *both* of the places a reveal writes it.
 *
 * A custom field is revealed through two pieces of state — [ItemEditUiState.revealedFields] for the
 * detail screen's `SecretReadField`, the index set for the editor — and one auto-hide has to clear
 * both, because only one auto-hide survives. A detail-screen reveal arms a timer under the key
 * `custom:N` that drops the key from `revealedFields`, then hands off to `revealCustomFieldNow`,
 * which schedules under the *identical* key; scheduling cancels the timer already held under that
 * key, so the second body is the only one that ever runs. When it cleared just the index set, the
 * detail row stayed legible past its 30 s promise — and with it the ungated clipboard that
 * `requestCopy` grants any field still in `revealedFields`.
 *
 * Subtracting the key on the editor-only path, where it was never added, is a no-op.
 */
internal fun ItemEditUiState.customFieldAutoHidden(index: Int): ItemEditUiState = copy(
    revealedCustomFieldIndices = revealedCustomFieldIndices - index,
    revealedFields = revealedFields - customFieldRevealKey(index),
)

/** Whether exposing [field] — by revealing it or by copying it — needs re-authentication first. */
/**
 * Copies an embedded [Totp] out of a Login into the same state fields a standalone TOTP item
 * uses.
 *
 * A `Login.totp` has been imported and persisted correctly since B4a — from KeePass and from
 * every source that carries a seed — but nothing anywhere read it back out, so a login's second
 * factor was invisible and unusable. Reusing the TOTP item's fields rather than adding a second
 * set is what lets `TotpCard`, the ticker and the reveal gate work for both without branching.
 *
 * A login with no factor clears those fields rather than leaving them alone. `loadItem` builds on
 * the state it already holds, so on the detail screen's reload after the editor removed a factor,
 * "leave them alone" kept the removed seed: the code card and the "2FA secret" row stayed on screen,
 * ticking, until the user left (device pass 2026-10-04, A3 LOG-07).
 */
internal fun ItemEditUiState.withLoginTotp(totp: Totp?): ItemEditUiState =
    if (totp == null) {
        val none = ItemEditUiState()
        copy(
            totpIssuer = none.totpIssuer,
            totpAccount = none.totpAccount,
            totpSecret = none.totpSecret,
            totpAlgorithm = none.totpAlgorithm,
            totpDigits = none.totpDigits,
            totpPeriodSec = none.totpPeriodSec,
            totpSteam = none.totpSteam,
            totpCurrentCode = none.totpCurrentCode,
            totpRemainingSeconds = none.totpRemainingSeconds,
        )
    } else {
        copy(
            totpIssuer = totp.issuer,
            totpAccount = totp.account,
            totpSecret = totp.secretBase32,
            totpAlgorithm = totp.algorithm,
            totpDigits = totp.digits.toString(),
            totpPeriodSec = totp.periodSec.toString(),
            totpSteam = totp.steam,
        )
    }

/**
 * Whether this item has a second factor to compute — the ticker's condition and
 * [ItemEditViewModel.refreshTotpCode]'s guard, which must be the same question.
 *
 * A Login carries a second factor too (see `withLoginTotp`); this is not TOTP-item-only.
 */
internal fun ItemEditUiState.carriesTotpSecret(): Boolean =
    (type == ItemEditUiState.ItemType.TOTP || type == ItemEditUiState.ItemType.LOGIN) &&
        totpSecret.isNotBlank()

/**
 * Whether the typed 2FA secret is one the generator cannot read.
 *
 * What the SECRET KEY well's error ring and its caption are driven from. Until 2026-09-20 (sweep
 * 4B-3) an invalid Base32 secret — `ABCDEF01`, say, where 0 and 1 are not in the alphabet — was
 * refused at Save with the generic `itemedit_error_check_fields` banner and **no field marked at
 * all**: the preview card simply vanished, every well kept its normal ring, and the refusal was
 * untraceable from where Save sits. That is the exact failure the DIGITS and PERIOD marks beside it
 * were added to end, on the one field this item type cannot do without.
 *
 * Asked of the decoder rather than of a regex, so it answers the same question `toContent` asks and
 * the two can never disagree about what is valid — length and trailing bits included, not just the
 * alphabet. Normalised exactly as `toTotp` normalises it, and with DIGITS and PERIOD left at their
 * defaults: those two carry their own marks, and a 7 typed into DIGITS must not also paint the
 * secret red.
 *
 * Blank is not an error. The user may still be typing, and an empty required field has its own
 * refusal (`itemedit_error_totp_secret_required`).
 */
internal fun ItemEditUiState.totpSecretIsUnreadable(): Boolean {
    if (totpSecret.isBlank()) return false
    val candidate = Totp(
        secretBase32 = totpSecret.filterNot(Char::isWhitespace).uppercase(Locale.ROOT),
    )
    return runCatching { TotpGenerator.generate(candidate) }.isFailure
}

internal fun ItemEditUiState.requiresReauthFor(field: String): Boolean =
    field in ALWAYS_REAUTH_FIELDS ||
        field.startsWith(PASSWORD_HISTORY_FIELD_PREFIX) ||
        isHiddenCustomField(field) ||
        reprompt

/**
 * Whether [field] names a custom field the user marked **Hidden**.
 *
 * Gated on every item, whatever its own reprompt flag says. UI/UX spec §5.2 lists "notes/fields the
 * user marked secret" beside the CVV and the TOTP seed, and until 2026-09-20 (release sweep 2B-1)
 * nothing implemented that half: `custom:N` is in no fixed set, so a hidden field was gated only
 * when the item's reprompt flag happened to be on — and on an ordinary imported login a field
 * called "TOTP seed" or "Security answer" revealed on one tap and copied to the clipboard on
 * another, with the copy control not even labelled as gated.
 *
 * Not the argument [NOTE_BODY_FIELD] refuses. That one is about gating every note ever written,
 * including the ones whose owner never asked; here the user reached for the Hidden type by hand on
 * this one field. The marking *is* the request, and honouring it is the whole point of offering it.
 */
private fun ItemEditUiState.isHiddenCustomField(field: String): Boolean =
    customFieldIndexOf(field)
        ?.let { customFields.getOrNull(it) }
        ?.type == CustomFieldType.HIDDEN

/**
 * Strips everything that isn't the user's own content — reveal state, in-flight flags, the ticking
 * TOTP code, entitlement, and the transient input buffers for sub-dialogs — so two snapshots can
 * be compared to answer "are there unsaved edits?".
 */
/**
 * Whether a title is still the one derived from the website, rather than one the user typed.
 *
 * Blank counts as ours: there is nothing to protect. Otherwise it is ours only while it still
 * exactly equals [lastDerived] — the moment the user edits the title the two diverge and the
 * derivation stops for good.
 *
 * A free function so it can be tested without a ViewModel. It replaced a plain `title.isBlank()`
 * check, which looked equivalent and was not: that check runs on every keystroke, and `hostOf`
 * accepts a host the instant its TLD reaches two characters, so on any three-letter TLD the title
 * stopped being blank one keystroke early and froze — "github.com" became the title "github.co",
 * every time, plausibly enough to go uncorrected.
 */
internal fun titleIsStillDerived(title: String, lastDerived: String?): Boolean =
    title.isBlank() || title == lastDerived

/**
 * The title [ItemEditViewModel.isDirty] compares a new item against: the pristine one, unless the
 * title in the form is still exactly the suggestion the screen filled in (2026-10-15).
 *
 * A new Login now opens with "Login 1" already in its Title, so without this an untouched form
 * differs from a pristine one and Back asks to discard nothing. Once the user edits the title the
 * two diverge and the prompt returns; anything else they enter differs on its own.
 */
internal fun titleAfterForgiving(title: String, applied: String?, pristine: String): String =
    applied?.takeIf { it == title } ?: pristine

/** The state with the identity camera's refusal cleared; see [ItemEditViewModel.clearIdentityPhotoFailure]. */
internal fun ItemEditUiState.withoutIdentityPhotoRefusal(): ItemEditUiState =
    copy(identityPhotoFailed = false, identityPhotoQuotaMustRemove = null)

// A word typed into a phrase slot and not yet committed is rescued first, the way Save rescues it
// (`withPendingPhraseDraftCommitted`): the draft is stripped below as presentation, but once it has
// been typed over its slot it is the only place that input lives, and Back used to drop it without
// asking. A draft that merely mirrors its slot commits to itself, so focusing a slot is still no edit.
internal fun ItemEditUiState.editableOnly(): ItemEditUiState = withPendingPhraseDraftCommitted().copy(
    revealedFields = emptySet(),
    revealedCustomFieldIndices = emptySet(),
    pendingRevealField = null,
    copyAfterReveal = false,
    copyGrantedField = null,
    isSaving = false,
    saved = false,
    trashed = false,
    error = null,
    errorArg = null,
    totpCurrentCode = "",
    totpRemainingSeconds = 0,
    showQrScanner = false,
    showNewFolderInput = false,
    newFolderName = "",
    tagInput = "",
    folders = emptyList(),
    // Stripped for the same reason `folders` is, and missing it produced a real defect: opening any
    // existing item's editor and pressing Back with nothing touched always raised "Discard
    // changes?". `loadItem()` anchors `baseline` at the end of its own run, but `loadFolders()` is
    // the next call in `init` and it is what fills these counts — so the baseline was anchored
    // against an empty map and every later comparison differed by it. New items dodged it only
    // because the screen calls `presetType()` afterwards, which re-anchors a second time; an
    // existing item gets no such second anchor. The picker's row counts are presentation, not the
    // user's content, so the fix belongs here rather than in the ordering.
    folderItemCounts = emptyMap(),
    // The same argument as the counts above: filled by `loadFolders()`, which runs after the
    // baseline is anchored, and not something the user typed.
    existingLoginTitles = emptySet(),
    // Which branches of the folder tree are open is picker presentation, not content. The add
    // flow's `seedFolderFromPending` writes it alongside `folderId` when the FAB is pressed inside
    // a *nested* folder, and `isDirty()` forgives `folderId` but could not forgive this — so an
    // untouched new item opened from a depth-2 folder asked to discard changes it did not have.
    isPro = false,
    canPurchase = false,
    proPrice = null,
    showProPrompt = false,
    // In-flight capture state, not content. Without these, tapping the capture action and having it
    // fail would count as an unsaved edit and raise the discard confirmation on the way out — for a
    // change that produced nothing. `coordinateFix` is deliberately NOT reset: a captured fix the
    // user has not saved yet is exactly the kind of work the discard prompt exists to protect.
    coordinateCapturing = false,
    coordinateCaptureStartedElapsedMillis = null,
    coordinateFailure = null,
    coordinateConvergence = CaptureConvergence.SEARCHING,
    // A refused shutter press for the same reason `coordinateFailure` is here: it produced no
    // content, so it must not read as an unsaved edit and raise the discard prompt on the way out.
    identityPhotoQuotaMustRemove = null,
    // Which slot is focused, what is half-typed into it, and whether reveal is on are capture-screen
    // presentation, not content — the words, the count and the passphrase are. Without these
    // resets, tapping a slot or toggling reveal on an otherwise-unchanged phrase would raise the
    // discard confirmation on the way out, exactly the coordinate-capture bug above.
    phraseDraft = "",
    phraseFocus = -1,
    // Whether the gate is armed, and which slot is waiting behind it, are gate bookkeeping — not
    // content. `revealedFields = emptySet()` above already strips the reveal itself, since
    // `phraseRevealAll` reads from there.
    phraseGateRequired = false,
    phrasePendingFocus = -1,
    // Derived from the words (async), not content — resetting it keeps a vault-kit re-check from
    // reading as an unsaved edit against the baseline.
    phraseIsVaultKit = false,
    // How the password in the well was produced, not the password itself. It is never written to the
    // vault, so a value that differs only by this is not an edit — and `seedGeneratedPassword`
    // deliberately forgives the password it seeds, which this would otherwise un-forgive.
    generatedEntropyBits = null,
)

/**
 * The nav argument every item route carries — `item/{itemId}` and `item/{itemId}/edit`.
 *
 * Spelled out here rather than imported: `:feature:itemedit` cannot depend on `:app`, where the
 * routes are declared. If the graph ever renames it, this degrades rather than breaks — the
 * construction-time load finds nothing, [isShowingWrongItem] holds the screen exactly as it did
 * before this existed, and the screen's own effect still loads the item.
 */
internal const val ROUTE_ITEM_ID = "itemId"

@HiltViewModel
class ItemEditViewModel @Inject constructor(
    private val repository: VaultRepository,
    private val lockManager: VaultLockManager,
    private val entitlement: Entitlement,
    private val savedStateHandle: SavedStateHandle,
    /**
     * Last, and defaulted, so the dozen existing test call sites keep their positional form.
     *
     * The default refuses rather than succeeding: a test that has not been given a provider is one
     * that is not about capture, and a silently working fake would let a capture path pass in a test
     * that never meant to exercise it. Hilt ignores the default and uses the binding in `AppModule`.
     */
    private val locationProvider: CurrentLocationProvider =
        CurrentLocationProvider { _, _, _ -> FixOutcome.Failure(LocationFailure.UNAVAILABLE) },
    /**
     * Last and defaulted, for the same reason [locationProvider] is: the dozen existing positional
     * test call sites keep compiling, and a test that was not given a store is one that is not
     * about capture. Null refuses rather than silently succeeding. Hilt supplies the real binding.
     */
    private val fileBlobStore: FileBlobStore? = null,
) : ViewModel() {
    // `isPro` is seeded from the flow's current value, not left to the collector in `init`. That
    // collector runs on viewModelScope, so between construction and its first emission a Pro user
    // reads as free — long enough for the add form to refuse a Card and raise the upsell at someone
    // who already paid. `Entitlement.isPro` is a StateFlow, so the value is there to be read.
    private val _state = MutableStateFlow(
        ItemEditUiState(isPro = entitlement.isPro.value, canPurchase = entitlement.canPurchase,
            proPrice = entitlement.displayPrice.value),
    )
    val state: StateFlow<ItemEditUiState> = _state.asStateFlow()
    private var originalItem: VaultItem? = null

    /**
     * The route id [loadItem] has already run a lookup for, whatever the answer was.
     *
     * The honest trace of an attempt, and it has to be its own field. `error` looks like one and is
     * not: every `update {}` clears it (see below), and `loadFolders()` runs immediately after
     * [restoreRequestedItem] in `init` — so a not-found error set during construction is wiped
     * before anything can read it. `anItemThatIsGoneIsLookedUpOnceAndThenLeftAlone` was written
     * against `error` first and failed on exactly that: `expected:<2131624192> but was:<null>`.
     */
    private var attemptedLoadOf: String? = null

    /** The form as last loaded or saved; anything different from this is an unsaved edit. */
    private var baseline: ItemEditUiState = _state.value.editableOnly()

    /**
     * An untouched form, captured before anything can have been typed into one.
     *
     * Used only for a **new** item, to answer "is there anything here to lose?" rather than "does
     * this differ from where it started". The two come apart whenever something moves the starting
     * position after [baseline] was anchored — a type chip, a folder the add flow preset — and then
     * an editor holding literally nothing asked whether to discard changes. Reported from a phone,
     * 2026-08-18: *"empty login (literally nothing) asks me to discard changes"*.
     */
    private val pristineNew: ItemEditUiState = _state.value.editableOnly()

    /**
     * A password the *app* minted unasked, when the add sheet's Password row opened this form.
     *
     * Forgiven by [isDirty] for exactly as long as it is still untouched: the user pressed
     * "Password", saw a value they never chose, and pressing Back must not claim they have edits.
     * Re-minting one costs a tap, so nothing is lost by discarding it silently.
     *
     * Deliberately **not** set by [adoptGeneratedPassword]: that value came from the Generator
     * because the user asked to save *this* password, and it exists nowhere else.
     */
    private var seededPassword: String? = null

    /** Whether [presetType] has already run for this ViewModel — see the guard there for why. */
    private var presetTypeApplied = false

    /** Whether [seedGeneratedPassword] has already run — see the guard there for why. */
    private var seededPasswordApplied = false

    /**
     * The last title [onUriChanged] derived from the first website, or null if it never has.
     *
     * A ViewModel field rather than UI state on purpose: it is bookkeeping about where the title
     * came from, not content, so it must not reach `editableOnly()` and count as an unsaved edit.
     * The ViewModel survives rotation, which is the only lifetime this needs to cover.
     */
    private var lastDerivedTitle: String? = null

    /**
     * The last title the screen filled in from [suggestedTitle], or null if it never has. Same kind
     * of bookkeeping as [lastDerivedTitle], and forgiven by [isDirty] while it is still untouched.
     */
    private var suggestedTitleApplied: String? = null

    /**
     * Whether leaving now would discard work (UI/UX spec §5.1 unsaved-change protection).
     *
     * Compares against a snapshot rather than tracking a flag in every change handler: a new
     * editable field is then covered automatically, and the only maintenance is adding genuinely
     * transient state to [editableOnly]. Getting that wrong warns unnecessarily instead of
     * silently dropping the user's typing, which is the right way round to fail.
     */
    fun isDirty(): Boolean {
        val current = _state.value.editableOnly()
        if (!_state.value.isNew) return current != baseline
        // Type and folder are starting positions, not work: whichever the form is pointing at, an
        // otherwise-empty new item has nothing in it the user could be sorry to lose. Anything they
        // actually entered — a title, a password, a note, a tag, a generated secret — differs from
        // `pristineNew` and still prompts.
        //
        // Both clauses are needed, and dropping either brings back a bug this project has already
        // shipped. Without the `pristineNew` one, an untouched form whose starting position moved
        // after `baseline` was anchored — a type chip, a folder the add flow preset — asks to
        // discard nothing (reported from a phone, 2026-08-18). Without the `baseline` one, a *saved*
        // new item stays dirty forever: `save()` re-anchors `baseline` and nothing else, and the
        // item is still `isNew`, so the discard prompt fires on work that is already in the vault.
        // A password the app seeded on the user's behalf is a starting position too — but only
        // while it is still exactly the value that was seeded. Edit it, or regenerate it, and the
        // two diverge and the prompt comes back. See [seededPassword] for why the Generator
        // handover is excluded from this.
        val seedForgiven = seededPassword?.takeIf { it == current.password }
        return current != baseline &&
            current != pristineNew.copy(
                type = current.type,
                folderId = current.folderId,
                password = seedForgiven ?: pristineNew.password,
                title = titleAfterForgiving(current.title, suggestedTitleApplied, pristineNew.title),
            )
    }

    init {
        // Before loadFolders, so the picker's expanded path is computed against the item's own
        // folder rather than against nothing.
        restoreRequestedItem()
        loadFolders()
        // Ticks only while there is a second factor to tick. This was an unconditional 1 Hz loop for
        // the whole life of the ViewModel, so a Note, a Card or a read-only Passkey left open on the
        // back stack woke the main dispatcher every second to call a function that early-returns.
        // Driven off the state rather than checked once, because the item is not loaded yet when
        // `init` runs — and the "no TOTP" branch still runs [refreshTotpCode] once, which is what
        // clears a stale code when a secret is deleted.
        viewModelScope.launch {
            _state
                .map { it.carriesTotpSecret() }
                .distinctUntilChanged()
                .collectLatest { ticking ->
                    if (!ticking) {
                        refreshTotpCode()
                        return@collectLatest
                    }
                    while (isActive) {
                        refreshTotpCode()
                        delay(1_000)
                    }
                }
        }
        viewModelScope.launch {
            entitlement.isPro.collect { pro -> _state.update { it.copy(isPro = pro) } }
        }
        // Follow writes made elsewhere. The detail screen and the edit form are separate back-stack
        // entries and therefore separate instances of this ViewModel, so editing an item and saving
        // popped back to a detail screen still holding what it loaded on the way in — its
        // `LaunchedEffect(itemId)` cannot re-run, because itemId did not change. The user had to
        // navigate away and back to see whether the save had taken.
        viewModelScope.launch {
            repository.onSaved.collect {
                val current = _state.value
                // Deliberately does nothing for a form with work in it. The edit screen has typing
                // to protect, and a concurrent write to the item it is editing is already handled,
                // properly, by expectedUpdatedAt and the conflict notice — reloading underneath the
                // user would both discard their edits and bypass that.
                if (current.isNew || current.isSaving || current.trashed || isDirty()) return@collect
                // The folder list, not only the item. `loadItem` rebuilds the item's fields —
                // `folderId` included — but reads nothing about folders, and the detail screen
                // resolves the folder's *name* by looking `folderId` up in this list. So creating
                // a folder from inside the editor's picker and saving came back to a screen whose
                // list predated the folder: the lookup missed, and an item that was filed read
                // `Folder: Add to folder` until the user left and re-entered (4A-F10, 2026-09-20).
                // Same argument as the reload itself — this is a write made elsewhere.
                loadFolders()
                // A save racing an auto-lock reaches here with the vault already sealed — the
                // autofill save-confirm activity shares this vault and writes from its own
                // process. `loadItem` owns that rule now and returns; before it did,
                // `ItemEditViewModelTest` failed here with "vault is locked".
                loadItem(current.itemId.toString())
            }
        }
        // The open item can stop being visible while this screen holds it. Reveal a hidden login,
        // open it, then hand the phone over: the background grace expires, the reveal dies, the
        // vault list conceals it — and this screen carries on showing the title, the username and a
        // password one tap from being unmasked, because nothing told it anything had changed.
        //
        // A per-screen filter would be the wrong fix (and the design forbids it). What this collects
        // is the same projection every other surface reads, so "is my item still visible" has one
        // answer for the whole app. Expiry needs no special case: an expired lease puts the record
        // back in `liveItems`, so the screen simply stays.
        viewModelScope.launch {
            repository.presentationState.collect { presentation ->
                val current = _state.value
                // Ahead of every early return below, because a new item takes one of them and is the
                // case this exists for: a lock drops the draft — a generated password the user went
                // off to set on a website included — and nothing used to say so. Only the fact
                // crosses the lock, never the draft (QA 2026-10-03).
                if (lockManager.lockState.value !is LockState.Unlocked &&
                    !current.saved && !current.concealed && !discardReported && isDirty()
                ) {
                    discardReported = true
                    LkUnlockNotices.queue(R.string.itemedit_notice_discarded_on_lock)
                }
                // A new item is not in the vault yet, and a save in flight is momentarily neither
                // here nor there. Neither is a concealment.
                if (current.isNew || current.isSaving || current.concealed) return@collect
                // Deliberately narrow: *concealed*, not merely absent. The first version of this
                // asked "is my item still in liveItems", and that is true of an item being deleted,
                // of a save in flight, and — because `presentationState` is a StateFlow seeded with
                // Locked whose upstream has not necessarily produced yet — of a screen that simply
                // subscribed too early. It emptied seven existing ItemEditViewModelTest cases.
                //
                // `concealedItemIds` is populated whether or not a reveal session is running, so the
                // pair of conditions says exactly one thing: this item is under a lease *and* the
                // projection is not currently showing it. Nothing else can satisfy both, and an
                // ordinary item can never satisfy the first.
                // *Concealed*, not merely absent. An earlier version asked only "is my item still in
                // liveItems", which is also false of an item being deleted, of a save in flight, and
                // — because `presentationState` is a StateFlow seeded with Locked whose upstream has
                // not necessarily produced yet — of a screen that subscribed too early.
                //
                // `concealedItemIds` is populated whether or not a reveal session is running, so the
                // pair of conditions says exactly one thing: this item is under a lease *and* the
                // projection is not currently showing it. An ordinary item can never satisfy the
                // first, which is what makes this safe to run on every emission.
                val concealedNow = presentation is VaultPresentationState.Unlocked &&
                    current.itemId in presentation.snapshot.concealedItemIds &&
                    presentation.snapshot.liveItems.none { it.id == current.itemId }
                // The lock case reaches the same place by a different route. MainActivity's lock
                // observer already replaces the graph with Unlock, but this ViewModel outlives that
                // on the back stack and should not still be holding decrypted fields.
                val lockedNow = lockManager.lockState.value !is LockState.Unlocked
                if (!concealedNow && !lockedNow) return@collect
                // Replaced wholesale rather than field-by-field: the state holds the decrypted
                // password, TOTP secret, card number and note body, and enumerating them is how one
                // gets missed the next time a field is added. These are `String`s, so this drops the
                // references for the collector — it does not wipe the characters, and nothing here
                // should claim it does.
                // Ahead of the state replacement, because the replacement alone does not reach the
                // GNSS receiver: the job holds the platform listener, and a vault that just locked
                // must not leave one running behind a screen the user can no longer see.
                cancelLocationCapture()
                // The loaded record goes too (O1-02, 2026-10-02). `save()` writes through it, and the
                // state below is a blank default Login: kept, it let a retyped title commit an empty
                // Login over the hidden item. `blockedBeforeTitle` refuses a concealed save as well.
                originalItem = null
                _state.value = ItemEditUiState(isNew = false, itemId = current.itemId, concealed = true)
            }
        }
    }

    /**
     * Loads the item the route names, at construction, before anything is composed.
     *
     * The detail screen used to start this from `LaunchedEffect(itemId)`. A launched effect runs
     * after the frame that created it has been drawn — measured on an emulator, API 36, with the
     * screen logging its own draws: `DRAW skeleton` at t=1033343 ms, `loadItem ENTER` at t=1033345,
     * `DRAW content` at t=1033377. So *every* entry painted one frame of placeholder — a tile with
     * no identity and skeleton bars where the fields go — and only then the item.
     *
     * That frame is normally invisible, because the screen's own fade has barely started when it is
     * drawn. Opening an item, going back, and opening it again immediately is what exposes it: the
     * second entry begins while the pop is still fading, so the placeholder is composited at real
     * opacity over a screen that is still on its way out. One visible flash of the wrong thing, and
     * only when you are quick — which is exactly how it was reported, and why it never showed up in
     * a screenshot or a build log.
     *
     * The cure is not a better placeholder, it is not needing one. `isPro` above is seeded from its
     * flow's current value for the same reason and with the same sentence: the answer is already
     * there at construction, so read it instead of waiting for something asynchronous to deliver it.
     *
     * The lookup is a linear scan of the vault on the main thread, and this moves it from one frame
     * after composition to inside it — so it was worth measuring rather than assuming. On a
     * 10,000-item vault, the size the 2026-07-31 device pass used, constructing this whole ViewModel
     * took a median of 0.98 ms over seven samples (worst 2.62 ms; the bare scan alone 0.44 ms),
     * against 8.33 ms of frame budget at 120 Hz. Measured on the API 36 emulator, 2026-08-12. The
     * cost is not new — it was always paid, one frame later — and it does not threaten a frame.
     *
     * Locked vault: [loadItem] returns without touching the state, so the screen stays held and
     * the unlock observer in `MainActivity` gets there first. That case is process death restoring
     * a back stack, and it is the one the screens' own effects exist to cover.
     */
    private fun restoreRequestedItem() {
        val id = savedStateHandle.get<String>(ROUTE_ITEM_ID) ?: return
        // `item/new?type=…` can hand "new" through this argument. A UUID is the only thing that
        // names a stored item, so anything else is the add form and has nothing to load.
        if (runCatching { UUID.fromString(id) }.isFailure) return
        loadItem(id)
    }

    /**
     * Whether the screens' fallback effect still has work to do.
     *
     * [restoreRequestedItem] covers every entry but one — a back stack restored while the vault was
     * sealed, where [loadItem] read nothing and left no trace. That is the case the effect exists
     * for, and it is not the same question as "is the state wrong?".
     *
     * A route naming an item that no longer exists also leaves the state wrong, permanently:
     * [loadItem]'s not-found branch sets [ItemEditUiState.error] and deliberately does not adopt the
     * id, so [isShowingWrongItem] stays true forever. Asking that alone, the effect fired a second
     * lookup — another full scan of the vault — to arrive at the answer this ViewModel had already
     * reached during construction. [attemptedLoadOf] is the trace that tells the two apart.
     */
    fun needsInitialLoad(requestedId: String): Boolean =
        attemptedLoadOf != requestedId && isShowingWrongItem(_state.value, requestedId)

    /**
     * Whether the vault was asked for this item and did not have it.
     *
     * The counterpart to [needsInitialLoad], off the same trace: that one asks whether a lookup is
     * still owed, this one whether the lookup already happened and came back empty. The screen needs
     * both, because [isShowingWrongItem] alone cannot tell "not yet" from "not ever" and held the
     * same skeleton for both — forever, in the second case.
     */
    fun itemIsMissing(requestedId: String): Boolean =
        isMissingItem(_state.value, requestedId, lookupRan = attemptedLoadOf == requestedId)

    /**
     * Moves the item being viewed to the trash.
     *
     * [ItemEditUiState.trashed] is only set once the write has landed, and that is what sends the
     * detail screen back — navigating on the tap instead would strand a failure on a screen the
     * user has already left, with the item still in the vault and nothing saying so.
     */
    fun moveToTrash() {
        val id = _state.value.itemId
        if (_state.value.isNew) return
        viewModelScope.launch {
            runCatching { repository.moveToTrash(id) }
                .onSuccess {
                    // The confirmation is deliberately *not* posted here (4A-F7, 2026-09-20).
                    // Setting `trashed` sends the detail screen back, which destroys its
                    // NavBackStackEntry and clears this ViewModel — so an undo lambda closing over
                    // `viewModelScope` would be launched on a cancelled scope and do nothing at
                    // all, which is worse than offering no undo. That argument used to end in "so
                    // this route has no Undo", and the result was three trash routes with three
                    // different safety nets. It ends one step further along now: the notice and its
                    // button belong to an owner that outlives the screen, so `ItemDetailScreen`
                    // hands both to the host (`TrashUndoBus` in `:app`) as it leaves.
                    update { copy(trashed = true) }
                }
                .onFailure { _state.update { it.copy(error = ACTION_ERROR, errorArg = null) } }
        }
    }

    /**
     * Toggles the favourite flag from the *detail* screen, straight through to the vault.
     *
     * Immediate rather than dirty-and-Save, on the same argument [clearPasswordHistory] records:
     * the detail screen has no Save button, so an action offered there has to land when it is
     * tapped. The editor's own checkbox is the other half and behaves the opposite way, because
     * that screen does have a Save and everything on it waits for it.
     *
     * Re-read under the vault lock rather than written from `originalItem`, so a flag the vault
     * list changed while this screen was open is the one being flipped. `updatedAt` is deliberately
     * left alone: marking an item is not editing it, and bumping it would make the item look
     * freshly changed to every merge rule and sort order that reads that field — exactly the
     * argument `clearPasswordHistory` makes for the same decision.
     */
    fun toggleFavoriteNow() {
        val id = _state.value.itemId
        if (_state.value.isNew) return
        viewModelScope.launch {
            runCatching { repository.updateWith(id) { it.copy(favorite = !it.favorite) } }
                // The committed item, not a locally-inverted guess: `updateWith` returns what it
                // wrote, and mirroring that is what keeps the star honest if the write was
                // reconciled against a change made elsewhere.
                .onSuccess { committed -> update { copy(favorite = committed.favorite) } }
                .onFailure { _state.update { it.copy(error = ACTION_ERROR, errorArg = null) } }
        }
    }

    /**
     * Deletes every stored previous password for this item, immediately and permanently.
     *
     * Writes straight through to the vault rather than marking the editor dirty and waiting for
     * Save. The action is offered on the *detail* screen, which has no Save button, and "delete
     * these secrets" is not a change anyone expects to have to confirm twice.
     *
     * The item is re-read here instead of using the loaded `originalItem`: the autofill save-confirm
     * activity shares this vault and can have changed the password since this screen opened, and the
     * user asked to remove the history — not to reinstate whatever else was on screen at the time.
     * `expectedUpdatedAt` is deliberately left null for the same reason; there is no conflict to
     * resolve when the only field being written is the one being emptied.
     */
    fun clearPasswordHistory() {
        val id = _state.value.itemId
        if (_state.value.isNew) return
        viewModelScope.launch {
            runCatching {
                val current = repository.visibilitySnapshot().liveItems.find { it.id == id }
                    ?: error("item not found: $id")
                val login = current.content as? Login ?: error("not a login: $id")
                // This read decides only whether there is anything to do; the clear itself
                // re-reads under the vault lock, so a password the autofill activity wrote in
                // between keeps its new value instead of being replaced by this stale copy.
                if (login.passwordHistory.isEmpty()) return@runCatching
                repository.updateWith(id) { stored ->
                    val storedLogin = stored.content as? Login ?: error("not a login: $id")
                    stored.copy(
                        content = storedLogin.copy(passwordHistory = emptyList()),
                        // Deliberately NOT bumping `updatedAt`: this removes data, it does not
                        // change the credential, and bumping it would make the item look freshly
                        // edited to every merge rule and sort order that reads that field.
                    )
                }
            }.onSuccess {
                // Cleared here as well as by the reload below, because `toContent` writes the
                // *state's* `passwordHistory` back on the next save. `loadItem` now declines on a
                // sealed vault, so a vault that sealed between the write above and this line would
                // otherwise leave the deleted entries in hand for that save to restore. Deleted
                // secrets must not be able to come back, and one line is a cheap way to be sure of
                // it. Reaching that window also needs the ViewModel to outlive the lock, which it
                // does not — `MainActivity`'s lock observer pops the whole graph inclusive — so
                // this is defence against a path neither a test nor the emulator could construct,
                // and it is written down as such rather than claimed as a fixed bug.
                update { copy(passwordHistory = emptyList()) }
                // Reload rather than patching the snapshot: `originalItem` is what a later save
                // writes back, and leaving it holding the history would resurrect the entries the
                // user just deleted the next time they edited the item.
                loadItem(id.toString())
                LkNotices.post(R.string.itemedit_notice_password_history_cleared)
            }.onFailure {
                _state.update { it.copy(error = ACTION_ERROR, errorArg = null) }
            }
        }
    }

    fun dismissProPrompt() = update { copy(showProPrompt = false) }

    fun unlockPro(activity: Activity) {
        // Play's sheet is another process: without declaring the excursion, the auto-lock grace
        // timer runs during checkout and throws the user to the unlock screen mid-purchase. Same
        // reason the import wizard declares its file picker.
        lockManager.beginExternalPicker()
        viewModelScope.launch {
            try {
                reportPurchase(entitlement.purchase(activity))
            } finally {
                lockManager.endExternalPicker()
            }
        }
    }

    /**
     * Says what a finished purchase did, for the outcomes that would otherwise say nothing at all.
     *
     * The result used to be discarded here and at Settings' and the vault list's copies of this
     * function, so the paths that return before Play's sheet ever appears — no billing connection,
     * no product details, a launch Play refused — left the price button looking inert. Worse here
     * than anywhere else: the Pro prompt is dismissed synchronously on the same click, so the screen
     * just went back to normal. Being offline is the ordinary state of this app's users.
     */
    private fun reportPurchase(outcome: PurchaseOutcome) = when (outcome) {
        // A completed purchase flips `isPro`, which announces itself; a cancel is the user's own
        // doing; a refused second tap has the flow that refused it still on screen.
        PurchaseOutcome.Purchased,
        PurchaseOutcome.Cancelled,
        PurchaseOutcome.AlreadyInFlight,
        -> Unit
        // Not an error, and deliberately not on the error channel: nothing failed and there is
        // nothing to retry. LkNotices outlives this screen, which matters because the prompt that
        // raised the purchase is already gone.
        PurchaseOutcome.Pending ->
            LkNotices.post(com.localkeep.core.ui.R.string.core_ui_pro_gate_purchase_pending)
        // A notice rather than an error, and for the same reason as Pending: the prompt that raised
        // the purchase is already dismissed, and the remedy this names lives on another screen, so
        // it has to outlive this one.
        PurchaseOutcome.AlreadyOwned ->
            LkNotices.post(com.localkeep.core.ui.R.string.core_ui_pro_gate_purchase_already_owned)
        PurchaseOutcome.Failed ->
            setError(com.localkeep.core.ui.R.string.core_ui_pro_gate_purchase_failed)
    }

    fun loadItem(id: String) {
        // A sealed vault has nothing to read, and `getAllItems` goes through `requireVault`, which
        // throws rather than saying so. Not defensive programming — reproduced on an emulator:
        // open an item, background the app, `am kill` it, relaunch. NavController restores the
        // back stack, this screen composes while the vault is still sealed, and the app dies with
        // `IllegalStateException: vault is locked` at ItemDetailScreen's effect before
        // MainActivity's lock observer has moved the user to the unlock screen. It predates the
        // load-at-construction change — the pre-fix build crashes identically — but every caller
        // of this function is one of the three places that can hit it, so the rule belongs here
        // once rather than at each of them.
        //
        // Returning leaves the state describing no item, so `isShowingWrongItem` keeps the screen
        // held. That is the right picture for a sealed vault anyway: nothing of the item is shown
        // on the way to the unlock screen.
        if (lockManager.lockState.value !is LockState.Unlocked) return
        // Recorded before the answer, because "did a lookup run?" is the question, not "did one
        // succeed?". A sealed vault returns above and deliberately leaves no trace: nothing was
        // read, so the screen must still try again once the vault is open.
        attemptedLoadOf = id
        update { copy(isLoading = true) }
        val uuid = runCatching { UUID.fromString(id) }.getOrNull()
        // Through the projection, and the not-found branch below is what a concealed item takes. The
        // restored-back-stack case this function already documents is the same one that matters for
        // concealment: `item/{id}` comes back after a lock, and the lease it points at may now be
        // hiding. "Item not found" is the right thing to say — it is what the vault list also shows.
        val item = uuid?.let { wanted ->
            repository.visibilitySnapshot().liveItems.find { it.id == wanted }
        }
        if (item == null) {
            _state.update {
                it.copy(error = R.string.itemedit_error_item_not_found, errorArg = null, isLoading = false)
            }
            return
        }
        originalItem = item
        val common = _state.value.copy(
            isNew = false,
            isLoading = false,
            itemId = item.id,
            title = item.title,
            folderId = item.folderId,
            tags = item.tags,
            customFields = item.customFields,
            favorite = item.favorite,
            reprompt = item.reprompt,
            hasAttachments = item.attachments.isNotEmpty(),
            attachmentCount = item.attachments.size,
            attachmentImageCount = item.attachments.count { it.mime.startsWith("image/") },
            createdAt = item.createdAt,
            updatedAt = item.updatedAt,
            error = null,
            errorArg = null,
        )
        _state.value = when (val content = item.content) {
            is Login -> common.copy(
                type = ItemEditUiState.ItemType.LOGIN,
                username = content.username,
                password = content.password,
                // Trimmed on the way out as well as on the way in: a vault written before the cap
                // existed can hold an unbounded list, and the screen showing it must not be the
                // one place that bound does not apply.
                passwordHistory = PasswordHistory.trim(content.passwordHistory),
                passwordChangedAt = content.passwordChangedAt,
                uris = content.uris.map(UriMatch::uri),
                loginNotes = content.notes,
            ).withLoginTotp(content.totp)
            is Note -> common.copy(
                type = ItemEditUiState.ItemType.NOTE,
                noteBody = content.body,
            )
            is Totp -> common.copy(
                type = ItemEditUiState.ItemType.TOTP,
                totpIssuer = content.issuer,
                totpAccount = content.account,
                totpSecret = content.secretBase32,
                totpAlgorithm = content.algorithm,
                totpDigits = content.digits.toString(),
                totpPeriodSec = content.periodSec.toString(),
                totpSteam = content.steam,
            )
            is Card -> common.copy(
                type = ItemEditUiState.ItemType.CARD,
                cardHolder = content.holder,
                cardNumber = content.number,
                cardExpMonth = content.expMonth?.toString().orEmpty(),
                cardExpYear = content.expYear?.toString().orEmpty(),
                cardCvv = content.cvv,
                cardPin = content.pin,
                cardNotes = content.notes,
                cardBrand = content.brand,
            )
            is Coordinate -> common.copy(
                type = ItemEditUiState.ItemType.COORDINATE,
                // The stored snapshot, carried through untouched. Editing a coordinate item must
                // not re-capture: the user opened it to rename or re-file it far more often than to
                // replace where it points, and a value that silently became "here, now" on every
                // open would be the item quietly lying about what it records.
                coordinateFix = content,
                coordinateJustCaptured = false,
                coordinateCapturing = false,
                coordinateFailure = null,
                coordinateConvergence = CaptureConvergence.SEARCHING,
            )
            is Identity -> common.copy(
                type = ItemEditUiState.ItemType.IDENTITY,
                identityFirstName = content.firstName,
                identityLastName = content.lastName,
                identityCompany = content.company,
                identitySsn = content.ssn,
                identityPassport = content.passport,
                identityLicense = content.license,
                identityEmail = content.email,
                identityPhone = content.phone,
                identityAddressLine1 = content.address.line1,
                identityAddressLine2 = content.address.line2,
                identityAddressCity = content.address.city,
                identityAddressState = content.address.state,
                identityAddressZip = content.address.zip,
                identityAddressCountry = content.address.country,
            )
            is SeedPhrase -> common.copy(
                type = ItemEditUiState.ItemType.PHRASE,
                phraseWords = content.words,
                phraseWordCount = content.wordCount.takeIf { it > 0 } ?: 12,
                phrasePassphrase = content.passphrase,
                // No slot focused on an item opened to view: the detail screen renders it masked,
                // and an edit begins by tapping a slot, which sets the focus then — behind the gate
                // armed below.
                phraseFocus = -1,
                // These words came out of the vault, so showing them is a disclosure and not a
                // capture: both the Reveal pill and the first slot tap must pass §5.2 first.
                phraseGateRequired = true,
            )
            is Passkey -> common.withPasskeyContent(content)
            // A content type this editor still has no mapping for — a File, in practice, which is
            // routed to `FileItemEditScreen`/`FileItemEditViewModel` instead and should never reach
            // here. `common` leaves `type` at its LOGIN default, and `toContent()` would then build
            // an *empty* Login and persist it over the real content. The flag is what stops that;
            // the error text alone never did, because nothing read it before writing.
            else -> common.copy(
                error = R.string.itemedit_error_unsupported_type,
                readOnly = true,
            )
        }
        // Anchor the unsaved-change baseline to what was actually loaded.
        baseline = _state.value.editableOnly()
        refreshTotpCode()
    }

    /**
     * Applies the type chosen in the add sheet (§4.4) to a brand-new item.
     *
     * Ignored once the item exists: an item's type is fixed after creation, and a stale deep link
     * must not be able to reinterpret a saved Card as a Login. Re-anchors the unsaved-change
     * baseline so arriving with a preset type isn't itself reported as an unsaved edit.
     */
    fun presetType(value: ItemEditUiState.ItemType) {
        if (!_state.value.isNew) return
        // Applied once per ViewModel, and that is the whole point. `LaunchedEffect(presetType)` in
        // the screen re-fires on every Activity recreation — a rotation — while this ViewModel
        // survives it, scoped to the back-stack entry. So a second call arrived on a form the user
        // had already typed into, and re-anchoring `baseline` below then laundered their real
        // unsaved content into the "nothing has changed" snapshot: the next Back press found the
        // form clean and binned the work with no discard prompt at all.
        //
        // The recovery-phrase branch made it worse than a lost prompt. Its `phraseWords = List(12)`
        // rebuild is a starting position, and re-running it after a rotation wiped every word the
        // user had entered.
        //
        // The type is fixed at creation anyway, so there is never a legitimate second application.
        if (presetTypeApplied) return
        presetTypeApplied = true
        update {
            when (value) {
                // A recovery phrase opens on an empty 12-slot grid with the first slot focused, and
                // with reprompt already on: an item whose entire content is the secret starts
                // guarded, unlike every other type where reprompt is a preference (comp § notes).
                // Re-anchored below, so neither the grid nor the flag counts as an unsaved edit.
                ItemEditUiState.ItemType.PHRASE -> copy(
                    type = value,
                    phraseWordCount = 12,
                    phraseWords = List(12) { "" },
                    phraseFocus = 0,
                    reprompt = true,
                )
                else -> copy(type = value)
            }
        }
        baseline = _state.value.editableOnly()
    }

    /**
     * Adopts a password the generator handed over, if one is waiting.
     *
     * Deliberately does *not* re-anchor the unsaved-change baseline the way [presetType] does. A
     * preset type is a starting position; a generated password is work the user asked to keep, and
     * leaving the form dirty is what makes the discard confirmation fire if they back out. The
     * alternative is silently binning a password that exists nowhere else.
     *
     * Reveals the value it writes, through [revealNow] — the same call [generatePassword] makes on
     * the very next line after writing its own value, for the same reason: this password was made by
     * the user's own tap on the Generator screen moments ago, not a stored secret being disclosed.
     * Left masked until 2026-09-13 — the one "the app just made this for you"
     * path in this file that did not reveal what it had just handed over. Loosens no gate: a new
     * item's `reprompt` defaults to `false` ([ItemEditUiState.reprompt]) and `requiresReauthFor` only
     * trips on `ALWAYS_REAUTH_FIELDS`, a `passwordHistory:` prefix, or `reprompt = true` — none of
     * which apply to a brand-new Login, so nothing this call reveals was behind a re-auth gate.
     */
    fun adoptGeneratedPassword() {
        if (!_state.value.isNew) return
        val generated = PendingSecret.take() ?: return
        // The Generator's own verdict comes across with the value (see
        // [ItemEditUiState.generatedEntropyBits]). Without it this form scored the handed-over
        // password with zxcvbn and reported a different band from the one the user had just read on
        // the screen they came from — F-29, 2026-09-15.
        update {
            copy(
                type = ItemEditUiState.ItemType.LOGIN,
                password = generated.secret,
                generatedEntropyBits = generated.entropyBits,
            )
        }
        revealNow("password")
    }

    /**
     * Whether this tier may create one more of [state]'s type.
     *
     * Two things it must not do, both of which the `PRO_GATED_TYPES` membership test it replaced did
     * by construction, because under that model a free vault was allowed *none*:
     *
     *  - **It must not refuse the first one.** Cards and Identity documents stopped being Pro-only on
     *    2026-09-03; one of each is free. A check that still asks "is this type gated" answers yes to
     *    a user who is entitled to the item they are creating.
     *  - **It must not refuse an edit.** A vault can legitimately hold more than the cap — import
     *    never charges against it, and a lapsed Pro purchase is the other route — and the allowance
     *    limits creation only. The comment on the save-path call site used to argue the opposite and
     *    was right for the model it was written under; declining to write *any* change to an item the
     *    user already owns is holding their own data hostage, which is why the cap now stops the
     *    `isNew` case alone.
     *
     * A repository read that throws is treated as "not blocked" rather than as a refusal: the vault
     * locking mid-form is the ordinary cause, the lock is already routing the app away, and failing
     * open here costs at most one item over the cap while failing closed would refuse a legitimate
     * first card for a reason the UI cannot explain.
     *
     * Called from [onTypeChanged] and from the save path. Note that [onTypeChanged] has no
     * production caller — only `androidTest` — so the save-path call is the one that runs; see the
     * comment there for how the add sheet's own refusal sits in front of it.
     */
    private fun creationBlockedByAllowance(state: ItemEditUiState): Boolean {
        val kind = when (state.type) {
            ItemEditUiState.ItemType.CARD -> ProTypeQuota.Kind.CARD
            ItemEditUiState.ItemType.IDENTITY -> ProTypeQuota.Kind.IDENTITY
            else -> return false
        }
        if (state.isPro || !state.isNew) return false
        val used = runCatching { ProTypeQuota.usedFromRepository(repository)[kind] ?: 0 }
            .getOrElse { return false }
        return ProTypeQuota.exceedsFreeLimit(used, isPro = false)
    }

    fun onTypeChanged(value: ItemEditUiState.ItemType) {
        if (creationBlockedByAllowance(_state.value.copy(type = value))) {
            update { copy(showProPrompt = true) }
        } else {
            update { copy(type = value) }
        }
    }
    fun onTitleChanged(value: String) = update { copy(title = value, titleError = false) }

    /** The screen filling Title from [suggestedTitle]: a title change that is not the user's work. */
    fun onTitleSuggested(value: String) {
        suggestedTitleApplied = value
        onTitleChanged(value)
    }
    fun onFavoriteChanged(value: Boolean) = update { copy(favorite = value) }
    fun onRepromptChanged(value: Boolean) = update { copy(reprompt = value) }

    // ── recovery phrase ──────────────────────────────────────────────────────────────────────
    /** Resize the grid to [count] slots, keeping the words already entered, and focus the first gap. */
    fun onPhraseWordCountChanged(count: Int) {
        val current = _state.value
        // The same gate `focusPhraseSlot` and `togglePhraseReveal` carry, and it was the one phrase
        // control without it. Shrinking the count takes every word past the new size out of what Save
        // writes, so on a loaded item this let a user who had NOT passed re-auth - and therefore could
        // not see the words - destroy the ones they could not see. (Since 2026-10-04 the cut words
        // are set aside and a larger count puts them back — see `withPhraseWordCount` — but a save
        // in between still drops them, so the gate stands.)
        //
        // Routed through `pendingRevealField` rather than refused outright, so the control behaves
        // like every other gated one on this screen: prompt, then act.
        if (count < current.phraseWordCount && current.phraseGateRequired && !current.phraseRevealAll) {
            update { copy(pendingRevealField = PHRASE_FIELD) }
            return
        }
        update {
            if (count == phraseWordCount && phraseWords.size == count) return@update this
            withPhraseWordCount(count)
        }
        recheckVaultKit()
    }

    /** Lowercase, letters only — the wordlist and its four-letter prefixes are all `[a-z]`. */
    fun onPhraseDraftChanged(value: String) {
        update { copy(phraseDraft = value.lowercase(Locale.ROOT).filter { it in 'a'..'z' }) }
        touchPhraseReveal()
    }

    /**
     * Place [word] in the focused slot (or the first empty one), advance to the next empty slot and
     * clear the draft. Focus lands on the next gap *after* this slot when there is one, else on the
     * first remaining gap, else -1 once the grid is full — the order the comp commits in.
     */
    fun commitPhraseWord(word: String) {
        update {
            val i = phraseFocus.takeIf { it in phraseWords.indices } ?: phraseWords.indexOfFirst { it.isBlank() }
            if (i < 0) return@update this
            val next = phraseWords.toMutableList().also { it[i] = word }
            val afterI = (i + 1 until next.size).firstOrNull { next[it].isBlank() }
            copy(phraseWords = next, phraseDraft = "", phraseFocus = afterI ?: next.indexOfFirst { it.isBlank() })
        }
        touchPhraseReveal()
        recheckVaultKit()
    }

    /**
     * Focus a slot for editing, raising the §5.2 prompt first when the phrase came from the vault.
     *
     * Focusing loads that slot's stored word into [ItemEditUiState.phraseDraft], and the entry field
     * renders the draft in clear — it has to, because you cannot correct a word you cannot see. So
     * on a stored phrase this is a disclosure just as much as the Reveal pill is, and gating only
     * the pill would have left the whole phrase readable one tap at a time. The tap is not thrown
     * away: [ItemEditUiState.phrasePendingFocus] holds it until the prompt passes.
     */
    fun focusPhraseSlot(index: Int) {
        val current = _state.value
        if (index !in current.phraseWords.indices) return
        if (current.phraseGateRequired && !current.phraseRevealAll) {
            update { copy(pendingRevealField = PHRASE_FIELD, phrasePendingFocus = index) }
            return
        }
        // Commit whatever is in the outgoing slot before loading the new one, exactly as saving
        // does. Without this the draft was simply overwritten by `phraseWords[index]`, so a word
        // the user had typed and then moved on from was gone — and every cue said it had landed:
        // the field bordered green, the hint read "One match", and the word showed in that slot's
        // own cell while it held focus. The only thing that ever wrote to `phraseWords` was the
        // autocomplete chip, which is small, optional-looking, and not where a user tapping
        // through twelve slots is looking.
        //
        // It failed silently and late: Save simply never enabled, with nothing saying which slots
        // were empty. `withPendingPhraseDraftCommitted` is reused rather than a stricter
        // wordlist-match rule so that navigating and saving cannot disagree about what the grid
        // holds — a half-typed word left visible in its cell is recoverable, and losing it is not.
        update { withPhraseSlotFocused(index) }
        touchPhraseReveal()
        recheckVaultKit()
    }

    fun onPhrasePassphraseChanged(value: String) = update { copy(phrasePassphrase = value) }

    /**
     * The Reveal/Hide pill on the words header.
     *
     * A capture flips straight through — the user is typing the secret in, and nothing is being
     * disclosed to them that they do not already hold. An edit of a stored phrase goes through
     * [toggleFieldRevealed] under [PHRASE_FIELD], which is in `ALWAYS_REAUTH_FIELDS`: prompt to
     * show, no prompt to hide, and an auto-hide timer either way.
     */
    fun togglePhraseReveal() {
        if (_state.value.phraseGateRequired) {
            toggleFieldRevealed(PHRASE_FIELD)
            return
        }
        if (_state.value.phraseRevealAll) hidePhrase() else revealNow(PHRASE_FIELD)
    }

    private fun hidePhrase() {
        autoHideJobs.remove(PHRASE_FIELD)?.cancel()
        update { copy(revealedFields = revealedFields - PHRASE_FIELD) }
    }

    /**
     * Restart the phrase's auto-hide timer on every interaction with the grid.
     *
     * Without this the timeout would fire mid-edit and take the focused slot, the half-typed word
     * and the reveal with it — punishing the one user who is demonstrably still at the phone. The
     * timer is meant to catch a phone put down or handed over, which is exactly the case where
     * nothing calls this.
     */
    private fun touchPhraseReveal() {
        if (PHRASE_FIELD in _state.value.revealedFields) schedulePhraseAutoHide()
    }

    /**
     * Fill the grid from a pasted phrase — split on whitespace, lowercased, left to right up to the
     * current count. Extra words are dropped: a longer paste does not silently grow the grid.
     *
     * **Offers Undo when it overwrites something**, for the same reason [removeCustomField] does and
     * with the same argument against a confirmation dialog. "Paste all" is a pill 8dp from the
     * Reveal/Hide toggle on the Words header, so the mistake is a near-miss on the control beside
     * it — and what it overwrites is a recovery phrase, which is typically the only copy of the
     * thing it opens. Before this, one wrong tap replaced every word entered so far and the only
     * way back was to type them again from the paper. A dialog would stand in front of the common
     * case (pasting into an empty grid) to guard the rare one; Undo charges nothing for the common
     * case. Safe to offer on the same terms as [removeCustomField]: it re-enters `_state` directly
     * rather than through [viewModelScope], and pasting navigates nowhere.
     *
     * Silent when there was nothing to lose — an all-blank grid is the ordinary first paste.
     *
     * **Names it when the paste itself was too long.** The doc comment above already says "extra
     * words are dropped" as if that were a settled, visible fact; until this, it was neither —
     * pasting an 24-word phrase into a 12-word grid kept the first twelve and threw the rest away
     * with no sign anything had been cut. This is the *common* overflow case (first paste, nothing
     * to overwrite) and one the Undo notice above never covers: there is nothing to restore, since
     * the dropped words never landed on the grid. So it gets its own notice, no action, naming the
     * count — the same shape `itemedit_detail_attachments_photos` uses for "N photos", not the
     * quota copy's "remove N" (nothing here is refused, only trimmed). Not shown alongside the
     * Undo notice above: [LkNotices] is single-slot, and restoring the whole previous grid already
     * forgets the overflow along with everything else it forgets.
     */
    fun pastePhrase(text: String) {
        val previous = _state.value
        val pasted = text.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotBlank() }
        update {
            if (pasted.isEmpty()) return@update this
            withPastedPhrase(List(phraseWordCount) { pasted.getOrElse(it) { "" } })
        }
        recheckVaultKit()
        // The words set aside by a smaller count are part of what a paste replaces, so they count
        // towards "overwrote" and come back with the Undo.
        val overwrote = (previous.phraseWords + previous.phraseWordsSetAside).any { it.isNotBlank() } &&
            (_state.value.phraseWords != previous.phraseWords ||
                _state.value.phraseWordsSetAside != previous.phraseWordsSetAside)
        if (overwrote) {
            // The notice names no word and shows no count: a recovery phrase must not be described
            // in a banner that outlives the screen it was pasted into.
            LkNotices.post(
                R.string.itemedit_notice_phrase_replaced,
                actionLabelRes = R.string.itemedit_notice_undo,
                onAction = {
                    update {
                        copy(
                            phraseWords = previous.phraseWords,
                            phraseWordsSetAside = previous.phraseWordsSetAside,
                            phraseDraft = previous.phraseDraft,
                            phraseFocus = previous.phraseFocus,
                        )
                    }
                    recheckVaultKit()
                },
            )
            return
        }
        val overflow = (pasted.size - previous.phraseWordCount).coerceAtLeast(0)
        if (overflow > 0) {
            LkNotices.post(LkNotices.Message.Plural(R.plurals.itemedit_notice_phrase_paste_overflow, overflow))
        }
    }

    /**
     * Re-evaluate whether the (now-changed) grid is *this vault's own* Recovery Kit — the fifth
     * banner state. Only a checksum-valid 24-word grid can be, so anything short of VALID clears the
     * flag at once; a VALID grid launches the crypto check (a KEK derivation and one unwrap against
     * the vault's recovery block) and stores the answer, discarding it if the words moved on while it
     * ran. Fail-open lives in [VaultLockManager.matchesOwnRecoveryKit].
     */
    private fun recheckVaultKit() {
        val words = _state.value.phraseWords
        if (SeedPhraseWordList.validator.status(words) != SeedPhraseStatus.VALID) {
            if (_state.value.phraseIsVaultKit) update { copy(phraseIsVaultKit = false) }
            return
        }
        viewModelScope.launch {
            // Off the main dispatcher: matchesOwnRecoveryKit reads the whole vault file and runs a KEK
            // derivation plus one unwrap, which would jank the editor on a large vault the moment a
            // checksum-valid 24-word grid is entered. CPU-bound crypto dominates, so Default, not IO.
            val isKit = withContext(Dispatchers.Default) { lockManager.matchesOwnRecoveryKit(words) }
            if (_state.value.phraseWords == words) update { copy(phraseIsVaultKit = isKit) }
        }
    }
    fun onUsernameChanged(value: String) = update { copy(username = value) }
    // `generatedEntropyBits` describes the value the generator drew, so one keystroke ends it: the
    // string in the well is no longer the one whose entropy was measured, and the form falls back to
    // scoring what is actually there.
    fun onPasswordChanged(value: String) {
        // The undo ends with it. `passwordBeforeGenerate` arms the "Undo the generated password"
        // affordance and nothing disarmed it, so after a generate the user could clear the well,
        // type a password by hand, and still be offered the undo — which then replaced what they
        // had typed with the generated value, and disappeared, leaving no second undo to take that
        // back (4A-F2, 2026-09-20). The control exists to take back a mis-tap; once the value has
        // been edited by hand there is no mis-tap left to reverse, only work to destroy.
        if (value != _state.value.password) {
            passwordBeforeGenerate = null
            entropyBitsBeforeGenerate = null
            passwordRevealedBeforeGenerate = false
        }
        update { copy(password = value, generatedEntropyBits = null) }
    }

    /**
     * Fills the password field with a fresh generated value, using the generator's own defaults.
     *
     * The editor had no way to make a password: the route was to leave for the Generator tab —
     * abandoning a half-filled form, or shepherding the value back through a clipboard the app
     * spends the rest of its time clearing. Rotating a password is the exact moment this is needed.
     *
     * Deliberately not configurable here. The Generator screen is where the length and character
     * classes live, and a second, smaller copy of those controls inside the item form would be a
     * second place for them to disagree.
     */
    /**
     * The same generation, on the add sheet's Password row rather than the user's regenerate tap.
     *
     * Its own entry point because [generatePassword] cannot tell the two apart — it takes no
     * argument and is shared by both call sites — and they need opposite dirty semantics. The user
     * asked for *an item*, not for this particular string, so [isDirty] forgives what this records
     * in [seededPassword]. Pressing regenerate goes through [generatePassword] and still counts.
     */
    fun seedGeneratedPassword() {
        // One shot per ViewModel, for the same reason `presetType` is — and this one is subtler,
        // because the screen's guard looks sufficient. `LaunchedEffect(itemId, startGenerated)`
        // re-fires on every Activity recreation while this ViewModel survives it, and its
        // `state.password.isEmpty()` test cannot tell "never seeded" from "seeded, then cleared by
        // the user". So clearing the offered password to type or paste your own, then rotating
        // before typing, silently substituted a *different* random password — one the user never
        // asked for and never saw generated. Save without noticing and the item holds it.
        if (seededPasswordApplied) return
        seededPasswordApplied = true
        generatePassword()
        seededPassword = _state.value.password
    }

    /**
     * What the password field held before the last [generatePassword], or null if there is nothing
     * to go back to.
     *
     * Regenerate is the one control in a form well that destroys a value rather than revealing or
     * copying one, and until 2026-09-09 it did so unconditionally: no confirmation, no undo, and
     * the button sat immediately beside Copy. Geometry alone cannot make that safe — a control that
     * cannot be taken back has to be either unreachable by accident or reversible, and reversible is
     * the kinder of the two. Held in the ViewModel rather than the composable so it survives the
     * recomposition the generated value itself triggers.
     *
     * Deliberately NOT `rememberSaveable`-equivalent: it is dropped on process death along with the
     * rest of the editing session, and a stale undo offering a password from a previous sitting
     * would be worse than none.
     */
    private var passwordBeforeGenerate: String? = null

    /**
     * Whether the password well was already revealed when [generatePassword] fired.
     *
     * [generatePassword] reveals what it writes, and says why: the value was made by the user's own
     * tap and is not a stored secret being disclosed. That reasoning does not survive the undo,
     * which puts the *stored* secret back into a field the generate step left unmasked - so the
     * reveal state has to travel with the value.
     */
    private var passwordRevealedBeforeGenerate: Boolean = false

    /**
     * The entropy that described the password [generatePassword] replaced, travelling with it.
     *
     * Same argument as [passwordRevealedBeforeGenerate]: the undo puts a different string back into
     * the well, and a bit count left over from the value that string replaced would be a verdict
     * about something that is no longer there. Null whenever the replaced value was typed.
     */
    private var entropyBitsBeforeGenerate: Double? = null

    /** Whether an undo is currently on offer — drives the affordance beside the strength meter. */
    val canUndoGeneratedPassword: Boolean get() = passwordBeforeGenerate != null

    /**
     * Put back what [generatePassword] replaced.
     *
     * One level, not a stack: the control exists to take back a mis-tap, and a mis-tap is one tap.
     */
    fun undoGeneratedPassword() {
        val previous = passwordBeforeGenerate ?: return
        val previousBits = entropyBitsBeforeGenerate
        passwordBeforeGenerate = null
        entropyBitsBeforeGenerate = null
        // Re-mask unless the field was already revealed before the regenerate. On a reprompt-gated
        // login the dice sits on the strength row and needs no reveal to reach, so regenerate then
        // undo used to put the real stored password on screen in the clear - and, because
        // `requestCopy` short-circuits on `field in revealedFields`, straight onto the clipboard -
        // with no biometric or password check on either route. Two taps past a gate the user had
        // explicitly asked for. Found by the 2026-09-09 functional sweep, in the undo added to this
        // file the same day.
        val wasRevealed = passwordRevealedBeforeGenerate
        update {
            copy(
                password = previous,
                generatedEntropyBits = previousBits,
                revealedFields = if (wasRevealed) revealedFields else revealedFields - "password",
            )
        }
    }

    fun generatePassword() {
        val generated = PasswordGenerator.generateRandom()
        // Captured before the write, and only when there was something to lose — generating into an
        // empty field is not destructive and must not arm an undo that would blank the field.
        val before = _state.value
        passwordBeforeGenerate = before.password.takeIf { it.isNotEmpty() }
        entropyBitsBeforeGenerate = before.generatedEntropyBits
        passwordRevealedBeforeGenerate = "password" in before.revealedFields
        // The bits travel with the value — see [ItemEditUiState.generatedEntropyBits]. Taken from
        // the draw rather than measured off the string afterwards, because entropy is a property of
        // how the value was drawn and the string cannot tell you which pool it came from.
        update { copy(password = generated.value, generatedEntropyBits = generated.entropyBits) }
        // Revealed with it, through the ordinary reveal path so the §5.2 auto-hide still runs. A
        // field that fills with twenty bullets gives no sign anything happened, and this value was
        // made by the user's own tap a moment ago — it is not a stored secret being disclosed.
        revealNow("password")
    }
    fun onUriChanged(index: Int, value: String) {
        // Computed before the update so the check reads the title as it stands now, and recorded
        // after so the next keystroke can tell its own suggestion from a name the user typed.
        val current = _state.value
        val derived = if (index == 0 && titleIsStillOurSuggestion(current.title)) {
            hostOf(value)
        } else {
            null
        }
        if (derived != null) lastDerivedTitle = derived
        applyUriChange(index, value, derived)
    }

    /**
     * A title we put there, rather than one the user chose.
     *
     * Blank counts: nothing to protect. Otherwise it is ours only while it is still exactly the
     * last value [onUriChanged] derived — the moment the user edits it, the two diverge and the
     * derivation stops for good.
     *
     * This replaced a plain `title.isBlank()` test, which looked equivalent and was not. That test
     * runs on every keystroke, and `hostOf` accepts a host as soon as its TLD reaches two
     * characters — so on any three-letter TLD the title stopped being blank one keystroke early and
     * froze there, turning "github.com" into "github.co" every single time.
     */
    private fun titleIsStillOurSuggestion(title: String): Boolean =
        titleIsStillDerived(title, lastDerivedTitle)

    private fun applyUriChange(index: Int, value: String, derived: String?) = update {
        if (index !in uris.indices) return@update this
        val next = uris.toMutableList().also { it[index] = value }
        // A login for a site is almost always called after the site. Filling a *blank* title from
        // the first website saves the user typing the same word twice — and it happens here, in the
        // form, where they can see it land and overwrite it, rather than silently at save. Save
        // still refuses a blank title: that stop is deliberate, and quietly filling it in behind the
        // user would remove the one guard that keeps items findable.
        copy(uris = next, title = derived ?: title, titleError = if (derived != null) false else titleError)
    }

    fun addUri() = update { copy(uris = uris + "") }

    fun removeUri(index: Int) = update {
        if (index !in uris.indices) this else copy(uris = uris.filterIndexed { i, _ -> i != index })
    }
    // Typing restarts a reprompt note's reveal timer, as [touchPhraseReveal] does for the grid
    // (O1-03, 2026-10-02). The gate swaps the editable well for a masked plate, so a timer that ran
    // out mid-sentence took the field, the caret and the keyboard from someone still typing.
    fun onNoteBodyChanged(value: String) {
        val taken = noteAfterEdit(_state.value.noteBody, value) ?: return refuseOversizedNote()
        update { copy(noteBody = taken) }
        if (NOTE_BODY_FIELD in _state.value.revealedFields) {
            scheduleAutoHide(NOTE_BODY_FIELD) { copy(revealedFields = revealedFields - NOTE_BODY_FIELD) }
        }
    }
    fun onTotpIssuerChanged(value: String) = update { copy(totpIssuer = value) }
    fun onTotpAccountChanged(value: String) = update { copy(totpAccount = value) }
    fun onTotpSecretChanged(value: String) = update { copy(totpSecret = value) }
    fun onTotpAlgorithmChanged(value: TotpAlgorithm) = update { copy(totpAlgorithm = value) }
    fun onTotpDigitsChanged(value: String) = update { copy(totpDigits = value) }
    fun onTotpPeriodSecChanged(value: String) = update { copy(totpPeriodSec = value) }
    fun onTotpSteamToggled(value: Boolean) = update { copy(totpSteam = value) }
    fun showQrScanner() = update { copy(showQrScanner = true) }
    fun dismissQrScanner() = update { copy(showQrScanner = false) }
    fun onCardHolderChanged(value: String) = update { copy(cardHolder = value) }
    // Digits only, enforced here rather than only asked for by the keyboard type. A numeric IME is
    // a hint: it does not apply to a hardware keyboard, to a paste, to voice input, or to the
    // several IMEs that keep a symbol row visible anyway. Filtering on the way in also means the
    // stored value is a clean PAN, which is what the detail screen's grouping and the last-4 search
    // index both assume.
    //
    // Length caps are the widest real ones (19-digit PANs exist, and so do 12-digit PINs on some
    // banks) so the cap only ever stops nonsense, never a legitimate card.
    // Clears its own mark, exactly as `onTitleChanged` does: a ring that outlives the thing it
    // describes is worse than no ring, because the user has already fixed it.
    fun onCardNumberChanged(value: String) =
        update { copy(cardNumber = value.digits(max = 19), cardNumberError = false) }
    fun onCardExpMonthChanged(value: String) = update { copy(cardExpMonth = value.digits(max = 2)) }
    fun onCardExpYearChanged(value: String) = update { copy(cardExpYear = value.digits(max = 4)) }
    fun onCardCvvChanged(value: String) = update { copy(cardCvv = value.digits(max = 4)) }
    fun onCardPinChanged(value: String) = update { copy(cardPin = value.digits(max = 12)) }
    fun onCardNotesChanged(value: String) {
        val taken = noteAfterEdit(_state.value.cardNotes, value) ?: return refuseOversizedNote()
        update { copy(cardNotes = taken) }
    }

    fun onLoginNotesChanged(value: String) {
        val taken = noteAfterEdit(_state.value.loginNotes, value) ?: return refuseOversizedNote()
        update { copy(loginNotes = taken) }
    }

    /** See [MAX_NOTE_CHARS]. Said, because a paste that silently does nothing reads as a broken field. */
    private fun refuseOversizedNote() {
        LkNotices.postRefusal(R.string.itemedit_notes_too_long)
    }
    fun onIdentityFirstNameChanged(value: String) = update { copy(identityFirstName = value) }
    fun onIdentityLastNameChanged(value: String) = update { copy(identityLastName = value) }
    fun onIdentityCompanyChanged(value: String) = update { copy(identityCompany = value) }
    fun onIdentitySsnChanged(value: String) = update { copy(identitySsn = value) }
    fun onIdentityPassportChanged(value: String) = update { copy(identityPassport = value) }
    fun onIdentityLicenseChanged(value: String) = update { copy(identityLicense = value) }
    fun onIdentityEmailChanged(value: String) = update { copy(identityEmail = value) }
    fun onIdentityPhoneChanged(value: String) = update { copy(identityPhone = value) }
    fun onIdentityAddressLine1Changed(value: String) = update { copy(identityAddressLine1 = value) }
    fun onIdentityAddressLine2Changed(value: String) = update { copy(identityAddressLine2 = value) }
    fun onIdentityAddressCityChanged(value: String) = update { copy(identityAddressCity = value) }
    fun onIdentityAddressStateChanged(value: String) = update { copy(identityAddressState = value) }
    fun onIdentityAddressZipChanged(value: String) = update { copy(identityAddressZip = value) }
    fun onIdentityAddressCountryChanged(value: String) = update { copy(identityAddressCountry = value) }
    fun onFolderSelected(value: UUID?) = update {
        copy(folderId = value, showNewFolderInput = false)
    }
    fun onNewFolderNameChanged(value: String) = update { copy(newFolderName = value) }

    // Folders are free. They were Pro, which meant a free install could accumulate a few hundred
    // logins with no way to organise them — and it punished exactly the people who used the app
    // enough to be worth converting. Pro is what the vault holds beyond passwords, not whether you
    // may tidy it.
    fun showNewFolderInput() {
        // At the top level, which is where the comp's card says it goes ("At the top level") and the
        // only honest answer once the chooser draws the tree flat: with nothing expanded there is no
        // "where the user is looking" to create inside. Nesting is done from the Folders screen,
        // which has a parent picker and is the surface that manages folders.
        update { copy(showNewFolderInput = true, newFolderParentId = null) }
    }

    /**
     * Abandons the new-folder name without creating it.
     *
     * Clears the text as well as the card, which is not merely tidiness: [save] commits a pending
     * name through `createFolderInternal`, so a name left behind by a cancelled create would be
     * made into a real folder by the next Save — a folder the user explicitly backed out of.
     */
    fun hideNewFolderInput() {
        update { copy(showNewFolderInput = false, newFolderName = "") }
    }

    fun onTagInputChanged(value: String) = update { copy(tagInput = value) }

    /**
     * The picker's Create. Success collapses the card, selects the new folder and clears the name,
     * so each folder is a small reset that starts again from "New folder" (2026-10-14: "it has to
     * be a small reset each time one adds a new folder" — reversing the 2026-10-12 keep-open run).
     * A refusal (duplicate sibling) leaves the card open with the typed name and the error.
     */
    fun createFolder() {
        viewModelScope.launch { createFolderInternal(keepNaming = false) }
    }

    /**
     * Commits the pending folder name. True when committed or nothing was pending; false when
     * refused (error already set).
     *
     * Extracted so [save] can run it too: the small tick was the only control that committed a
     * typed folder name, and the screen's prominent Save silently discarded it.
     *
     * [keepNaming] leaves the card open and empty for the next name. Nothing passes true now: the
     * 2026-10-12 run-of-folders behaviour was reversed on 2026-10-14, and both [createFolder] and
     * [save] collapse the card — a card still open on the form the user returns to would be the next
     * thing they met.
     */
    private suspend fun createFolderInternal(keepNaming: Boolean = false): Boolean {
        val name = _state.value.newFolderName.trim()
        if (name.isEmpty()) return true
        // Siblings only. "Bank" inside Work and "Bank" inside Personal are two different places.
        val parentId = _state.value.newFolderParentId
        if (_state.value.folders.any { it.parentId == parentId && it.name.equals(name, ignoreCase = true) }) {
            setError(R.string.itemedit_error_folder_name_exists)
            return false
        }
        return runCatching {
            // Created inside whatever folder is expanded in the picker, and through the repository
            // so the hierarchy's own rules — sibling names, the depth limit — apply here exactly as
            // they do on the management screen.
            val folder = repository.createFolder(name, _state.value.newFolderParentId)
            loadFolders()
            update {
                copy(
                    folderId = folder.id,
                    newFolderName = "",
                    showNewFolderInput = keepNaming,
                )
            }
        }.onFailure { setError(ACTION_ERROR) }.isSuccess
    }

    // T7 (2026-08-12): startRenamingFolder/onRenameFolderNameChanged/cancelRenamingFolder/
    // renameFolder and the renamingFolderId/renameFolderName state fields they backed were removed
    // here — rename is now reachable only from the Folders management screen (FolderScreen /
    // Tags are free, for the same reason folders are: finding things again is not a premium
    // feature, it is what a password manager is.
    fun addTag() {
        val current = _state.value
        val tag = current.tagInput.trim()
        if (tag.isEmpty()) return
        // A case-insensitive duplicate used to fall out of the `if` and do nothing at all: the
        // typed text stayed in the box, no tag appeared, no message was shown. Silent refusal is
        // the same signal as a broken button. Say which tag it already has — the whole point is
        // that "Work" and "work" are the same tag, and that is not obvious from the outside.
        val existing = current.tags.firstOrNull { it.equals(tag, ignoreCase = true) }
        if (existing != null) {
            _state.value = current.copy(
                tagInput = "",
                error = R.string.itemedit_error_already_tagged,
                errorArg = existing,
            )
            return
        }
        _state.value = current.copy(
            tags = current.tags + tag,
            tagInput = "",
            error = null,
            errorArg = null,
        )
    }

    fun removeTag(tag: String) = update { copy(tags = tags - tag) }

    // Free, like tags and folders. The `if (!isPro) return` that used to guard this outlived the
    // gate it was defending: the UI stopped hiding the section, so all the guard did was make
    // "Add custom field" a button that silently does nothing for every free user.
    fun addCustomField() {
        update { copy(customFields = customFields + CustomField()) }
    }

    fun updateCustomFieldName(index: Int, value: String) =
        updateCustomField(index) { it.copy(name = value) }

    fun updateCustomFieldValue(index: Int, value: String) =
        updateCustomField(index) { it.copy(value = value) }

    fun updateCustomFieldType(index: Int, value: CustomFieldType) =
        updateCustomField(index) { it.copy(type = value) }

    /**
     * Removing a field re-masks every other one.
     *
     * Reveal state is keyed by list position, and so is the pending auto-hide — [scheduleAutoHide]
     * closes over the index that was revealed. Shifting the surviving indices down (which is what
     * this used to do) left those two disagreeing: reveal the third field, delete the first, and
     * the timer went looking for an index nobody held any more, so the secret stayed on screen for
     * the rest of the editing session. Clearing is the honest fix at this granularity — the stale
     * timer then subtracts from an empty set, and the worst a later timer can do is hide something
     * early, which is the safe direction to be wrong in.
     */
    /**
     * Drops the custom field at [index], offering an Undo when there was anything in it.
     *
     * A filled row went on one mistaken tap of a trash icon sitting next to a Show button, and the
     * only way back was to discard every unsaved edit on the screen. Undo rather than a
     * confirmation dialog: a dialog would stand in front of removing the empty row the user just
     * added, which is the common case, to guard the rare one.
     *
     * Unlike [moveToTrash], this Undo is safe to offer — it re-enters `_state` directly rather than
     * through [viewModelScope], and the screen it belongs to is still on the back stack, because
     * removing a field does not navigate anywhere.
     */
    fun removeCustomField(index: Int) {
        val removed = _state.value.customFields.getOrNull(index) ?: return
        update {
            copy(
                customFields = customFields.filterIndexed { itemIndex, _ -> itemIndex != index },
                revealedCustomFieldIndices = emptySet(),
            )
        }
        if (removed.name.isBlank() && removed.value.isBlank()) return
        LkNotices.post(
            R.string.itemedit_notice_custom_field_removed,
            actionLabelRes = R.string.itemedit_notice_undo,
            onAction = {
                update {
                    copy(
                        // Back where it was, unless later removals have made that index no longer
                        // exist — then it goes on the end rather than not coming back at all.
                        customFields = customFields.toMutableList().apply {
                            add(index.coerceAtMost(size), removed)
                        },
                        revealedCustomFieldIndices = emptySet(),
                    )
                }
            },
        )
    }

    /**
     * Reveals a hidden custom field — through the same §5.2 gate as every other secret.
     *
     * It used to flip the reveal set directly. A hidden custom field is where people keep the
     * things that have no dedicated box: recovery codes, an account PIN, a second password. On an
     * item marked "reprompt" the password was gated and these were not, so the strongest protection
     * the app offers was one tap away from being irrelevant.
     */
    fun toggleCustomFieldRevealed(index: Int) {
        val current = _state.value
        // Hiding never needs authentication — only exposing does.
        if (index in current.revealedCustomFieldIndices) {
            update { copy(revealedCustomFieldIndices = revealedCustomFieldIndices - index) }
            return
        }
        if (current.requiresReauthFor(customFieldRevealKey(index))) {
            // Hand off to the screen, which owns the Activity the prompt needs.
            update { copy(pendingRevealField = customFieldRevealKey(index)) }
            return
        }
        revealCustomFieldNow(index)
    }

    private fun revealCustomFieldNow(index: Int) {
        update { copy(revealedCustomFieldIndices = revealedCustomFieldIndices + index) }
        scheduleAutoHide(customFieldRevealKey(index)) { customFieldAutoHidden(index) }
    }

    /**
     * Fields that always demand re-authentication before they are shown, per UI/UX spec §5.2 —
     * regardless of the item's own reprompt setting. A card's CVV/PIN and a TOTP seed are each
     * enough on their own to use or clone the credential.
     */
    private val alwaysReauthFields = ALWAYS_REAUTH_FIELDS

    private fun requiresReauth(field: String): Boolean = _state.value.requiresReauthFor(field)

    fun toggleFieldRevealed(field: String) {
        // Hiding never needs authentication — only exposing does.
        if (field in _state.value.revealedFields) {
            update { copy(revealedFields = revealedFields - field) }
            return
        }
        if (requiresReauth(field)) {
            // Hand off to the screen, which owns the Activity the prompt needs.
            update { copy(pendingRevealField = field) }
            return
        }
        revealNow(field)
    }

    /** Called by the screen once [RevealAuth] has actually passed. */
    fun onRevealAuthenticated() {
        val current = _state.value
        val field = current.pendingRevealField ?: return
        val forCopy = current.copyAfterReveal
        val pendingSlot = current.phrasePendingFocus
        update { copy(pendingRevealField = null, copyAfterReveal = false, phrasePendingFocus = -1) }
        revealNow(field)
        // The prompt was raised by a slot tap, so finish the tap — same reasoning as `copyAfterReveal`
        // below: sending the user back to tap a second time is how people learn to leave a secret
        // revealed. Ordered after `revealNow` so its auto-hide is already armed when the focus lands.
        if (field == PHRASE_FIELD && pendingSlot in current.phraseWords.indices) {
            update { copy(phraseFocus = pendingSlot, phraseDraft = phraseWords[pendingSlot]) }
        }
        // The user asked to copy and was interrupted by the prompt. Finishing the job they started
        // is the point — sending them back to tap Copy a second time is how people learn to leave
        // secrets revealed instead.
        if (forCopy) update { copy(copyGrantedField = field) }
    }

    /** Called when the prompt was cancelled or failed — the secret stays masked. */
    fun onRevealDenied(@androidx.annotation.StringRes messageRes: Int? = null) {
        _state.update {
            it.copy(
                pendingRevealField = null,
                copyAfterReveal = false,
                // A refused prompt must not leave a slot queued to open the moment some *other*
                // field's prompt passes.
                phrasePendingFocus = -1,
                error = messageRes,
                errorArg = null,
            )
        }
    }

    /**
     * Copying a secret goes through the same §5.2 gate as revealing one.
     *
     * It used to go through no gate at all: Show raised a biometric prompt while Copy, sitting
     * right beside it, put the same value on the clipboard for anyone to paste. That made the
     * prompt decorative — and copying is the worse of the two, because the secret then leaves this
     * FLAG_SECURE window entirely. `VaultItemRow` already refuses inline copy on reprompt items for
     * exactly this reason; this brings the detail screen in line with it.
     */
    fun requestCopy(field: String) {
        val current = _state.value
        if (field in current.revealedFields || !current.requiresReauthFor(field)) {
            update { copy(copyGrantedField = field) }
            return
        }
        update { copy(pendingRevealField = field, copyAfterReveal = true) }
    }

    /** The row that owns [ItemEditUiState.copyGrantedField] calls this once it has copied. */
    fun onCopyPerformed() = update { copy(copyGrantedField = null) }

    private fun revealNow(field: String) {
        update { copy(revealedFields = revealedFields + field) }
        if (field == PHRASE_FIELD) schedulePhraseAutoHide() else {
            scheduleAutoHide(field) { copy(revealedFields = revealedFields - field) }
        }
        // A custom field is revealed through two pieces of state — `revealedFields` on the detail
        // screen, the index set in the editor — and one authentication has to satisfy both, or
        // passing the prompt from the editor would leave the field still masked.
        customFieldIndexOf(field)?.let(::revealCustomFieldNow)
    }

    /**
     * The phrase's auto-hide, which takes the focused slot and its draft down with the reveal.
     *
     * The grid alone is not the whole exposure: the focused slot renders [ItemEditUiState.phraseDraft]
     * in clear whatever the reveal says, so re-masking the other eleven words while leaving one of
     * them on screen would be a timeout that does not time anything out. Only on a phrase that came
     * from the vault — on a capture the words are the user's own, in front of them, and dropping
     * their focus mid-entry would cost work to protect nothing.
     */
    private fun schedulePhraseAutoHide() = scheduleAutoHide(PHRASE_FIELD) {
        val remasked = copy(revealedFields = revealedFields - PHRASE_FIELD)
        if (phraseGateRequired) remasked.copy(phraseFocus = -1, phraseDraft = "") else remasked
    }

    /** Pending auto-hides, one per revealed field. See [scheduleAutoHide]. */
    private val autoHideJobs = mutableMapOf<String, Job>()

    /**
     * Re-masks a revealed secret after [AUTO_HIDE_MILLIS] (UI/UX spec §5.2 "auto-hide after a short
     * timeout") so a secret left on screen — phone put down, handed over, shoulder-surfed — doesn't
     * stay exposed for the whole editing session. Re-tapping reveal restarts the timer.
     *
     * Restarting is what [key] is for. This used to launch an unowned coroutine per reveal, so the
     * timers accumulated instead: reveal a password, hide it, reveal it again ten seconds later, and
     * the first timer — still counting from the first reveal — re-masked the field twenty seconds
     * early. The KDoc claimed the restart the code never did.
     */
    private fun scheduleAutoHide(key: String, hide: ItemEditUiState.() -> ItemEditUiState) {
        autoHideJobs.remove(key)?.cancel()
        autoHideJobs[key] = viewModelScope.launch {
            delay(AUTO_HIDE_MILLIS)
            _state.update { it.hide() }
            autoHideJobs.remove(key)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Save current location
    // ---------------------------------------------------------------------------------------

    /**
     * The capture that is still wanted, and the coroutine running it.
     *
     * A generation counter rather than "cancel and hope", because cancellation is asynchronous and a
     * platform callback can arrive after it. The sequence that motivates it is ordinary: the user
     * taps capture indoors, waits, taps again by the window, and the first request finally resolves
     * — with a fix from the first spot, thirty seconds stale, arriving *after* the second one. Only
     * the current generation may write to the state; anything older is dropped on the floor.
     *
     * The item id is checked alongside it because this ViewModel is reused across routes: an
     * in-flight capture must never publish into an editor that is now showing a different item.
     */
    private var captureJob: Job? = null

    /**
     * The warm-up, kept apart from [captureJob] because the two must never be confused.
     *
     * A capture produces a result and this produces nothing; a capture is what the user asked for
     * and this is speculative. Holding them in one field would eventually let a cancel meant for
     * one hit the other.
     */
    private var warmUpJob: Job? = null
    private var captureGeneration: Int = 0

    /**
     * Takes one fresh fix and puts it in the form, replacing whatever was there.
     *
     * Called by the screen *after* it has run the permission flow, and the provider re-checks the
     * grant regardless — the screen's answer is about a dialog, and the provider's is about the
     * state of the system at the moment of the call, which is what matters when a permission was
     * revoked in Settings while this screen sat open.
     *
     * Nothing is written to the vault here. A capture produces a value in the form, exactly like
     * typing a password does, and Save is still the only thing that commits it.
     */
    /**
     * Ask the receiver to wake up, because the user is looking at a coordinate item.
     *
     * Time-to-first-fix dominates a capture and nothing in the app can shorten it — see
     * [CurrentLocationProvider.warmUp]. What this does is pay it early, so the tap begins against a
     * receiver that is already tracking rather than one starting from cold.
     *
     * Three conditions, and all of them are about honesty rather than about efficiency. The editor
     * must be showing a coordinate item, so the system's location indicator only appears while the
     * user is looking at something whose entire purpose is a location. Precise location must already
     * be granted, so this never prompts and never escalates. And no capture may be running, because
     * a speculative request alongside a real one is just two requests.
     *
     * Nothing it receives is read, and the freshness rule is untouched: a pre-tap fix is still
     * refused by [com.localkeep.core.location.LocationFixPolicy], and the tap still stamps its own
     * reference instant. This warms the hardware, not the answer.
     */
    fun warmLocationReceiver() {
        if (warmUpJob?.isActive == true) return
        if (captureJob?.isActive == true) return
        if (_state.value.readOnly) return
        if (lockManager.lockState.value !is LockState.Unlocked) return
        warmUpJob = viewModelScope.launch { locationProvider.warmUp(WARM_UP_MILLIS) }
    }

    /** Stop it, on leaving the section or on starting a real capture. Idempotent. */
    fun stopWarmingLocationReceiver() {
        warmUpJob?.cancel()
        warmUpJob = null
    }

    /**
     * One reading, on the ordinary window.
     *
     * There was briefly a `precise` parameter here that ran [FixRequest.preciseReading] — a
     * five-minute occupation offered as a second button. It was removed on 2026-08-20 on the
     * principal's judgement, and the judgement is right: a location field in a password manager is
     * a utility, the realistic budget is five to twenty seconds, and a control asking someone to
     * stand still for five minutes is one nobody presses. `preciseReading` itself stays — its
     * caller is the field-measurement harness, where a long occupation is the best available
     * stand-in for a surveyed mark and every short capture is scored against it, which is how
     * the field measurements were scored.
     */
    fun captureCurrentLocation() {
        val snapshot = _state.value
        // The editor cannot represent this item's content, so it must not write to it. Same guard
        // as `save()`; a capture that succeeded here would leave a fix in a form whose Save is
        // already refused, which reads as the app losing the work.
        if (snapshot.readOnly) return
        if (lockManager.lockState.value !is LockState.Unlocked) return

        captureJob?.cancel()
        // The receiver is either already warm or was never going to be; either way the speculative
        // request has done its work and the real one is about to replace it. Leaving it running
        // would mean two overlapping requests on the same provider for the whole capture.
        stopWarmingLocationReceiver()
        // What the form held before this tap, so a Cancel can put it back. Interim readings
        // overwrite `coordinateFix` as they arrive, and without this there is nothing to restore.
        fixBeforeCapture = snapshot.coordinateFix to snapshot.coordinateJustCaptured
        val generation = ++captureGeneration
        val itemId = snapshot.itemId
        val request = FixRequest(allowMockProvider = BuildConfig.DEBUG)
        update {
            copy(
                coordinateCapturing = true,
                coordinateFailure = null,
                error = null,
                coordinateCaptureStartedElapsedMillis = android.os.SystemClock.elapsedRealtime(),
                coordinateCaptureTimeoutMillis = request.timeoutMillis,
                coordinateConvergence = CaptureConvergence.SEARCHING,
            )
        }

        captureJob = viewModelScope.launch {
            // `request` is built above, before the state update, so the ring below counts against
            // the same deadline this call will actually enforce. The one knob it sets: a synthetic
            // fix — `adb emu geo fix`, a mock-provider app — is refused outright in a release
            // build, because the emulator's default is 0,0, which passes every other rule in
            // `LocationFixPolicy` and would put Null Island in a real user's vault,
            // indistinguishable from a place they went. A debug build has to accept one, because
            // that is the only way the flow can be driven on an emulator at all.
            val outcome = locationProvider.currentFix(
                request,
                onConvergence = { next ->
                    // Same three guards as the interim below, for the same reason: a settle
                    // announced by a superseded capture would tell the user to stop waiting on a
                    // reading that is no longer the one in the form.
                    if (generation == captureGeneration &&
                        _state.value.itemId == itemId &&
                        lockManager.lockState.value is LockState.Unlocked
                    ) {
                        update { copy(coordinateConvergence = next) }
                    }
                },
                onInterim = { interim ->
                    // Live, while the capture is still running, so cancelling keeps the best
                    // reading seen rather than throwing the work away. It no longer feeds a number
                    // on screen — that job moved to `coordinateConvergence` above, which answers
                    // the question the metres were only ever a proxy for — but it still decides
                    // what gets saved and which accuracy band the caption will use.
                    //
                    // Guarded by the same generation/identity/lock checks the final result gets,
                    // because an interim from a superseded capture is exactly as wrong as a final one.
                    if (generation == captureGeneration &&
                        _state.value.itemId == itemId &&
                        lockManager.lockState.value is LockState.Unlocked
                    ) {
                        update {
                            copy(
                                coordinateJustCaptured = true,
                                coordinateFix = Coordinate(
                                    latitude = interim.latitude,
                                    longitude = interim.longitude,
                                    capturedAt = interim.capturedAt,
                                    accuracyMeters = interim.accuracyMeters,
                                ),
                            )
                        }
                    }
                },
            )
            // Every one of these is a real sequence, not defensive padding: a superseded retry, a
            // ViewModel reused for another item, and a vault that locked while the receiver was
            // still searching. In all three the correct action is to write nothing at all — the
            // state has already been replaced by whatever caused them.
            if (generation != captureGeneration) return@launch
            if (_state.value.itemId != itemId) return@launch
            if (lockManager.lockState.value !is LockState.Unlocked) return@launch

            when (outcome) {
                is FixOutcome.Success -> update {
                    copy(
                        coordinateCapturing = false,
                        coordinateCaptureStartedElapsedMillis = null,
                        coordinateFailure = null,
                        // The capture is over, so the live question is over with it. Left at its
                        // last value it would be read by the next capture's first frame as an
                        // answer about a series that has not started.
                        coordinateConvergence = CaptureConvergence.SEARCHING,
                        coordinateJustCaptured = true,
                        coordinateFix = Coordinate(
                            latitude = outcome.fix.latitude,
                            longitude = outcome.fix.longitude,
                            capturedAt = outcome.fix.capturedAt,
                            accuracyMeters = outcome.fix.accuracyMeters,
                        ),
                    )
                }
                is FixOutcome.Failure -> update {
                    copy(
                        coordinateCapturing = false,
                        coordinateCaptureStartedElapsedMillis = null,
                        coordinateConvergence = CaptureConvergence.SEARCHING,
                        // A cancellation is not an error and gets no message: the user pressed Back,
                        // or asked again. Telling them their own action failed is noise.
                        coordinateFailure = outcome.reason.takeIf { it != LocationFailure.CANCELLED },
                    )
                }
            }
        }
    }

    /**
     * Stops refining and keeps the best reading so far.
     *
     * The same function the Stop control calls, and the name is the only thing that changed about
     * it: because interim fixes now land in the form as they arrive, stopping early no longer
     * discards anything. Indoors the accuracy converges within a few seconds and then sits still,
     * and this is how the user says "that is as good as it is going to get" without waiting out a
     * deadline that has nothing left to give them.
     */
    fun cancelLocationCapture() {
        // Bumped as well as cancelled, so a callback already on its way in cannot resume the
        // spinner after the user has stopped it.
        captureGeneration++
        captureJob?.cancel()
        captureJob = null
        update { copy(coordinateCapturing = false, coordinateCaptureStartedElapsedMillis = null) }
    }

    /**
     * Stops the capture and puts the form back the way it was before the tap.
     *
     * The other half of [cancelLocationCapture], and the one the "Cancel" label needs to be true.
     * Interim readings land in the form as they arrive, so before this existed a Cancel pressed
     * over a rough first epoch *kept* that epoch — the label said discard and the button did the
     * opposite, which is why the principal could not tell "Cancel" and "Keep the rough reading"
     * apart: they were the same function. The screen calls this while the reading is not yet worth
     * offering (see `readingIsOfferable`) and the keeping one once it is.
     *
     * Restores both the fix and its provenance flag, because a saved fix that was masked before the
     * tap must be masked again after it — [ItemEditUiState.coordinateJustCaptured] is what decides
     * that, and the interim set it true.
     */
    fun abandonLocationCapture() {
        captureGeneration++
        captureJob?.cancel()
        captureJob = null
        val before = fixBeforeCapture
        update {
            copy(
                coordinateCapturing = false,
                coordinateCaptureStartedElapsedMillis = null,
                coordinateConvergence = CaptureConvergence.SEARCHING,
                coordinateFix = if (before != null) before.first else coordinateFix,
                coordinateJustCaptured = if (before != null) before.second else coordinateJustCaptured,
            )
        }
    }

    /**
     * The fix and its `coordinateJustCaptured` flag as they were when the running capture began.
     * Held here rather than in [ItemEditUiState]: it is not something the screen renders, and a
     * ViewModel field survives rotation exactly as the capture itself does.
     */
    private var fixBeforeCapture: Pair<Coordinate?, Boolean>? = null

    /**
     * Records the answer to the system permission dialog, when it was no.
     *
     * Taken from the screen rather than inferred from a failed capture, because the two are
     * different moments: refusing the dialog means no request is made at all, and running one
     * anyway just to be told what the app already knows would spin the receiver for nothing.
     */
    fun onLocationPermissionRefused(approximateOnly: Boolean) {
        captureGeneration++
        captureJob?.cancel()
        captureJob = null
        update {
            copy(
                coordinateCapturing = false,
                coordinateCaptureStartedElapsedMillis = null,
                coordinateFailure = if (approximateOnly) {
                    LocationFailure.APPROXIMATE_ONLY
                } else {
                    LocationFailure.PERMISSION_DENIED
                },
            )
        }
    }

    /** Clears the last capture failure — on a retry, or when the user has read it. */
    fun dismissLocationFailure() = update { copy(coordinateFailure = null) }

    /** Whether this screen has already queued its lock-discard notice — see the presentation collector. */
    private var discardReported = false

    override fun onCleared() {
        // The receiver is powered while a request is outstanding. A screen the user has left must
        // not keep it that way, and viewModelScope alone does not reach the platform listener — the
        // provider's own cancellation path does, which is what cancelling this job triggers.
        captureGeneration++
        captureJob?.cancel()
        captureJob = null
        super.onCleared()
    }

    fun scanQrCode(raw: String) {
        OtpAuthParser.parse(raw).fold(
            onSuccess = { totp ->
                update { withScannedTotp(totp) }
                refreshTotpCode()
            },
            // Matches the field label. The scheme name is no help at the moment the paste failed,
            // and this string is reached by the QR scanner too, where the user pasted nothing.
            onFailure = { setError(R.string.itemedit_error_invalid_otpauth_link) },
        )
    }

    // ---------------------------------------------------------------------------------------
    // Identity document photographs.
    //
    // The camera runs before the item exists, which is the whole point: "photograph the document"
    // has to mean the shutter, not a form with a button on it that leads to a screen with another
    // button on it. That works because a blob is content-addressed storage with no reference to
    // any item — `FileItemEditViewModel.onCaptured` has captured into an unsaved item this way
    // since Phase 5, and this is the same move.
    // ---------------------------------------------------------------------------------------

    /**
     * Strips, encrypts and remembers one captured frame. [onDone] fires on the main thread with
     * true once the photograph is safely in the blob store.
     *
     * Reported rather than assumed: the capture wizard shows the frame it just took and asks the
     * user to accept it, so it must not advance past a page whose bytes never landed.
     *
     * **Charged against [AttachmentQuota], before the strip and the encrypt.** Every page shot here
     * becomes an attachment on the saved item, so this route spends the same seven free slots
     * `AttachmentPickerViewModel.attach` spends — and until 2026-09-07 it was the one add path that
     * never asked. It could not be reached by a free user then, because the route itself was gated
     * on `isPro`; removing that gate (which was wrong — the free tier is entitled to one identity
     * document) is what makes this check load-bearing rather than defensive.
     *
     * `adding` counts the pages already taken for *this* item as well as the new frame: they are
     * encrypted blobs on disk but the item is unsaved, so the repository has not heard of them yet
     * and a per-frame check of "one more" would let a whole passport through one page at a time.
     */
    /**
     * Clears the capture refusal - the failure flag and the quota count behind it - when the user
     * takes another shot or moves to another page. The quota count is part of the refusal: it picks
     * the sentence the failure flag then shows, and left behind it let a refusal raised on page 2
     * follow Back to a front page that was already stored.
     */
    fun clearIdentityPhotoFailure() = update { withoutIdentityPhotoRefusal() }

    /**
     * Whether the *next* frame would be refused by [AttachmentQuota] — the same read
     * [captureIdentityPhoto] does before it strips and encrypts, exposed so the chooser page can
     * ask it before the shutter is ever shown. `null` means there is room; otherwise the "remove
     * N" count [captureIdentityPhoto] would itself compute. A repository read that throws fails
     * open, same as `creationBlockedByAllowance` — the vault locking mid-check is the ordinary
     * cause, and the lock already owns the exit.
     */
    fun identityPhotoQuotaMustRemove(): Int? {
        val used = runCatching { AttachmentQuota.used(repository) }.getOrNull() ?: return null
        val adding = _state.value.identityPhotos.size + 1
        return if (AttachmentQuota.exceedsFreeLimit(used, entitlement.isPro.value, adding = adding)) {
            AttachmentQuota.mustRemove(used, adding)
        } else {
            null
        }
    }

    fun captureIdentityPhoto(jpegBytes: ByteArray, onDone: (Boolean) -> Unit) {
        val store = fileBlobStore
        if (store == null) {
            onDone(false)
            return
        }
        // Mirror the store == null branch: a concurrent-capture no-op must still resolve the
        // callback. A fast double-tap on the shutter reaches here with a capture already in flight;
        // returning without onDone left the second tap's continuation unresolved and could desync
        // the thumbnail rail from state.identityPhotos.
        if (_state.value.identityPhotoSaving) {
            onDone(false)
            return
        }
        // Refused here rather than at save time, so the pages already in hand survive the refusal
        // and the user is told which one did not fit while the camera is still up. Kept as its own
        // check (not just a call to identityPhotoQuotaMustRemove()) because this one also needs
        // `used` to compute the state update below without a second repository read; the two must
        // stay logically identical, which is what the KDoc on that function says.
        val used = runCatching { AttachmentQuota.used(repository) }.getOrNull()
        if (used != null) {
            val adding = _state.value.identityPhotos.size + 1
            if (AttachmentQuota.exceedsFreeLimit(used, entitlement.isPro.value, adding = adding)) {
                _state.update {
                    it.copy(identityPhotoQuotaMustRemove = AttachmentQuota.mustRemove(used, adding))
                }
                onDone(false)
                return
            }
        }
        update { copy(identityPhotoSaving = true, identityPhotoQuotaMustRemove = null) }
        viewModelScope.launch {
            // A private copy of the DEK, not the manager's live array: the strip and the chunked
            // encrypt can outlive the auto-lock grace timer, and lock() zeroes the manager's own
            // array underneath us — sealing the blob under an all-zero key. Same reason
            // AttachmentPickerViewModel takes a snapshot.
            val session = lockManager.sessionSnapshotOrNull()
            if (session == null) {
                update { copy(identityPhotoSaving = false, identityPhotoFailed = true) }
                onDone(false)
                return@launch
            }
            val attachment = try {
                withContext(Dispatchers.IO) {
                    // There is no metadata toggle on this route and there must not be one: a
                    // photograph of a passport carries where it was taken.
                    //
                    // `sanitize`, the Attachments camera's fail-closed path, not `strip` (S3-08,
                    // 2026-10-02): `strip` returns the original bytes, Exif and all, on any failure,
                    // and its own KDoc rules it out for privacy claims. A frame that cannot be cleaned
                    // now fails the capture through the catch below instead of being stored.
                    val stripped = when (val sanitized = ExifStripper.sanitize(jpegBytes, "image/jpeg")) {
                        is SanitizerResult.Success -> sanitized.bytes
                        is SanitizerResult.Failed,
                        is SanitizerResult.Unsupported,
                        -> error("identity photo could not be sanitized")
                    }
                    val blobRef = store.encryptToBlob(java.io.ByteArrayInputStream(stripped), session.dek)
                    Attachment(
                        id = UUID.randomUUID(),
                        // No timestamp in the name — the strip above exists precisely so *when* the
                        // picture was taken is not stored, and a filename is shown in clear.
                        filename = "document-${_state.value.identityPhotos.size + 1}.jpg",
                        mime = "image/jpeg",
                        sizeBytes = stripped.size.toLong(),
                        blobRef = blobRef,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } finally {
                Zeroizer.wipe(session.dek)
            }
            if (attachment == null) {
                update { copy(identityPhotoSaving = false, identityPhotoFailed = true) }
                onDone(false)
            } else {
                update { copy(identityPhotos = identityPhotos + attachment, identityPhotoSaving = false) }
                onDone(true)
            }
        }
    }

    /** Drops the most recent photograph — the Retake control. The blob is left to the sweep. */
    fun retakeLastIdentityPhoto() {
        update { copy(identityPhotos = identityPhotos.dropLast(1)) }
    }

    /**
     * Save under the name chosen in the last step of a new item.
     *
     * One call rather than `onTitleChanged` followed by `save()`, because those are two separate
     * `MutableStateFlow` writes and [save] reads `_state.value` fresh: any recomposition or
     * cancellation landing between them would run the save against the old title. Setting and
     * saving in one function makes that gap impossible to open at a call site.
     *
     * [save] still refuses a blank title on its own. This does not weaken that guard — it feeds it.
     */
    fun saveWithTitle(title: String) {
        update { copy(title = title, titleError = false) }
        save()
    }

    /**
     * Everything [save] refuses for a reason that is not the title, in the order it refuses it.
     *
     * Split out on 2026-08-19, when the title stopped being a field in the form and became the last
     * step of a new item ([SaveSheet]). That step must not open over an item that cannot be saved
     * anyway: asking someone to name a 2FA entry and *then* telling them it has no secret spends
     * their answer on nothing. So the naming step runs these first, through [readyToName], and the
     * title check stays where it was — in [save], as the last thing between the user and the write.
     *
     * Returns true when it has already reported the refusal; the caller only has to stop.
     */
    private fun blockedBeforeTitle(snapshot: ItemEditUiState): Boolean {
        // A concealed form holds a blank default, not the item (O1-02, 2026-10-02), and with
        // `originalItem` dropped a save would even take the add path under the stored id. Refused
        // silently: the screen is already leaving, and a message would say a hidden item exists.
        if (snapshot.concealed) return true
        // Refused here rather than only in the UI. The editor cannot represent this item's content,
        // so every field it would write is a default it invented — saving is data destruction, not
        // an edit, and a guard that lives only in a button's visibility is one navigation change
        // away from being gone.
        if (snapshot.readOnly) {
            setError(R.string.itemedit_error_readonly_cannot_save)
            return true
        }
        // The allowance's only live gate inside the editor, and worth saying plainly rather than
        // calling this defence in depth: `onTypeChanged` has **no production call site**. The route
        // the add sheet actually uses is `presetType`, which does not gate — `ItemEditScreen` says
        // so in as many words at its `LaunchedEffect(presetType)`. So the layering is: the add sheet
        // refuses first and routes to its own quota dialog, and anything that gets past it (a deep
        // link, a stale route) is stopped here, at save. The paragraph that used to sit here
        // described `onTypeChanged` as a second live layer; that was inherited from the
        // `PRO_GATED_TYPES` version and was already untrue then.
        //
        // What it deliberately no longer catches is an *edit*. The paragraph here used to argue for
        // refusing those too, and that was right while a free vault was allowed zero of these types:
        // every such item was one the tier had never been entitled to. Since 2026-09-03 one card and
        // one identity are free and the allowance limits creation only, so a saved edit is a user
        // changing their own legitimately-held record. Import never charged against the cap and a
        // lapsed Pro purchase leaves records behind, so a vault over the cap is an ordinary state,
        // not an exploit — and refusing to write to it would be holding the user's data hostage.
        if (creationBlockedByAllowance(snapshot)) {
            update { copy(showProPrompt = true) }
            return true
        }
        // Ahead of toContent(), so the ordinary "I haven't filled it in yet" case never reaches the
        // parser and comes back as whatever the parser thinks of an empty string.
        if (snapshot.type == ItemEditUiState.ItemType.TOTP && snapshot.totpSecret.isBlank()) {
            setError(R.string.itemedit_error_totp_secret_required)
            return true
        }
        // Same shape as the TOTP guard above and for the same reason: an item with no position is
        // not a partial coordinate, it is an empty one, and letting it save would put a row in the
        // vault that answers the only question it exists to answer with 0, 0.
        if (snapshot.type == ItemEditUiState.ItemType.COORDINATE && snapshot.coordinateFix == null) {
            setError(R.string.itemedit_error_coordinate_required)
            return true
        }
        // The disabled Save button is the gate the user meets — this is the backstop behind it, the
        // same shape as the TOTP and coordinate guards. A phrase that is incomplete, misspelled or
        // fails its checksum is refused here; the banner on the form already says which of those it
        // is, so this reuses the one generic "check the fields" line rather than restating it.
        if (snapshot.type == ItemEditUiState.ItemType.PHRASE && snapshot.phraseSaveBlocked) {
            setError(R.string.itemedit_error_check_fields)
            return true
        }
        // The wizard already refuses this and the flat form did not, which is the whole defect: both
        // are views over this one state — [CardWizard] says so in its own doc comment — but only the
        // wizard has a minimum, `canAdvance = digits.isNotEmpty()` on its number step. So a card
        // could not be *walked* out of the wizard blank while "Enter everything at once" saved one
        // that was nothing but a title: no number, no expiry, no CVV, no PIN. Found on a device,
        // 2026-09-06.
        //
        // Creation only, unlike the TOTP and coordinate guards above. Those refuse an item that
        // cannot mean anything whatever its history. A *stored* card with no number is an ordinary
        // record — an import that carried a cardholder and an expiry and nothing else — and refusing
        // to write to it would hold the user's own data hostage. That is the same line the allowance
        // gate above already drew between creating and editing, and for the same reason.
        //
        // The generic "check the fields" rather than a card-specific line: a new user-facing string
        // cannot ship without its four translations, and none can be produced in this repo (the
        // translation step is the one flow that needs a human).
        if (snapshot.isNew &&
            snapshot.type == ItemEditUiState.ItemType.CARD &&
            snapshot.cardNumber.isBlank()
        ) {
            // The field is marked as well as the banner set — see [ItemEditUiState.cardNumberError].
            // "Check the item fields" on a seven-field form does not say which field, and this is
            // the only one it can ever mean.
            update { copy(cardNumberError = true) }
            setError(R.string.itemedit_error_check_fields)
            return true
        }
        // Expiry, on exactly the reasoning of the card-number guard above, extended to the field it
        // never covered. `save()` has a `require(!cardExpMonthInvalid)` backstop, so a bad expiry
        // did not corrupt anything - it threw, was caught by the surrounding `runCatching`, and
        // surfaced as the generic "check the fields" *inside the naming sheet*, one screen away
        // from the field at fault and after the user had already been asked to name the thing.
        //
        // The wizard cannot reach this: its ExpiryStep refuses to advance. Only the flat "Enter
        // everything at once" form can, which is why the guard the wizard makes unnecessary is
        // still needed here.
        //
        // Unlike the card number this one CAN name its field, because both messages already exist
        // and are already translated - no new string, so no DeepSeek step to wait on.
        if (snapshot.type == ItemEditUiState.ItemType.CARD && snapshot.cardExpMonthInvalid) {
            setError(R.string.itemedit_card_exp_month_error)
            return true
        }
        // An unfinished year — three digits (S3-09, 2026-10-02) or one (C1, 2026-10-04) — is refused
        // here with the same message: it is the invalid year the inline check deliberately leaves unmarked.
        if (snapshot.type == ItemEditUiState.ItemType.CARD &&
            (snapshot.cardExpYearInvalid || snapshot.cardExpYearUnfinished)
        ) {
            setError(R.string.itemedit_card_exp_year_error)
            return true
        }
        // The catch-all behind the named guards above: anything else the content cannot be built
        // from — a login's 2FA secret that is not base32, say — is refused here, on the form, where
        // its own inline message already sits. It used to reach the naming step and come back from
        // [save] as "Check the item fields" inside the sheet, over the field at fault (QA 2026-10-03).
        if (runCatching { snapshot.toContent() }.isFailure) {
            setError(R.string.itemedit_error_check_fields)
            return true
        }
        return false
    }

    /**
     * Whether the naming step may open — that is, whether this item would save if it had a name.
     *
     * The refusal is reported by [blockedBeforeTitle] before this returns false, so the caller
     * shows nothing of its own: the message is already on the form, beside or below the field that
     * caused it, which is where the user can act on it.
     */
    fun readyToName(): Boolean {
        val snapshot = _state.value
        if (snapshot.isSaving) return false
        if (blockedBeforeTitle(snapshot)) return false
        // Cleared on the way in, because the naming step shows `error` and a stale one would greet
        // the user with a refusal that has already been fixed — the coordinate item is the easy
        // case: fail to save with no fix, take a reading, press Save, and the sheet opens quoting
        // the failure that reading just resolved.
        update { copy(error = null, errorArg = null) }
        return true
    }

    fun save() {
        if (_state.value.isSaving) return
        // Commit a pending phrase-word edit the same way the pending folder name is committed below:
        // a correction typed into a focused slot but not chip-committed must not be dropped on save.
        update { withPendingPhraseDraftCommitted() }
        val snapshot = _state.value
        if (blockedBeforeTitle(snapshot)) return
        // The title is the only thing the vault list, search and every picker have to go on, so an
        // untitled item is one the user cannot find again by any means the app offers — it just
        // becomes another "Untitled" row. Every other field is genuinely optional; this one is what
        // makes the item retrievable, so it is the one thing worth stopping for.
        //
        // Still here, and still the backstop, even though a new item is now named in a step whose
        // button dims on a blank field. A guard that lives only in a button's enabled state is one
        // navigation change away from being gone — the same argument the readOnly check above makes.
        if (snapshot.title.isBlank()) {
            update { copy(titleError = true) }
            setError(R.string.itemedit_error_title_required)
            return
        }
        val content = runCatching { snapshot.toContent() }.getOrElse {
            // Deliberately a fixed, generic resource rather than `it.message`: the specific
            // `require()` reasons in toContent()/toTotp() (card expiry range, TOTP digit/period
            // range) are internal validation text, per checkNoHardcodedUiStrings's own
            // `internalOnly` exclusion and the do-not-translate list's "never user-facing" exception
            // text category — and every one of those cases is already shown inline, by the field
            // that owns it, before Save can even be pressed. Echoing `it.message` here would also
            // have meant threading an arbitrary, untranslatable exception string through a
            // `@StringRes Int` field, which cannot represent it at all.
            setError(R.string.itemedit_error_check_fields)
            return
        }
        // Same normalization `toContent()` applies before its own `.distinct()` — computed here,
        // not read back off `content`, because `Login.uris` is already deduplicated by the time
        // this function could inspect it. Counted before the write succeeds; posted only after it
        // does, in the `onSuccess` below, so a failed save never claims a drop that was never
        // written.
        val duplicateWebsitesDropped = if (snapshot.type == ItemEditUiState.ItemType.LOGIN) {
            val trimmed = snapshot.uris.map(String::trim).filter(String::isNotEmpty)
            trimmed.size - trimmed.distinct().size
        } else {
            0
        }
        update { copy(isSaving = true, error = null) }
        viewModelScope.launch {
            // A pending folder name is visible work in progress; the prominent Save must not
            // silently discard what the small tick would have kept (2026-07-29 hand-test).
            if (snapshot.showNewFolderInput && snapshot.newFolderName.isNotBlank()) {
                if (!createFolderInternal()) {
                    _state.update { it.copy(isSaving = false) }
                    return@launch
                }
            }
            // Read after the commit above, never from the snapshot: the folder the item belongs in
            // is the one that now exists, not the null that was selected when Save was pressed.
            val folderId = _state.value.folderId
            runCatching {
                val now = Instant.now()
                val existing = originalItem
                val item = if (existing == null) {
                    VaultItem(
                        id = snapshot.itemId,
                        title = snapshot.title.trim(),
                        folderId = folderId,
                        tags = snapshot.tags,
                        favorite = snapshot.favorite,
                        createdAt = now,
                        updatedAt = now,
                        reprompt = snapshot.reprompt,
                        customFields = snapshot.customFields,
                        // The document photographs, whose blobs were written while the camera was
                        // still open. This is the moment they stop being garbage the sweep would
                        // collect and become the item's content.
                        attachments = snapshot.identityPhotos,
                        content = content,
                    )
                } else {
                    // Read now, not at load: "Manage attachments" writes the list while this form
                    // is open, and a dirty form is never reloaded. See [attachmentsForEdit].
                    val stored = repository.visibilitySnapshot().let { s ->
                        s.liveItems.firstOrNull { it.id == existing.id }
                            ?: s.trashItems.firstOrNull { it.id == existing.id }
                    }
                    existing.copy(
                        title = snapshot.title.trim(),
                        folderId = folderId,
                        tags = snapshot.tags,
                        favorite = snapshot.favorite,
                        updatedAt = now,
                        reprompt = snapshot.reprompt,
                        customFields = snapshot.customFields,
                        // Appended, never assigned: the attachments screen owns this list, and
                        // overwriting it with this editor's pending photos would delete every file
                        // the user attached there.
                        attachments = attachmentsForEdit(
                            stored = stored?.attachments,
                            loaded = existing.attachments,
                            pending = snapshot.identityPhotos,
                        ),
                        content = content,
                    )
                }
                if (existing == null) {
                    // Mapped rather than discarded. The folder can be deleted from the drawer while
                    // this editor is open, and an item pointing at one that is gone shows up in no
                    // list at all — the add path is where that race is longest, because the user
                    // opened the editor from inside the folder and then typed for a minute.
                    when (repository.addItem(item)) {
                        com.localkeep.core.vault.AddItemOutcome.FOLDER_ABSENT -> UpdateOutcome.SAVED_FOLDER_ABSENT
                        com.localkeep.core.vault.AddItemOutcome.ADDED -> UpdateOutcome.UPDATED
                    }
                } else {
                    // The `updatedAt` this editor loaded. Something else can have written the item
                    // in the meantime — the autofill Save-confirm activity shares this vault, so
                    // accepting a browser's "update your saved password?" while an edit screen sits
                    // open is an ordinary Tuesday. Without this the editor's stale copy silently
                    // reinstated the old password.
                    repository.updateItem(item, expectedUpdatedAt = existing.updatedAt)
                }
            }.onSuccess { outcome ->
                if (outcome == UpdateOutcome.SAVED_FOLDER_ABSENT) {
                    // The item is saved either way — refusing the write would lose an edit the user
                    // really made. What they are told is where it went, because "filed nowhere" is
                    // the one outcome they cannot deduce from the list they land back on.
                    com.localkeep.core.ui.nav.PendingFolderSelection.clear()
                    update { copy(isSaving = false, saved = true, folderId = null) }
                    LkNotices.postRefusal(R.string.itemedit_folder_absent_notice)
                    baseline = _state.value.editableOnly()
                    return@onSuccess
                }
                if (outcome == UpdateOutcome.SAVED_INTO_TRASH) {
                    // The folder this item lived in was moved to Trash by another surface while the
                    // edit was open, so the item was saved into Trash with it (the repository returns
                    // this outcome ahead of FOLDER_ABSENT). The edit is kept and recoverable, but
                    // that it is now in Trash is the one thing the user cannot read off the list they
                    // return to — so say it, mirroring the folder-absent branch above. Core's KDoc and
                    // FolderTrashTest both require the editor to surface this.
                    update { copy(isSaving = false, saved = true) }
                    LkNotices.post(R.string.itemedit_saved_into_trash_notice)
                    baseline = _state.value.editableOnly()
                    return@onSuccess
                }
                if (outcome == UpdateOutcome.KEPT_NEWER_PASSWORD) {
                    // Reload before saying anything: the fields must show what is actually stored,
                    // or the notice would sit above the stale password it is warning about.
                    loadItem(snapshot.itemId.toString())
                    update {
                        copy(
                            isSaving = false,
                            saved = false,
                            conflictNotice = R.string.itemedit_conflict_notice,
                        )
                    }
                } else {
                    update { copy(isSaving = false, saved = true) }
                    // No shell notice any more, on any route.
                    //
                    // It existed because `saved = true` used to navigate away immediately, so a
                    // confirmation shown by this screen would be torn down in the frame it
                    // appeared — the notice was the only place left to say it. The Identity camera
                    // route was already the exception, because it holds its own Saved screen and
                    // the notice drew on top of that screen's Done button (measured on
                    // emulator-5554, 2026-08-19).
                    //
                    // Every save now holds that same Saved screen, so the exception is the rule:
                    // measured again on emulator-5554, 2026-08-19, the notice overlapped Done on an
                    // ordinary note save exactly as it did on the identity one. The Saved screen
                    // names what it wrote and states what protects it, which is more than the
                    // notice said.
                }
                // Not folded into the "no shell notice" rule above: that rule is about the generic
                // "item saved" confirmation the Saved screen now makes redundant. This says
                // something the Saved screen does not — a website the user typed did not end up on
                // the item — the same reasoning that keeps the folder-absent and saved-into-trash
                // notices two branches up despite the same Saved-screen overlap.
                if (duplicateWebsitesDropped > 0) {
                    LkNotices.post(
                        LkNotices.Message.Plural(
                            R.plurals.itemedit_notice_duplicate_website_dropped,
                            duplicateWebsitesDropped,
                        ),
                    )
                }
                // Saved work is no longer unsaved, so leaving must stop warning about it.
                baseline = _state.value.editableOnly()
            }.onFailure { failure ->
                // A scope cancellation (the ViewModel being cleared mid-write) is cooperative
                // control flow, not a save failure — rethrow it instead of flashing ACTION_ERROR,
                // matching SettingsViewModel.runOperation and this file's own identity-capture path.
                if (failure is CancellationException) throw failure
                _state.update {
                    it.copy(isSaving = false, error = saveFailureMessage(failure), errorArg = null)
                }
            }
        }
    }

    /** Seeds the folder from the list's FAB, on the new-item route only. See [PendingFolderSelection]. */
    fun seedFolderFromPending() {
        val pending = com.localkeep.core.ui.nav.PendingFolderSelection.take() ?: return
        if (_state.value.folders.none { it.id == pending }) return
        update {
            copy(
                folderId = pending,
                // A selection the user cannot see is indistinguishable from none, so the picker
                // opens with the seeded folder's ancestors already open.
            )
        }
    }

    private fun loadFolders() {
        // Through the vault list's own projection, not the raw vault. The picker offers places to
        // file an item, and a trashed folder is not one: filing there would put the item somewhere
        // the list cannot show and Trash does not list either. Neither is an invisible folder outside
        // a reveal — read raw, the picker offered one by name under "Change folder" while the list
        // hid it (QA 2026-10-03).
        val visible = runCatching { repository.visibilitySnapshot() }.getOrNull()
        val folders = visible?.liveFolders.orEmpty().sortedByDisplayName { it.name }
        // Live, visible items only, matching the folder list above: a trashed item is not in the
        // folder any more in any sense the user would recognise, and counting a concealed one would
        // say a hidden entry is in there.
        val liveItems = visible?.liveItems.orEmpty()
        val loginTitles = liveItems.filter { it.content is Login }.mapTo(HashSet()) { it.title.trim() }
        // Whole-subtree counts, as every other folder picker shows them: counted direct, a folder
        // whose items all sat in a subfolder read "(0)" here and "(4)" one screen away (QA
        // 2026-10-03). Each filed folder climbs its own ancestor chain once, bounded by a visited
        // set for a cyclic list on disk.
        val parentOf = folders.associate { it.id to it.parentId }
        val counts = HashMap<UUID, Int>()
        liveItems.mapNotNull { it.folderId }.groupingBy { it }.eachCount().forEach { (folderId, n) ->
            val seen = HashSet<UUID>()
            var current: UUID? = folderId.takeIf { it in parentOf }
            while (current != null && seen.add(current)) {
                counts[current] = (counts[current] ?: 0) + n
                current = parentOf[current]
            }
        }
        update {
            copy(
                folders = folders,
                folderItemCounts = counts,
                existingLoginTitles = loginTitles,
            )
        }
    }

    /**
     * Drops a login's second factor.
     *
     * Needed the moment the seed became visible: a field the user can see but can never remove is
     * its own trap, and the only alternative was deleting and re-creating the whole login.
     */
    fun removeLoginTotp() = update {
        if (type != ItemEditUiState.ItemType.LOGIN) {
            this
        } else {
            copy(
                totpIssuer = "",
                totpAccount = "",
                totpSecret = "",
                totpCurrentCode = "",
                totpRemainingSeconds = 0,
            )
        }
    }

    private fun refreshTotpCode() {
        val current = _state.value
        if (!current.carriesTotpSecret()) {
            if (current.totpCurrentCode.isNotEmpty()) {
                _state.value = current.copy(totpCurrentCode = "", totpRemainingSeconds = 0)
            }
            return
        }
        val totp = runCatching { current.toTotp() }.getOrNull()
        val code = totp?.let { runCatching { TotpGenerator.generate(it) }.getOrNull() }.orEmpty()
        val remaining = totp?.let {
            runCatching { TotpGenerator.remainingSeconds(it) }.getOrDefault(0)
        } ?: 0
        _state.update {
            it.copy(totpCurrentCode = code, totpRemainingSeconds = remaining)
        }
    }

    private fun update(transform: ItemEditUiState.() -> ItemEditUiState) {
        _state.update { it.transform().copy(error = null, errorArg = null) }
    }

    private fun setError(@androidx.annotation.StringRes messageRes: Int) {
        _state.update { it.copy(error = messageRes, errorArg = null) }
    }

    private fun updateCustomField(
        index: Int,
        transform: (CustomField) -> CustomField,
    ) = update {
        copy(
            customFields = customFields.mapIndexed { itemIndex, field ->
                if (itemIndex == index) transform(field) else field
            },
        )
    }

    private fun ItemEditUiState.toContent(): ItemContent = when (type) {
        ItemEditUiState.ItemType.LOGIN -> {
            val original = originalItem?.content as? Login ?: Login()
            // A whitespace-only username or password is stored as nothing, as a whitespace-only
            // website already is. The view, the list's quick copy and Health all read blank as
            // absent, so a stored "    " was a password the editor showed and every other screen
            // denied — and, once replaced, a history entry with nothing in it (QA 2026-10-03).
            val storedUsername = username.takeUnless { it.isBlank() }.orEmpty()
            val storedPassword = password.takeUnless { it.isBlank() }.orEmpty()
            // Stamp passwordChangedAt only when the secret actually changes (not on metadata edits),
            // so health "old password" age reflects the password, not any edit. A newly-typed
            // password on a brand-new item counts as a change (original.password == "").
            val passwordChanged = storedPassword != original.password
            val changedAt = Instant.now()
            original.copy(
                username = storedUsername,
                password = storedPassword,
                notes = loginNotes,
                // The editor records history too, as autofill's save prompt always has. It did not
                // until 2026-07-31, which meant rotating a password by hand and rotating the same
                // password through the browser left the vault in two different states — and once
                // the history became something the user can actually see, an incomplete list is a
                // false account of what happened rather than merely a missing feature.
                passwordHistory = if (passwordChanged) {
                    PasswordHistory.append(passwordHistory, original.password, changedAt)
                } else {
                    passwordHistory
                },
                passwordChangedAt =
                    if (passwordChanged) changedAt else original.passwordChangedAt,
                // Match strategies survive a round-trip through the editor. The form holds only
                // the URI strings, so an imported per-URI strategy (HOST/EXACT/STARTS_WITH/REGEX/
                // NEVER, mapped by BitwardenJsonImporter) would be flattened to the DOMAIN default
                // on every save — silently changing where autofill offers a credential, with no
                // notice and no undo. Carry the original strategy for any URI that is unchanged;
                // a genuinely new URI gets the default.
                //
                // `.distinct()` below drops a URI that repeats one already in the list, with no
                // sign here that it happened — this function returns only the finished content, and
                // has no notice channel of its own to say so. `save()` computes the same count
                // ahead of this call and posts it once the write actually succeeds.
                uris = uris.asSequence()
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .distinct()
                    .map { uri ->
                        val originalStrategy = original.uris
                            .firstOrNull { it.uri == uri }
                            ?.strategy
                        if (originalStrategy != null) {
                            UriMatch(uri = uri, strategy = originalStrategy)
                        } else {
                            UriMatch(uri)
                        }
                    }
                    .toList(),
                // Rebuilt from state rather than carried through by `original.copy`, so that
                // removing the second factor actually persists. Carrying it through was correct
                // while nothing could edit it and silently wrong the moment something could.
                //
                // Validated the same way the standalone TOTP branch validates (see the TOTP case
                // below): a Login's embedded 2FA secret must be Base32-decodable, so an invalid one
                // fails save() with "check the fields" rather than persisting and then producing no
                // code where autofill and quick-copy expect one.
                totp = if (totpSecret.isBlank()) null else toTotp().also { TotpGenerator.generate(it) },
            )
        }
        ItemEditUiState.ItemType.NOTE -> Note(noteBody)
        ItemEditUiState.ItemType.TOTP -> toTotp().also {
            TotpGenerator.generate(it)
        }
        ItemEditUiState.ItemType.PHRASE -> toSeedPhrase()
        ItemEditUiState.ItemType.CARD -> {
            // The same predicate the form shows inline. The guard used to be
            // `month == null || month in 1..12`, which let a non-numeric month parse to null and
            // take the *blank* path: the value the user typed was dropped on save, silently.
            require(!cardExpMonthInvalid) { "Expiration month must be 1–12" }
            require(!cardExpYearInvalid && !cardExpYearUnfinished) { "Expiration year is invalid" }
            val month = cardExpMonth.trim().toIntOrNull()
            val year = cardExpYear.trim().toIntOrNull()
            Card(
                holder = cardHolder,
                number = cardNumber,
                // Derived on save rather than stored as the user types: it is a function of the
                // number, so keeping it in the form state would only create a second place for it
                // to be wrong.
                // Detection stays authoritative for a recognisable PAN, but a card whose number
                // the IIN ranges don't match (loyalty/gift/co-brand) keeps the brand it was
                // imported with instead of being flattened to UNKNOWN on every save.
                brand = cardBrandToStore(cardNumber, carried = cardBrand, carriedFor = (originalItem?.content as? Card)?.number),
                expMonth = month,
                expYear = year,
                cvv = cardCvv,
                pin = cardPin,
                notes = cardNotes,
            )
        }
        ItemEditUiState.ItemType.COORDINATE -> {
            // `save()` has already refused a null fix with a message the user can act on, so this is
            // the invariant rather than the check — reaching it means something bypassed Save.
            val fix = requireNotNull(coordinateFix) { "no captured fix" }
            require(fix.isValid()) { "captured fix is out of range" }
            fix
        }
        ItemEditUiState.ItemType.IDENTITY -> Identity(
            firstName = identityFirstName,
            lastName = identityLastName,
            company = identityCompany,
            ssn = identitySsn,
            passport = identityPassport,
            license = identityLicense,
            email = identityEmail,
            phone = identityPhone,
            address = Address(
                line1 = identityAddressLine1,
                line2 = identityAddressLine2,
                city = identityAddressCity,
                state = identityAddressState,
                zip = identityAddressZip,
                country = identityAddressCountry,
            ),
        )
        // Defense in depth, not the gate the user meets: `readOnly` (set unconditionally by
        // `withPasskeyContent`) already withholds the Edit button and Save's `enabled`, and `save()`
        // itself returns early on a readOnly snapshot before this ever runs. There is no editable
        // form for a passkey to rebuild from, so if a save somehow still reached here, returning the
        // stored credential unchanged is the only safe answer — building a fresh `Passkey()` would
        // silently overwrite `privateKeyRef`, `credentialId` and `signCounter` with defaults.
        ItemEditUiState.ItemType.PASSKEY ->
            requireNotNull(originalItem?.content as? Passkey) { "no stored passkey content" }
    }

    private fun ItemEditUiState.toTotp(): Totp {
        val digits = totpDigits.toIntOrNull()
        require(digits == 6 || digits == 8) { "TOTP digits must be 6 or 8" }
        val period = totpPeriodSec.toIntOrNull()
        require(period != null && period > 0) { "TOTP period must be positive" }
        return Totp(
            issuer = totpIssuer.trim(),
            account = totpAccount.trim(),
            secretBase32 = totpSecret
                .filterNot(Char::isWhitespace)
                .uppercase(Locale.ROOT),
            algorithm = totpAlgorithm,
            digits = digits,
            periodSec = period,
            steam = totpSteam,
        )
    }

    private fun <T> Set<T>.toggle(value: T): Set<T> =
        if (value in this) this - value else this + value

    companion object {
        /** How long a revealed secret stays visible before it re-masks itself (§5.2). */
        private const val AUTO_HIDE_MILLIS = 30_000L

        /**
         * The hard cap on a warm-up, in case the user opens a coordinate item and simply leaves it
         * there. Thirty seconds is about one warm-to-hot transition, so it covers the case it exists
         * for and then stops; the composable's own teardown handles the ordinary ending, and this
         * handles the one where there is no ending. Roughly 0.5 mWh at the top of the receiver's
         * draw — three thousandths of a percent of a phone battery.
         */
        private const val WARM_UP_MILLIS = 30_000L
        // Every path that sets this — save, create folder, rename folder — leaves the control that
        // failed on screen and re-enabled, so the gap the user was left with was not a missing
        // button but a message that never said trying again was the move.
        @androidx.annotation.StringRes
        internal val ACTION_ERROR: Int = R.string.itemedit_error_action_failed

        /**
         * The one save failure retrying cannot fix.
         *
         * Names the constraint (this device's memory), states what is safe (the stored vault), and
         * gives the only action that works (make the vault smaller). It deliberately does not print
         * the heap figure: "48 MiB" means nothing to the person reading it, and the number is the
         * device's, not something they chose or can change.
         */
        @androidx.annotation.StringRes
        internal val VAULT_TOO_LARGE_ERROR: Int = R.string.itemedit_error_vault_too_large

        /**
         * The other save failure retrying cannot fix: the device ran out of storage mid-write
         * (AtomicVaultWriter's FileOutputStream or Files.move throws ENOSPC as a plain
         * IOException — there is no dedicated exception type for it). "Try again" sends the user
         * to press a button that fails identically until they free space.
         */
        @androidx.annotation.StringRes
        internal val OUT_OF_SPACE_ERROR: Int = R.string.itemedit_error_out_of_space

        /**
         * A third save failure retrying alone cannot fix: the vault has outgrown the fixed payload
         * bucket its container was built around ([VaultBucketExhaustedException]). Unlike the two
         * above this one *is* fixable by the user — removing items brings the payload back under the
         * bucket — so the message says exactly that. It never names why the bucket is fixed, because
         * that reason is a configured decoy vault and saying so would defeat its deniability.
         */
        @androidx.annotation.StringRes
        internal val BUCKET_EXHAUSTED_ERROR: Int = R.string.itemedit_error_bucket_exhausted

    }
}

/**
 * What a failed save says.
 *
 * Almost everything that can go wrong here is transient — a lock that landed mid-write, a storage
 * hiccup — and [ItemEditViewModel.ACTION_ERROR] is right for all of it: the changes are still in the
 * form, and pressing Save again works.
 *
 * One failure is not transient. A vault that no longer fits this device's heap fails identically on
 * every retry, so telling the user to try again sends them to press a button that cannot succeed,
 * for as long as they are willing to. Separated out here, in plain logic, because the branch is
 * one line in a coroutine that no test can reach.
 */
@androidx.annotation.StringRes
internal fun saveFailureMessage(failure: Throwable): Int = when {
    failure is VaultTooLargeForDeviceException -> ItemEditViewModel.VAULT_TOO_LARGE_ERROR
    failure is VaultBucketExhaustedException -> ItemEditViewModel.BUCKET_EXHAUSTED_ERROR
    failure.isOutOfSpace() -> ItemEditViewModel.OUT_OF_SPACE_ERROR
    else -> ItemEditViewModel.ACTION_ERROR
}

/**
 * Whether [this] is the disk-full failure AtomicVaultWriter's FileOutputStream/Files.move
 * surfaces on ENOSPC. The JVM/Android platform gives that condition no dedicated exception
 * type — it is a plain [java.io.IOException] (or, from `Files.move`, a
 * [java.nio.file.FileSystemException], itself an IOException) whose message names the errno
 * reason. Checked, never displayed — exception text never reaches the UI.
 */
private fun Throwable.isOutOfSpace(): Boolean =
    this is java.io.IOException &&
        message?.contains("No space left", ignoreCase = true) == true

/**
 * The recovery-phrase content a save writes. Out of `toContent` so a JVM test can reach it.
 *
 * Backstop, not the gate the user meets: `blockedBeforeTitle` and the disabled Save button both
 * refuse an unsaveable phrase before this runs, and `save()` catches this `require` into the
 * generic "check the fields" error rather than crashing. Same shape as the card-expiry and
 * TOTP-range requires — the specific reason is already on the banner. Words are lowercased/trimmed
 * to the canonical BIP-39 form on the way in.
 *
 * CHECKSUM passes here deliberately (4C-14): a phrase whose words are all real but whose checksum
 * does not verify is saveable with a coral warning, because the user who cannot re-read their steel
 * plate needs somewhere to put what they have. Anything that is not a complete grid of real BIP-39
 * words still fails — see [ItemEditUiState.phraseSaveBlocked].
 */
internal fun ItemEditUiState.toSeedPhrase(): SeedPhrase {
    require(!phraseSaveBlocked) { "recovery phrase cannot be saved in this state" }
    return SeedPhrase(
        words = phraseWords.map { it.trim().lowercase(Locale.ROOT) },
        // Verbatim, unlike the words (S3-04, 2026-10-02). A BIP-39 passphrase is arbitrary text and
        // whitespace is part of it: a wallet derives a different seed for "my pass " than for
        // "my pass", so trimming stored a secret that opens a different, empty wallet. The login
        // password is stored untrimmed for the same reason.
        passphrase = phrasePassphrase,
    )
}

/**
 * The attachment list an edit of an existing item writes back.
 *
 * Built on what the vault holds at the moment of the write ([stored]), never on the copy this
 * editor loaded (O4-01, 2026-10-02). The editor manages no attachment but its own pending identity
 * photographs, and its form links to "Manage attachments", which writes the list directly. Saving
 * the load-time list after a visit there dropped the file just attached — its blob then swept at
 * the next unlock — and brought back one just removed. [loaded] is the fallback only for a record
 * the projection does not list (trashed with its folder while the form was open). A pending photo
 * already on [stored], as after a conflict reload, is not added twice.
 */
internal fun attachmentsForEdit(
    stored: List<Attachment>?,
    loaded: List<Attachment>,
    pending: List<Attachment>,
): List<Attachment> {
    val base = stored ?: loaded
    return base + pending.filterNot { photo -> base.any { it.id == photo.id } }
}

/**
 * Keeps the digits, drops everything else, and caps the length. See the card field setters.
 *
 * Any Unicode decimal digit is kept but written as ASCII (S3-10, 2026-10-02). An Arabic-Indic or
 * fullwidth digit passes `isDigit`, and stored as typed it made a "clean PAN" that
 * `CardBrands.detect` misread (Visa is matched on the character '4') and whose indexed last four an
 * ASCII search could not find.
 */
internal fun String.digits(max: Int): String =
    filter(Char::isDigit).take(max).map { '0' + Character.digit(it, 10) }.joinToString("")

/**
 * The site name inside whatever the user typed into a website row, or null if there isn't one yet.
 *
 * Deliberately not `java.net.URL`, which throws on anything without a scheme — and "no scheme" is
 * how people type a website. This works on `bank.example.com` as readily as on
 * `https://bank.example.com/login?x=1`.
 *
 * Stays quiet rather than guessing. It only ever fills a *blank* title, and a wrong guess is worse
 * than none: the user then has to notice it and undo it instead of just typing what they meant. A
 * half-typed host is not a title, and a suggestion appearing letter by letter would fight the
 * person typing.
 */
internal fun hostOf(raw: String): String? {
    val host = raw.trim()
        .substringAfter("://")
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        // After the path split, so a password containing '@' in a userinfo-style URL cannot drag
        // part of the path into the title.
        .substringAfterLast('@')
        .substringBefore(':')
        .removePrefix("www.")
        .lowercase()
    // A dot with a plausible TLD after it is the cheapest test for "this is a hostname, and not
    // three letters on the way to becoming one".
    return host.takeIf { it.contains('.') && it.substringAfterLast('.').length >= 2 }
}

/**
 * The longest the editor lets a notes field grow.
 *
 * A Compose text field lays its whole value out again on every change, and a note grown to ~300,000
 * characters by repeated pasting froze the editor for minutes — long enough to auto-lock behind the
 * "isn't responding" dialog and lose the note (QA 2026-10-03). Twenty thousand characters saved in
 * about four seconds on the slowest emulator measured that day, and holds any key, certificate or
 * recovery text a password manager is asked to keep.
 */
internal const val MAX_NOTE_CHARS = 20_000

/**
 * [proposed] if a notes field may take it, or null to refuse it.
 *
 * Growth past [MAX_NOTE_CHARS] is refused whole — a paste cut off part-way would store half a key
 * and say nothing — and shrinking is always allowed, so a note already longer than the cap (an
 * import, which never came through this editor) can still be edited down.
 */
internal fun noteAfterEdit(previous: String, proposed: String): String? =
    if (proposed.length <= MAX_NOTE_CHARS || proposed.length <= previous.length) proposed else null
