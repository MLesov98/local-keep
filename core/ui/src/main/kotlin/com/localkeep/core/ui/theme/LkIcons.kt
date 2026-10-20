package com.localkeep.core.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * LocalKeep's icon set, drawn to the visual pass rather than taken from `material-icons`.
 *
 * Two reasons this is worth hand-drawing. The obvious one is that Material's filled glyphs are
 * heavier and rounder than this design, and mixing them in is instantly visible. The load-bearing
 * one is that several of these icons carry meaning nothing off-the-shelf has: the shield-with-a-
 * keyhole *is* the product mark, [Fingerprint] appears only where a biometric prompt will actually
 * be raised, and [Lock] on a row means "this field is gated behind re-auth" — not "locked" in the
 * generic sense. Borrowing an approximate glyph for those would make the vocabulary mushy.
 *
 * All are 24×24 stroke outlines with no fill, so a single set works at every size and takes its
 * colour from `Icon(tint = …)`.
 */
object LkIcons {
    /** The product mark: a shield with a keyhole. */
    val Shield = stroked(
        "M12 2.6 20 6v6.1c0 5-3.4 8.3-8 9.3-4.6-1-8-4.3-8-9.3V6l8-3.4Z",
        "M12 13.1v3",
    ) + circle(12f, 11f, 2.1f)

    /** Shield with a tick — the security dashboard, and anything that has been verified. */
    val ShieldCheck = stroked(
        "M12 3.6 19.4 6.6v5.8c0 4.6-3.1 7.7-7.4 8.6-4.3-.9-7.4-4-7.4-8.6V6.6L12 3.6Z",
        "M9 12.2l2.3 2.3 4-4.3",
    )

    /** Two overlapping shields: the duress / decoy vault. */
    val ShieldDecoy = stroked(
        "M9.4 3.9 4.4 5.9v4.6c0 3.1 1.5 5.4 3.8 6.7",
        "M12.8 5.8 19.6 8.4v5c0 4.2-2.9 7-6.8 7.8-3.9-.8-6.8-3.6-6.8-7.8v-5l6.8-2.6Z",
    )

    val Lock = stroked(
        "M8.4 10.5V8a3.6 3.6 0 0 1 7.2 0v2.5",
        "M5 13.5v3.5a3 3 0 0 0 3 3h8a3 3 0 0 0 3-3v-3.5a3 3 0 0 0-3-3H8a3 3 0 0 0-3 3Z",
    )

    /**
     * A safe door with a dial: *someone else's* password vault, in the import wizard's source grid.
     *
     * Drawn because that grid had nothing neutral left to give 1Password. It used [Lock], which this
     * set reserves for "this field is gated behind re-auth" — while the comment beside it stated the
     * rule it was breaking (V3-07, 2026-09-17). The glyph carries no promise about this app at all,
     * which is the whole requirement: [Shield] is LocalKeep's own mark and would brand a competitor
     * as this product, [Key] belongs to logins and to the password well, [Passkey] to that item type.
     *
     * Deliberately generic rather than a brand mark, per the note on `ImportSource.icon`: bundling
     * vendor logos would ship trademarks with no licence, and fetching them is a network call this
     * app does not make.
     */
    val VaultDoor = stroked(
        "M4.6 5.4a2 2 0 0 1 2-2h10.8a2 2 0 0 1 2 2v13.2a2 2 0 0 1-2 2H6.6a2 2 0 0 1-2-2V5.4Z",
        "M12 3.6v4.2",
        "M12 16.2v4.2",
        "M4.8 12h3",
        "M16.2 12h3",
        width = 1.55f,
    ) + circle(12f, 12f, 4.2f, width = 1.55f)

    val Eye = stroked(
        "M2.8 12S6.6 5.8 12 5.8 21.2 12 21.2 12 17.4 18.2 12 18.2 2.8 12 2.8 12Z",
    ) + circle(12f, 12f, 2.7f)

