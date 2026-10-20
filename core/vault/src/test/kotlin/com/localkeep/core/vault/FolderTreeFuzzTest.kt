package com.localkeep.core.vault

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import com.localkeep.core.model.Folder
import com.localkeep.core.model.FolderTree
import java.time.Instant
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Random folder forests, including shapes no write path can produce, put through every function
 * that walks the tree.
 *
 * The hand-written cases in `FolderTreeTest` cover the defects someone thought of. This covers the
 * property that has to hold for all of them: nothing here may loop forever, and `normalize` must
 * always hand back something `validate` accepts — because it is the merge and restore path, and a
 * normalize that returned a still-invalid tree would turn a repairable vault into an unwritable one.
 *
 * Lives under `core/vault/src/test` so `checkFuzzTargetsCovered` can see it.
 */
class FolderTreeFuzzTest {

    @Test
    fun `normalize always produces a tree validate accepts`() = repeatWithSeeds { folders ->
        val result = FolderTree.normalize(folders)
        val validation = FolderTree.validate(result.folders)
        assertTrue(validation.isValid, "normalize left $validation")
        assertTrue(result.folders.map { it.id }.toSet() == folders.map { it.id }.toSet(), "normalize lost a folder")
    }

    @Test
    fun `normalize is idempotent`() = repeatWithSeeds { folders ->
        val once = FolderTree.normalize(folders).folders
        assertTrue(FolderTree.normalize(once).changes.isEmpty(), "a second normalize still moved something")
    }

    @Test
    fun `every walk terminates and stays bounded`() = repeatWithSeeds { folders ->
        folders.forEach { folder ->
            val descendants = FolderTree.descendantIds(folders, folder.id)
            assertTrue(descendants.size <= folders.size)
            val depth = FolderTree.depthOf(folders, folder.id)
            assertTrue(depth == null || depth <= folders.size)
            FolderTree.pathOf(folders, folder.id)
            FolderTree.wouldBeDescendant(folders, folder.id, folders.random().id)
        }
    }

    @Test
    fun `a promotion plan applied to a normalized tree leaves it valid`() = repeatWithSeeds { raw ->
        val folders = FolderTree.normalize(raw).folders
        val deleted = folders.random()
        val plan = FolderTree.promotionPlan(folders, deleted.id)
        val after = folders.filter { it.id != deleted.id }
            .map { folder -> plan[folder.id]?.let { folder.copy(parentId = it) } ?: folder }
            .map { if (it.parentId == deleted.id) it.copy(parentId = null) else it }
        assertTrue(FolderTree.validate(after).isValid, "deleting ${deleted.id} left an invalid tree")
    }

    /**
     * The same invariant, driven by Jazzer instead of by seeds.
     *
     * The seeded cases above are the regression harness — they run on every build and a failure
     * names its own seed. This is the search: Jazzer mutates toward the shapes nobody thought to
     * write down, and `normalize` is the right thing to point it at because it is the only folder
     * function that runs on data from outside the app (a merge, a restored snapshot) and the only
     * one whose failure is unrecoverable rather than merely wrong — a tree it leaves invalid is a
     * vault that refuses every folder write from then on.
     */
    @FuzzTest(maxDuration = "10m")
    fun normalize(data: FuzzedDataProvider) {
        val count = data.consumeInt(0, 40)
        if (count == 0) return
        val ids = List(count) { UUID.nameUUIDFromBytes(byteArrayOf(it.toByte())) }
        val folders = ids.mapIndexed { index, id ->
            Folder(
                id = id,
                name = data.consumeString(8),
                parentId = when (val pick = data.consumeInt(-2, count - 1)) {
                    -2 -> null
                    -1 -> UUID.nameUUIDFromBytes("absent".toByteArray())
                    else -> ids[pick]
                },
                updatedAt = data.consumeBoolean().takeIf { it }?.let { Instant.ofEpochSecond(data.consumeLong(0, 1_000_000)) },
                parentUpdatedAt = data.consumeBoolean().takeIf { it }?.let { Instant.ofEpochSecond(data.consumeLong(0, 1_000_000)) },
            ).also { _ -> index }
        }

        val result = FolderTree.normalize(folders)
        val validation = FolderTree.validate(result.folders)
        if (!validation.isValid) {
            throw AssertionError(
                "normalize left a tree validate rejects: $validation\n\n" +
                    "This is the repair path for every vault arriving from outside — a merge or a " +
                    "restored snapshot. A tree it cannot fix is a vault that refuses every folder " +
                    "write from then on, with no way out from inside the app.",
            )
        }
        if (result.folders.map { it.id }.toSet() != folders.map { it.id }.toSet()) {
            throw AssertionError("normalize lost or invented a folder")
        }
    }

    /**
     * Fixed seeds rather than a fresh `Random()`: a failure has to be reproducible from the test
     * output alone, and a shape that only appears one run in fifty is exactly the one worth keeping.
     */
    private fun repeatWithSeeds(check: (List<Folder>) -> Unit) {
        (0L until 200L).forEach { seed ->
            val folders = randomForest(Random(seed))
            runCatching { check(folders) }.onFailure { failure ->
                throw AssertionError("seed $seed, ${folders.size} folders: ${failure.message}", failure)
            }
        }
    }

    /** A forest with dangling parents, cycles and over-deep chains all reachable. */
    private fun randomForest(random: Random): List<Folder> {
        val size = random.nextInt(1, 25)
        val ids = List(size) { UUID.randomUUID() }
        val stranger = UUID.randomUUID()
        return ids.mapIndexed { index, id ->
            val parent = when (random.nextInt(10)) {
                0 -> null
                1 -> stranger                     // dangling
                2 -> id                           // self-parent
                3 -> ids.random(random)           // anything, cycles included
                else -> ids.getOrNull(index - 1)  // long chains, past MAX_DEPTH
            }
            Folder(
                id = id,
                name = "f$index",
                parentId = parent,
                updatedAt = if (random.nextBoolean()) null else Instant.ofEpochSecond(random.nextLong(1, 1_000_000)),
                parentUpdatedAt = if (random.nextBoolean()) null else Instant.ofEpochSecond(random.nextLong(1, 1_000_000)),
            )
        }
    }
}
