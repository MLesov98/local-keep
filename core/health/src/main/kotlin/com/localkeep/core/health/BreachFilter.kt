package com.localkeep.core.health

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.math.BigInteger
import java.security.MessageDigest

internal const val BREACH_FILTER_MAGIC = 0x4C504246 // "LPBF"
internal const val BREACH_FILTER_FORMAT_VERSION = 1

// Upper bounds used only to reject an obviously-corrupt header before allocating from it (a real
// ~15-20MB asset is well under these). They exist so a bit-flipped length field fails fast as an
// IllegalArgumentException instead of triggering a giant/negative allocation. See load().
//
// TIGHTENED 2026-07-28, after the fuzz harness (`:test:fuzz`) produced a 78-byte input that passes
// every `require` here and then asks for a 320 MB array. The old bounds were authored for a
// desktop: 50,000,000 checkpoints is two LongArrays totalling 800 MB, and 512 MB of body is twice
// what a phone's whole heap tends to be. Both are now derived from the asset that actually ships
// rather than picked as "obviously too big":
//
//   body:        a real corpus body is ~15-20 MB, so 64 MB is 3x headroom.
//   checkpoints: count = entries / blockSize. At the default blockSize of 4096, even a billion-entry
//                corpus needs ~244,000; 1,000,000 is 4x headroom and costs at most 16 MB across the
//                two arrays.
//
// The bounds are only half the fix, and the smaller half. A bound cannot tell a truthful length from
// a lie — see readExactly/readLongsExactly below, which never allocate a declared length up front.
private const val MAX_SANE_VINTAGE_LEN = 4 * 1024
private const val MAX_SANE_CHECKPOINT_COUNT = 1_000_000
private const val MAX_SANE_BODY_LEN = 64 * 1024 * 1024

// Where an incremental read starts. Small enough that a lying length field costs almost nothing
// before EOFException, large enough that a legitimate 20 MB body doubles into place in ~9 steps.
private const val INITIAL_READ_CHUNK_BYTES = 64 * 1024
private const val INITIAL_READ_CHUNK_LONGS = 8 * 1024

/**
 * Opens the breach-filter payload, or returns `null` when this build ships none.
 * `:core:health` is pure Kotlin/JVM and has zero Android/Context knowledge, so it
 * never names a classpath path or an Android asset itself — the caller (`:feature:health`'s
 * Hilt wiring) decides where the bytes come from: the real Android asset at
 * `app/src/main/assets/breached-passwords.filter`, in both flavours.
 *
 * The nullable return is not dead. It was how `foss` said "no corpus ships here" before the corpus
 * moved into `src/main`; it stays because a source is still allowed to have nothing to open, and
 * because tests construct one that does.
 */
fun interface BreachFilterSource {
    /** Opens the filter payload, or returns null when there is none to open. */
    fun open(): InputStream?
}

/**
 * Offline, zero-network compromised-password membership test over a bundled
 * Golomb-Rice compressed set (GCS). `:core:health` never reads the asset itself: an
 * instance is constructed with a [BreachFilterSource] (injected by `:feature:health`'s
 * Hilt wiring), and this class has zero knowledge of Android assets, classpath
 * resources, or which flavor it's running in.
 *
 * Honesty contract (spec §12.7, non-negotiable):
 *   - a HIT means "potentially compromised" — false positives exist at the
 *     disclosed rate ([DISCLOSED_FALSE_POSITIVE_DENOMINATOR]).
 *   - a MISS means "not in the most-common-breached set" — NEVER "confirmed
 *     safe": the set is a curated subset of an 800M+ breach corpus.
 *   - **no data at all** (`hasData == false` — an asset that failed to open or
 *     to parse) is a THIRD, distinct state and must never be
 *     silently reported as a "miss". [ensureLoaded] never throws; callers that
 *     need to tell "not breached" apart from "no breach data available" (e.g.
 *     to hide the whole breach card) check [hasData].
 */
