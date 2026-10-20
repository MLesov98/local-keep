package com.localkeep.core.vault

import com.localkeep.core.crypto.AesGcmSivAead
import com.localkeep.core.crypto.CryptoConstants
import com.localkeep.core.crypto.KdfParams
import com.localkeep.core.crypto.SecureRandomProvider
import com.localkeep.core.crypto.VaultSubkeys
import com.localkeep.core.crypto.WrappedDek
import com.localkeep.core.crypto.Zeroizer
import com.localkeep.core.model.Vault
import com.localkeep.core.model.VaultSerializer
import java.time.Instant

/** Version-1 vault container codec. Password derivation and filesystem IO live elsewhere. */
object VaultFileCodec {
    /**
     * The smallest bucket this codec will *decode*. Deliberately not the same as
     * [SAVE_MIN_PLAINTEXT_BUCKET]: every vault written before 2026-09-04 has a bucket sized to its
     * own contents, often 8 KiB or less, and raising this constant would refuse to open all of them.
     */
    const val MIN_PLAINTEXT_BUCKET: Int = 256

    /**
     * The floor every *save* pads to, whether or not a decoy is configured — and the "whether or
     * not" is the entire point.
     *
     * This value used to be applied only by `setupDuress`, through a `DURESS_MIN_PLAINTEXT_BUCKET`
     * constant that had no doc explaining it. Its purpose was headroom: regions are forced to equal
     * length, a decoy can only be re-bucketed by someone holding both keys, and a decoy that
     * outgrows its region raises `VaultBucketExhaustedException`. Sound reasoning — but applying it
     * to one case and not the other turned it into the tell it was meant to prevent. A vault with no
     * decoy bucketed to its own contents (~16 KB for a small vault); configuring duress jumped the
     * file to ~2 MB and it stayed there, because every later save goes through `replaceRegion`,
     * which keeps the bucket. For any real vault under 1 MiB — which is essentially all of them, at
     * the ~840 bytes/item this project measured — **"vault.enc is ~2 MB" meant "a decoy exists"**,
     * from a single file, with no differential and no timing. Not even root: Android's own
     * Settings → Apps → Storage shows the jump.
     *
     * Applied to every save, the size says nothing about duress. The cost is real and accepted: a
     * new vault is ~2 MB rather than ~16 KB, and the rolling snapshots plus `vault.enc.bak` carry
     * the same floor. Existing vaults converge on their next save.
     *
     * Note this closes the *size* oracle only. Which region a save rewrites was a separate signal,
     * closed for the configured-but-unused case by [commitSave] -- see its doc for the residual.
     */
    const val SAVE_MIN_PLAINTEXT_BUCKET: Int = 1 shl 20
    private const val LENGTH_PREFIX_SIZE = Int.SIZE_BYTES
    private const val MAX_PLAINTEXT_BUCKET = 1 shl 29

    /** Encrypts [vault] into region 0 and writes authenticated random filler into region 1. */
    fun encrypt(
        vault: Vault,
        encKey: ByteArray,
        prevHeader: VaultHeader,
        recoveryBlock: ByteArray,
    ): ByteArray = encryptBothRegions(
        realVault = vault,
        encKeyReal = encKey,
        decoyVault = null,
        encKeyDecoy = null,
        prevHeader = prevHeader,
        recoveryBlock = recoveryBlock,
    )