    val EyeOff = stroked(
        "M4.4 8.4C3.3 9.7 2.8 12 2.8 12S6.6 18.2 12 18.2c1.5 0 2.8-.4 4-1",
        "M19 15.3c1.5-1.6 2.2-3.3 2.2-3.3S17.4 5.8 12 5.8c-.6 0-1.1 0-1.6.2",
        "M4.5 4.5l15 15",
    )

    val Copy = stroked(
        "M8.5 6.5v-.5a2.5 2.5 0 0 1 2.5-2.5h6.5a3 3 0 0 1 3 3v8a3 3 0 0 1-3 3h-.5",
        "M15.5 20.5h-8a3 3 0 0 1-3-3v-10a3 3 0 0 1 3-3h8a3 3 0 0 1 3 3v10a3 3 0 0 1-3 3Z",
    )

    val Check = stroked("M6 12.6 10.2 16.8 18.4 8", width = 2.1f)

    val Search = stroked("M15.6 15.6 19.5 19.5") + circle(10.8f, 10.8f, 6.3f)

    val Close = stroked("M6.5 6.5l11 11", "M17.5 6.5l-11 11", width = 2f)

    /** A star that is not set: the favourite toggle's "off" state, and the glyph for the idea of a favourite. */
    val Star = stroked(STAR_PATH)

    /**
     * A star that is set: the same outline as [Star], filled solid.
     *
     * The favourite toggle used to tell its two states apart by tint alone, which says nothing to
     * anyone who cannot tell jade from grey. The shape now changes with the state — outline when
     * off, solid when on — so the colour is a second cue and not the only one. Use it only for
     * "this is a favourite"; an outline star with a different tint is still [Star].
     */
    val StarFilled = filled(STAR_PATH)

    /** Only ever shown where a biometric prompt will genuinely be raised. */
    val Fingerprint = stroked(
        "M12 4.4c-2.1 0-3.9.9-5.1 2.2",
        "M4.7 9.4C4.3 10.5 4.1 11.7 4.1 13c0 2.1.4 3.9 1.1 5.3",
        "M19.3 9.6c.4 1.1.6 2.2.6 3.4 0 1.5-.2 2.9-.6 4",
        "M17.4 6.9A7.4 7.4 0 0 0 14 4.9",
        "M8.2 19.4c-.5-1.5-.8-3.2-.8-5 0-2.6 2-4.6 4.6-4.6s4.6 2 4.6 4.6c0 1.6-.2 3-.6 4.2",
        "M11.6 20c-.3-1.2-.5-2.6-.5-4.1a.9.9 0 0 1 1.8 0c0 1.4.1 2.6.3 3.6",
    )

    val Refresh = stroked(
        "M19.5 12a7.5 7.5 0 1 1-2.4-5.5",
        "M19.8 3.8v3.6h-3.6",
        width = 1.7f,
    )

    val ChevronRight = stroked("M9.5 5.5 16 12l-6.5 6.5", width = 1.8f, autoMirror = true)
    val ChevronLeft = stroked("M14.5 5.5 8 12l6.5 6.5", width = 1.8f, autoMirror = true)

    /**
     * The open state of a disclosure, paired with [ChevronRight]'s closed one.
     *
     * Not mirrored: it points at the rows it revealed below, and "below" does not flip in an RTL
     * layout the way "forward" does.
     */
    val ChevronDown = stroked("M5.5 9.5 12 16l6.5-6.5", width = 1.8f)
    val Plus = stroked("M12 5.5v13", "M5.5 12h13", width = 2.1f)
    // Three bars of equal length, which is what Material Symbols' `menu` draws. The middle bar was
    // 11 units against the others' 15 — a flourish with no platform backing, and one that borrows
    // the tapering silhouette of a filter/sort glyph. A nav icon this heavily learned costs
    // recognition speed for every unit it deviates.
    val Menu = stroked("M4.5 7h15", "M4.5 12h15", "M4.5 17h15")