class BreachFilter(
    /**
     * Below this many entries, a loaded asset is treated as **no data at all** rather than as a
     * corpus.
     *
     * This exists because `checkBreachCorpusNotPlaceholder` — the build gate that stops the
     * placeholder shipping — protects the release process and not the user. The placeholder in
     * `app/src/main/assets/breached-passwords.filter` is 190 bytes and is a **valid, loadable
     * filter containing 33 passwords**, whose own vintage string reads "ad-hoc placeholder corpus
     * … replace before release". Nothing at runtime noticed: `hasData` was true, so the Security
     * screen rendered ordinary "not found in the breach set" verdicts against 33 passwords, and the
     * user read that as a check having happened.
     *
     * The rule is unambiguous — *"no 'checked against the breach set' when no check ran"* — and a
     * gate that can only be enforced at build time is one bypass away from making that claim untrue.
     * So the honesty is enforced here too, where the verdict is actually produced.
     *
     * 100,000 is deliberately far below any real corpus (millions) and far above any plausible
     * fixture, so it separates "someone shipped a stub" from "someone shipped a corpus" without
     * needing to be tuned. Tests that build a small fixture pass `0` explicitly.
     */
    private val minimumCredibleEntries: Long = MIN_CREDIBLE_ENTRY_COUNT,
    // Last on purpose, so every existing `BreachFilter { ... }` SAM call site keeps
    // compiling: a trailing lambda binds to the last parameter. Putting the threshold
    // second broke six call sites for no benefit.
    private val source: BreachFilterSource,
) {

    companion object {
        /** See [minimumCredibleEntries]. */
        const val MIN_CREDIBLE_ENTRY_COUNT: Long = 100_000L

        /** Golomb-Rice parameter. Universe size = entryCount * 2^riceK. */
        const val DEFAULT_RICE_K = 10

        /** Entries per checkpoint — bounds the worst-case linear decode per lookup. */
        const val DEFAULT_BLOCK_SIZE = 4096

        /** Disclosed false-positive rate: 1-in-2^riceK per lookup (≈0.098% at the default k=10). */
        val DISCLOSED_FALSE_POSITIVE_DENOMINATOR: Long get() = 1L shl DEFAULT_RICE_K
    }

    private data class LoadedFilter(
        val universeSize: Long,
        val entryCount: Long,
        val riceK: Int,
        val blockSize: Int,
        val vintage: String,
        val checkpointValues: LongArray,
        val checkpointBitOffsets: LongArray,
        val body: ByteArray,
    )

    @Volatile private var loaded: LoadedFilter? = null
    @Volatile private var attempted: Boolean = false

    /**
     * True once the outcome cannot change by reading the same asset again — this build ships none,
     * the bytes are corrupt or truncated, or what loaded was the placeholder. [retryLoadIfTransientlyFailed]
     * leaves these alone; anything else (an I/O blip, an [OutOfMemoryError] under pressure) is
     * allowed a fresh attempt.
     */
    @Volatile private var terminal: Boolean = false

    fun isLoaded(): Boolean = loaded != null

    /**
     * True once a real filter is loaded. False in `foss` (source returns null), if opening/parsing
     * failed, or — see [minimumCredibleEntries] — if what loaded was a placeholder.
     */
    val hasData: Boolean get() = loaded != null

    /** Human-readable vintage baked into the asset (e.g. "corpus snapshot 2026-05, app v1.4"); "not loaded" when [hasData] is false. */
    fun filterVintage(): String = loaded?.vintage ?: "not loaded"

    /**
     * How many distinct entries the loaded filter holds; 0 when [hasData] is false.
     *
     * This is the header's post-dedup bucket count, so it is a **lower bound** on the number of
     * passwords the corpus was built from — two passwords that collide into one bucket are counted
     * once. That direction is deliberate and is why this is the number the UI states rather than
     * `universeSize shr riceK` (the pre-dedup input size): a size claim on a security screen may
     * understate what the app checked against, never overstate it.
     */
    fun filterEntryCount(): Long = loaded?.entryCount ?: 0L

    /**
     * Asks [source] for the payload and loads it, unless already attempted. Safe to call
     * repeatedly. NEVER throws: a null source (`foss`), an I/O failure, or a malformed asset
     * all leave [hasData] `false` instead of crashing the caller — the feature is Pro-gated
     * off in `foss` regardless, so "no data" here is an expected, silent no-op, not an error.
     *
     * A **placeholder** asset is discarded here and reported as no data. See
     * [minimumCredibleEntries]: the shipped stub parses perfectly and holds 33 passwords, so
     * without this the Security screen would render genuine-looking "not in the breach set"
     * verdicts having checked essentially nothing.
     */
    fun ensureLoaded() {
        // Fast path, no lock: both fields are @Volatile and every lookup comes through here.
        if (loaded != null || attempted) return
        // Locked, because a retry can now happen and two of them must not decode at once. Before
        // retries existed, one failure latched forever, so a process made at most one attempt and
        // the racy check-then-set could not produce concurrent loads. It can now — a health scan
        // and the settings screen ask independently — and two simultaneous ~15-20 MB decodes are
        // the memory pressure this whole fix is about.
        synchronized(this) {
            if (loaded != null || attempted) return
            attempted = true
            try {
                // `use`, not a bare open: `load` reads the stream and never closes it, so every path
                // through here — success, a failed `require`, or the catch below — leaked the
                // AssetManager file handle the play flavour's source hands over. Once per process
                // rather than per lookup, because `attempted` gates re-entry and the instance is a Hilt
                // singleton, but a file handle held open for the life of the process is not one to
                // keep.
                val stream = source.open()
                if (stream == null) {
                    // This build ships no asset at all. Nothing to retry.
                    terminal = true
                    return
                }
                stream.use { load(it) }
                val entries = loaded?.entryCount ?: 0
                if (entries < minimumCredibleEntries) {
                    // The placeholder parses perfectly, so it arrives here rather than in the catch.
                    // It will parse identically next time, hence terminal.
                    loaded = null
                    terminal = true
                }
            } catch (t: Throwable) {
                // The asset is untrusted (may be bit-rotted/truncated). The class contract is to
                // DEGRADE TO "no data", never crash — so this deliberately catches Throwable, not just
                // Exception: a corrupted length field can yield NegativeArraySizeException or, for a
                // huge positive value, OutOfMemoryError (both would otherwise escape and crash the app).
                loaded = null
                if (isTerminalFailure(t)) terminal = true
            }
        }
    }

    /**
     * Whether re-reading the same asset could produce a different answer.
     *
     * The distinction is the whole point of [retryLoadIfTransientlyFailed]: a corrupt asset must
     * latch (or the app re-decodes ~15-20 MB on every scan forever), and a transient failure must
     * not (or one unlucky moment decides the answer for the life of the process).
     */
    private fun isTerminalFailure(t: Throwable): Boolean = when (t) {
        // A failed `require` in load(): bad magic, unsupported version, a corrupt length, a
        // checkpoint offset outside the body. The same bytes fail identically every time.
        is IllegalArgumentException -> true
        // A corrupt length field taken at face value.
        is NegativeArraySizeException -> true
        // Truncated asset: readExactly/readLongsExactly ran out of stream.
        is EOFException -> true
        // OutOfMemoryError, IOException, anything else. The asset may well be intact and a later
        // attempt may succeed — so this must not decide the answer for the whole process.
        else -> false
    }

    /**
     * Clears a previous *transient* failure so the next [ensureLoaded] tries again. A terminal
     * outcome — no asset in this build, a corrupt or truncated one, or the placeholder — is left
     * alone, because re-reading the same bytes cannot change it.
     *
     * Call this once per scan, not per lookup. [isPotentiallyBreached] calls [ensureLoaded] for
     * every password it checks, and retrying there would re-decode the whole corpus once per
     * password.
     *
     * Exists because `attempted` used to be set before the attempt and never cleared, so the first
     * failure decided `hasData` for the life of the process. On a foss debug build (2026-09-01) an
     * import was enough to trigger it: `HealthDashboardViewModel.refresh()` runs once per vault
     * commit, several fire during an import, one lost the race, and Password health then told the
     * user "The breach list could not be read on this device" until the app was force-stopped —
     * a claim about their device that a restart disproved.
     */
    fun retryLoadIfTransientlyFailed() {
        if (loaded == null && !terminal) attempted = false
    }

    /** Loads a filter asset from an arbitrary stream — the real asset, or a test fixture. */
    fun load(input: InputStream) {
        val din = DataInputStream(input.buffered())
        require(din.readInt() == BREACH_FILTER_MAGIC) { "not a breach-filter asset (bad magic)" }
        val version = din.readInt()
        require(version == BREACH_FILTER_FORMAT_VERSION) { "unsupported breach-filter format version: $version" }
        val universeSize = din.readLong()
        val entryCount = din.readLong()
        val riceK = din.readInt()
        val vintageLen = din.readInt()
        require(vintageLen in 0..MAX_SANE_VINTAGE_LEN) { "corrupt vintage length: $vintageLen" }
        val vintageBytes = ByteArray(vintageLen).also { din.readFully(it) }
        val blockSize = din.readInt()
        require(blockSize > 0) { "corrupt block size: $blockSize" }
        val checkpointCount = din.readInt()
        require(checkpointCount in 0..MAX_SANE_CHECKPOINT_COUNT) { "corrupt checkpoint count: $checkpointCount" }
        val checkpointValues = din.readLongsExactly(checkpointCount)
        val checkpointBitOffsets = din.readLongsExactly(checkpointCount)
        val bodyLen = din.readInt()
        require(bodyLen in 0..MAX_SANE_BODY_LEN) { "corrupt body length: $bodyLen" }
        // Every checkpoint bit-offset must land inside the body — else a query would index out of
        // range mid-probe. Validate up front so a corrupt directory degrades to "no data".
        val bodyBits = bodyLen.toLong() * 8
        require(checkpointBitOffsets.all { it in 0..bodyBits }) { "checkpoint offset outside body" }
        val body = din.readExactly(bodyLen)

        loaded = LoadedFilter(
            universeSize = universeSize,
            entryCount = entryCount,
            riceK = riceK,
            blockSize = blockSize,
            vintage = String(vintageBytes, Charsets.UTF_8),
            checkpointValues = checkpointValues,
            checkpointBitOffsets = checkpointBitOffsets,
            body = body,
        )
        attempted = true
    }

    /**
     * Returns true if [password] hashes into a bucket present in the compressed set.
     * Returns `false` (never throws) when [hasData] is false — check [hasData] first if the
     * caller needs to distinguish "not breached" from "no breach data available at all".
     * See the class doc for the mandatory non-absolute wording when surfacing a `true` result.
     */
    fun isPotentiallyBreached(password: String): Boolean {
        ensureLoaded()
        val filter = loaded ?: return false
        val target = breachBucketIndex(password, filter.universeSize)
        // Defense in depth: even a header that passed load()'s validation could still be internally
        // inconsistent enough for the Rice decode to index past the body. The contract is "never
        // throw" — so a corrupt-body probe degrades to "not found", never a crash mid-lookup.
        return try {
            probe(filter, target)
        } catch (t: Throwable) {
            false
        }
    }

    private fun probe(filter: LoadedFilter, target: Long): Boolean {
        // Binary search the checkpoint directory for the last block whose start value <= target.
        var lo = 0
        var hi = filter.checkpointValues.size - 1
        var block = 0
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (filter.checkpointValues[mid] <= target) {
                block = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }

        var running = filter.checkpointValues[block]
        if (running == target) return true

        // At most (blockSize - 1) more entries exist before the next checkpoint (or before
        // entryCount, for the final block) — bounds the linear decode precisely.
        val entriesInBlock = minOf(
            (filter.blockSize - 1).toLong(),
            filter.entryCount - 1 - block.toLong() * filter.blockSize,
        ).toInt()
        if (entriesInBlock <= 0) return false

        val reader = RiceBitReader(filter.body, filter.checkpointBitOffsets[block])
        repeat(entriesInBlock) {
            running += reader.readRice(filter.riceK)
            if (running == target) return true
            if (running > target) return false
        }
        return false
    }
}