    /**
     * Encrypts both payload regions under independent keys when [decoyVault] is present.
     * Without a decoy, region 1 is random filler encrypted with [encKeyReal].
     */
    fun encryptBothRegions(
        realVault: Vault,
        encKeyReal: ByteArray,
        decoyVault: Vault?,
        encKeyDecoy: ByteArray?,
        prevHeader: VaultHeader,
        /**
         * The trailing [VaultRecoveryBlock], carried verbatim.
         *
         * Required, with no default, on purpose. This function rebuilds the container from
         * scratch, so a caller that forgets the block silently deletes the user's Recovery Kit —
         * and the caller that would forget is the ordinary save path, which runs every time an
         * item is edited. A missing argument is a compile error; a wrong default would be a vault
         * that quietly stops being recoverable somewhere between saving a note and needing it.
         * Use [recoveryBlockOf] to carry the existing one forward.
         */
        recoveryBlock: ByteArray,
        minimumPlaintextBucketSize: Int = SAVE_MIN_PLAINTEXT_BUCKET,
    ): ByteArray {
        require((decoyVault == null) == (encKeyDecoy == null)) {
            "decoy vault and key must be supplied together"
        }
        require(recoveryBlock.size == VaultRecoveryBlock.ENCODED_SIZE) {
            "recovery block must be ${VaultRecoveryBlock.ENCODED_SIZE} bytes"
        }
        require(
            minimumPlaintextBucketSize >= MIN_PLAINTEXT_BUCKET &&
                minimumPlaintextBucketSize <= MAX_PLAINTEXT_BUCKET &&
                minimumPlaintextBucketSize.isPowerOfTwo(),
        ) { "minimum plaintext bucket must be a supported power of two" }
        val realVaultBytes = VaultSerializer.encode(realVault)
        val decoyVaultBytes = decoyVault?.let(VaultSerializer::encode)
        try {
            val realSize = realVaultBytes.size.toLong() + LENGTH_PREFIX_SIZE
            val decoySize = (decoyVaultBytes?.size?.toLong() ?: 0L) + LENGTH_PREFIX_SIZE
            val bucketSize = bucketFor(
                maxOf(realSize, decoySize, minimumPlaintextBucketSize.toLong()),
            )
            val header = headerForSave(
                previous = prevHeader,
                plaintextBucketSize = bucketSize,
                preserveSecondSlot = decoyVault != null,
            )
            val aad = VaultHeaderCodec.payloadAad(header)
            val realPlaintext = framedPlaintext(realVaultBytes, bucketSize)
            val secondPlaintext = decoyVaultBytes?.let {
                framedPlaintext(it, bucketSize)
            } ?: SecureRandomProvider.nextBytes(bucketSize)
            try {
                val realCiphertext = AesGcmSivAead.seal(
                    encKeyReal,
                    header.regions[0].nonce,
                    realPlaintext,
                    aad,
                )
                val secondCiphertext = AesGcmSivAead.seal(
                    encKeyDecoy ?: encKeyReal,
                    header.regions[1].nonce,
                    secondPlaintext,
                    aad,
                )
                check(realCiphertext.size == header.regions[0].length)
                check(secondCiphertext.size == header.regions[1].length)
                return VaultHeaderCodec.encode(header) +
                    realCiphertext +
                    secondCiphertext +
                    recoveryBlock
            } finally {
                Zeroizer.wipe(realPlaintext)
                Zeroizer.wipe(secondPlaintext)
            }
        } finally {
            Zeroizer.wipe(realVaultBytes)
            Zeroizer.wipe(decoyVaultBytes)
        }
    }

    fun decrypt(
        fileBytes: ByteArray,
        encKey: ByteArray,
    ): Pair<VaultHeader, Vault> = decryptRegion(fileBytes, encKey, regionIndex = 0)

    fun decryptRegion(
        fileBytes: ByteArray,
        encKey: ByteArray,
        regionIndex: Int,
    ): Pair<VaultHeader, Vault> {
        require(regionIndex in 0 until VaultHeader.NUM_REGIONS) {
            "region index must be 0 or 1"
        }
        val header = VaultHeaderCodec.decode(fileBytes)
        validateContainer(header, fileBytes.size)
        val region = header.regions[regionIndex]
        val ciphertext = fileBytes.copyOfRange(region.offset, region.offset + region.length)
        val plaintext = AesGcmSivAead.open(
            encKey,
            region.nonce,
            ciphertext,
            VaultHeaderCodec.payloadAad(header),
        )
        try {
            require(plaintext.size >= LENGTH_PREFIX_SIZE) { "vault payload is truncated" }
            val vaultLength = readInt(plaintext, 0)
            require(vaultLength in 0..(plaintext.size - LENGTH_PREFIX_SIZE)) {
                "vault length prefix is out of range"
            }
            val payloadEnd = LENGTH_PREFIX_SIZE + vaultLength
            require(plaintext.allZeroFrom(payloadEnd)) { "vault padding is malformed" }
            val vaultBytes = plaintext.copyOfRange(LENGTH_PREFIX_SIZE, payloadEnd)
            return try {
                header to VaultSerializer.decode(vaultBytes)
            } finally {
                Zeroizer.wipe(vaultBytes)
            }
        } finally {
            Zeroizer.wipe(plaintext)
        }
    }

