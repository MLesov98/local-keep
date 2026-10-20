package com.localkeep.core.common

import java.net.URI

/**
 * The host of a URL, lowercased, with a leading `www.` removed — the identity two logins are
 * compared on.
 *
 * Shared because two places ask the same question and have to answer it the same way: the import
 * merger deciding whether an incoming login is the one already in the vault, and the security
 * dashboard's duplicate detector deciding whether two vault entries are the same account. When
 * they disagree the user sees the contradiction directly — an import that adds a second copy of a
 * login the dashboard then reports as a duplicate of the first.
 *
 * Deliberately the host and not the registrable domain. Getting from `login.example.co.uk` to
 * `example.co.uk` needs the Public Suffix List, which is a shipped, dated artefact — exactly the
 * kind of thing an offline app avoids depending on. Comparing full hosts is the conservative
 * direction: `mail.example.com` and `example.com` are treated as two accounts when they may be
 * one, which is a missed match rather than a wrong one.
 */
object UrlHosts {

    /** Empty for blank input. Never returns something that is not a host. */
    fun normalise(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val absolute = if ("://" in trimmed) trimmed else "https://$trimmed"
        // The fallback parses the authority by hand rather than reaching for the whole raw string.
        // `java.net.URI.host` is null more often than it looks — an underscore in the host makes it
        // registry-based, so `https://my_bank.example.com/login` returns null — and substituting
        // the *entire URL* for a host meant that entry and `https://my_bank.example.com` produced
        // two different keys and never matched. The one thing this function must not do is answer
        // with something that is not a host.
        val host = runCatching { URI(absolute).host }.getOrNull() ?: authorityOf(absolute)
        return host.lowercase().removePrefix("www.")
    }

    /**
     * The port a URL names explicitly, or null when it names none or only the ordinary web ones.
     *
     * [normalise] drops the port on purpose - it is the identity two logins are compared on, and
     * `example.com` and `example.com:8443` are plausibly one site typed two ways. But two services
     * on one box (`192.168.1.10:8080` and `192.168.1.10:9000`, a NAS, a router, a dev machine) are
     * not, and a caller that must not merge them asks for the port separately. 80 and 443 read as
     * "no port": `http://host:80` and `https://host:443` are the same site as the bare host.
     *
     * Kept apart from [normalise] rather than folded into it, because [normalise] also titles
     * untitled CSV rows and a port in a title would change what the user sees.
     */
    fun explicitPort(raw: String): Int? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val absolute = if ("://" in trimmed) trimmed else "https://$trimmed"
        val uri = runCatching { URI(absolute) }.getOrNull()
        // A registry-based authority (an underscore in the host) parses with host == null and
        // port == -1, so only trust the parsed port when the parse produced a host.
        val port = if (uri?.host != null) {
            uri.port.takeIf { it >= 0 }
        } else {
            val authority = authorityPart(absolute)
            val digits = if (authority.startsWith('[')) {
                authority.substringAfter(']', "").removePrefix(":")
            } else {
                authority.substringAfter(':', "")
            }
            digits.toIntOrNull()
        }
        return port?.takeUnless { it == 80 || it == 443 }
    }

    /** Host portion of an absolute URL: after the scheme, before the path, minus userinfo and port. */
    private fun authorityOf(url: String): String {
        val authority = authorityPart(url)
        // A bracketed IPv6 literal keeps its colons; everything else loses the port.
        return if (authority.startsWith('[')) {
            authority.substringBefore(']') + "]"
        } else {
            authority.substringBefore(':')
        }
    }

    /** Everything between the scheme and the path, minus userinfo: `host[:port]`. */
    private fun authorityPart(url: String): String = url.substringAfter("://")
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('@')
}