    /**
     * A funnel: the vault list's filter control.
     *
     * The tapering silhouette is the shape [Menu]'s comment refuses to borrow, drawn here on
     * purpose because here it means what it looks like. One closed outline rather than three
     * shrinking bars, so at 18dp it still reads as a funnel and not as a misdrawn menu glyph.
     */
    val Filter = stroked("M4 5.5h16l-6.2 7.3v5l-3.6 1.8v-6.8L4 5.5Z")

    val Globe = stroked(
        "M3.6 12h16.8",
        "M12 3.4c2.2 2.4 3.3 5.3 3.3 8.6s-1.1 6.2-3.3 8.6c-2.2-2.4-3.3-5.3-3.3-8.6S9.8 5.8 12 3.4Z",
    ) + circle(12f, 12f, 8.6f)

    val OpenExternal = stroked("M9 15 15 9", "M10.5 8.5H15V13", width = 1.7f)

    /**
     * A map pin: a saved coordinate.
     *
     * Deliberately a pin and not [Globe], which already means "a website" on every login row — and
     * not a compass or an arrow, both of which suggest heading somewhere. This item type is one
     * place, marked. The teardrop is drawn tip-down so it reads as pointing at a spot rather than
     * floating above one, which is the whole difference between a pin and a balloon.
     */
    val Pin = stroked(
        "M12 21.2c4.2-4.6 6.3-8 6.3-10.4a6.3 6.3 0 1 0-12.6 0c0 2.4 2.1 5.8 6.3 10.4Z",
    ) + circle(12f, 10.6f, 2.4f)

    /** A stopwatch: TOTP, and anything time-boxed. */
    val Clock = stroked("M12 9.4V13l2.6 1.7", "M9.4 3.4h5.2") + circle(12f, 13f, 7.6f)

    /**
     * A camera: taking a picture, and the disclosure that asks for the camera to do it.
     *
     * [Eye] stood in for this at seven call sites — the identity capture route, the "Capture" and
     * "Take a photo" buttons, and all four camera-permission disclosures. An eye means "reveal what
     * is masked" everywhere else in this app, so on a control that opens the camera it said the
     * wrong thing twice: it named the wrong act, and it borrowed the glyph that gates secrets.
     */
    val Camera = stroked(
        "M9.1 7.6 10.4 5.2h3.2l1.3 2.4h3.6a3 3 0 0 1 3 3v6.2a3 3 0 0 1-3 3H5.5a3 3 0 0 1-3-3v-6.2a3 3 0 0 1 3-3Z",
        width = 1.55f,
    ) + circle(12f, 13.9f, 3.4f, width = 1.55f)

    val Card = stroked(
        "M3.6 9.2a2.6 2.6 0 0 1 2.6-2.6h11.6a2.6 2.6 0 0 1 2.6 2.6v5.6a2.6 2.6 0 0 1-2.6 2.6H6.2a2.6 2.6 0 0 1-2.6-2.6V9.2Z",
        "M3.6 10.4h16.8",
        "M6.8 14h2.8",
        width = 1.55f,
    )

    val Identity = stroked(
        "M4.2 8a2.6 2.6 0 0 1 2.6-2.6h10.4A2.6 2.6 0 0 1 19.8 8v8a2.6 2.6 0 0 1-2.6 2.6H6.8A2.6 2.6 0 0 1 4.2 16V8Z",
        "M6.6 15.8c.5-1.6 1.7-2.5 2.9-2.5s2.4.9 2.9 2.5",
        "M14.9 9.8h3M14.9 12.6h3",
        width = 1.55f,
    ) + circle(9.5f, 10.3f, 1.9f, width = 1.55f)