    /**
     * Returns whether [encKey] authenticates [regionIndex], without parsing its plaintext.
     *
     * A real key authenticates the random filler region only while duress is not configured.
     * This detects configuration after real unlock without a plaintext marker in the header.
     */
    fun authenticatesRegion(
        fileBytes: ByteArray,
        encKey: ByteArray,
        regionIndex: Int,
    ): Boolean {
        require(regionIndex in 0 until VaultHeader.NUM_REGIONS) {
            "region index must be 0 or 1"
        }
        val header = VaultHeaderCodec.decode(fileBytes)
        validateContainer(header, fileBytes.size)
        val region = header.regions[regionIndex]
        val ciphertext = fileBytes.copyOfRange(region.offset, region.offset + region.length)
        var plaintext: ByteArray? = null
        return try {
            plaintext = AesGcmSivAead.open(
                encKey,
                region.nonce,
                ciphertext,
                VaultHeaderCodec.payloadAad(header),
            )
            true
        } catch (_: com.localkeep.core.crypto.AeadAuthException) {
            false
        } finally {
            Zeroizer.wipe(ciphertext)
            Zeroizer.wipe(plaintext)
        }
    }

    /**
     * Re-encrypts one region in place while preserving the independently keyed opposite region.
     * Region size and header stay unchanged; callers needing a larger bucket must hold both keys.
     *
     * The region keeps its existing nonce, and that is deliberate rather than an oversight — do not
     * "fix" it by drawing a fresh one. The nonces live in the header, the header is the AAD both
     * regions authenticate under, and only one of the two keys is in hand here. Changing this
     * region's nonce rewrites the AAD for the *other* region too, and with no duress key available
     * to reseal it, that region stops authenticating: the decoy vault becomes unopenable by the one
     * password meant to open it, which is the failure the whole duress feature exists to avoid.
     *
     * The cost is an equality leak. AES-GCM-SIV is nonce-misuse resistant, so reuse does not expose
     * the key or the plaintext; what it does expose is that two saves produced identical ciphertext,
     * which tells an observer holding both copies of the file that nothing in that region changed
     * between them. Metadata about when the user edited, not what they edited — accepted knowingly,
     * and cheaper than the alternative above.
     */
    fun replaceRegion(
        fileBytes: ByteArray,
        vault: Vault,
        encKey: ByteArray,
        regionIndex: Int,
    ): ByteArray {
        require(regionIndex in 0 until VaultHeader.NUM_REGIONS) {
            "region index must be 0 or 1"
        }
        val header = VaultHeaderCodec.decode(fileBytes)
        validateContainer(header, fileBytes.size)
        val region = header.regions[regionIndex]
        val bucketSize = region.length - CryptoConstants.AEAD_TAG_LEN
        val vaultBytes = VaultSerializer.encode(vault)
        try {
            // Typed rather than a bare require: a real save routed here (a vault with a configured
            // decoy shares this fixed-size bucket) that has outgrown the bucket must reach the
            // editor as a distinct, honest failure — "remove some items", not the generic "try
            // again", which would be false since every retry serializes the same oversized vault.
            if (vaultBytes.size > bucketSize - LENGTH_PREFIX_SIZE) {
                throw VaultBucketExhaustedException()
            }
            val plaintext = framedPlaintext(vaultBytes, bucketSize)
            try {
                val ciphertext = AesGcmSivAead.seal(
                    encKey,
                    region.nonce,
                    plaintext,
                    VaultHeaderCodec.payloadAad(header),
                )
                return fileBytes.copyOf().also {
                    ciphertext.copyInto(it, destinationOffset = region.offset)
                }
            } finally {
                Zeroizer.wipe(plaintext)
            }
        } finally {
            Zeroizer.wipe(vaultBytes)
        }
    }

