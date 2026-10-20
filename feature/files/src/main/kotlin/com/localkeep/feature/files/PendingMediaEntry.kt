package com.localkeep.feature.files

/**
 * Runs [publish] against a freshly inserted `IS_PENDING = 1` row and returns [entry], [discard]ing
 * the row if [publish] does not finish.
 *
 * A pending row is invisible to the gallery until something clears the flag, and only the app that
 * created it can clear or remove it. So the failure most likely to happen during a restore — the
 * decrypt into the output stream running out of disk part-way through a large video — otherwise
 * leaves a zero-length placeholder the user can neither see nor delete, one more of them per retry.
 *
 * [Throwable] rather than [Exception] on purpose: an oversized restore is exactly where an
 * [OutOfMemoryError] turns up, and the row wants clearing in that case too. The original failure is
 * rethrown untouched, and a [discard] that fails in its turn is swallowed so it cannot mask it.
 */
internal fun <T> completePendingEntry(entry: T, discard: (T) -> Unit, publish: (T) -> Unit): T {
    try {
        publish(entry)
    } catch (e: Throwable) {
        runCatching { discard(entry) }
        throw e
    }
    return entry
}