    val Note = stroked(
        "M7.4 4.6h6.4l4.2 4.2v10.6a1 1 0 0 1-1 1H7.4a1 1 0 0 1-1-1V5.6a1 1 0 0 1 1-1Z",
        "M13.6 4.7v4.3h4.3",
        "M9.2 13h5.6M9.2 16h4",
        width = 1.55f,
    )

    /** A folder: the filing a vault item sits in. */
    val Folder = stroked(
        "M3.8 7.2a1.8 1.8 0 0 1 1.8-1.8h3.1l2 2.3h7.5a1.8 1.8 0 0 1 1.8 1.8v7.9a1.8 1.8 0 0 1-1.8 1.8H5.6a1.8 1.8 0 0 1-1.8-1.8Z",
        width = 1.55f,
    )

    /**
     * [Folder] with its front panel tipped forward, for the moment a drag is hovering over it.
     *
     * A separate glyph rather than a tint change on [Folder], because "this folder will take what
     * you are holding" has to read at a glance while a finger is covering part of the row — and a
     * colour shift alone is the one signal a user with a colour-vision deficiency may not get. Same
     * `stroked` construction and the same 1.55 weight as its neighbour, so the cross-fade between
     * them does not change the row's visual weight.
     */
    val FolderOpen = stroked(
        "M3.8 7.2a1.8 1.8 0 0 1 1.8-1.8h3.1l2 2.3h7.5a1.8 1.8 0 0 1 1.8 1.8v1.2H3.8Z",
        "M3.8 10.7h16.4l-1.7 6.9a1.8 1.8 0 0 1-1.75 1.4H5.6a1.8 1.8 0 0 1-1.8-1.8Z",
        width = 1.55f,
    )

    /**
     * A luggage tag, punched. Not the same shape as [Folder] on purpose: a folder is where an item
     * lives and a tag is one of many labels on it, and two glyphs that differed only in outline
     * would leave the bulk bar offering two identical-looking buttons that do different things.
     */
    val Tag = stroked(
        "M11.4 4.2h6.4a2 2 0 0 1 2 2v6.4l-7.5 7.5a1.6 1.6 0 0 1-2.3 0l-6.1-6.1a1.6 1.6 0 0 1 0-2.3Z",
        width = 1.55f,
    ) + circle(16.1f, 7.9f, 1.15f, width = 1.55f)

    /**
     * A framed picture with a horizon and a sun: **the device's photo gallery**, and only that.
     *
     * Added 2026-09-20 (4C-17). "From gallery" led with [Import], the two-arrows-passing mark this
     * set binds to *migration* — the glyph the drawer puts on "bring another password manager's
     * data in". Reading an image out of the system picker is not that, and the two controls sat
     * one screen apart. `LkIcons` had no image glyph at all, which is why the reuse happened and
     * why this is a new mark rather than a re-pointing of an old one.
     *
     * Not a substitute for [Camera] ("take a picture", which creates an image that never reaches
     * the gallery) and not for [Attachment] ("browse documents", any file). The three add-routes on
     * the File editor now differ by glyph as well as by label.
     */
    val Gallery = stroked(
        "M3.4 7.4a3 3 0 0 1 3-3h11.2a3 3 0 0 1 3 3v9.2a3 3 0 0 1-3 3H6.4a3 3 0 0 1-3-3V7.4Z",
        "M4 17.1l4.2-4.2a2 2 0 0 1 2.8 0l3.4 3.4",
        "M13.4 14.2l1.4-1.4a2 2 0 0 1 2.8 0l2.9 2.9",
        width = 1.55f,
    ) + circle(9.1f, 9.2f, 1.3f, width = 1.55f)

    /** A paperclip: file attachments. */
    val Attachment = stroked(
        "M17.6 11.4l-6.4 6.4a3.6 3.6 0 0 1-5.1-5.1l7-7a2.4 2.4 0 0 1 3.4 3.4l-7 7a1.2 1.2 0 0 1-1.7-1.7l6.2-6.2",
        width = 1.55f,
    )