/**
 * Maps [password] into `[0, universeSize)` via the first 8 bytes of its SHA-256
 * digest, treated as an unsigned 64-bit integer and rescaled by a wide multiply
 * (`hash * universeSize >>> 64`) — the standard fixed-point technique for a
 * modulo-bias-free hash-to-range mapping. MUST be byte-identical at asset-build
 * time ([BreachFilterAssetBuilder]) and at query time ([BreachFilter]), or every
 * lookup silently misses. Not perf-critical (called once per password check,
 * never in a hot loop), so `BigInteger` for the 64x64→128-bit multiply is fine.
 */
internal fun breachBucketIndex(password: String, universeSize: Long): Long {
    val digest = MessageDigest.getInstance("SHA-256").digest(password.toByteArray(Charsets.UTF_8))
    val unsignedHash64 = BigInteger(1, digest.copyOfRange(0, 8))
    return unsignedHash64.multiply(BigInteger.valueOf(universeSize)).shiftRight(64).toLong()
}

/**
 * Reads exactly [count] bytes, growing into the read instead of allocating [count] up front.
 *
 * `ByteArray(count)` is the obvious spelling and it is the bug. [count] is read out of the file, so
 * it is a claim, not a fact: the fuzz harness produced a **78-byte input declaring a 320 MB body**
 * that passed every bounds check above and then exhausted the heap — before `readFully` had a
 * chance to discover the stream held 18 bytes. Growing by doubling means a length the stream cannot
 * honour costs one 64 KB buffer and an `EOFException`, and a legitimate 20 MB body still arrives in
 * about nine steps.
 *
 * Committed reproducer: `test/fuzz/src/test/resources/.../BreachFilterFuzzTestInputs/load/crash-4ab334c0…`.
 */