    /**
     * Replaces one region with fresh authenticated random filler, sealed under [encKey].
     *
     * The same thing [encryptBothRegions] writes into region 1 when there is no decoy — that
     * function's `?: SecureRandomProvider.nextBytes(bucketSize)` branch — but spliced into an
     * existing container instead of rebuilding one. Needed because a container that is not the live
     * vault cannot be rebuilt: [encryptBothRegions] rebuilds the whole file from a decrypted `Vault`
     * and fresh nonces, and the stale containers a decoy has to be swept out of are `vault.enc.bak`
     * and the snapshot ring, whose region 0 must survive byte for byte.
     *
     * **The caller must not hand this a region that already authenticates under [encKey].** The
     * region keeps its existing nonce, for the reason [replaceRegion] documents at length, so
     * resealing different plaintext under the *same* key and nonce reuses the pair. AES-GCM-SIV is
     * nonce-misuse resistant, so that exposes neither key nor plaintext — but the one caller here
     * has no reason to do it: a region that already opens under the real key is filler, and there is
     * nothing to overwrite. Where the key differs (a decoy region being overwritten with real-key
     * filler, which is the whole use) no pair is reused at all.
     */
    fun replaceRegionWithFiller(
        fileBytes: ByteArray,
        encKey: ByteArray,
        regionIndex: Int,
    ): ByteArray {
        require(regionIndex in 0 until VaultHeader.NUM_REGIONS) {
            "region index must be 0 or 1"
        }
        val header = VaultHeaderCodec.decode(fileBytes)
        validateContainer(header, fileBytes.size)
        val region = header.regions[regionIndex]
        val plaintext = SecureRandomProvider.nextBytes(region.length - CryptoConstants.AEAD_TAG_LEN)
        try {
            val ciphertext = AesGcmSivAead.seal(
                encKey,
                region.nonce,
                plaintext,
                VaultHeaderCodec.payloadAad(header),
            )
            return fileBytes.copyOf().also {
                ciphertext.copyInto(it, destinationOffset = region.offset)
            }
        } finally {
            Zeroizer.wipe(plaintext)
        }
    }

    /**
     * The ordinary save: rewrites exactly the one payload region this session owns and leaves
     * every other byte of the container identical.
     *
     * **The container must not say whether a decoy is configured**, and until 2026-09-11 it did.
     * The size oracle was closed by applying [SAVE_MIN_PLAINTEXT_BUCKET] to every save; this is the
     * differential that survived it. With a decoy configured, a save had to go through
     * [replaceRegion] -- only one of the two keys is ever in hand -- so the header froze and one
     * region changed. With no decoy, the save rebuilt the whole container through [encrypt], drawing
     * fresh region nonces and fresh filler every time. Two generations of `vault.enc` therefore
     * disclosed which of the two it was, and two generations always exist: `AtomicVaultWriter` keeps
     * `vault.enc.bak` and `RollingBackupManager` keeps five snapshots.
     *
     * The fix is to make the cheaper shape universal rather than the richer one: the no-decoy save
     * also splices one region, so the header, the unused slot and the opposite region freeze in both
     * configurations. Freezing the filler does not weaken it -- a frozen region of authenticated
     * random bytes is exactly what a frozen decoy region looks like, which is the point.
     *
     * **The honest residual, which this does not close:** a save made *inside* a decoy session
     * rewrites region 1, and a vault with no decoy never rewrites region 1 at all. So an observer
     * holding two generations still learns that a decoy was opened, though no longer that one
     * exists. Perfect symmetry is not reachable here -- a real-session save must write its data to
     * region 0, so it cannot imitate a save that writes only region 1. What is reachable, and what
     * this delivers, is that a decoy that is configured and never opened is indistinguishable from
     * no decoy at all, which is the state a duress vault sits in until the day it is needed.
     * `docs/THREAT-MODEL.md` states the residual.
     *
     * Growth is the one case that still rebuilds. A vault that outgrows its bucket cannot be
     * re-bucketed by [replaceRegion], and re-bucketing needs both keys, so a duress container
     * surfaces [VaultBucketExhaustedException] to the editor exactly as before. That asymmetry is
     * pre-existing and visible to the user rather than to the file: crossing a bucket boundary
     * changes the file's length either way, so the rebuild discloses nothing the size did not
     * already. The cost of routing the common case here instead is that a no-decoy container no
     * longer shrinks when the vault does -- its bucket is a high-water mark, like a decoy's.
     */
    fun commitSave(
        fileBytes: ByteArray,
        vault: Vault,
        encKey: ByteArray,
        isDuressSession: Boolean,
    ): ByteArray {
        val regionIndex = if (isDuressSession) 1 else 0
        // A container written before recovery blocks existed would otherwise keep announcing its
        // age by its length for ever: the rebuild-every-save path used to give it one, and a splice
        // does not. The block is noise, so this says nothing about whether a kit exists.
        val container = withNoiseRecoveryBlockIfAbsent(fileBytes)
        return try {
            replaceRegion(container, vault, encKey, regionIndex)
        } catch (exhausted: VaultBucketExhaustedException) {
            val decoyConfigured = !authenticatesRegion(container, encKey, regionIndex = 1)
            if (isDuressSession || decoyConfigured) throw exhausted
            encrypt(
                vault = vault,
                encKey = encKey,
                prevHeader = VaultHeaderCodec.decode(container),
                recoveryBlock = recoveryBlockOf(container),
            )
        }
    }