    /**
     * A key: logins as an item type, **and** the leading glyph on a password well.
     *
     * The second use is not a drift. `LkPasswordField` used to lead with [Lock], which this set
     * reserves for "this field is gated behind re-auth" — a padlock on every ordinary password
     * spent that meaning on fields making no such promise and left the ones that do with nothing to
     * distinguish them (G16). Both uses are "a credential", which is why they can share a glyph;
     * written down because a sweep read the one-line KDoc as ruling the password well out
     * (W3, 2026-09-12).
     */
    val Key = stroked(
        "M14.6 9.4a3.6 3.6 0 1 0-3.4 3.6l-.9.9v1.6H9v1.6H7.4v1.6H5v-2.6l6.2-6.2",
        width = 1.55f,
    ) + circle(15.5f, 8.5f, 0.95f, width = 1.55f)

    /**
     * A ring key, drawn horizontally: the Passkey item type, and only that.
     *
     * Deliberately *not* [Key], which this set gives to logins and to the password well. Sharing one
     * glyph between the two was a decision taken on 2026-09-13 ("a passkey is the same errand by a
     * better mechanism") and reversed on 2026-09-16, because the Filter sheet lists Logins and
     * Passkeys as two adjacent rows and drew them with a pixel-identical key — a control whose whole
     * job is to tell item types apart, saying nothing at the one place a user asks it to.
     *
     * A key and not something else, because a passkey *is* a key; the silhouette is what carries the
     * difference. [Key] is diagonal with a solid head and teeth on its lower left; this is
     * horizontal, its bow is a pierced ring, and its teeth fall from the shaft's end. Not a
     * fingerprint — this set reserves [Fingerprint] for "a biometric prompt will actually be
     * raised", which is a promise a list row and a filter chip cannot keep.
     */
    val Passkey = stroked(
        "M13.9 12h6.3",
        "M17.1 12v2.8",
        "M20.2 12v2.2",
        width = 1.55f,
    ) + circle(9.4f, 12f, 4.4f, width = 1.55f) + circle(9.4f, 12f, 1.5f, width = 1.55f)

    /** Stacked layers: "all items". */
    val Layers = stroked(
        "M12 3.8 20 7.6 12 11.4 4 7.6 12 3.8Z",
        "M4 12.1l8 3.8 8-3.8",
        "M4 16.5l8 3.8 8-3.8",
        width = 1.55f,
    )

    /** A sealed box: the Recovery Kit. */
    val RecoveryKit = stroked(
        "M12 3.8l7 4v8.4l-7 4-7-4V7.8l7-4Z",
        "M9 10.7h6M9 13.5h4",
        width = 1.55f,
    )

    /**
     * The Recovery-phrase item type (a stored BIP-39 seed). Deliberately the same box drawing the
     * comp uses for it — and, as it happens, the same glyph as [RecoveryKit]: seed material looks
     * like seed material. Kept as its own name rather than reusing [RecoveryKit], because this app's
     * one standing rule about these two is that *Recovery Kit* (the vault's own words) and *Recovery
     * phrase* (someone else's, stored as an item) never share a name in code.
     */
    val SeedPhrase = stroked(
        "M12 3.8l7 4v8.4l-7 4-7-4V7.8l7-4Z",
        "M9 10.7h6M9 13.5h4",
        width = 1.55f,
    )

    /**
     * Removing hidden data *from* a file — metadata cleanup — never deleting the file itself.
     *
     * Added 2026-10-09 because "Delete all metadata" wore [Trash], and on a phone it read as the
     * button that deletes the photo (the persona review put it as the scariest control on the
     * screen). [Trash] keeps its one meaning: something goes away entirely.
     */
    val Erase = stroked(
        "M8.6 19.4 4.7 15.5a1.5 1.5 0 0 1 0-2.1l8.6-8.6a1.5 1.5 0 0 1 2.1 0l3.9 3.9a1.5 1.5 0 0 1 0 2.1l-8.6 8.6",
        "M9.4 8.7l5.9 5.9",
        "M8.6 19.4h10.8",
        width = 1.55f,
    )