private fun DataInputStream.readExactly(count: Int): ByteArray {
    if (count == 0) return ByteArray(0)
    var buffer = ByteArray(minOf(count, INITIAL_READ_CHUNK_BYTES))
    var filled = 0
    while (filled < count) {
        if (filled == buffer.size) buffer = buffer.copyOf(minOf(count, buffer.size * 2))
        readFully(buffer, filled, buffer.size - filled)
        filled = buffer.size
    }
    return buffer
}

/** [readExactly] for the checkpoint directories, which have the same allocate-from-a-claim shape. */
private fun DataInputStream.readLongsExactly(count: Int): LongArray {
    if (count == 0) return LongArray(0)
    var buffer = LongArray(minOf(count, INITIAL_READ_CHUNK_LONGS))
    var filled = 0
    while (filled < count) {
        if (filled == buffer.size) buffer = buffer.copyOf(minOf(count, buffer.size * 2))
        while (filled < buffer.size) buffer[filled++] = readLong()
    }
    return buffer
}

/**
 * Builds a Golomb-Rice compressed-set asset from a plaintext password corpus.
 * OFFLINE USE ONLY — never invoked by the running app (no network, no live
 * corpus fetch here or anywhere in this module). See [BuildBreachFilterAsset]
 * for the maintainer-run tool that calls this.
 */
