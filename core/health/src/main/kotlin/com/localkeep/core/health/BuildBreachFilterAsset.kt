package com.localkeep.core.health

import java.io.File

/**
 * Maintainer-run OFFLINE tool. Produces `breached-passwords.filter` from a
 * plaintext password corpus (one password per line, UTF-8). Never invoked by
 * the shipped app or by any CI job — run manually, before each release that
 * refreshes the vintage:
 *
 *   ./gradlew :core:health:compileKotlin
 *   kotlin -cp <core:health runtime classpath> \
 *       com.localkeep.core.health.BuildBreachFilterAssetKt \
 *       corpus.txt app/src/main/assets/breached-passwords.filter \
 *       "corpus snapshot 2026-05, app v1.4"
 *
 * Licensing/sourcing note: the input corpus must come from a source the
 * maintainer is legally entitled to redistribute a derived (hashed, bucketed,
 * lossy) form of. This tool never fetches anything itself — zero network,
 * always, matching every other line of code in this module.
 */
fun main(args: Array<String>) {
    require(args.size == 3) { "usage: BuildBreachFilterAsset <corpus.txt> <output.filter> <vintage string>" }
    val (corpusPath, outputPath, vintage) = args

    val passwords = File(corpusPath).useLines { lines -> lines.filter { it.isNotBlank() }.toList() }
    println("loaded ${passwords.size} candidate passwords from $corpusPath")

    val assetBytes = BreachFilterAssetBuilder.build(passwords, vintage = vintage)
    File(outputPath).writeBytes(assetBytes)

    println("wrote ${assetBytes.size} bytes (~${assetBytes.size / 1_000_000} MB) to $outputPath")
}