    val Trash = stroked(
        "M5.4 7.8h13.2",
        "M9.4 7.8V5.9a1 1 0 0 1 1-1h3.2a1 1 0 0 1 1 1v1.9",
        "M7.3 7.8l.8 11a1 1 0 0 0 1 .9h5.8a1 1 0 0 0 1-.9l.8-11",
        "M10.7 11.3v5M13.3 11.3v5",
        width = 1.55f,
    )

    /**
     * A pencil: open this record for editing.
     *
     * Its own glyph rather than [Settings], which the row overflow's "Edit" borrowed until
     * 2026-09-16 — the same two-slider mark the drawer puts on *app settings*, so one picture stood
     * for "change this item" and "change the whole app". The word beside it was fixed on 2026-09-11
     * and the glyph was left behind; a menu whose label and icon disagree is read by the icon.
     */
    val Edit = stroked(
        "M4.8 19.2l.8-3.4L15.8 5.6a1.9 1.9 0 0 1 2.7 2.7L8.2 18.4l-3.4.8Z",
        "M14.4 7l2.7 2.7",
        width = 1.55f,
    )

    /** Sliders, not a cog — this app's settings are choices along a scale, not machinery. */
    val Settings = stroked(
        "M5.4 8.6h3.4M13.2 8.6h5.4",
        "M5.4 15.4h5.2M15 15.4h3.6",
        width = 1.55f,
    ) + circle(11f, 8.6f, 2.2f, width = 1.55f) + circle(12.8f, 15.4f, 2.2f, width = 1.55f)

    /** Two arrows passing: migrate / import. Horizontal, so it must mirror in RTL — otherwise
     *  the "into the app" arrow reads as pointing back out. */
    val Import = stroked(
        "M4.8 9.1h11.4",
        "M13.2 6.1l3 3-3 3",
        "M19.2 14.9H7.8",
        "M10.8 17.9l-3-3 3-3",
        width = 1.55f,
        autoMirror = true,
    )

    val Export = stroked(
        "M12 14.9V5.7",
        "M8.8 8.9 12 5.7l3.2 3.2",
        "M5.2 14.6v3a1.8 1.8 0 0 0 1.8 1.8h10a1.8 1.8 0 0 0 1.8-1.8v-3",
        width = 1.55f,
    )

    val Info = stroked("M12 11v5.2", "M12 8.2v.01") + circle(12f, 12f, 8.6f)

    val Warning = stroked("M12 8.2v4.4", "M12 15.6v.01", width = 1.7f) + circle(12f, 12f, 8.6f)

    /** A die face: the passphrase suggester. */
    val Dice = stroked("M4.4 8.4a4 4 0 0 1 4-4h7.2a4 4 0 0 1 4 4v7.2a4 4 0 0 1-4 4H8.4a4 4 0 0 1-4-4V8.4Z") +
        dot(9f, 9f) + dot(15f, 9f) + dot(9f, 15f) + dot(15f, 15f)

    /** A crown, drawn flat: Pro. */
    val Crown = stroked("M4 8.2l3.4 2.6L12 4.6l4.6 6.2L20 8.2l-1.5 9.2h-13L4 8.2Z", width = 1.4f)

    /** Framing / expand corners: media viewer zoom toggle. */
    val Zoom = stroked(
        "M4.6 9.2V4.6h4.6M19.4 9.2V4.6h-4.6M4.6 14.8v4.6h4.6M19.4 14.8v4.6h-4.6",
        width = 1.6f,
    )

    // ── places and gestures ───────────────────────────────────────────────
    // Drawn for the migration wizard's step illustrations (§6.2), where the picture has to say
    // *where* the user is — on a desktop, in a browser, in a menu — not just what the step is about.

