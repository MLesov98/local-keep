package com.localkeep.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Tier G register: for every string that asserts a security *property*, what it may never say —
 * in all five languages. Part of the security-copy review protocol.
 *
 * Tier G is the tier for claims rather than descriptions: where the vault data lives, what protects
 * it, what an attacker cannot do. `UnlockSubtitleTest` has guarded one of them since "on this device
 * only" had to be removed for the fourth time, and its own comment says why it is not enough — it is
 * a substring match on English words, so the identical false claim in German passes it silently.
 * This file is the half that was missing.
 *
 * Three properties:
 *
 * 1. **No banned phrase.** Per key, per language, the specific false claim that must never return.
 *    Hand-authored: someone decided what "never leaves this device" is in German, once, carefully.
 *    A generated list would only ever re-encode the English.
 * 2. **The load-bearing clause survives.** Half of these strings exist to state a *limitation* —
 *    that the breach set is offline, that a delete is not a secure erase, that the phone holds the
 *    only copy *if you turn backup off*. Deleting the qualifier is the cheapest way to make one of
 *    these false, and no banned phrase catches a deletion. So those keys assert what must remain.
 * 3. **The register is total.** [taggedTierGKeys] reads the `SECURITY-COPY: tier G` markers out of
 *    every module's base resources, and the test fails if a marked key is missing here. Adding a
 *    Tier G string without deciding what it may never say becomes a build failure instead of an
 *    omission nobody notices — which is exactly how `vaultlist_count_device_suffix` carried a false
 *    claim through a whole localisation pass with the defect written in its own XML comment.
 *
 * Keys with nothing bannable are still listed, with the reason. "Considered, nothing to ban" and
 * "never looked at" have to be distinguishable, or the totality check means nothing.
 *
 * **This is not a translation review.** It catches a known false claim coming back. It cannot catch
 * a false claim nobody thought to write a phrase for — that is what the 3-vote adversarial pass in
 * the security-copy protocol is for, and neither substitutes for a native reader. There isn't one on this
 * project (decided 2026-07-30); this is part of what ships instead.
 */
class TierGClaimsTest {

    /**
     * Guards [and] itself, because the bug it replaces was invisible: `Map.plus(Map)` over two
     * families keyed by the same five languages silently kept only the right-hand list, so three
     * entries in the register banned half of what they named and every string still passed. A
     * composition bug that drops bans cannot fail the suite it weakens — only this can.
     */
    @Test
    fun composingTwoBannedFamiliesKeepsBothOfThem() {
        val composed = REVEAL_IS_PERMANENT and CONCEALMENT_IS_PROTECTION
        for (lang in REVEAL_IS_PERMANENT.keys) {
            val missing = (REVEAL_IS_PERMANENT.getValue(lang) + CONCEALMENT_IS_PROTECTION.getValue(lang))
                .filterNot { it in composed.getValue(lang) }
            assertTrue(
                missing.isEmpty(),
                "composing dropped $missing from [$lang] — the register would ban less than it names",
            )
        }
    }