object BreachFilterAssetBuilder {

    fun build(
        passwords: List<String>,
        riceK: Int = BreachFilter.DEFAULT_RICE_K,
        blockSize: Int = BreachFilter.DEFAULT_BLOCK_SIZE,
        vintage: String,
    ): ByteArray {
        require(passwords.isNotEmpty()) { "corpus must not be empty" }
        // universeSize is derived from the PRE-dedup corpus size and stored explicitly in the
        // header — recomputing it later from the post-dedup entry count would silently shift
        // every bucket boundary and break every lookup.
        val universeSize = passwords.size.toLong() shl riceK

        val sortedBuckets = passwords.asSequence()
            .map { breachBucketIndex(it, universeSize) }
            .distinct()
            .sorted()
            .toList()

        val writer = RiceBitWriter()
        val checkpointValues = mutableListOf<Long>()
        val checkpointBitOffsets = mutableListOf<Long>()
        var previous = 0L
        sortedBuckets.forEachIndexed { index, value ->
            writer.writeRice(value - previous, riceK)
            previous = value
            if (index % blockSize == 0) {
                checkpointValues += value
                checkpointBitOffsets += writer.bitPosition()
            }
        }
        val body = writer.toByteArray()

        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { dos ->
            dos.writeInt(BREACH_FILTER_MAGIC)
            dos.writeInt(BREACH_FILTER_FORMAT_VERSION)
            dos.writeLong(universeSize)
            dos.writeLong(sortedBuckets.size.toLong())
            dos.writeInt(riceK)
            val vintageBytes = vintage.toByteArray(Charsets.UTF_8)
            dos.writeInt(vintageBytes.size)
            dos.write(vintageBytes)
            dos.writeInt(blockSize)
            dos.writeInt(checkpointValues.size)
            checkpointValues.forEach { dos.writeLong(it) }
            checkpointBitOffsets.forEach { dos.writeLong(it) }
            dos.writeInt(body.size)
            dos.write(body)
        }
        return out.toByteArray()
    }
}