    /** A monitor on a stand: "do this on a computer, not the phone". */
    val Desktop = stroked(
        "M4 6.4a1.8 1.8 0 0 1 1.8-1.8h12.4A1.8 1.8 0 0 1 20 6.4v7.8a1.8 1.8 0 0 1-1.8 1.8H5.8A1.8 1.8 0 0 1 4 14.2V6.4Z",
        "M12 16v3.4",
        "M9 19.4h6",
        width = 1.55f,
    )

    /** A handset: "do this on the phone in your hand". */
    val Phone = stroked(
        "M7.8 3.4h8.4a1.6 1.6 0 0 1 1.6 1.6v14a1.6 1.6 0 0 1-1.6 1.6H7.8a1.6 1.6 0 0 1-1.6-1.6V5a1.6 1.6 0 0 1 1.6-1.6Z",
        "M10.7 17.4h2.6",
        width = 1.55f,
    )

    /** An arrow settling into a tray: saving the export where you can find it again. */
    val Download = stroked(
        "M12 4.6v9.2",
        "M8.8 10.6 12 13.8l3.2-3.2",
        "M5.2 15.6v2.2a1.8 1.8 0 0 0 1.8 1.8h10a1.8 1.8 0 0 0 1.8-1.8v-2.2",
        width = 1.55f,
    )

    /** A table with a marked header row: the CSV column-matching step. */
    val Table = stroked(
        "M4.6 7.4a2 2 0 0 1 2-2h10.8a2 2 0 0 1 2 2v9.2a2 2 0 0 1-2 2H6.6a2 2 0 0 1-2-2V7.4Z",
        "M4.6 9.8h14.8",
        "M10.4 9.8v8.8",
        width = 1.55f,
    )

    /** The overflow affordance the steps keep referring to as ⋯ or ⋮. */
    val MoreDots = dot(6.6f, 12f) + dot(12f, 12f) + dot(17.4f, 12f)

    // ── media transport ───────────────────────────────────────────────────
    // Outlined like the rest of the set rather than the solid triangle/bars a media app usually
    // draws. A filled glyph next to these strokes reads as a different app's control.

    val Play = stroked("M8.8 6.2 18 12l-9.2 5.8V6.2Z", width = 1.7f)

    val Pause = stroked("M9.6 6.4v11.2", "M14.4 6.4v11.2", width = 2f)

    /** A circling arrow: start the clip again once it has ended. */
    val Replay = stroked(
        "M19.4 12a7.4 7.4 0 1 1-2.4-5.5",
        "M19.6 4.4v3.8h-3.8",
        width = 1.55f,
    )

    /**
     * Put the previous value back — a left-turning arrow, the mirror of [Replay].
     *
     * Distinct from [Replay] on purpose even though they are the same stroke reflected: Replay
     * means "do that again", Undo means "unmake what just happened", and the password field now
     * shows one of them next to the control that armed it. Two glyphs pointing the same way for
     * opposite acts is the collision this icon set already gets wrong elsewhere.
     */
    /**
     * A clock with a backward arrow: go to how things were at a point in time.
     *
     * Restoring a snapshot used the plain [Refresh] circle, which [is] the Generator's glyph — the
     * two sat four rows apart in the drawer's TOOLS section drawing one shape for two unrelated
     * destinations, so a user scanning by icon could not tell "make me a password" from "replace my
     * vault with an older copy". Refresh stays with the Generator, where the bottom bar uses it too.
     */
    val History = stroked(
        "M4.6 12a7.4 7.4 0 1 0 2.4-5.5",
        "M4.4 4.4v3.8h3.8",
        "M12 7.6V12l3 1.8",
        width = 1.55f,
    )

    val Undo = stroked(
        "M4.6 12a7.4 7.4 0 1 0 2.4-5.5",
        "M4.4 4.4v3.8h3.8",
        width = 1.55f,
    )
}