    @Test
    fun noTierGStringMakesABannedClaim() {
        val failures = REGISTER.flatMap { claim ->
            val text = claim.read()
            claim.never.flatMap { (lang, phrases) ->
                phrases.filter { it.lowercase() in text.getValue(lang).lowercase() }
                    .map { "${claim.key} [$lang] must not say \"$it\" — says: ${text.getValue(lang)}" }
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun everyLoadBearingQualifierIsStillThere() {
        val failures = REGISTER.flatMap { claim ->
            val text = claim.read()
            claim.always.flatMap { (lang, phrases) ->
                phrases.filterNot { it.lowercase() in text.getValue(lang).lowercase() }
                    .map { "${claim.key} [$lang] must still say \"$it\" — says: ${text.getValue(lang)}" }
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    /** A Tier G string that exists in English and not in French is a Tier G string nobody guards. */
    @Test
    fun everyTierGStringIsTranslatedIntoAllFiveLanguages() {
        REGISTER.forEach { it.read() } // read() errors by name on a missing translation
    }

    @Test
    fun everyStringTaggedTierGIsInTheRegister() {
        assertEquals(
            REGISTER.map { "${it.module}:${it.key}" }.toSortedSet(),
            taggedTierGKeys(),
            "a string is tagged SECURITY-COPY: tier G but the register above does not decide what " +
                "it may never say. Add it — with banned phrases, or with the reason it has none.",
        )
    }
}

// =================================================================================================
// Claim families. Defined once because the same false claim recurs across modules, and a phrase
// list retyped per key is a phrase list that drifts per key.
// =================================================================================================

/**
 * "The vault is only here / never leaves this device."
 *
 * False, and the single most persistent claim in this codebase: `vault.enc` reaches Android Auto
 * Backup unless the user turned cloud backup off. The backup agent is in the shared manifest, so it
 * holds for both flavours, and `foss` having no INTERNET permission does not save it — the OS
 * transport performs the upload, not the app.
 */
private val VAULT_NEVER_LEAVES = mapOf(
    "en" to listOf(
        "only on this device", "this device only", "never leaves this device",
        "never leaves your device", "locked to this device", "stays on this device",
    ),
    "fr" to listOf(
        "uniquement sur cet appareil", "seulement sur cet appareil", "ne quitte jamais cet appareil",
        "ne quitte jamais votre appareil", "verrouillé sur cet appareil", "reste sur cet appareil",
    ),
    "de" to listOf(
        "nur auf diesem gerät", "verlässt niemals dieses gerät", "verlässt nie dieses gerät",
        "verlässt niemals ihr gerät", "an dieses gerät gesperrt", "bleibt auf diesem gerät",
    ),
    "es" to listOf(
        "solo en este dispositivo", "sólo en este dispositivo", "nunca sale de este dispositivo",
        "nunca sale de su dispositivo", "bloqueado a este dispositivo", "permanece en este dispositivo",
    ),
    "pt" to listOf(
        "apenas neste dispositivo", "só neste dispositivo", "nunca sai deste dispositivo",
        "nunca sai do seu dispositivo", "bloqueado a este dispositivo", "permanece neste dispositivo",
    ),
)

/**
 * The same claim, narrowed to the vault itself.
 *
 * For the one string where "never leaves this device" is *true* — `onboarding_cloudbackup_body` says
 * it of the master password, which really does stay here; only the ciphertext goes to Google. That
 * sentence is the reason [VAULT_NEVER_LEAVES] cannot be applied blanket-wise, and the reason this
 * narrower list exists: the claim must never migrate from the password to the vault.
 */
private val THE_VAULT_ITSELF_NEVER_LEAVES = mapOf(
    "en" to listOf("your vault never leaves", "the vault never leaves"),
    "fr" to listOf("votre coffre ne quitte jamais", "le coffre ne quitte jamais"),
    "de" to listOf("ihr tresor verlässt niemals", "ihr tresor verlässt nie", "der tresor verlässt"),
    "es" to listOf("su caja fuerte nunca sale", "la caja fuerte nunca sale"),
    "pt" to listOf("seu cofre nunca sai", "o cofre nunca sai"),
)

/**
 * Absolutes about the cryptography.
 *
 * Everything here is breakable given a weak enough master password — which is the whole reason
 * `settings_error_password_too_weak` exists. A string promising otherwise contradicts the app's own
 * warning, and reaches past the ceiling in `docs/THREAT-MODEL.md`.
 */
/**
 * The phone-scope clause in the import wizard's sandbox paragraph.
 *
 * The sentence is true because it is *scoped*: Android's sandbox stops other apps **on this phone**
 * reading the vault. Drop the scope and it becomes a claim about the world, which is the shape of
 * every Tier G defect this project has already shipped and fixed \u2014 the security-copy protocol's own
 * summary of the 2026-07-30 batch is "each was true of the app and stated of the system". No banned
 * phrase catches a deletion, so the qualifier is asserted rather than guarded against.
 */
private val ON_THIS_PHONE = mapOf(
    "en" to listOf("on this phone"),
    "fr" to listOf("sur ce t\u00e9l\u00e9phone"),
    "de" to listOf("auf diesem telefon"),
    "es" to listOf("en este tel\u00e9fono"),
    "pt" to listOf("neste celular"),
)

private val UNBREAKABLE = mapOf(
    "en" to listOf(
        "unbreakable", "impossible to crack", "cannot be cracked", "military-grade",
        "military grade", "unhackable", "100% secure",
    ),
    "fr" to listOf(
        "incassable", "impossible à casser", "impossible à déchiffrer", "de qualité militaire",
        "de niveau militaire", "inviolable",
    ),
    "de" to listOf(
        "unknackbar", "unmöglich zu knacken", "nicht zu knacken", "militärischer standard",
        "militärqualität", "unhackbar",
    ),
    "es" to listOf(
        "irrompible", "imposible de descifrar", "imposible de romper", "grado militar",
        "nivel militar", "inviolable",
    ),
    "pt" to listOf(
        "inquebrável", "impossível de quebrar", "impossível de decifrar", "grau militar",
        "nível militar", "inviolável",
    ),
)

/**
 * Biometrics presented as a replacement for the master password.
 *
 * They are not one. The Keystore key unwraps a stored copy; the password is still the only thing
 * that recovers the vault on a new device or after biometric enrollment is invalidated. A user who
 * believes their fingerprint *is* the credential is a user who forgets the password.
 */
/**
 * Promises about location that no userspace app can keep.
 *
 * What the app may honestly say is narrow: it reads a position only when the user taps, it keeps
 * that one reading in the encrypted vault, and it sends nothing. What it must never say is anything
 * about what the *device* does — Android's own location services, the network operator and the
 * satellites are all outside this app, and a sentence like "nobody can see where you are" is false
 * in a way the user cannot check and would rely on. The banned forms are the generalisations a
 * translator naturally reaches for when compressing the honest sentence.
 */
private val LOCATION_IS_INVISIBLE_TO_EVERYONE = mapOf(
    "en" to listOf(
        "nobody can see where you are", "no one can see where you are", "no one can track you",
        "nobody can track you", "untraceable", "completely anonymous", "cannot be tracked",
    ),
    "fr" to listOf(
        "personne ne peut voir où vous êtes", "personne ne peut vous suivre",
        "introuvable", "totalement anonyme", "impossible à suivre",
    ),
    "de" to listOf(
        "niemand kann sehen, wo sie sind", "niemand kann sie verfolgen",
        "nicht auffindbar", "völlig anonym", "unmöglich zu verfolgen",
    ),
    "es" to listOf(
        "nadie puede ver dónde está", "nadie puede rastrearle", "nadie puede rastrearlo",
        "ilocalizable", "totalmente anónimo", "imposible de rastrear",
    ),
    "pt" to listOf(
        "ninguém pode ver onde você está", "ninguém pode rastreá-lo",
        "irrastreável", "totalmente anônimo", "impossível de rastrear",
    ),
)

private val BIOMETRICS_REPLACE_THE_PASSWORD = mapOf(
    "en" to listOf("replaces your master password", "no longer need your master password"),
    "fr" to listOf("remplace votre mot de passe principal", "plus besoin de votre mot de passe principal"),
    "de" to listOf("ersetzt ihr master-passwort", "brauchen ihr master-passwort nicht mehr"),
    "es" to listOf("reemplaza su contraseña maestra", "ya no necesita su contraseña maestra"),
    "pt" to listOf("substitui sua senha mestra", "não precisa mais da sua senha mestra"),
)

/**
 * A normal file delete described as a secure erase.
 *
 * Android gives apps no low-level access that would make one possible. Only the affirmative forms
 * are banned: the string that carries this claim exists to *deny* it, so it necessarily contains
 * the words "secure erase" itself.
 */
private val SECURELY_ERASED = mapOf(
    "en" to listOf("securely erased", "permanently wiped", "unrecoverable"),
    "fr" to listOf("effacé de manière sécurisée", "définitivement effacé", "irrécupérable"),
    "de" to listOf("sicher gelöscht", "endgültig gelöscht", "unwiederbringlich"),
    "es" to listOf("borrado de forma segura", "eliminado permanentemente", "irrecuperable"),
    "pt" to listOf("apagado com segurança", "excluído permanentemente", "irrecuperável"),
)

/**
 * A clean scan against the bundled corpus reported as a clean scan against every breach.
 *
 * The corpus ships with the app and is a subset. `HealthAdviceBreachClaimTest` guards the English
 * shape of this in `:feature:health`; this is the same claim in the other four languages.
 */
private val NOT_IN_ANY_BREACH = mapOf(
    "en" to listOf("not in any breach", "never been breached", "no known breaches"),
    "fr" to listOf("dans aucune fuite", "jamais été compromis", "aucune violation connue"),
    "de" to listOf("in keiner datenpanne", "nie kompromittiert", "keine bekannten datenlecks"),
    "es" to listOf("en ninguna filtración", "nunca ha sido filtrada", "sin filtraciones conocidas"),
    "pt" to listOf("em nenhum vazamento", "nunca foi vazada", "sem vazamentos conhecidos"),
)

/**
 * Root detection presented as reliable, or as a defence.
 *
 * `RootCheck` is four `su` paths and a `test-keys` build tag. Any root-hiding tool passes it, and
 * the app never blocks on the result. A string that promises detection is worse than silence: it
 * converts "we looked for the obvious signs" into "this device is clean", which is the one
 * conclusion the check cannot support. The mirror of ROOT_IS_HARMLESS — that one stops the warning
 * reassuring, this one stops the explanation overpromising.
 *
 * No blocking verb is banned here, though "it never blocks you" is exactly the kind of sentence
 * this tier polices. The first draft banned "blocks" and the test immediately failed on the
 * English string — which contains the word only to *deny* it. Same trap SECURELY_ERASED documents:
 * a string whose job is to refuse a claim necessarily says the words. Ban the affirmative form or
 * nothing, and here the affirmative form is already covered by the protection phrasings below.
 */
private val ROOT_DETECTION_IS_RELIABLE = mapOf(
    "en" to listOf("always detect", "detects any", "guarantees", "protects you from"),
    "fr" to listOf("détecte toujours", "détecte tout", "garantit", "vous protège contre"),
    "de" to listOf("erkennt immer", "erkennt jede", "garantiert", "schützt sie vor"),
    "es" to listOf("siempre detecta", "detecta cualquier", "garantiza", "le protege de"),
    "pt" to listOf("sempre detecta", "detecta qualquer", "garante", "protege você de"),
)

/** A root warning that reassures instead of warning is worse than no warning. */
private val ROOT_IS_HARMLESS = mapOf(
    "en" to listOf("still fully protected", "no risk", "still safe"),
    "fr" to listOf("toujours entièrement protégé", "aucun risque", "sans risque"),
    "de" to listOf("weiterhin vollständig geschützt", "kein risiko", "ohne risiko"),
    "es" to listOf("sigue estando totalmente protegido", "ningún riesgo", "sin riesgo"),
    "pt" to listOf("continua totalmente protegido", "nenhum risco", "sem risco"),
)

/**
 * "Hiding an entry protects it."
 *
 * The claim the Make Invisible feature is most likely to grow, because it is what a user *wants* to
 * be true. It is not: concealment is a projection over the decrypted vault. A hidden item is still
 * in `vault.enc`, still in every backup, and still visible to anyone holding the file and the
 * password. The spec says so in its first paragraph, and `docs/THREAT-MODEL.md`
 * puts the compromised-device tier out of scope in writing.
 *
 * "Deleted" and "permanently" are here for the opposite reason: hiding is always reversible, and a
 * string that reads as destructive would stop people using the feature at all.
 */
/**
 * Merges two banned-phrase families per language.
 *
 * `Map.plus(Map)` is the wrong tool here and was used at three call sites until the
 * 2026-08-31 sweep: both operands are keyed by the same five language codes, so `plus`
 * *replaces* each language's list with the right-hand one instead of extending it, and every
 * phrase in the left-hand family was silently dropped. Those three entries read as guarding two
 * claim families and guarded one. Compose with [and], never with `+`.
 */
private infix fun Map<String, List<String>>.and(
    other: Map<String, List<String>>,
): Map<String, List<String>> =
    (keys + other.keys).associateWith { lang ->
        (this[lang].orEmpty() + other[lang].orEmpty()).distinct()
    }

private val CONCEALMENT_IS_PROTECTION = mapOf(
    "en" to listOf(
        "encrypted", "deleted", "permanently", "cannot be recovered", "no one can", "nobody can",
        "secure", "protected",
    ),
    "fr" to listOf(
        "chiffré", "supprimé", "définitivement", "personne ne peut", "sécurisé", "protégé",
    ),
    "de" to listOf(
        "verschlüsselt", "gelöscht", "endgültig", "niemand kann", "geschützt",
    ),
    // Stems, not full words, for the two languages that inflect for gender on the far side of the
    // match. Matching is a plain lowercase substring, so `chiffré` happens to cover `chiffrée` and
    // `protegido` covers `protegidos` — but neither covers the *feminine*: `protegida`, `cifrada`,
    // `eliminada` all slip through a masculine-singular list. This feature hides folders, and
    // `carpeta` and `pasta` are both feminine, so the one sentence most likely to carry the false
    // claim is the one the net would have missed. Nothing shipped trips this; the hole was in the
    // guard, not in the catalogue.
    "es" to listOf(
        "cifrad", "eliminad", "permanentemente", "nadie puede", "segur", "protegid",
    ),
    "pt" to listOf(
        "encriptad", "criptografad", "eliminad", "permanentemente", "ninguém pode", "protegid",
    ),
)

/**
 * "The reveal phrase is the master password."
 *
 * A different secret with a different job, and conflating them is dangerous in both directions: it
 * invites people to reuse the master password as their reveal phrase, and it makes the Settings
 * recovery route — which really does take the vault password — read as circular.
 *
 * The recovery clause says **vault password**, never master password, so the same sentence stays
 * true inside the duress vault, where the current vault password is the decoy's. Spanish and
 * Portuguese both drifted to "master password" on the first pass and were corrected; German caught
 * it itself by grepping `backup_import_vault_password_label`. That is three independent arrivals at
 * the same rule, which is why it is pinned here rather than left to review.
 */
private val REVEAL_PHRASE_IS_THE_MASTER_PASSWORD = mapOf(
    "en" to listOf("master password"),
    "fr" to listOf("mot de passe principal"),
    "de" to listOf("master-passwort", "masterpasswort"),
    "es" to listOf("contraseña maestra"),
    "pt" to listOf("senha mestra"),
)

/** The two facts `vaultlist_hide_phrase_body` exists to state. Dropping either is the whole defect. */
private val PHRASE_BODY_KEEPS_ITS_TWO_FACTS = mapOf(
    "en" to listOf("search box", "settings", "vault password"),
    "fr" to listOf("barre de recherche", "paramètres", "mot de passe du coffre"),
    "de" to listOf("suchfeld", "einstellungen", "tresor-passwort"),
    "es" to listOf("cuadro de búsqueda", "ajustes", "contraseña de la caja fuerte"),
    "pt" to listOf("caixa de pesquisa", "configurações", "senha do cofre"),
)

/**
 * "You can get your forgotten reveal phrase back."
 *
 * You cannot. `VaultRepository.changeRevealPhrase` replaces a verifier; the phrase itself is never
 * stored and nothing in the app can render it. What Settings recovers is the *entries* — which is
 * why every string here has to keep those two apart. German's first draft of the vault-list sentence
 * left a pronoun that attached the promise to the phrase instead of the entries, and two independent
 * reviewers landed on it, so this is the exact wording family that drifts.
 */
private val THE_PHRASE_ITSELF_IS_RECOVERABLE = mapOf(
    "en" to listOf("view your reveal phrase", "see your reveal phrase", "show your reveal phrase", "recover your reveal phrase"),
    "fr" to listOf("voir votre phrase", "afficher votre phrase", "récupérer votre phrase"),
    "de" to listOf("ihre phrase anzeigen", "phrase wiederherstellen", "phrase einsehen"),
    "es" to listOf("ver su frase", "mostrar su frase", "recuperar su frase"),
    "pt" to listOf("ver sua frase", "mostrar sua frase", "recuperar sua frase"),
)

/** The reveal is session-scoped, and the banner's whole job is to say so. */
private val REVEAL_IS_PERMANENT = mapOf(
    "en" to listOf("always", "permanently", "from now on"),
    "fr" to listOf("toujours", "définitivement", "désormais"),
    "de" to listOf("immer", "dauerhaft", "ab jetzt"),
    "es" to listOf("siempre", "permanentemente", "a partir de ahora"),
    "pt" to listOf("sempre", "permanentemente", "a partir de agora"),
)

// =================================================================================================
// The register.
// =================================================================================================

private val REGISTER = listOf(

    // --- app -------------------------------------------------------------------------------

    Claim(
        "app", "app_drawer_subtitle",
        note = "Names what the build is: no account, no sync server. True, and must not grow into " +
            "a claim about the vault file, which Auto Backup does upload.",
        never = VAULT_NEVER_LEAVES,
    ),
    Claim(
        "app", "app_drawer_invisible_items",
        note = "The drawer's second door to the Invisible-items screen, and word-for-word the same " +
            "label as feature/settings' settings_hidden_items_title in all five languages — the " +
            "same claim in a new place, not a new one, so it inherits that entry's ban rather " +
            "than arguing its own. It carries no count and no status: a row reading '3 hidden' " +
            "would disclose by existing, and this one is present with zero records and inside a " +
            "decoy session alike.",
        never = CONCEALMENT_IS_PROTECTION,
    ),
    Claim(
        "app", "app_root_warning_message",
        note = "The warning banner. Its whole content is the hedge — that hardware-backed key " +
            "protection may be weaker here. A translation that reassures has inverted it.",
        never = ROOT_IS_HARMLESS,
        always = mapOf(
            "en" to listOf("weaker"), "fr" to listOf("affaiblies"), "de" to listOf("schwächer"),
            "es" to listOf("débiles"), "pt" to listOf("fracas"),
        ),
    ),
    Claim(
        "app", "app_stale_export_message",
        note = "Said an encrypted export was the only copy that survives losing the device, until " +
            "2026-07-30. StaleExportReminder.shouldRemind reads one export timestamp and never " +
            "asks whether cloud backup is on, so anyone with Auto Backup enabled saw that " +
            "sentence while a Google-held copy also survived. It now claims what is true whatever " +
            "the backup setting: an export is the copy the user *controls*. Both halves asserted " +
            "— the banned superlative, and the word that replaced it.",
        never = mapOf(
            "en" to listOf("the only copy that survives", "only copy that survives"),
            "fr" to listOf("la seule copie qui survive", "la seule copie qui survit"),
            "de" to listOf("die einzige kopie, die dem verlust", "die einzige kopie, die überlebt"),
            "es" to listOf("la única copia que sobrevive"),
            "pt" to listOf("a única cópia que sobrevive"),
        ),
        always = mapOf(
            "en" to listOf("you control"), "fr" to listOf("vous contrôlez"),
            "de" to listOf("sie selbst kontrollieren"), "es" to listOf("usted controla"),
            "pt" to listOf("você controla"),
        ),
    ),

    // --- core/ui ---------------------------------------------------------------------------

    Claim(
        "core/ui", "core_ui_always_on_encryption_summary",
        note = "Names the primitives. Naming an algorithm is a fact; promising it cannot be broken " +
            "is a claim, and a false one against a weak master password.",
        never = UNBREAKABLE,
    ),
    Claim(
        "core/ui", "core_ui_always_on_encryption_detail",
        note = "Same, at length.",
        never = UNBREAKABLE,
    ),
    Claim(
        "core/ui", "core_ui_always_on_throttling_summary",
        note = "That guesses slow down and the slowing cannot be disabled — both true of the app's " +
            "own unlock path.",
        never = UNBREAKABLE,
    ),
    Claim(
        "core/ui", "core_ui_always_on_throttling_detail",
        note = "Claimed guessing stayed slow \"even with the vault file in hand\", until " +
            "2026-07-30 — the one case where the backoff does nothing. An attacker holding " +
            "vault.enc runs their own cracker and never reaches this code; what costs them per " +
            "guess is Argon2id. It also contradicted settings_error_password_too_weak, which " +
            "correctly names the password as the only protection offline. The card now scopes " +
            "the backoff to the app and names what actually pays offline, so Argon2id has to " +
            "stay in it: without that name the sentence admits a gap and leaves it unexplained.",
        never = mapOf(
            "en" to listOf("even with the vault file"),
            // Both spellings: the old string said "le fichier coffre", which the French repair
            // round corrected to "le fichier du coffre". Banning only the ungrammatical form
            // would let the claim return in correct French.
            "fr" to listOf("même avec le fichier coffre", "même avec le fichier du coffre"),
            "de" to listOf("selbst mit der tresor-datei"),
            "es" to listOf("incluso con el archivo de la caja fuerte"),
            "pt" to listOf("mesmo com o arquivo do cofre"),
        ),
        always = mapOf(
            "en" to listOf("argon2id"), "fr" to listOf("argon2id"), "de" to listOf("argon2id"),
            "es" to listOf("argon2id"), "pt" to listOf("argon2id"),
        ),
    ),
    Claim(
        "core/ui", "core_ui_always_on_phishing_summary",
        note = "Autofill *asks* before filling a mismatched site. It does not block phishing, and " +
            "the verb is the entire claim — so the verb is what is asserted.",
        always = mapOf(
            "en" to listOf("asks"), "fr" to listOf("demande"), "de" to listOf("fragt"),
            "es" to listOf("pregunta"), "pt" to listOf("pergunta"),
        ),
    ),
    Claim(
        "core/ui", "core_ui_always_on_phishing_detail",
        note = "Same verb, same reason.",
        always = mapOf(
            "en" to listOf("asks"), "fr" to listOf("demande"), "de" to listOf("fragt"),
            "es" to listOf("pregunta"), "pt" to listOf("pergunta"),
        ),
    ),
    Claim(
        "core/ui", "core_ui_always_on_screenblock_summary",
        note = "FLAG_SECURE on every window. Real, and bounded: it stops the framework capture " +
            "path, not a camera pointed at the screen.",
        never = UNBREAKABLE,
    ),
    Claim(
        "core/ui", "core_ui_always_on_screenblock_detail",
        note = "Same, at length.",
        never = UNBREAKABLE,
    ),
    Claim(
        "core/ui", "core_ui_always_on_root_summary",
        note = "The dash clause is the claim: a warning, not a defence. Drop it and the row " +
            "reads as a protection the app does not provide.",
        never = ROOT_DETECTION_IS_RELIABLE,
    ),
    Claim(
        "core/ui", "core_ui_always_on_root_detail",
        note = "Two qualifiers carry this one, and deleting either leaves a sentence that " +
            "overclaims. The first concedes the check is defeated by software written to hide " +
            "root — without it, a quiet launch reads as a clean device. The second concedes that " +
            "a compromised OS beats every password manager, which is THREAT-MODEL T3 stated to " +
            "the user rather than only to a reader of the repo.",
        never = ROOT_DETECTION_IS_RELIABLE,
        always = mapOf(
            "en" to listOf("hide", "no password manager"),
            "fr" to listOf("masquer", "aucun gestionnaire de mots de passe"),
            "de" to listOf("verbergen", "kein passwort-manager"),
            "es" to listOf("ocultar", "ning\u00fan gestor de contrase\u00f1as"),
            "pt" to listOf("ocultar", "nenhum gerenciador de senhas"),
        ),
    ),
    Claim(
        "core/ui", "core_ui_always_on_leaves_device_summary",
        note = "The one fact card that admits the backup. Its truth is conditional — an encrypted " +
            "backup *if you allow it* — and a translation that drops the condition states the " +
            "opposite of the card's purpose.",
        never = VAULT_NEVER_LEAVES,
        always = mapOf(
            "en" to listOf("if you allow"), "fr" to listOf("autorisez"), "de" to listOf("erlauben"),
            "es" to listOf("autoriza"), "pt" to listOf("autorizar"),
        ),
    ),
    Claim(
        "core/ui", "core_ui_always_on_leaves_device_detail",
        note = "Spells out the Auto Backup upload, the Play crash reports and the clipboard sync. " +
            "It has to keep naming Google — that sentence is the disclosure.",
        never = VAULT_NEVER_LEAVES,
        always = mapOf(
            "en" to listOf("google"), "fr" to listOf("google"), "de" to listOf("google"),
            "es" to listOf("google"), "pt" to listOf("google"),
        ),
    ),
    Claim(
        "core/ui", "core_ui_deriving_rationale",
        note = "Explains that the unlock wait is Argon2id and not an artificial delay.",
        never = UNBREAKABLE,
    ),

    // --- feature/backup --------------------------------------------------------------------

    Claim(
        "feature/backup", "backup_screen_subtitle",
        note = "The Backup & export header, and the fifth appearance of the same false claim: it " +
            "said \"Everything here stays on this device unless you move it\" until 2026-08-31, " +
            "one row above the cloud-backup switch whose own off-state hint reads \"Auto Backup " +
            "includes the encrypted vault\". It predated the register and carried no marker, so " +
            "nothing here was watching it — the coverage gap the security-copy protocol names. The clause " +
            "is not replaced by a softer reassurance; the header now lists what the screen holds, " +
            "and \"cloud\" is asserted so it cannot quietly go back to naming only the local half.",
        never = VAULT_NEVER_LEAVES,
        always = mapOf(
            "en" to listOf("cloud"), "fr" to listOf("cloud"), "de" to listOf("cloud"),
            "es" to listOf("nube"), "pt" to listOf("nuvem"),
        ),
    ),

    // --- feature/health --------------------------------------------------------------------

    Claim(
        "feature/health", "health_advice_clean_breach_checked",
        note = "The scope word is the claim: checked against the bundled offline corpus, not " +
            "against every breach that exists. Drop \"offline\" and the sentence promises " +
            "something no offline app can know.",
        never = NOT_IN_ANY_BREACH,
        always = mapOf(
            "en" to listOf("offline"), "fr" to listOf("hors ligne"), "de" to listOf("offline"),
            "es" to listOf("sin conexión"), "pt" to listOf("offline"),
        ),
    ),
    Claim(
        "feature/health", "health_advice_breached",
        note = "The highest-priority advice line, and until 2026-08-31 the most unhedged sentence " +
            "on the screen: \"it is already in a public breach set\", flat, next to a category " +
            "label that says \"Potentially compromised\". A Golomb-Rice set answers with a " +
            "disclosed false-positive rate, so what the app knows is that the password matched a " +
            "compressed summary of the list. The banned phrases are the flat form in each " +
            "language; \"offline\" is asserted for the same reason it is on " +
            "health_advice_clean_breach_checked — an offline app cannot speak for every breach " +
            "that exists, and the scope word is the whole difference.",
        never = mapOf(
            "en" to listOf("already in a public breach set", "is already in a breach set"),
            "fr" to listOf("figure déjà dans une base de fuites", "est déjà dans une base de fuites"),
            "de" to listOf("ist bereits in einer öffentlichen", "ist bereits in einer datenleck-liste"),
            "es" to listOf("ya está en un conjunto público", "ya está en el conjunto de filtraciones"),
            "pt" to listOf("já está em um conjunto público", "já está no conjunto de vazamentos"),
        ),
        always = mapOf(
            "en" to listOf("offline"), "fr" to listOf("hors ligne"), "de" to listOf("offline"),
            "es" to listOf("sin conexión"), "pt" to listOf("offline"),
        ),
    ),
    Claim(
        "feature/health", "health_footnote_breach_available_undated",
        note = "The footnote that discloses the filter's false-positive rate. Said \"A hit is " +
            "right about 999 times in 1,000\" until 2026-08-31, and 0.999 is 1 minus the " +
            "disclosed FPR (1-in-2^riceK, 1-in-1024 at the shipped k=10) — the specificity, " +
            "P(no hit | not in the set), stated as if it were the precision, P(in the set | hit). " +
            "Precision depends on how many of this user's passwords are actually breached: 0.999 " +
            "needs about half of them to be, and for a vault of generated passwords every hit " +
            "that ever appears is false. Banned per language is that inverted form, in both the " +
            "number and the verb that attached it to the wrong conditional. The miss qualifier is " +
            "asserted because it is the half that was always right, and deleting it is the " +
            "cheapest way to turn a disclosure into a guarantee.",
        never = mapOf(
            "en" to listOf("999 times in 1,000", "999 out of 1,000", "a hit is right about"),
            "fr" to listOf("999 fois sur 1 000", "une correspondance est exacte"),
            "de" to listOf("999 von 1.000", "ein treffer stimmt"),
            "es" to listOf("999 de 1.000", "una coincidencia acierta"),
            "pt" to listOf("999 em 1.000", "está certa cerca de"),
        ),
        always = mapOf(
            // Two anchors per language, not one. The note above says the miss qualifier is
            // asserted; until 2026-09-10 only the guarantee clause was, and German had quietly
            // dropped the qualifier — "nicht in der Offline-Datenleck-Liste" scopes the corpus by
            // where it lives instead of by what is in it, so a miss read as "not breached" rather
            // than "not among the most common". fr, es and pt all kept it, which is what made the
            // German an outlier rather than a translation convention. A register whose note claims
            // an assertion it does not make is worse than one that claims nothing.
            "en" to listOf("never a guarantee", "most-common-breached"),
            "fr" to listOf("jamais une garantie", "plus courants"),
            "de" to listOf("sicherheitsgarantie", "häufigsten"),
            "es" to listOf("nunca una garantía", "más filtradas"),
            "pt" to listOf("nunca uma garantia", "mais vazadas"),
        ),
    ),

    // --- feature/import --------------------------------------------------------------------

    Claim(
        "feature/import", "import_done_delete_footnote",
        note = "Exists to deny a secure erase, so it must keep denying it — a deleted qualifier " +
            "here leaves a plain 'deleted' that a user reads as unrecoverable.",
        never = SECURELY_ERASED,
        always = mapOf(
            "en" to listOf("not a secure erase"), "fr" to listOf("effacement sécurisé"),
            "de" to listOf("sichere löschung"), "es" to listOf("borrado seguro"),
            "pt" to listOf("limpeza segura"),
        ),
    ),

    // --- feature/itemedit ------------------------------------------------------------------

    Claim(
        "feature/itemedit", "itemedit_detail_screenshot_notice",
        note = "Said \"Nothing here is synced or sent anywhere\" until 2026-07-30 — the Auto " +
            "Backup claim again in different words, contradicting " +
            "core_ui_always_on_leaves_device_detail one screen away. The subject is now the app, " +
            "which really does send nothing; the OS-level disclosure stays on the fact card " +
            "rather than being repeated on every detail screen. The clipboard caveat is the one " +
            "thing on this screen that can still expose a secret, so it is asserted.",
        never = mapOf(
            "en" to listOf("sent anywhere"),
            "fr" to listOf("envoyé où que ce soit", "envoyé nulle part"),
            "de" to listOf("irgendwohin gesendet", "nirgendwohin gesendet"),
            "es" to listOf("se envía a ningún lado", "enviado a ningún lugar"),
            "pt" to listOf("enviado para lugar algum", "enviado a lugar algum"),
        ),
        always = mapOf(
            "en" to listOf("clipboard"), "fr" to listOf("presse-papiers"),
            "de" to listOf("zwischenablage"), "es" to listOf("portapapeles"),
            "pt" to listOf("área de transferência"),
        ),
    ),
    Claim(
        "feature/itemedit", "itemedit_edit_header_subtitle",
        note = "Encrypted on save — true, and must not inflate into a promise about strength.",
        never = UNBREAKABLE,
    ),
    Claim(
        "feature/itemedit", "itemedit_totp_camera_dialog_title",
        note = "NO BANNED PHRASE — \"Use the camera?\" is a question, not a claim. Tagged Tier G " +
            "because it heads a permission rationale, and listed here so the totality check can " +
            "tell a considered key from a forgotten one.",
    ),
    Claim(
        "feature/itemedit", "itemedit_totp_camera_dialog_body",
        note = "The permission rationale. Its force is the scope word — the camera is used *only* " +
            "while scanning — and without it the sentence grants a general camera permission.",
        always = mapOf(
            "en" to listOf("only"), "fr" to listOf("uniquement"), "de" to listOf("nur"),
            "es" to listOf("solo"), "pt" to listOf("apenas"),
        ),
    ),
    Claim(
        "feature/itemedit", "itemedit_coordinate_dialog_title",
        note = "NO BANNED PHRASE — \"Use your location?\" is a question, not a claim. Tagged Tier G " +
            "because it heads a permission rationale, and listed here so the totality check can " +
            "tell a considered key from a forgotten one.",
    ),
    Claim(
        "feature/itemedit", "itemedit_coordinate_dialog_body",
        note = "The location rationale, and the one place the app describes what it does with a " +
            "position. Its force is the scope word — location is read *only* when the user taps — " +
            "and without it the sentence grants an open-ended location permission. The banned set " +
            "is the generalisation a compressed translation drifts into: this app can promise what " +
            "*it* does, and nothing at all about the device, the network or the satellites.",
        never = LOCATION_IS_INVISIBLE_TO_EVERYONE,
        always = mapOf(
            "en" to listOf("only"), "fr" to listOf("uniquement"), "de" to listOf("nur"),
            "es" to listOf("solo"), "pt" to listOf("apenas"),
        ),
    ),
    Claim(
        "feature/itemedit", "itemedit_error_vault_too_large",
        note = "A save failure. The reassurance is load-bearing: what is already stored is " +
            "unchanged. A translation that loses it reads as data loss during a memory error.",
        always = mapOf(
            "en" to listOf("unchanged"), "fr" to listOf("inchangé"), "de" to listOf("unverändert"),
            "es" to listOf("sin cambios"), "pt" to listOf("inalterado"),
        ),
    ),

    // --- feature/onboarding ----------------------------------------------------------------

    Claim(
        "feature/onboarding", "onboarding_welcome_body",
        note = "The first security claim a user ever reads: no account, no sync server, no way for " +
            "us to read or reset. The reset clause is what makes it a warning rather than a boast.",
        never = VAULT_NEVER_LEAVES,
        always = mapOf(
            "en" to listOf("reset"), "fr" to listOf("réinitialiser"),
            "de" to listOf("zurückzusetzen"), "es" to listOf("restablezcamos"),
            "pt" to listOf("redefinirmos"),
        ),
    ),
    Claim(
        "feature/onboarding", "onboarding_password_body",
        note = "Says the password cannot be reset. Lose that clause and a user picks a password " +
            "they expect to recover.",
        always = mapOf(
            "en" to listOf("reset"), "fr" to listOf("réinitialiser"),
            "de" to listOf("zurücksetzen"), "es" to listOf("restablecerla"),
            "pt" to listOf("redefini-la"),
        ),
    ),
    Claim(
        "feature/onboarding", "onboarding_password_weak_consent_title",
        note = "NO BANNED PHRASE — \"Use this password anyway\" is the consent button on the weak- " +
            "password dialog. It asserts nothing; the claim is in the body, which is Tier F.",
    ),
    Claim(
        "feature/onboarding", "onboarding_biometric_body",
        note = "Biometrics are a convenience over the password, never a replacement, and the " +
            "biometrics can never recover the vault on their own.",
        never = BIOMETRICS_REPLACE_THE_PASSWORD,
        always = mapOf(
            "en" to listOf("master password"), "fr" to listOf("mot de passe principal"),
            "de" to listOf("master-passwort"), "es" to listOf("contraseña maestra"),
            "pt" to listOf("senha mestra"),
        ),
    ),
    Claim(
        "feature/onboarding", "onboarding_biometric_enabled_notice",
        note = "The confirmation after enrollment. One sentence, and half of it is the reminder " +
            "that the password still works.",
        never = BIOMETRICS_REPLACE_THE_PASSWORD,
        always = mapOf(
            "en" to listOf("master password"), "fr" to listOf("mot de passe principal"),
            "de" to listOf("master-passwort"), "es" to listOf("contraseña maestra"),
            "pt" to listOf("senha mestra"),
        ),
    ),
    Claim(
        "feature/onboarding", "onboarding_cloudbackup_body",
        note = "The one place \"never leaves this device\" is true — said of the master password, " +
            "which really does stay here while the ciphertext goes to Google. So the narrow ban: " +
            "the claim must never migrate from the password to the vault. And it must keep naming " +
            "Google, because that is the disclosure the step exists to make.",
        never = THE_VAULT_ITSELF_NEVER_LEAVES,
        always = mapOf(
            "en" to listOf("google"), "fr" to listOf("google"), "de" to listOf("google"),
            "es" to listOf("google"), "pt" to listOf("google"),
        ),
    ),
    Claim(
        "feature/onboarding", "onboarding_cloudbackup_footnote",
        note = "\"This phone holds the only copy\" is true *conditionally* — if you turn backup " +
            "off. The condition is the entire difference between a fact and the false claim this " +
            "tier exists for, so the condition is what is asserted.",
        always = mapOf(
            "en" to listOf("if you turn it off"), "fr" to listOf("si vous la désactivez"),
            "de" to listOf("wenn sie sie deaktivieren"), "es" to listOf("si la desactiva"),
            "pt" to listOf("se você desativá-lo"),
        ),
    ),
    Claim(
        "feature/onboarding", "onboarding_recovery_pdf_warning_line2",
        note = "A 'where the vault lives' claim scoped to the *app*: it asserts LocalKeep has no " +
            "server to send the vault to, and must never widen into 'never leaves your device' — " +
            "Android Auto Backup can still take vault.enc (§8.2), which is why the onboarding " +
            "cloud-backup step owns that disclosure. Tagged 2026-08-06 after a sweep found the " +
            "PDF claiming 'your vault is never sent anywhere' on a default install.",
        never = VAULT_NEVER_LEAVES,
        always = mapOf(
            "en" to listOf("no server"), "fr" to listOf("aucun serveur"),
            "de" to listOf("keinen Server"), "es" to listOf("ningún servidor"),
            "pt" to listOf("nenhum servidor"),
        ),
    ),

    // --- feature/backup --------------------------------------------------------------------
    //
    // Registered 2026-09-10. These four were untagged and unregistered while
    // `backup_screen_subtitle` - which IS tier G - delegates its own rationale to them by name
    // ("the cloud-backup row and footnote below it carry the honest version. Guarded by
    // TierGClaimsTest in :app"). They were not. The register's totality check runs over *tagged*
    // keys, so an untagged claim is invisible to it and nothing else catches one.
    //
    // Every `always` anchor below is a phrase the shipped translation already carries. Registering
    // what is there rather than what someone thinks ought to be there is the only safe direction
    // for a register added after the strings: it can fail loudly on the next edit, and it cannot
    // silently bless a translation nobody has read.

    Claim(
        "feature/backup", "backup_cloud_include_label",
        note = "The switch. Names Android's backup by mechanism so it cannot read as a general " +
            "promise about everything that leaves the device.",
        never = VAULT_NEVER_LEAVES,
        always = mapOf(
            "en" to listOf("android"), "fr" to listOf("android"), "de" to listOf("android"),
            "es" to listOf("android"), "pt" to listOf("android"),
        ),
    ),
    Claim(
        "feature/backup", "backup_cloud_disable_hint_on",
        note = "The ON state's hint, and the negation is the whole of it: this string exists to " +
            "say the vault will NOT be included. A translation that loses the negative inverts a " +
            "security property while still reading fluently - the exact failure the D24 revert " +
            "caught in de and es on a different key.",
        never = VAULT_NEVER_LEAVES,
        always = mapOf(
            "en" to listOf("not include"), "fr" to listOf("inclura pas"), "de" to listOf("nicht"),
            "es" to listOf("no incluir"), "pt" to listOf("não incluir"),
        ),
    ),
    Claim(
        "feature/backup", "backup_cloud_disable_hint_off",
        note = "The OFF state's hint. Two load-bearing halves: that Auto Backup DOES include the " +
            "vault, and that what it includes is encrypted. Dropping the first turns a disclosure " +
            "into a reassurance; dropping the second turns a reassurance into an alarm.",
        never = VAULT_NEVER_LEAVES,
        always = mapOf(
            "en" to listOf("includes", "encrypted"),
            "fr" to listOf("inclut", "chiffré"),
            "de" to listOf("enthält", "verschlüsselten"),
            "es" to listOf("incluye", "cifrada"),
            "pt" to listOf("inclui", "criptografado"),
        ),
    ),
    Claim(
        "feature/backup", "backup_cloud_schedule_footnote",
        note = "When the upload happens, which is the half a user acts on. Load-bearing: that " +
            "Android picks the moment, and that a recent change may therefore not be in the " +
            "backup yet. Dropping the second clause turns a caveat into a schedule description " +
            "and quietly promises the backup is current — the opposite of why the line exists. " +
            "The banned phrases are the ones that would make it a guarantee of immediacy.",
        never = mapOf(
            "en" to listOf("immediately", "instantly", "always up to date", "as soon as you save"),
            "fr" to listOf("immédiatement", "instantanément", "toujours à jour"),
            "de" to listOf("sofort", "unverzüglich", "immer aktuell"),
            "es" to listOf("inmediatamente", "al instante", "siempre actualizada"),
            "pt" to listOf("imediatamente", "instantaneamente", "sempre atualizado"),
        ),
        always = mapOf(
            "en" to listOf("android decides", "may not"),
            "fr" to listOf("android qui décide", "peut ne pas"),
            "de" to listOf("android entscheidet", "noch nicht"),
            "es" to listOf("android decide", "puede no estar"),
            "pt" to listOf("android decide", "pode ainda não"),
        ),
    ),
    Claim(
        "feature/backup", "backup_cloud_schedule_footnote_off",
        note = "The OFF state's twin of the line above, added 2026-09-16 (S3). Two halves carry the " +
            "claim: Android, not the app, decides when a backup pass runs; and a vault copy already " +
            "uploaded is NOT withdrawn by turning the switch off — it can stay in the Google account " +
            "until that next pass. The banned phrases are the ones that would promise the copy is " +
            "deleted, removed or gone, or that anything happens immediately.",
        never = mapOf(
            "en" to listOf("is deleted", "is removed", "will be deleted", "will be removed", "is gone", "immediately", "right away", "the app decides"),
            "fr" to listOf("est supprimée", "sera supprimée", "est effacée", "sera effacée", "immédiatement", "aussitôt", "application qui décide"),
            "de" to listOf("wird gelöscht", "gelöscht wird", "wird entfernt", "entfernt wird", "ist weg", "sofort", "umgehend", "die app entscheidet"),
            "es" to listOf("se elimina", "se eliminará", "se borra", "se borrará", "inmediatamente", "de inmediato", "al instante", "la aplicación decide"),
            "pt" to listOf("é excluída", "será excluída", "é apagada", "será apagada", "é removida", "será removida", "imediatamente", "na hora", "o app decide", "o aplicativo decide"),
        ),
        always = mapOf(
            "en" to listOf("android decides", "can stay"),
            "fr" to listOf("android qui décide", "peut rester"),
            "de" to listOf("android entscheidet", "bleiben kann"),
            "es" to listOf("android decide", "puede permanecer"),
            "pt" to listOf("android decide", "pode permanecer"),
        ),
    ),
    Claim(
        "feature/settings", "settings_cloud_backup_summary_included",
        note = "The ON-state summary of the Settings cloud-backup row (F-41, 2026-09-16); its sibling " +
            "settings_cloud_backup_summary is the OFF state. It states inclusion and nothing else: no " +
            "reassurance (encrypted, safe, protected) and nothing about plaintext. The banned phrases " +
            "are the reassurances a fluent rewrite tends to add; the anchors are the inclusion verb " +
            "and the feature name each language already uses two rows away.",
        never = mapOf(
            "en" to listOf("safe", "secure", "protected", "plain text", "plaintext", "unencrypted", "only on this device"),
            "fr" to listOf("sécurisé", "en sécurité", "protégé", "en clair", "non chiffré", "uniquement sur cet appareil"),
            "de" to listOf("geschützt", "klartext", "unverschlüsselt", "nur auf diesem gerät"),
            "es" to listOf("seguro", "segura", "protegid", "texto plano", "sin cifrar", "solo en este dispositivo"),
            "pt" to listOf("seguro", "segura", "protegid", "texto simples", "sem criptografia", "apenas neste dispositivo"),
        ),
        always = mapOf(
            "en" to listOf("included", "auto backup"),
            "fr" to listOf("inclus", "auto backup"),
            "de" to listOf("enthalten", "auto backup"),
            "es" to listOf("incluida", "copia de seguridad"),
            "pt" to listOf("incluído", "auto backup"),
        ),
    ),
    Claim(
        "feature/backup", "backup_cloud_footnote",
        note = "What Google holds, and what still stands between it and the vault. Both halves are " +
            "load-bearing: ciphertext alone would be a boast, and the master-password requirement " +
            "alone would not say what was uploaded.",
        never = VAULT_NEVER_LEAVES,
        always = mapOf(
            "en" to listOf("ciphertext", "master password"),
            "fr" to listOf("texte chiffré", "mot de passe principal"),
            "de" to listOf("verschlüsselter text", "master-passwort"),
            "es" to listOf("texto cifrado", "contraseña maestra"),
            "pt" to listOf("texto cifrado", "senha mestra"),
        ),
    ),
    // Registered 2026-09-20 with the string itself (release sweep finding 1C-F4). Excluding
    // `blobs/` from full_backup_content.xml is deliberate and correct; the screen's silence about
    // it was the defect, so this line is the one place a user learns that a cloud restore comes
    // back without their files.
    Claim(
        "feature/backup", "backup_cloud_attachments_footnote",
        note = "What Auto Backup leaves behind. It must keep saying attachments are excluded AND " +
            "name what does travel: a line that only said \"not included\" would not say not " +
            "included in what, and one that promised the files come back would be the original " +
            "defect in the other direction.",
        never = VAULT_NEVER_LEAVES,
        always = mapOf(
            "en" to listOf("not included", "vault"),
            "fr" to listOf("ne sont pas incluses", "coffre"),
            "de" to listOf("nicht enthalten", "tresor"),
            "es" to listOf("no se incluyen", "caja fuerte"),
            "pt" to listOf("não são incluídos", "cofre"),
        ),
    ),

    // --- feature/settings ------------------------------------------------------------------

    Claim(
        "feature/settings", "settings_cloud_backup_summary",
        note = "Names the exact mechanism it controls, which is what stops the row from reading " +
            "as a general promise that nothing leaves. The name is not the same in every " +
            "language: Android's own Spanish UI calls this feature \"Copia de seguridad\" " +
            "(SettingsGoogle.apk, privacy_settings_title [es], read off the emulator 2026-09-10), " +
            "so pinning es to the English product name pointed users at a phrase their phone " +
            "does not use. The other three locales keep it because those UIs do.",
        never = VAULT_NEVER_LEAVES,
        always = mapOf(
            "en" to listOf("auto backup"), "fr" to listOf("auto backup"), "de" to listOf("auto backup"),
            "es" to listOf("copia de seguridad"), "pt" to listOf("auto backup"),
        ),
    ),
    Claim(
        "feature/settings", "settings_error_password_too_weak",
        note = "The most important true sentence in the app: the vault file can be attacked " +
            "offline, so the password is the only thing protecting it. Both halves are asserted.",
        never = UNBREAKABLE,
        always = mapOf(
            "en" to listOf("offline"), "fr" to listOf("hors ligne"), "de" to listOf("offline"),
            "es" to listOf("sin conexión"), "pt" to listOf("offline"),
        ),
    ),

    // --- feature/unlock --------------------------------------------------------------------

    Claim(
        "feature/unlock", "unlock_recovery_qr_camera_dialog_title",
        note = "NO BANNED PHRASE — \"Use the camera?\" is a question, not a claim. Tagged Tier G " +
            "because it heads a permission rationale, and listed here so the totality check can " +
            "tell a considered key from a forgotten one. Same treatment as its twin " +
            "itemedit_coordinate_dialog_title.",
    ),
    Claim(
        "feature/unlock", "unlock_recovery_qr_camera_dialog_body",
        note = "The permission rationale for the Recovery Kit scanner, and the exact twin of " +
            "itemedit_totp_camera_dialog_body — same camera, same dialog, same two claims — so it " +
            "carries the same anchors plus the one its sibling does not need. First: the scope " +
            "word, without which the sentence grants a general camera permission. Second: the " +
            "negation in \"No photo or video is saved or sent\", which is a claim about where data " +
            "goes, and is the reason this is Tier G rather than Tier B. It is true and checkable: " +
            "core/ui/QrScanner.kt binds ImageAnalysis alone — no ImageCapture, no VideoCapture, no " +
            "Recorder, no MediaStore write — so nothing is ever encoded to a file, and `foss` has " +
            "no INTERNET permission to send one with. Drop the negation and the sentence still " +
            "reads fluently while promising nothing, which is exactly the failure the German " +
            "backup_cloud_disable_hint_on red-proof caught on a different key.",
        always = mapOf(
            "en" to listOf("only", "no photo or video"),
            "fr" to listOf("uniquement", "aucune photo ni vidéo"),
            "de" to listOf("nur", "kein foto oder video"),
            "es" to listOf("solo", "no se guarda ni envía"),
            "pt" to listOf("apenas", "nenhuma foto ou vídeo"),
        ),
    ),

    Claim(
        "feature/unlock", "unlock_subtitle",
        note = "The screen every user sees at every unlock. UnlockSubtitleTest guards the English " +
            "next to the string; this is the same claim in the four languages it cannot reach.",
        never = VAULT_NEVER_LEAVES,
        always = mapOf(
            "en" to listOf("master password"), "fr" to listOf("mot de passe principal"),
            "de" to listOf("master-passwort"), "es" to listOf("contraseña maestra"),
            "pt" to listOf("senha mestra"),
        ),
    ),
    Claim(
        "feature/unlock", "unlock_footer_disclaimer",
        note = "The other sentence on that same screen. Said \"Your vault opens only with your " +
            "password\" until 2026-08-31, directly above the Use Recovery Kit button and beside " +
            "the biometric one, both of which open the real vault with no password — the 24 " +
            "words unwrap a DEK that lives inside the vault file itself, so scoping the claim to " +
            "the exported file would have been false too. Now scoped to the master password, " +
            "which really does stay here, in the spec's own wording. Two families are banned " +
            "inline rather than composed, because `+` on these maps replaces a language's list " +
            "instead of extending it: the exclusivity claim in each language, and the vault half " +
            "of VAULT_NEVER_LEAVES — the password half has to stay sayable, which is exactly the " +
            "distinction THE_VAULT_ITSELF_NEVER_LEAVES was written for.",
        never = mapOf(
            "en" to listOf(
                "only with your password", "only with your master password", "only your password",
                "your vault never leaves", "the vault never leaves",
            ),
            "fr" to listOf(
                "uniquement avec votre mot de passe", "seulement avec votre mot de passe",
                "votre coffre ne quitte jamais", "le coffre ne quitte jamais",
            ),
            "de" to listOf(
                "nur mit ihrem passwort", "nur mit ihrem master-passwort",
                "ihr tresor verlässt niemals", "ihr tresor verlässt nie", "der tresor verlässt",
            ),
            "es" to listOf(
                "solo con su contraseña", "sólo con su contraseña", "únicamente con su contraseña",
                "su caja fuerte nunca sale", "la caja fuerte nunca sale",
            ),
            "pt" to listOf(
                "apenas com sua senha", "somente com sua senha", "só com sua senha",
                "seu cofre nunca sai", "o cofre nunca sai",
            ),
        ),
        always = mapOf(
            "en" to listOf("master password"), "fr" to listOf("mot de passe principal"),
            "de" to listOf("master-passwort"), "es" to listOf("contraseña maestra"),
            "pt" to listOf("senha mestra"),
        ),
    ),

    // --- feature/vaultlist -----------------------------------------------------------------

    Claim(
        "feature/vaultlist", "vaultlist_count_device_suffix",
        note = "Said \"locked to this device\" in all five languages until 2026-07-30. Nothing " +
            "binds the vault to the hardware: the key comes from the master password alone, the " +
            "file exports, and Auto Backup uploads it. Claimed only that it is encrypted here until " +
            "2026-09-16; now every language names the mechanism — encryption by the vault password — " +
            "as a noun phrase, so nothing has to agree with the plural count (F-42).",
        never = VAULT_NEVER_LEAVES,
        always = mapOf(
            "en" to listOf("%1\$s"), "fr" to listOf("%1\$s"), "de" to listOf("%1\$s"),
            "es" to listOf("%1\$s"), "pt" to listOf("%1\$s"),
        ),
    ),

    Claim(
        "feature/vaultlist", "vaultlist_count_device_folders_suffix",
        note = "The same line with a folder count folded in, added 2026-08-12 when folders became " +
            "containers: the root lists only unfiled items, so counting items alone read as data " +
            "loss. It carries the identical encryption claim and inherits every decision made " +
            "about its sibling, including the 2026-09-16 move from a locational noun to the mechanism.",
        never = VAULT_NEVER_LEAVES,
        always = mapOf(
            "en" to listOf("%1\$s", "%2\$s"), "fr" to listOf("%1\$s", "%2\$s"),
            "de" to listOf("%1\$s", "%2\$s"), "es" to listOf("%1\$s", "%2\$s"),
            "pt" to listOf("%1\$s", "%2\$s"),
        ),
    ),

    // --- feature/vaultlist: Make Invisible -------------------------------------------------------
    //
    // Concealment is a projection over the decrypted vault, not a second encrypted region. Every
    // string here is one a user could reasonably read as "this is now protected", and none of them
    // may say it. The counterpart risk is the opposite one: a string that reads as *destructive*
    // would stop people using a feature that is entirely reversible.

    Claim(
        "feature/vaultlist", "vaultlist_revealed_header",
        note = "The banner while a reveal session is running. Two facts: this is temporary, and it " +
            "ends with the session. A rendering that reads as a permanent setting turns the amber " +
            "state indicator into a lie about what happens when the phone locks. Since 2026-09-02 " +
            "the session is additionally time-bounded — RevealSession.WINDOW_MS, counted down on " +
            "the banner beside this sentence — so the true claim is \"ends with the session, or " +
            "sooner\". The session word is still required and still load-bearing: the countdown " +
            "is a second line that a translation could drop or a narrow screen could truncate, " +
            "and this sentence has to remain true on its own without it.",
        never = REVEAL_IS_PERMANENT and CONCEALMENT_IS_PROTECTION,
        always = mapOf(
            "en" to listOf("session"), "fr" to listOf("session"), "de" to listOf("sitzung"),
            "es" to listOf("sesión"), "pt" to listOf("sessão"),
        ),
    ),
    Claim(
        "feature/vaultlist", "vaultlist_revealed_state_description",
        note = "The TalkBack state description for the same banner. Names the state, claims nothing " +
            "about protection; the session word carries the same load as in the visible header.",
        never = REVEAL_IS_PERMANENT,
    ),
    Claim(
        "feature/vaultlist", "vaultlist_revealed_announcement",
        note = "The one-shot announcement when the session becomes revealed. Same two facts as the " +
            "header, spoken instead of shown — a screen-reader user gets this once and never sees " +
            "the banner again, so the session scope has to be in the sentence itself.",
        never = REVEAL_IS_PERMANENT,
        always = mapOf(
            "en" to listOf("session"), "fr" to listOf("session"), "de" to listOf("sitzung"),
            "es" to listOf("sesión"), "pt" to listOf("sessão"),
        ),
    ),
    Claim(
        "feature/vaultlist", "vaultlist_hide_again",
        note = "Ends the reveal session immediately. An action, not a claim — but it must not read " +
            "as a permanent or protective act, because it is neither: it returns to the ordinary " +
            "concealed state the lease already described.",
        never = CONCEALMENT_IS_PROTECTION,
    ),
    Claim(
        "feature/vaultlist", "vaultlist_hide_action",
        note = "The row/folder menu entry that opens the duration sheet. Considered, nothing to " +
            "ban beyond the protection family: it is one word plus an ellipsis, and the ellipsis " +
            "is what promises a further step rather than an immediate irreversible act.",
        never = CONCEALMENT_IS_PROTECTION,
    ),
    Claim(
        "feature/vaultlist", "vaultlist_hide_sheet_title",
        note = "Names the target being hidden. Carries the item or folder title as %1\$s, so the " +
            "placeholder is load-bearing — without it the sheet asks about nothing in particular.",
        never = CONCEALMENT_IS_PROTECTION,
        always = mapOf(
            "en" to listOf("%1\$s"), "fr" to listOf("%1\$s"), "de" to listOf("%1\$s"),
            "es" to listOf("%1\$s"), "pt" to listOf("%1\$s"),
        ),
    ),
    Claim(
        "feature/vaultlist", "vaultlist_hide_preset_5min",
        note = "A duration chip. Considered, nothing to ban: a number and a unit.",
    ),
    Claim(
        "feature/vaultlist", "vaultlist_hide_preset_30min",
        note = "A duration chip. Considered, nothing to ban: a number and a unit.",
    ),
    Claim(
        "feature/vaultlist", "vaultlist_hide_preset_1hour",
        note = "A duration chip. Considered, nothing to ban: a number and a unit.",
    ),
    Claim(
        "feature/vaultlist", "vaultlist_hide_preset_1day",
        note = "A duration chip. Considered, nothing to ban: a number and a unit.",
    ),
    Claim(
        "feature/vaultlist", "vaultlist_hide_preset_indefinite",
        note = "The no-expiry lease. The one duration chip that could mislead — it must mean 'until " +
            "you stop it', never 'forever' in the sense of unrecoverable. Recovery from Settings " +
            "works identically for an indefinite lease, including in the duress vault.",
        never = CONCEALMENT_IS_PROTECTION,
    ),
    Claim(
        "feature/vaultlist", "vaultlist_hide_preset_custom",
        note = "Opens the amount + unit step. Considered, nothing to ban.",
    ),
    Claim(
        "feature/vaultlist", "vaultlist_hide_custom_amount_label",
        note = "Field label for the custom duration's number. Considered, nothing to ban.",
    ),
    Claim(
        "feature/vaultlist", "vaultlist_hide_custom_unit_minutes",
        note = "Unit chip. Considered, nothing to ban.",
    ),
    Claim(
        "feature/vaultlist", "vaultlist_hide_custom_unit_hours",
        note = "Unit chip. Considered, nothing to ban.",
    ),
    Claim(
        "feature/vaultlist", "vaultlist_hide_custom_unit_days",
        note = "Unit chip. Considered, nothing to ban.",
    ),
    Claim(
        "feature/vaultlist", "vaultlist_hide_custom_confirm",
        note = "Commits the custom lease. An action; must not read as protective or permanent.",
        never = CONCEALMENT_IS_PROTECTION,
    ),
    Claim(
        "feature/vaultlist", "vaultlist_hide_custom_error",
        note = "Names the actual bound — a whole number, at most ten years (HiddenLease.MAX_CUSTOM " +
            "is 3,653 days). Tier F as well as G: the number in the sentence is the number the " +
            "validator enforces, so a translation that rounds it to 'ten years or so' is wrong " +
            "about something the user can test in the field.",
        always = mapOf(
            "en" to listOf("ten years"), "fr" to listOf("dix ans"), "de" to listOf("zehn jahren"),
            "es" to listOf("diez años"), "pt" to listOf("dez anos"),
        ),
    ),
    Claim(
        "feature/vaultlist", "vaultlist_hide_phrase_title",
        note = "Introduces the reveal phrase, once, at the first hide. Must not read as the master " +
            "password — the whole point is that this is a second, weaker, different secret.",
        never = REVEAL_PHRASE_IS_THE_MASTER_PASSWORD,
    ),
    Claim(
        "feature/vaultlist", "vaultlist_hide_phrase_body",
        note = "The highest-risk string in the feature. Two load-bearing facts: the phrase is typed " +
            "in the SEARCH BOX, and forgetting it is recoverable from SETTINGS with the VAULT " +
            "PASSWORD or biometric. Dropping the second turns a reversible feature into an " +
            "irreversible one, and nobody would find out until they had already forgotten a phrase. " +
            "'Vault password' rather than 'master password' is deliberate and load-bearing in its " +
            "own right: the sentence has to stay true in a duress session, where the current vault " +
            "password is the decoy's. Spanish and Portuguese both drifted to 'master password' on " +
            "the first translation pass and were corrected.",
        never = REVEAL_PHRASE_IS_THE_MASTER_PASSWORD and CONCEALMENT_IS_PROTECTION,
        always = PHRASE_BODY_KEEPS_ITS_TWO_FACTS,
    ),
    Claim(
        "feature/vaultlist", "vaultlist_hide_phrase_label",
        note = "The field label for the phrase being set. Same collision risk as the title: it must " +
            "not name the master password, and it must not reuse the glossary's secret-field " +
            "'reveal' verb, or the user reads it as the control that unmasks one password.",
        never = REVEAL_PHRASE_IS_THE_MASTER_PASSWORD,
    ),
    Claim(
        "feature/vaultlist", "vaultlist_hide_phrase_confirm",
        note = "Commits the first phrase and the first hide together (Decision 4 — one commit, so a " +
            "failure never leaves hidden data without the promised recovery). Considered, nothing " +
            "to ban beyond the protection family.",
        never = CONCEALMENT_IS_PROTECTION,
    ),
    Claim(
        "feature/vaultlist", "vaultlist_hide_error_collision",
        note = "Refuses a phrase that would match a visible item, because such a phrase would " +
            "reveal on every keystroke of an ordinary search. States the real reason rather than a " +
            "generic 'invalid', and names no item.",
    ),
    Claim(
        "feature/vaultlist", "vaultlist_marker_hidden",
        note = "The amber badge on a row that is under a lease, shown only inside a reveal session. " +
            "Amber is state, not failure (LkColors). One word; must not read as an error or as a " +
            "protection claim.",
        never = CONCEALMENT_IS_PROTECTION,
    ),
    // --- feature/settings: authenticated Hidden-items recovery -----------------------------------
    //
    // The screen the vault-list copy points at. Two extra risks live here and nowhere else: the gate
    // names a credential, and it must name the *vault* password so the words stay true inside the
    // duress vault where that password is the decoy's; and the phrase can be changed here, never
    // shown, so nothing may suggest a forgotten one is retrievable.

    Claim(
        "feature/settings", "settings_group_privacy",
        note = "The Settings group heading. Considered, nothing to ban: one word, no claim.",
    ),
    Claim(
        "feature/settings", "settings_hidden_items_title",
        note = "The row and the screen title. Present with zero, one and duress records, and it " +
            "carries no count or status — a row that read '3 hidden' would disclose by existing.",
        never = CONCEALMENT_IS_PROTECTION,
    ),
    Claim(
        "feature/settings", "settings_hidden_items_summary",
        note = "The row's subtitle, and the promise the whole feature rests on: whatever you hid, " +
            "you can get back. Must not imply the reveal phrase itself is retrievable.",
        never = THE_PHRASE_ITSELF_IS_RECOVERABLE and CONCEALMENT_IS_PROTECTION,
    ),
    Claim(
        "feature/settings", "settings_hidden_title",
        note = "Screen title, same words as the row. Considered, nothing to ban beyond the family.",
        never = CONCEALMENT_IS_PROTECTION,
    ),
    Claim(
        "feature/settings", "settings_hidden_back",
        note = "Navigation label. Considered, nothing to ban.",
    ),
    Claim(
        "feature/settings", "settings_hidden_gate_title",
        note = "The gate heading. Asks for identity, promises nothing about what is behind it — a " +
            "title naming a count would leak before the gate had been passed.",
        never = CONCEALMENT_IS_PROTECTION,
    ),
    Claim(
        "feature/settings", "settings_hidden_gate_body",
        note = "Names the two credentials the gate takes. 'Vault password', never 'master password': " +
            "in a duress session the current vault password is the decoy's, and the sentence has to " +
            "stay true there. Device credential is deliberately not offered and must not be named.",
        never = REVEAL_PHRASE_IS_THE_MASTER_PASSWORD,
        always = mapOf(
            "en" to listOf("vault password"), "fr" to listOf("mot de passe du coffre"),
            "de" to listOf("tresor-passwort"), "es" to listOf("contraseña de la caja fuerte"),
            "pt" to listOf("senha do cofre"),
        ),
    ),
    Claim(
        "feature/settings", "settings_hidden_gate_body_password_only",
        note = "The gate sentence when no biometric button is offered (after a panic lock, or no " +
            "strong sensor). Same rule as the body above: 'vault password', never 'master password'.",
        never = REVEAL_PHRASE_IS_THE_MASTER_PASSWORD,
        always = mapOf(
            "en" to listOf("vault password"), "fr" to listOf("mot de passe du coffre"),
            "de" to listOf("tresor-passwort"), "es" to listOf("contraseña de la caja fuerte"),
            "pt" to listOf("senha do cofre"),
        ),
    ),
    Claim(
        "feature/settings", "settings_hidden_gate_biometric",
        note = "The biometric button. BIOMETRIC_STRONG only — no device credential — so the label " +
            "says 'biometrics' rather than a generic 'unlock', which would invite a PIN. It names " +
            "no modality, because the gate cannot know which one is enrolled: canAuthenticate " +
            "answers only whether SOME Class-3 authenticator exists, so both 'fingerprint' and " +
            "'fingerprint or face' are false on ordinary devices.",
    ),
    Claim(
        "feature/settings", "settings_hidden_gate_password_label",
        note = "The field label. Same rule as the body, and the one most likely to be shortened to " +
            "'password' and then 'master password' by someone tidying up.",
        never = REVEAL_PHRASE_IS_THE_MASTER_PASSWORD,
        always = mapOf(
            "en" to listOf("vault password"), "fr" to listOf("mot de passe du coffre"),
            "de" to listOf("tresor-passwort"), "es" to listOf("contraseña de la caja fuerte"),
            "pt" to listOf("senha do cofre"),
        ),
    ),
    Claim(
        "feature/settings", "settings_hidden_gate_password_submit",
        note = "Submit button. Considered, nothing to ban.",
    ),
    Claim(
        "feature/settings", "settings_hidden_error_password",
        note = "The wrong-password error, and a duress-visible string: it must read identically in " +
            "the decoy vault, where the password that just failed was the decoy's. So it names the " +
            "'current vault password' and never 'master' or 'real' — either word tells a coercer " +
            "there is a second vault.",
        never = REVEAL_PHRASE_IS_THE_MASTER_PASSWORD,
    ),
    Claim(
        "feature/settings", "settings_hidden_error_session_ended",
        note = "The auto-lock-mid-prompt error. Names the cause the user can act on and nothing " +
            "about the vault's contents; never an exception string.",
    ),
    Claim(
        "feature/settings", "settings_hidden_empty_title",
        note = "The empty state. Deliberately the same words whether the feature was never used or " +
            "every lease expired — a different empty state for 'you had some once' is a disclosure.",
    ),
    Claim(
        "feature/settings", "settings_hidden_empty_body",
        note = "Restates recoverability on the screen where someone lands after forgetting a phrase.",
        never = THE_PHRASE_ITSELF_IS_RECOVERABLE,
    ),
    Claim(
        "feature/settings", "settings_hidden_until",
        note = "The lease line on a row, with the expiry as %1\$s. Tier F as well: the date shown is " +
            "the date the projection uses, and the placeholder is what makes that true.",
        always = mapOf(
            "en" to listOf("%1\$s"), "fr" to listOf("%1\$s"), "de" to listOf("%1\$s"),
            "es" to listOf("%1\$s"), "pt" to listOf("%1\$s"),
        ),
    ),
    Claim(
        "feature/settings", "settings_hidden_indefinite",
        note = "The no-expiry lease line. 'No end date', never 'forever' or 'permanent' — the entry " +
            "is recoverable from this very screen, and an indefinite lease is not a stronger one.",
        never = CONCEALMENT_IS_PROTECTION,
    ),
    Claim(
        "feature/settings", "settings_hidden_folder_label",
        note = "Distinguishes a folder row from an item row. Considered, nothing to ban.",
    ),
    Claim(
        "feature/settings", "settings_hidden_stop",
        note = "Ends the lease. Same word as the vault list's action in every language, so the copy " +
            "that promises recovery names the control the user then finds.",
    ),
    Claim(
        "feature/settings", "settings_hidden_change_duration",
        note = "Re-leases an entry. Considered, nothing to ban.",
    ),
    Claim(
        "feature/settings", "settings_hidden_stopped",
        note = "Confirms the entry is visible again, naming it as %1\$s. The placeholder is what " +
            "makes the confirmation checkable against the list.",
        always = mapOf(
            "en" to listOf("%1\$s"), "fr" to listOf("%1\$s"), "de" to listOf("%1\$s"),
            "es" to listOf("%1\$s"), "pt" to listOf("%1\$s"),
        ),
    ),
    Claim(
        "feature/settings", "settings_hidden_duration_changed",
        note = "Confirms the new lease. Same placeholder rule.",
        always = mapOf(
            "en" to listOf("%1\$s"), "fr" to listOf("%1\$s"), "de" to listOf("%1\$s"),
            "es" to listOf("%1\$s"), "pt" to listOf("%1\$s"),
        ),
    ),
    Claim(
        "feature/settings", "settings_hidden_duration_title",
        note = "The duration sheet's title, naming the target as %1\$s.",
        always = mapOf(
            "en" to listOf("%1\$s"), "fr" to listOf("%1\$s"), "de" to listOf("%1\$s"),
            "es" to listOf("%1\$s"), "pt" to listOf("%1\$s"),
        ),
    ),
    Claim("feature/settings", "settings_hidden_preset_5min", note = "Duration row. Nothing to ban."),
    Claim("feature/settings", "settings_hidden_preset_30min", note = "Duration row. Nothing to ban."),
    Claim("feature/settings", "settings_hidden_preset_1hour", note = "Duration row. Nothing to ban."),
    Claim("feature/settings", "settings_hidden_preset_1day", note = "Duration row. Nothing to ban."),
    Claim(
        "feature/settings", "settings_hidden_preset_indefinite",
        note = "As on the vault list: 'until you stop it', never 'forever'.",
        never = CONCEALMENT_IS_PROTECTION,
    ),
    Claim(
        "feature/settings", "settings_hidden_change_phrase",
        note = "Opens rotation. 'Change', never 'show' or 'recover' — this control replaces a " +
            "verifier and cannot display the old phrase.",
        never = THE_PHRASE_ITSELF_IS_RECOVERABLE,
    ),
    Claim(
        "feature/settings", "settings_hidden_phrase_body",
        note = "Explains what the phrase does and, load-bearing, what rotating it does NOT do: " +
            "changing the phrase un-hides nothing. Without that sentence someone rotates the phrase " +
            "expecting it to release their entries and concludes the feature is broken.",
        never = THE_PHRASE_ITSELF_IS_RECOVERABLE,
        always = mapOf(
            "en" to listOf("does not"), "fr" to listOf("aucun élément"),
            "de" to listOf("keine einträge"), "es" to listOf("ningún elemento"),
            "pt" to listOf("nenhum item"),
        ),
    ),
    Claim(
        "feature/settings", "settings_hidden_phrase_label",
        note = "Field label for the new phrase. Must not name the master password.",
        never = REVEAL_PHRASE_IS_THE_MASTER_PASSWORD,
    ),
    Claim(
        "feature/settings", "settings_hidden_phrase_confirm",
        note = "Commits the rotation, one commit. Considered, nothing to ban.",
    ),
    Claim(
        "feature/settings", "settings_hidden_phrase_collision",
        note = "Refuses a phrase that already matches a visible entry — such a phrase would reveal " +
            "on every keystroke of an ordinary search. Names the reason, never the entry.",
    ),
    Claim(
        "feature/settings", "settings_hidden_phrase_changed",
        note = "Confirms rotation. Says the phrase changed, never what it is.",
        never = THE_PHRASE_ITSELF_IS_RECOVERABLE,
    ),
    Claim(
        "feature/settings", "settings_hidden_explainer_health",
        note = "Says out loud that the security score moves when you hide and unhide. Volunteered " +
            "rather than left to be discovered: a score that drops for no visible reason is exactly " +
            "the kind of unexplained change that makes people distrust the number.",
    ),
    Claim(
        "feature/settings", "settings_hidden_explainer_storage",
        note = "The honesty string, and the one place in the app that states the limit of the whole " +
            "feature: hidden entries are still in the vault and in every backup, and hiding is not " +
            "encryption. It is deliberately NOT checked against CONCEALMENT_IS_PROTECTION — its job " +
            "is to *negate* the encryption claim, so it legitimately contains the word. Every " +
            "language must keep both nouns; a translation that drops 'and in every backup' turns " +
            "the app's most careful sentence into a half-truth.",
        always = mapOf(
            "en" to listOf("vault", "backup"), "fr" to listOf("coffre", "sauvegarde"),
            "de" to listOf("tresor", "sicherung"), "es" to listOf("caja fuerte", "copia de seguridad"),
            "pt" to listOf("cofre", "backup"),
        ),
    ),

    Claim(
        "feature/files", "files_attachments_photo_note",
        note = "Two claims in one sentence, added 2026-08-19 with the Identity photograph route, " +
            "and they point in opposite directions. The first is reassuring and is earned: " +
            "location and camera metadata really are removed, because a HEIC is re-encoded to " +
            "JPEG from a Bitmap before it is stored and every other still format goes through " +
            "ExifStripper. The second is the limitation, and it is the half that will be edited " +
            "away by someone tidying the sentence: attachment blobs are excluded from Android's " +
            "backup set (data_extraction_rules.xml and full_backup_content.xml both exclude " +
            "blobs/) while the typed fields of the same item are in it. A user who restores onto " +
            "a new phone gets the passport number back and not the photograph of the passport. " +
            "So the backup clause is asserted as load-bearing rather than merely allowed, and " +
            "the inverse claim is banned outright. " +
            "REWRITTEN 2026-09-03. It used to be about *photos* throughout, and it is shown on " +
            "every item type with an attachment list — so a saved location with nothing attached " +
            "read as a sentence about a photograph that did not exist, and a user attaching a PDF " +
            "was told nothing about it. The two clauses now carry their true scopes: metadata " +
            "stripping applies to IMAGES (only an image has EXIF to remove), the backup exclusion " +
            "applies to ATTACHMENTS (blobs/ is excluded whatever the file is). Widening the second " +
            "clause strengthens the disclosure rather than weakening it. " +
            "The fr/pt `always` phrases were shortened to their stems on the same date: they read " +
            "\"ne sont pas incluses\" and \"não são incluídas\", which pinned the *gender* of a " +
            "feminine plural subject (\"les photos\" / \"as fotos\") and would have forced a " +
            "mistranslation once the subject became masculine (\"os anexos\"). The load-bearing " +
            "part was always the negated inclusion, never the agreement, and the stems match both.",
        never = mapOf(
            "en" to listOf("photos are included in", "backed up", "included in the backup"),
            "fr" to listOf("les photos sont incluses", "sauvegardées"),
            "de" to listOf("fotos sind in der android-sicherung enthalten", "werden gesichert"),
            "es" to listOf("las fotos se incluyen", "se hace una copia de seguridad de las fotos"),
            "pt" to listOf("as fotos são incluídas", "as fotos têm backup"),
        ),
        always = mapOf(
            "en" to listOf("not included in"),
            // Stems, not full forms — see the note: these must survive a change of the subject's
            // grammatical gender, which the sentence has already undergone once.
            "fr" to listOf("ne sont pas inclu"),
            "de" to listOf("nicht in der android-sicherung enthalten"),
            "es" to listOf("no se incluyen"),
            "pt" to listOf("não são incluíd"),
        ),
    ),

    Claim(
        "feature/files", "files_edit_metadata_video_note",
        note = "The disclosure that replaced a silence. Photos go through ExifStripper; videos do " +
            "not, because container-level GPS and camera tags live in a different place in every " +
            "format (MP4 udta, QuickTime \u00a9xyz, and so on) and a remover that half-works would " +
            "be worse here than none — this is a password manager, and a false \"stripped\" claim " +
            "costs more than an honest \"kept\" one. Until this string existed the video case " +
            "showed nothing at all: no note, no button, no way to know the difference.\n\n" +
            "Two things are load-bearing, and a fluent translation can lose either. The NEGATION " +
            "(LocalKeep does *not* remove it) is the whole claim; drop it and the sentence still " +
            "reads well while asserting the opposite, which is exactly what the German " +
            "backup_cloud_disable_hint_on red-proof caught on a different key. And WHAT is kept " +
            "must be named, because \"metadata\" alone is a word most people do not map onto " +
            "\"where this was filmed\". Banned in the other direction too: any wording that " +
            "claims the data is gone.",
        never = mapOf(
            "en" to listOf("removes location", "metadata removed", "are stripped"),
            "fr" to listOf("supprime les données de localisation", "métadonnées supprimées"),
            "de" to listOf("entfernt standortdaten", "metadaten entfernt"),
            // Both carry the subject deliberately. Spanish and Portuguese negate with a bare
            // particle before the verb — "no elimina", "não remove" — so a banned phrase starting
            // at the verb is a substring of the CORRECT sentence and fires on it. Anchoring on
            // "LocalKeep <verb>" can only match a sentence where the negation is gone, which is
            // the claim being guarded against. French and German need no such care: "ne … pas"
            // and "keine" break the phrase from the inside.
            "es" to listOf("localkeep elimina los datos", "metadatos eliminados"),
            "pt" to listOf("localkeep remove os dados", "metadados removidos"),
        ),
        always = mapOf(
            "en" to listOf("does not remove", "location or camera data"),
            "fr" to listOf("ne supprime pas", "données de localisation"),
            "de" to listOf("entfernt keine", "standort"),
            "es" to listOf("no elimina", "datos de ubicación"),
            "pt" to listOf("não remove", "dados de localização"),
        ),
    ),
    Claim(
        "feature/files", "files_attachments_video_metadata_note",
        note = "The attachment picker's copy of files_edit_metadata_video_note — identical words, so it " +
            "carries the identical guard; see that entry for the reasoning.",
        never = mapOf(
            "en" to listOf("removes location", "metadata removed", "are stripped"),
            "fr" to listOf("supprime les données de localisation", "métadonnées supprimées"),
            "de" to listOf("entfernt standortdaten", "metadaten entfernt"),
            // Both carry the subject deliberately. Spanish and Portuguese negate with a bare
            // particle before the verb — "no elimina", "não remove" — so a banned phrase starting
            // at the verb is a substring of the CORRECT sentence and fires on it. Anchoring on
            // "LocalKeep <verb>" can only match a sentence where the negation is gone, which is
            // the claim being guarded against. French and German need no such care: "ne … pas"
            // and "keine" break the phrase from the inside.
            "es" to listOf("localkeep elimina los datos", "metadatos eliminados"),
            "pt" to listOf("localkeep remove os dados", "metadados removidos"),
        ),
        always = mapOf(
            "en" to listOf("does not remove", "location or camera data"),
            "fr" to listOf("ne supprime pas", "données de localisation"),
            "de" to listOf("entfernt keine", "standort"),
            "es" to listOf("no elimina", "datos de ubicación"),
            "pt" to listOf("não remove", "dados de localização"),
        ),
    ),
    Claim(
        "feature/files", "files_edit_metadata_present_note",
        note = "The uncertainty in this sentence is the accurate part, and it is the part a " +
            "tidying edit will remove. Standalone image cleanup became opt-in on 2026-08-25 — " +
            "save no longer strips, the user presses 'Delete all metadata' — so an image in the " +
            "editor may or may not carry EXIF. The app cannot tell which: MetadataCleanupState " +
            "is never persisted (core/model ItemContent.File has no such field) and loadItem() " +
            "resets every image to PENDING on reopen, so a photo cleaned last week is " +
            "indistinguishable from one imported a second ago. Both directions are therefore " +
            "banned. Asserting removal is the dangerous one — it tells a user their location is " +
            "gone when it may not be — but asserting presence is also false, and the hedge is " +
            "what makes the string true in both states, so it is asserted as load-bearing. " +
            "If the flag is ever persisted, this claim gets rewritten, not un-hedged in place. " +
            "Since 2026-10-09 MetadataProbe reads the bytes and files_edit_metadata_found_note " +
            "carries the measured case; this sentence remains only for HEIF, damaged files and " +
            "reads that failed, where the hedge is still the true answer.",
        never = mapOf(
            "en" to listOf("metadata removed", "will be removed", "has been removed", "no location"),
            "fr" to listOf("ont été supprimées", "seront supprimées", "métadonnées supprimées"),
            "de" to listOf("wurden entfernt", "werden entfernt", "metadaten entfernt"),
            "es" to listOf("se han eliminado", "se eliminarán", "metadatos eliminados"),
            "pt" to listOf("foram removidos", "serão removidos", "metadados removidos"),
        ),
        always = mapOf(
            "en" to listOf("may still"),
            "fr" to listOf("peut encore"),
            "de" to listOf("möglicherweise"),
            "es" to listOf("es posible que"),
            "pt" to listOf("pode conter"),
        ),
    ),

    Claim(
        "feature/files", "files_edit_metadata_found_note",
        note = "Added 2026-10-09 with MetadataProbe. Unlike files_edit_metadata_present_note this one " +
            "may say the metadata IS there, because the bytes were read and something Delete all " +
            "metadata would remove was found. What it may not say is what that metadata holds: the " +
            "probe does not parse it, and a JPEG comment segment counts the same as a GPS block. So " +
            "the content clause is a possibility (can include), never a finding about this photo — " +
            "a translation that turns it into 'shows where it was taken' claims the app read a " +
            "location it never looked at.",
        never = mapOf(
            "en" to listOf("contains your location", "shows where", "location was found", "has been removed"),
            "fr" to listOf("contient votre position", "indique où elle a été prise.", "ont été supprimées"),
            "de" to listOf("enthält ihren standort", "zeigt, wo", "wurden entfernt"),
            "es" to listOf("contiene su ubicación", "muestra dónde", "se han eliminado"),
            "pt" to listOf("contém sua localização", "mostra onde", "foram removidos"),
        ),
        always = mapOf(
            "en" to listOf("can include"),
            "fr" to listOf("peuvent"),
            "de" to listOf("können"),
            "es" to listOf("pueden"),
            "pt" to listOf("podem"),
        ),
    ),
    Claim(
        "feature/files", "files_original_prompt_body_photo",
        note = "The app's own question before Android's 'Allow LocalKeep to delete this photo?' " +
            "(2026-10-09). Two claims. The vault copy is saved and encrypted — true by the time this " +
            "shows, because it is asked only after the save committed. And the gallery original is " +
            "outside LocalKeep's protection — a claim about LocalKeep, deliberately NOT about the " +
            "gallery: the app does not know whether that photo is backed up, shared or visible to " +
            "other apps, so any wording that calls it public, unsafe or exposed overreaches. The " +
            "negation is load-bearing in the other direction too; lose it and the sentence says " +
            "LocalKeep protects the copy it is offering to delete.",
        never = mapOf(
            "en" to listOf("is public", "anyone can", "is unsafe", "localkeep protects it"),
            "fr" to listOf("est publique", "n\'importe qui", "localkeep la protège"),
            "de" to listOf("öffentlich", "jeder kann", "localkeep es schützt"),
            "es" to listOf("es pública", "cualquiera", "localkeep la protege"),
            "pt" to listOf("é pública", "qualquer pessoa", "localkeep a protege"),
        ),
        always = mapOf(
            "en" to listOf("does not protect", "encrypted"),
            "fr" to listOf("ne la protège pas", "chiffré"),
            "de" to listOf("nicht schützt", "verschlüsselt"),
            "es" to listOf("no la protege", "cifrado"),
            "pt" to listOf("não a protege", "criptografad"),
        ),
    ),
    Claim(
        "feature/files", "files_original_prompt_body_video",
        note = "files_original_prompt_body_photo's claim, about a video; see that entry. The object " +
            "pronoun follows the noun's gender, which is why es and pt assert a different negation.",
        never = mapOf(
            "en" to listOf("is public", "anyone can", "is unsafe", "localkeep protects it"),
            "fr" to listOf("est publique", "n\'importe qui", "localkeep la protège"),
            "de" to listOf("öffentlich", "jeder kann", "localkeep es schützt"),
            "es" to listOf("es público", "cualquiera", "localkeep lo protege"),
            "pt" to listOf("é público", "qualquer pessoa", "localkeep o protege"),
        ),
        always = mapOf(
            "en" to listOf("does not protect", "encrypted"),
            "fr" to listOf("ne la protège pas", "chiffré"),
            "de" to listOf("nicht schützt", "verschlüsselt"),
            "es" to listOf("no lo protege", "cifrado"),
            "pt" to listOf("não o protege", "criptografad"),
        ),
    ),
    Claim(
        "feature/files", "files_original_prompt_body_document",
        note = "The only confirmation a document delete gets (2026-10-09): DocumentsContract." +
            "deleteDocument raises no system dialog and there is no bin, so the irreversibility has " +
            "to be in this sentence or nowhere. Asserted, because a fluent translation softens it " +
            "first ('will be removed'). Banned: any wording that the file goes to, or can come back " +
            "from, a trash. Phrases are anchored so the correct negated sentence cannot match them " +
            "('il ne passe par aucune corbeille' does not contain 'déplacé dans la corbeille').",
        never = mapOf(
            "en" to listOf("can be restored", "moved to the bin", "moved to the trash", "localkeep protects it"),
            "fr" to listOf("peut être restauré", "déplacé dans la corbeille", "localkeep le protège"),
            "de" to listOf("wiederhergestellt werden", "in den papierkorb verschoben", "localkeep sie schützt"),
            "es" to listOf("se puede recuperar", "se mueve a la papelera", "localkeep lo protege"),
            "pt" to listOf("pode ser recuperado", "movido para a lixeira", "localkeep o protege"),
        ),
        always = mapOf(
            "en" to listOf("permanent", "does not go to a bin", "does not protect"),
            // "aucune / keinen / ninguna / nenhuma": the English says *a* bin, the OS one. With a
            // definite article each of these read as the app's own Trash, which is the glossary
            // word in all four (adversarial vote 2, 2026-10-09).
            "fr" to listOf("définitive", "aucune corbeille", "ne le protège pas"),
            "de" to listOf("endgültig", "keinen papierkorb", "nicht schützt"),
            "es" to listOf("permanente", "ninguna papelera", "no lo protege"),
            "pt" to listOf("permanente", "nenhuma lixeira", "não o protege"),
        ),
    ),

    Claim(
        "feature/files", "files_viewer_export_dialog_body",
        note = "The confirmation added 2026-08-26 for the viewer's Export, which until then wrote " +
            "a decrypted copy to shared storage on one tap with nothing said about it. Its whole " +
            "job is the negative claim, so the negation is asserted in all five languages rather " +
            "than merely permitted: a translation that loses `not` inverts the warning into " +
            "reassurance and the dialog becomes worse than no dialog. The banned list covers the " +
            "other direction — any wording implying the copy is still the vault's problem, or " +
            "still protected once it is out.",
        never = mapOf(
            "en" to listOf("still encrypted", "remains encrypted", "stays in the vault", "still protected"),
            "fr" to listOf("reste chiffrée", "toujours chiffrée", "reste dans le coffre", "reste protégée"),
            "de" to listOf("bleibt verschlüsselt", "weiterhin verschlüsselt", "bleibt im tresor", "bleibt geschützt"),
            "es" to listOf("sigue cifrada", "permanece cifrada", "permanece en la caja fuerte", "sigue protegida"),
            "pt" to listOf("continua criptografada", "permanece criptografada", "permanece no cofre", "continua protegida"),
        ),
        // Written without the apostrophe for the reason given on files_viewer_sheet_security_note:
        // read() matches raw XML, where the French contraction is `n\'est`, so a phrase containing
        // a bare `'` would never match the string it is meant to guard.
        always = mapOf(
            "en" to listOf("is not encrypted"),
            "fr" to listOf("est pas chiffrée"),
            "de" to listOf("nicht verschlüsselt"),
            "es" to listOf("no está cifrada"),
            "pt" to listOf("não é criptografada"),
        ),
    ),

    Claim(
        "feature/files", "files_viewer_sheet_security_note",
        note = "Until 2026-08-25 this said 'exporting strips them unless you ask otherwise', " +
            "which was false in both halves and had been shipped in five languages. Nothing " +
            "strips on the way out: the viewer's restore action is GalleryExporter.restore { " +
            "fileBlobStore.decryptToStream(...) }, a plain decrypt of the stored blob, and no " +
            "'ask otherwise' control has ever existed anywhere in that flow. Stripping was an " +
            "import-time thing even then, and is now an explicit user action. So any claim that " +
            "an export sanitizes anything is banned outright. The in-memory clause is the other " +
            "half of the sentence and is earned — no plaintext is written to shared storage " +
            "during viewing — so it is asserted rather than merely permitted.",
        never = mapOf(
            "en" to listOf("exporting strips", "strips them", "removes them", "unless you ask otherwise"),
            // No apostrophes in these: `read()` matches the raw XML, where French contractions are
            // written Android-style as `L\'exportation`, so a phrase containing a bare `'` can
            // never match the file it is meant to guard.
            "fr" to listOf("exportation les supprime", "les supprime", "sauf indication contraire"),
            "de" to listOf("werden sie entfernt", "beim exportieren werden sie", "sofern nicht anders"),
            "es" to listOf("la exportación los elimina", "los elimina", "a menos que se indique"),
            "pt" to listOf("a exportação os remove", "os remove", "a menos que você solicite"),
        ),
        // The banned list above is word-order bound, and that is not enough on its own. Every
        // phrase in it puts the object pronoun next to the verb, so the same false claim written
        // with a full noun — "Exporting automatically removes any location and camera data" —
        // walks straight through it, in all five languages. That was demonstrated rather than
        // imagined (2026-08-25 review), and no list of phrasings closes it, because the wrong
        // sentence has more shapes than anyone can enumerate.
        //
        // So the real guard is here instead, and it is shaped the other way round: assert the
        // clause that says export performs no transformation. A rewrite that claims stripping has
        // to delete that clause to make room for the claim — whatever words it chooses — and
        // deleting it is what this catches. Same reasoning as the qualifier assertions elsewhere
        // in this file: banning a lie needs the lie's exact words, asserting the truth does not.
        always = mapOf(
            "en" to listOf("in memory only", "exactly as the vault holds it"),
            "fr" to listOf("en mémoire uniquement", "tel que le coffre le conserve"),
            "de" to listOf("nur im speicher", "wie der tresor sie speichert"),
            "es" to listOf("solo en memoria", "tal y como lo guarda la caja fuerte"),
            "pt" to listOf("apenas na memória", "exatamente como o cofre o armazena"),
        ),
    ),

    Claim(
        "feature/itemedit", "itemedit_identity_saved_reauth",
        note = "The Identity capture flow's closing screen states the two properties of the item it " +
            "just made, and this is the security one: opening or copying a photographed document " +
            "costs a fresh biometric or device-credential challenge. It is earned rather than " +
            "aspirational — AttachmentPickerViewModel derives `highValue` from `item.content is " +
            "Identity`, so the gate is type-scoped and fires for every identity in the vault, " +
            "including ones imported before this flow existed, and it covers copy as well as " +
            "view. Both verbs are therefore load-bearing: a translation that keeps only 'open' " +
            "promises a weaker gate than the code enforces, which is the safe direction to be " +
            "wrong in but still makes the sentence a worse description of the app. What must " +
            "never appear is a softener — 'may be required', 'no re-authentication' — because " +
            "this is the line a user reads when deciding whether photographing a passport is " +
            "safe, and a conditional reads as 'sometimes'.",
        never = mapOf(
            "en" to listOf("no re-auth", "without re-authenticating", "may be required"),
            "fr" to listOf("aucune authentification", "peut \u00eatre requise", "sans authentification"),
            "de" to listOf("keine erneute authentifizierung", "kann erforderlich", "ohne erneute authentifizierung"),
            "es" to listOf("no se requiere", "puede requerirse", "sin autenticarse"),
            "pt" to listOf("n\u00e3o \u00e9 necess\u00e1ria", "pode ser necess\u00e1ria", "sem autentica\u00e7\u00e3o"),
        ),
        always = mapOf(
            "en" to listOf("open", "copy"),
            "fr" to listOf("ouvrir", "copier"),
            "de" to listOf("\u00f6ffnen", "kopieren"),
            "es" to listOf("abrir", "copiar"),
            "pt" to listOf("abrir", "copiar"),
        ),
    ),

    Claim(
        "feature/vaultlist", "vaultlist_stop_hiding",
        note = "Ends the lease outright — the constructive counterpart to Hide. Offered only for a " +
            "directly-leased record, never for one concealed by an ancestor, because stopping " +
            "something the user did not start would be a lie about what the action does. Must not " +
            "collide with the glossary's secret-field reveal verb in any language.",
        never = REVEAL_PHRASE_IS_THE_MASTER_PASSWORD,
    ),
    // The filter sheet's invisible-only narrowing, 2026-09-02. All three are composed only inside a
    // reveal session (FilterSheetContent gates them on revealSessionActive; FilterSheetContentTest
    // pins that), so they describe entries already on screen under the amber banner. Labels, not
    // claims — but they name the concealment vocabulary and so must not drift into calling it a
    // protection, which is the only false thing a filter label here could say.
    Claim(
        "feature/vaultlist", "vaultlist_filter_section_hidden",
        note = "Section heading over the invisible-only switch. A noun phrase naming the entries the " +
            "session is showing; must not read as encrypted, protected or deleted.",
        never = CONCEALMENT_IS_PROTECTION,
    ),
    Claim(
        "feature/vaultlist", "vaultlist_filter_hidden_only",
        note = "The switch's label and the active-filter token: narrows the list to concealed " +
            "entries. A filter, not an action on the entries — must not read as making anything " +
            "invisible, permanent or protected.",
        never = CONCEALMENT_IS_PROTECTION,
    ),
    Claim(
        "feature/vaultlist", "vaultlist_narrowed_hidden",
        note = "The count line's name for the same narrowing (\"3 items · invisible only\"). Two words, " +
            "mid-sentence; same bound as vaultlist_marker_hidden.",
        never = CONCEALMENT_IS_PROTECTION,
    ),

    // ── Item Flows, 2026-08-19 ────────────────────────────────────────────────────────────────

    Claim(
        "feature/itemedit", "itemedit_reprompt_caption_on",
        note = "The per-item reveal gate, described in its ON position. Earned: " +
            "`requiresReauthFor` returns true for every field once `reprompt` is set, and the Copy " +
            "button beside each secret derives its own `needsAuth` from the same call — so both " +
            "verbs are load-bearing and a translation that keeps only \"shown\" promises less than " +
            "the code enforces. What it must never claim is a *time* guarantee: a field already " +
            "revealed stays revealed until its auto-hide fires, so \"every time\" in the wall-clock " +
            "sense is false. The English says \"before it is shown or copied\" for that reason.",
        never = mapOf(
            "en" to listOf("no re-auth", "without asking", "may ask"),
            "fr" to listOf("sans demander", "peut demander"),
            "de" to listOf("ohne nachzufragen", "kann nachfragen"),
            "es" to listOf("sin preguntar", "puede preguntar"),
            "pt" to listOf("sem perguntar", "pode perguntar"),
        ),
    ),

    Claim(
        "feature/itemedit", "itemedit_reprompt_caption_off",
        note = "The same gate in its OFF position, and the entry that matters more of the two. The " +
            "obvious wording — \"secrets here open with the vault\" alone — is false: " +
            "`ALWAYS_REAUTH_FIELDS` holds a card's CVV, its PIN, a TOTP seed and a saved position " +
            "behind the prompt on every item regardless of the flag. The second sentence is " +
            "therefore load-bearing in every language: without it the caption tells a user their " +
            "card is less protected than it is, which is the direction a security caption must " +
            "never be wrong in.",
        always = mapOf(
            "en" to listOf("cvv", "pin", "2fa"),
            "fr" to listOf("cryptogramme", "2fa"),
            "de" to listOf("prüfziffer", "pin", "2fa"),
            "es" to listOf("cvv", "pin", "2fa"),
            "pt" to listOf("cvv", "pin", "2fa"),
        ),
    ),

    Claim(
        "feature/itemedit", "itemedit_note_assurance",
        note = "Under the secure-note body, where someone is deciding whether to type a recovery " +
            "phrase in. The design comp's sentence read " +
            "\"Nothing here is indexed or sent anywhere\", and the indexing half is false — " +
            "`VaultRepository`'s search corpus yields `Note.body` in full, so a note is findable " +
            "by any substring of its text. The shipped string states the searchability instead, " +
            "and that clause is the load-bearing one: dropping it restores the original false " +
            "claim by omission. 2026-10-15: the principal removed the \"same key / never sent " +
            "anywhere\" sentence, so the string is now the searchability clause alone; the " +
            "never/always lists below were not built on the removed sentence and are unchanged.",
        never = mapOf(
            "en" to listOf("not indexed", "no index", "cannot be searched"),
            "fr" to listOf("pas indexé", "non indexé", "ne peut pas être recherché"),
            "de" to listOf("nicht indiziert", "nicht durchsuchbar"),
            "es" to listOf("no se indexa", "sin índice", "no se puede buscar"),
            "pt" to listOf("não é indexada", "não indexada", "não pode ser pesquisada"),
        ),
        always = mapOf(
            "en" to listOf("search"),
            "fr" to listOf("recherche"),
            "de" to listOf("suche"),
            "es" to listOf("búsqueda"),
            "pt" to listOf("busca"),
        ),
    ),

    Claim(
        "feature/itemedit", "itemedit_edit_saving_body",
        note = "On the Encrypting screen every save now shows. \"Nothing leaves this device\" is the " +
            "app-wide no-network claim, earned by construction rather than by policy: the `foss` " +
            "merged manifest holds no INTERNET permission and `checkNoNetworkingDeps` fails the " +
            "build if a networking dependency appears. Never soften it to a probability.",
        never = mapOf(
            "en" to listOf("usually", "normally", "may be sent", "unless"),
            "fr" to listOf("généralement", "peut être envoyé", "sauf"),
            "de" to listOf("normalerweise", "kann gesendet", "es sei denn"),
            "es" to listOf("normalmente", "puede enviarse", "salvo"),
            "pt" to listOf("normalmente", "pode ser enviado", "exceto"),
        ),
    ),

    Claim(
        "feature/itemedit", "itemedit_edit_saving_body_note",
        note = "The secure-note wording of the same screen, and the same claim. Listed separately " +
            "rather than folded in because it is a separate key in five catalogues, and a register " +
            "that covers one of two near-identical claims is the failure mode this file exists for.",
        never = mapOf(
            "en" to listOf("usually", "normally", "may be sent", "unless"),
            "fr" to listOf("généralement", "peut être envoyé", "sauf"),
            "de" to listOf("normalerweise", "kann gesendet", "es sei denn"),
            "es" to listOf("normalmente", "puede enviarse", "salvo"),
            "pt" to listOf("normalmente", "pode ser enviado", "exceto"),
        ),
    ),

    Claim(
        "feature/itemedit", "itemedit_edit_saved_body",
        note = "On the Saved screen. Asserts one property — the item is under the same key as the " +
            "rest of the vault — which is true by construction: every item is serialised into one " +
            "vault file sealed with one DEK, and there is no per-item key. The claim to guard " +
            "against is the inverse one a translator might reach for, that this item is somehow " +
            "*extra* protected, which would be a promise nothing enforces.",
        never = mapOf(
            "en" to listOf("extra encryption", "additional key", "separate key"),
            "fr" to listOf("clé séparée", "chiffrement supplémentaire"),
            "de" to listOf("eigener schlüssel", "zusätzliche verschlüsselung"),
            "es" to listOf("clave aparte", "cifrado adicional"),
            "pt" to listOf("chave separada", "criptografia adicional"),
        ),
    ),

    Claim(
        "feature/itemedit", "itemedit_phrase_banner_incomplete_body",
        note = "The verdict banner while the grid is still being filled. States a limitation: the " +
            "BIP-39 checksum is over the whole phrase, so no partial entry has been validated and " +
            "the words typed so far are not known to be right. Nothing here is bannable \u2014 the " +
            "cheap way to make it false is to drop the negation, leaving a sentence that reads as " +
            "running validation \u2014 so the negation and the whole-phrase scope are asserted as " +
            "what must remain. A user who thinks the first eleven words were checked stops " +
            "proof-reading them.",
        always = mapOf(
            "en" to listOf("nothing is checked", "whole phrase"),
            "fr" to listOf("tant que le dernier mot", "la phrase enti\u00e8re"),
            "de" to listOf("nichts wird gepr\u00fcft", "gesamte phrase"),
            "es" to listOf("no se comprueba nada", "la frase entera"),
            "pt" to listOf("nada \u00e9 verificado", "a frase inteira"),
        ),
    ),

    Claim(
        "feature/itemedit", "itemedit_phrase_banner_badword_body",
        note = "Shown when a slot holds something that is not a BIP-39 word. Names where the " +
            "failure would otherwise surface \u2014 a restore, which is the worst possible moment \u2014 " +
            "and that is the clause carrying the warning. Drop it and the banner is a validation " +
            "notice about a typo rather than a statement that nothing downstream will catch this. " +
            "No banned phrase: the risk is a deletion, not a returning false claim.",
        always = mapOf(
            "en" to listOf("restore is the first place"),
            "fr" to listOf("la restauration serait le premier"),
            "de" to listOf("wiederherstellung", "zuerst"),
            "es" to listOf("la restauraci\u00f3n ser\u00eda el primer"),
            "pt" to listOf("a restaura\u00e7\u00e3o seria o primeiro"),
        ),
    ),

    Claim(
        "feature/itemedit", "itemedit_phrase_banner_checksum_body",
        note = "Real words, failing checksum. Two claims: the checksum cannot say WHICH word is " +
            "wrong (it is one hash over all of them), and nothing outside this screen will tell " +
            "the user either. The banned phrases are the first claim inverted \u2014 a translation " +
            "that offers to name the bad word promises something the arithmetic cannot do, and a " +
            "user then trusts the other eleven. The scope clause is asserted because deleting it " +
            "leaves a sentence implying some other tool would catch it.",
        never = mapOf(
            "en" to listOf("which word is wrong", "which one is wrong"),
            "fr" to listOf("quel mot est incorrect"),
            "de" to listOf("welches wort falsch"),
            "es" to listOf("qu\u00e9 palabra es incorrecta"),
            "pt" to listOf("qual palavra est\u00e1 errada"),
        ),
        always = mapOf(
            "en" to listOf("nothing outside this screen"),
            "fr" to listOf("rien en dehors de cet \u00e9cran"),
            "de" to listOf("nichts au\u00dferhalb dieses bildschirms"),
            "es" to listOf("nada fuera de esta pantalla"),
            "pt" to listOf("nada fora desta tela"),
        ),
    ),

    Claim(
        "feature/itemedit", "itemedit_phrase_banner_valid_body",
        note = "The one banner a user is pleased to see, and the one most likely to be " +
            "\u201cimproved\u201d into a guarantee. A valid checksum proves the words are self-consistent " +
            "\u2014 it does not prove they are THEIR phrase, that they were transcribed from the right " +
            "wallet, or that a restore will work; a wrong-but-valid phrase exists for every wallet " +
            "on earth. So the hedge is asserted and the three upgrades of it are banned. This is " +
            "the failure mode the security-copy protocol names twice: true of a measurement, stated as a " +
            "certainty.",
        never = mapOf(
            "en" to listOf("this phrase is correct", "this is your phrase", "will restore your"),
            "fr" to listOf("cette phrase est correcte", "restaurera"),
            "de" to listOf("diese phrase ist korrekt", "wallet wieder her"),
            "es" to listOf("esta frase es correcta", "restaurar\u00e1"),
            "pt" to listOf("esta frase est\u00e1 correta", "restaurar\u00e1"),
        ),
        always = mapOf(
            "en" to listOf("internally consistent"),
            "fr" to listOf("coh\u00e9rente en interne"),
            "de" to listOf("in sich stimmig"),
            "es" to listOf("coherente internamente"),
            "pt" to listOf("internamente consistente"),
        ),
    ),

    Claim(
        "feature/itemedit", "itemedit_phrase_banner_vaultkit_body",
        note = "The refusal shown when the words entered into a Recovery-phrase item are this " +
            "vault's OWN Recovery Kit. Its whole job is to send those words out of the vault and " +
            "onto paper; the load-bearing clause is the keep-it-outside instruction, and softening " +
            "it is exactly how a user talks themselves into storing the vault's key inside the " +
            "vault. No banned phrase — the danger here is a dropped instruction, not a returning " +
            "false claim — so the paper-and-outside instruction is asserted as what must remain.",
        always = mapOf(
            "en" to listOf("on paper", "outside"),
            "fr" to listOf("sur papier", "en dehors"),
            "de" to listOf("auf papier", "außerhalb"),
            "es" to listOf("en papel", "fuera"),
            "pt" to listOf("em papel", "fora"),
        ),
    ),

    Claim(
        "feature/itemedit", "itemedit_phrase_passphrase_caption",
        note = "The optional 25th word. States a limitation: nothing on the device verifies it, so " +
            "a typo is undetectable until a real restore fails. Inverting it — dropping the " +
            "negation, or reading as 'checked against the words' — turns the limitation into a " +
            "false assurance and a user saves a wrong passphrase believing it was validated. A " +
            "banned phrase cannot help: the correct string CONTAINS 'checked against them', led by " +
            "'never', and a substring match cannot tell the two apart. So the negation itself is " +
            "asserted as load-bearing — if it goes, the sentence has flipped.",
        always = mapOf(
            "en" to listOf("never checked"),
            "fr" to listOf("jamais vérifiée"),
            "de" to listOf("nie mit ihnen abgeglichen"),
            "es" to listOf("nunca se comprueba"),
            "pt" to listOf("nunca verificada"),
        ),
    ),
)

// =================================================================================================
// Harness.
// =================================================================================================

/**
 * One Tier G string and the decision made about it.
 *
 * [note] is not decoration: for the keys with no banned phrase it *is* the entry, and for the rest
 * it is the reason a reviewer can check the phrase list against.
 */
private class Claim(
    val module: String,
    val key: String,
    val note: String,
    val never: Map<String, List<String>> = emptyMap(),
    val always: Map<String, List<String>> = emptyMap(),
) {
    /** This string in all five languages, by language. Errors by name if one is missing. */
    fun read(): Map<String, String> = LOCALE_DIRS.mapValues { (lang, dir) ->
        val file = File(REPO, "$module/src/main/res/$dir/strings.xml")
        val xml = file.takeIf { it.isFile }?.readText()
            ?: error("$module has no $dir/strings.xml — $key is unguarded in $lang")
        Regex("""<string name="$key"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)
            // A <plurals> is read as every quantity joined. The claim is made by whichever form the
            // count happens to select, so a banned phrase hiding in `few` is exactly as false as one
            // in `other` — and the languages here have four quantity classes between them.
            ?: Regex("""<plurals name="$key"[^>]*>(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
                .find(xml)?.groupValues?.get(1)
                ?.let { body ->
                    Regex("""<item[^>]*>(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
                        .findAll(body).joinToString(" ") { it.groupValues[1] }
                }
            ?: error("$key missing from $module/$dir/strings.xml — a Tier G string cannot go untranslated")
    }
}

private val LOCALE_DIRS = mapOf(
    "en" to "values", "fr" to "values-fr", "de" to "values-de", "es" to "values-es", "pt" to "values-pt",
)

/**
 * Repo root, found by walking up for the settings file.
 *
 * This test reads other modules' resources, so it cannot use the working directory the way
 * `UnlockSubtitleTest` does. `app/build.gradle.kts` declares those files as task inputs — without
 * that the task sits UP-TO-DATE through the exact edit it exists to catch, which was measured, not
 * assumed: mutating a German string re-ran nothing until the inputs were declared.
 */
private val REPO: File = generateSequence(File("").absoluteFile) { it.parentFile }
    .firstOrNull { File(it, "settings.gradle.kts").isFile }
    ?: error("repo root not found above ${File("").absolutePath}")

/**
 * Every `module:key` whose base resources carry a `SECURITY-COPY: tier G` marker.
 *
 * Read from the marker rather than a second list, so the source of truth stays the file the
 * translator and the reviewer are both already looking at.
 */
private fun taggedTierGKeys(): Set<String> {
    val out = sortedSetOf<String>()
    REPO.walkTopDown()
        .onEnter { it.name != "build" && it.name != ".git" }
        .filter { it.path.endsWith("/src/main/res/values/strings.xml") }
        .forEach { file ->
            val module = file.relativeTo(REPO).path.removeSuffix("/src/main/res/values/strings.xml")
            var tagged = false
            Regex("""<!--(.*?)-->|<string name="([^"]+)"|<plurals name="([^"]+)"""", RegexOption.DOT_MATCHES_ALL)
                .findAll(file.readText())
                .forEach { m ->
                    val (comment, stringKey, pluralKey) = m.destructured
                    when {
                        // A run of comments before one string: any of them carrying the marker tags it.
                        stringKey.isEmpty() && pluralKey.isEmpty() -> if ("tier G" in comment) tagged = true
                        stringKey.isNotEmpty() -> {
                            if (tagged) out += "$module:$stringKey"
                            tagged = false
                        }
                        // A tagged <plurals> used to fall through here and clear the flag, so a
                        // counted security claim — "hidden for 3 more days" — could be marked Tier G
                        // and the totality check would still pass with nobody having decided what it
                        // may never say. The one place a claim is most likely to be counted is
                        // exactly the place the register silently skipped.
                        else -> {
                            if (tagged) out += "$module:$pluralKey"
                            tagged = false
                        }
                    }
                }
        }
    return out
}