/** Reads Golomb-Rice codes: unary quotient (zero-bits terminated by a one-bit) + a k-bit binary remainder. */
internal class RiceBitReader(private val data: ByteArray, startBit: Long) {
    private var bitPos = startBit

    fun readRice(k: Int): Long {
        var quotient = 0L
        while (readBit() == 0) quotient++
        var remainder = 0L
        repeat(k) { remainder = (remainder shl 1) or readBit().toLong() }
        return (quotient shl k) or remainder
    }

    private fun readBit(): Int {
        val byteIndex = (bitPos / 8).toInt()
        val bitIndex = 7 - (bitPos % 8).toInt()
        bitPos++
        return (data[byteIndex].toInt() shr bitIndex) and 1
    }
}

/** Writes Golomb-Rice codes. OFFLINE USE ONLY, paired 1:1 with [RiceBitReader]'s bit convention. */
internal class RiceBitWriter {
    private val bytes = mutableListOf<Byte>()
    private var currentByte = 0
    private var bitsInCurrentByte = 0
    private var totalBitsWritten = 0L

    /** Bit position where the *next* write will begin — used as a block's checkpoint offset. */
    fun bitPosition(): Long = totalBitsWritten

    fun writeRice(value: Long, k: Int) {
        require(value >= 0) { "Rice-coded deltas must be non-negative — is the input sorted with no repeats?" }
        val quotient = value ushr k
        repeat(quotient.toInt()) { writeBit(0) }
        writeBit(1)
        for (i in k - 1 downTo 0) writeBit(((value ushr i) and 1L).toInt())
    }

    private fun writeBit(bit: Int) {
        currentByte = (currentByte shl 1) or bit
        bitsInCurrentByte++
        totalBitsWritten++
        if (bitsInCurrentByte == 8) {
            bytes.add(currentByte.toByte())
            currentByte = 0
            bitsInCurrentByte = 0
        }
    }

    fun toByteArray(): ByteArray {
        if (bitsInCurrentByte > 0) {
            bytes.add((currentByte shl (8 - bitsInCurrentByte)).toByte())
            bitsInCurrentByte = 0
        }
        return bytes.toByteArray()
    }
}