// ── construction ──────────────────────────────────────────────────────────

private const val VIEWPORT = 24f

/**
 * Builds a 24×24 stroke-only [ImageVector] from SVG path data.
 *
 * Stroke is opaque white so that `Icon`'s tint — which is applied as a colour filter over the
 * finished raster — lands at full strength. Giving the paths their final colour here instead would
 * make every icon un-tintable, which is exactly what an accent-driven palette cannot afford.
 */
/**
 * @param autoMirror set on any glyph whose meaning is "the direction you came from" or "the
 *  direction this goes". `supportsRtl` is declared in the manifest and the layout itself flips
 *  correctly — every padding in the system is start/end — but a hand-drawn chevron is just a path,
 *  and a Back control whose arrow points at the screen you are already on is worse than no arrow.
 *  Icons that mean a *thing* rather than a direction (a shield, a globe) must stay unmirrored.
 */
private fun stroked(
    vararg pathData: String,
    width: Float = 1.6f,
    autoMirror: Boolean = false,
): ImageVector =
    ImageVector.Builder(
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = VIEWPORT,
        viewportHeight = VIEWPORT,
        autoMirror = autoMirror,
    ).apply {
        pathData.forEach { addStroke(it, width) }
    }.build()

private const val STAR_PATH = "m12 3.6 2.6 5.5 6 .8-4.4 4.2 1.1 6-5.3-2.9-5.3 2.9 1.1-6L3.4 9.9l6-.8L12 3.6Z"

/**
 * One closed path, filled and stroked in the same opaque white, so it tints like the outline set but
 * reads as a solid. The stroke keeps its outer edge on the outline's edge, so swapping [stroked] for
 * this does not make the glyph look smaller or larger.
 */
private fun filled(pathData: String, width: Float = 1.6f): ImageVector =
    ImageVector.Builder(
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = VIEWPORT,
        viewportHeight = VIEWPORT,
    ).apply {
        addPath(
            pathData = PathParser().parsePathString(pathData).toNodes(),
            fill = SolidColor(Color.White),
            stroke = SolidColor(Color.White),
            strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }.build()

/** Appends more strokes to an already-built vector. Lets a definition read as one expression. */
private operator fun ImageVector.plus(other: ImageVector): ImageVector =
    ImageVector.Builder(
        defaultWidth = defaultWidth,
        defaultHeight = defaultHeight,
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
    ).apply {
        copyGroup(this@plus)
        copyGroup(other)
    }.build()

private fun ImageVector.Builder.copyGroup(source: ImageVector) {
    source.root.forEach { node ->
        if (node is androidx.compose.ui.graphics.vector.VectorPath) {
            addPath(
                pathData = node.pathData,
                stroke = node.stroke,
                strokeLineWidth = node.strokeLineWidth,
                strokeLineCap = node.strokeLineCap,
                strokeLineJoin = node.strokeLineJoin,
            )
        }
    }
}

private fun ImageVector.Builder.addStroke(pathData: String, width: Float) {
    addPath(
        pathData = PathParser().parsePathString(pathData).toNodes(),
        stroke = SolidColor(Color.White),
        strokeLineWidth = width,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    )
}

/**
 * A circle as four arcs.
 *
 * [PathParser] handles `A` commands, and writing circles this way keeps every icon a single
 * mechanism — no mixing of parsed paths with programmatic shapes that would need separate tinting.
 */
private fun circle(cx: Float, cy: Float, r: Float, width: Float = 1.6f): ImageVector = stroked(
    "M${cx - r} ${cy}a$r $r 0 1 0 ${r * 2} 0a$r $r 0 1 0 ${-r * 2} 0",
    width = width,
)

/** A filled-looking dot, drawn as a very small stroked circle so it tints with everything else. */
private fun dot(cx: Float, cy: Float): ImageVector = circle(cx, cy, 0.6f, width = 1.2f)
