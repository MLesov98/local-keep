package com.localkeep.core.import

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.Base64
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads a KeePass key file down to the 32 bytes it contributes to the composite key.
 *
 * Written here rather than delegated to KeePassJava2's own `KdbxKeyFile`, which resolves its
 * `<Key><Data>` element through `javax.xml.xpath` and never checks the v2.0 `Hash` attribute. DOM
 * is what the rest of this module already reads KDBX with (see [KdbxImporter]'s `dom` flavour note
 * in `build.gradle.kts`), so it is the backend known to run on Android.
 *
 * The four shapes, in the order KeePass tries them:
 *
 *  1. **XML key file** — `<KeyFile><Key><Data>`. Version `1.x` carries base64; version `2.x`
 *     (`.keyx`) carries whitespace-separated hex plus an optional `Hash` attribute, which is the
 *     first four bytes of SHA-256 over the key and is *verified* here: a `.keyx` whose hash does
 *     not match is a damaged key file, and silently deriving a key from it would answer "wrong
 *     password" for a file the user has not mistyped.
 *  2. **Exactly 32 bytes** — the key, raw.
 *  3. **Exactly 64 hexadecimal characters** — KeePass 1.x's legacy form, decoded.
 *  4. **Anything else** — SHA-256 of the whole file, which is how KeePass lets any file at all act
 *     as a key file.
 *
 * Every array this returns is secret material and is wiped by its caller ([KdbxImporter.parse]).
 */
internal object KdbxKeyFile {

    /**
     * The picked file is not usable as a key file: a `.keyx` whose `Hash` attribute does not match
     * its own data, or key material that will not decode. The single exception type this object
     * raises, so [KdbxImporter.describeFailure] can tell a bad key file apart from everything the
     * KDBX library throws.
     */
    internal class Unreadable(message: String) : IllegalArgumentException(message)

    fun keyBytes(fileBytes: ByteArray): ByteArray {
        val xml = try {
            xmlKeyBytes(fileBytes)
        } catch (e: Unreadable) {
            throw e
        } catch (e: RuntimeException) {
            // Base64 and hex decoders answer a malformed `<Data>` with their own exception types.
            throw Unreadable("key file data could not be decoded: ${e.javaClass.simpleName}")
        }
        if (xml != null) return xml
        return when {
            fileBytes.size == KEY_LEN -> fileBytes.copyOf()
            fileBytes.size == KEY_LEN * 2 && fileBytes.all { it.isAsciiHexDigit() } ->
                decodeHex(String(fileBytes, Charsets.US_ASCII))
            // KeePass lets any file at all be a key file, so there is no "unreadable" case left.
            else -> sha256().digest(fileBytes)
        }
    }

    /**
     * Returns null when the bytes are not a `<KeyFile>` document at all, so the byte-length rules
     * above still get their turn. An [Unreadable] is *not* a "not XML" answer and propagates.
     */
    private fun xmlKeyBytes(fileBytes: ByteArray): ByteArray? {
        val root = runCatching { parseXml(fileBytes) }.getOrNull() ?: return null
        if (root.tagName != "KeyFile") return null
        val key = child(root, "Key") ?: return null
        val data = child(key, "Data") ?: return null
        val version = child(root, "Meta")?.let { child(it, "Version") }?.textContent?.trim().orEmpty()
        val text = data.textContent.orEmpty()
        // Version 2 is hex with the whitespace the format lays out in blocks of four; version 1
        // (and anything unversioned, which is how the oldest files read) is base64.
        val raw = if (version.startsWith("2")) {
            decodeHex(text.filterNot { it.isWhitespace() })
        } else {
            Base64.getMimeDecoder().decode(text.trim())
        }
        val expected = data.getAttribute("Hash").orEmpty().trim()
        if (expected.isNotEmpty()) {
            val actual = sha256().digest(raw).copyOfRange(0, expected.length / 2)
            if (!actual.contentEquals(decodeHex(expected))) {
                raw.fill(0)
                throw Unreadable("key file hash does not match its data")
            }
        }
        return raw
    }

    private fun parseXml(fileBytes: ByteArray): Element {
        val factory = DocumentBuilderFactory.newInstance()
        // The key file is user-picked and untrusted: no entity resolution, no external anything.
        runCatching { factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "") }
        runCatching { factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "") }
        factory.isExpandEntityReferences = false
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(fileBytes)).documentElement
    }

    private fun child(parent: Element, name: String): Element? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node is Element && node.tagName == name) return node
        }
        return null
    }

    private fun decodeHex(text: String): ByteArray {
        if (text.length % 2 != 0) throw Unreadable("hex key material has an odd length")
        val out = ByteArray(text.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(text[i * 2], 16)
            val lo = Character.digit(text[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) throw Unreadable("key material is not hexadecimal")
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun Byte.isAsciiHexDigit(): Boolean = Character.digit(toInt().toChar(), 16) >= 0

    private fun sha256(): MessageDigest = MessageDigest.getInstance("SHA-256")

    private const val KEY_LEN = 32
}