    private fun withNoiseRecoveryBlockIfAbsent(fileBytes: ByteArray): ByteArray {
        val header = VaultHeaderCodec.decode(fileBytes)
        validateContainer(header, fileBytes.size)
        return if (recoveryBlockOffset(header, fileBytes.size) >= 0) {
            fileBytes
        } else {
            // Appends a noise block; the container had no kit, so nothing is being revoked.
            withoutRecoveryKit(fileBytes)
        }
    }

    fun create(
        dek: ByteArray,
        salt: ByteArray,
        kdfParams: KdfParams,
        wrappedDek: WrappedDek,
        commitment: ByteArray,
    ): ByteArray {
        val header = VaultHeaderCodec.create(salt, kdfParams, wrappedDek, commitment)
        val encKey = VaultSubkeys.encKey(dek)
        val now = Instant.now()
        return try {
            encrypt(
                vault = Vault(createdAt = now, modifiedAt = now),
                encKey = encKey,
                prevHeader = header,
                // Noise: a brand-new vault has no kit until the user asks for one, and the file
                // must not say which of those it is.
                recoveryBlock = VaultRecoveryBlock.random(),
            )
        } finally {
            Zeroizer.wipe(encKey)
        }
    }

    internal fun validateContainer(
        header: VaultHeader,
        fileSize: Int,
    ) {
        val real = header.regions[0]
        val filler = header.regions[1]
        require(real.offset == VaultHeaderCodec.ENCODED_SIZE) {
            "region 0 offset is invalid"
        }
        require(real.length == filler.length) { "payload regions must have equal length" }
        require(real.length >= MIN_PLAINTEXT_BUCKET + CryptoConstants.AEAD_TAG_LEN) {
            "payload region is too short"
        }
        require(filler.offset.toLong() == real.offset.toLong() + real.length) {
            "region 1 offset is invalid"
        }
        // Either exactly the two regions — a container written before recovery blocks existed —
        // or the two regions plus one. Both are current: a legacy file gains its block on the next
        // save, and until then it simply has no Recovery Kit, which is what it had anyway.
        val regionsEnd = filler.offset.toLong() + filler.length
        require(
            regionsEnd == fileSize.toLong() ||
                regionsEnd + VaultRecoveryBlock.ENCODED_SIZE == fileSize.toLong(),
        ) { "vault container size does not match region descriptors" }
    }

    /** Byte offset of the trailing recovery block, or -1 when the container predates it. */
    internal fun recoveryBlockOffset(header: VaultHeader, fileSize: Int): Int {
        val regionsEnd = header.regions[1].offset.toLong() + header.regions[1].length
        if (regionsEnd < 0L || regionsEnd > fileSize.toLong()) return -1
        val trailing = fileSize.toLong() - regionsEnd
        return if (trailing == VaultRecoveryBlock.ENCODED_SIZE.toLong()) regionsEnd.toInt() else -1
    }

    /**
     * The wrapped DEK a Recovery Kit would unwrap, or null when this vault has no kit.
     *
     * Null and "the words are wrong" are deliberately the same answer to the caller — see
     * [VaultRecoveryBlock].
     */
    fun recoveryWrappedDek(fileBytes: ByteArray): WrappedDek? {
        val header = VaultHeaderCodec.decode(fileBytes)
        validateContainer(header, fileBytes.size)
        val offset = recoveryBlockOffset(header, fileBytes.size)
        if (offset < 0) return null
        return VaultRecoveryBlock.decode(fileBytes, offset)
    }

    /**
     * The container's existing recovery block, or a fresh noise block when it has none.
     *
     * What every rewrite of the file should pass to [encryptBothRegions] to leave the user's kit
     * alone. The exception is a master-password change, which rotates the DEK: the old block wraps
     * a key that no longer opens anything, so that path passes [VaultRecoveryBlock.random] and the
     * kit is genuinely revoked rather than left as a dud.
     *
     * Throws [IllegalArgumentException] on a container whose region descriptors do not describe the
     * file, rather than carrying bytes read from an offset those descriptors invented.
     */
    fun recoveryBlockOf(fileBytes: ByteArray): ByteArray {
        val header = VaultHeaderCodec.decode(fileBytes)
        validateContainer(header, fileBytes.size)
        val offset = recoveryBlockOffset(header, fileBytes.size)
        return if (offset < 0) {
            VaultRecoveryBlock.random()
        } else {
            fileBytes.copyOfRange(offset, offset + VaultRecoveryBlock.ENCODED_SIZE)
        }
    }

