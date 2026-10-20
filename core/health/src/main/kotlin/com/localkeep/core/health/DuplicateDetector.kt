package com.localkeep.core.health

import com.localkeep.core.common.UrlHosts
import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem

/**
 * Finds the same *account* entered into the vault more than once.
 *
 * Not the same thing as [ReuseOldDetector.findReused], and the difference is the whole reason this
 * exists. Reuse is one password across several accounts — a security failure, because one breach
 * cascades. A duplicate is one account occupying several items, which is a correctness failure: you
 * change the password on one copy, the other keeps the old one, and autofill goes on offering both.
 * The user then has a 50% chance of being told their own password is wrong, with nothing on screen
 * explaining why. Imports are where these come from — bringing the same export in twice, or
 * migrating from two managers that each had the account.
 *
 * Free, on the same grounds that made weak and reused free: this is arithmetic over items the user
 * already has. There is nothing to ship, nothing to keep current, and nothing to pay for.
 */
object DuplicateDetector {

    /**
     * Groups non-trashed Login items that name the same account.
     *
     * Two items match when they share a normalised host **and** a normalised username. Both halves
     * are required: two logins on `github.com` under different usernames are two real accounts, and
     * the same username at two providers is one person with two accounts. Only groups of 2+ are
     * returned.
     *
     * A login with no URI at all falls back to its title, so two hand-typed "Netflix" entries are
     * still caught. Items with a blank username are skipped entirely rather than all colliding into
     * one enormous group — a missing username is not evidence of anything.
     *
     * Every item is keyed by *each* of its hosts, so an entry listing `example.com` and
     * `login.example.com` is compared against both. The consequence is that one item can appear in
     * more than one group; callers wanting a flat set of affected ids should flatten and dedupe,
     * which is what [SecurityDashboard] does.
     *
     * Since 2026-10-02 a port is part of the account when both sides name one: `admin` on
     * `192.168.1.10:8080` and `admin` on `192.168.1.10:9000` are two services on one box, and
     * calling them a duplicate sends the user to merge a real credential away. A side naming no
     * port still matches every port of the same host - `192.168.1.10` is as plausibly one of them
     * typed short - so a pair that [UrlHosts.normalise] has always joined stays joined.
     */
    fun findDuplicates(items: List<VaultItem>): Map<String, List<VaultItem>> =
        items.asSequence()
            .filter { !it.isDeleted }
            .flatMap { item ->
                val login = item.content as? Login ?: return@flatMap emptySequence()
                val username = login.username.trim().lowercase()
                if (username.isEmpty()) return@flatMap emptySequence()
                val sites = login.uris
                    .mapNotNull { uri ->
                        UrlHosts.normalise(uri.uri).takeIf { it.isNotEmpty() }
                            ?.let { host -> host to UrlHosts.explicitPort(uri.uri) }
                    }
                    .distinct()
                    .ifEmpty {
                        val title = item.title.trim().lowercase()
                        if (title.isEmpty()) emptyList() else listOf("title:$title" to null)
                    }
                sites.asSequence().map { (host, port) -> "$host\u0000$username" to (port to item) }
            }
            .groupBy({ it.first }, { it.second })
            .flatMap { (key, members) -> splitByPort(key, members) }
            // distinctBy: an item listing the same host twice under different match strategies must
            // not group with itself and report a duplicate that is one entry.
            .associate { (key, group) -> key to group.distinctBy { it.id } }
            .filterValues { it.size >= 2 }

    /**
     * One host+username bucket, split wherever its members name different ports.
     *
     * Members that name no port belong to every port's group, so a bare `host` still matches
     * `host:8080` and `host:9000` - and those two still do not match each other. With one distinct
     * port or none every member is compatible with every other, and the bucket stays whole.
     */
    private fun splitByPort(
        key: String,
        members: List<Pair<Int?, VaultItem>>,
    ): List<Pair<String, List<VaultItem>>> {
        val ports = members.mapNotNull { it.first }.distinct()
        if (ports.size <= 1) return listOf(key to members.map { it.second })
        val unported = members.filter { it.first == null }.map { it.second }
        return ports.map { port ->
            "$key:$port" to (members.filter { it.first == port }.map { it.second } + unported)
        }
    }
}