    /**
     * A block meaning "no Recovery Kit". Noise, not an erased marker — see [VaultRecoveryBlock].
     */
    fun revokedRecoveryBlock(): ByteArray = VaultRecoveryBlock.random()

    /**
     * Returns [fileBytes] with any Recovery Kit revoked.
     *
     * Needed because the rotated-DEK case cannot simply leave the old block alone: it would still
     * decode, still unwrap, and hand back a key that no longer opens the vault — so the kit would
     * fail at the last possible moment instead of being reported as gone.
     */
    fun withoutRecoveryKit(fileBytes: ByteArray): ByteArray {
        val header = VaultHeaderCodec.decode(fileBytes)
        validateContainer(header, fileBytes.size)
        val regionsEnd = header.regions[1].offset + header.regions[1].length
        return fileBytes.copyOf(regionsEnd + VaultRecoveryBlock.ENCODED_SIZE).also {
            VaultRecoveryBlock.random().copyInto(it, destinationOffset = regionsEnd)
        }
    }

    /**
     * Returns [fileBytes] with its recovery block replaced, appending one if it had none.
     *
     * Splices rather than re-encrypting: the block sits outside both payload regions and outside
     * the payload AAD, so installing a Recovery Kit touches no ciphertext. That is what lets a kit
     * be created for a vault that has a duress vault configured — resealing region 1 would need
     * the duress password.
     */
    fun withRecoveryBlock(fileBytes: ByteArray, wrappedDek: WrappedDek): ByteArray {
        val header = VaultHeaderCodec.decode(fileBytes)
        validateContainer(header, fileBytes.size)
        val block = VaultRecoveryBlock.encode(wrappedDek)
        val regionsEnd = header.regions[1].offset + header.regions[1].length
        return fileBytes.copyOf(regionsEnd + VaultRecoveryBlock.ENCODED_SIZE).also {
            block.copyInto(it, destinationOffset = regionsEnd)
        }
    }

    private fun headerForSave(
        previous: VaultHeader,
        plaintextBucketSize: Int,
        preserveSecondSlot: Boolean,
    ): VaultHeader {
        val ciphertextSize = plaintextBucketSize + CryptoConstants.AEAD_TAG_LEN
        return VaultHeader(
            versioned = previous.versioned,
            slots = listOf(
                previous.slots[0],
                if (preserveSecondSlot) {
                    previous.slots[1]
                } else {
                    VaultHeaderCodec.randomUnusedSlot(previous.slots[0].kdfParams)
                },
            ),
            regions = listOf(
                PayloadRegion(
                    nonce = SecureRandomProvider.nextBytes(CryptoConstants.AEAD_NONCE_LEN),
                    offset = VaultHeaderCodec.ENCODED_SIZE,
                    length = ciphertextSize,
                ),
                PayloadRegion(
                    nonce = SecureRandomProvider.nextBytes(CryptoConstants.AEAD_NONCE_LEN),
                    offset = VaultHeaderCodec.ENCODED_SIZE + ciphertextSize,
                    length = ciphertextSize,
                ),
            ),
        )
    }

    private fun framedPlaintext(
        vaultBytes: ByteArray,
        bucketSize: Int,
    ): ByteArray = ByteArray(bucketSize).also {
        writeInt(it, 0, vaultBytes.size)
        vaultBytes.copyInto(it, destinationOffset = LENGTH_PREFIX_SIZE)
    }

    private fun bucketFor(minimumSize: Long): Int {
        require(minimumSize in 1..MAX_PLAINTEXT_BUCKET.toLong()) {
            "vault payload exceeds supported size"
        }
        var bucket = MIN_PLAINTEXT_BUCKET
        while (bucket < minimumSize) {
            bucket *= 2
        }
        return bucket
    }

    private fun writeInt(
        bytes: ByteArray,
        offset: Int,
        value: Int,
    ) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

    private fun ByteArray.allZeroFrom(start: Int): Boolean {
        for (index in start until size) {
            if (this[index] != 0.toByte()) return false
        }
        return true
    }

    private fun Int.isPowerOfTwo(): Boolean = this > 0 && this and (this - 1) == 0
}
