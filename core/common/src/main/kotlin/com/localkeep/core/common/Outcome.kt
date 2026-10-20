package com.localkeep.core.common

import kotlinx.coroutines.CancellationException

sealed interface Outcome<out T> {
    data class Success<out T>(val value: T) : Outcome<T>

    data class Failure(val error: AppError) : Outcome<Nothing>
}

inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Success -> Outcome.Success(transform(value))
    is Outcome.Failure -> this
}

inline fun <T, R> Outcome<T>.flatMap(transform: (T) -> Outcome<R>): Outcome<R> = when (this) {
    is Outcome.Success -> transform(value)
    is Outcome.Failure -> this
}

fun <T> Outcome<T>.getOrNull(): T? = (this as? Outcome.Success)?.value

inline fun <T, R> Outcome<T>.fold(
    onSuccess: (T) -> R,
    onFailure: (AppError) -> R,
): R = when (this) {
    is Outcome.Success -> onSuccess(value)
    is Outcome.Failure -> onFailure(error)
}

/**
 * Runs [block], turning any failure into [AppError.Unknown].
 *
 * The caught throwable is deliberately not retained as [AppError.cause]: exception chains from
 * parsing and crypto routinely quote the input they choked on, and that input is vault material.
 * Callers that need a specific, secret-free reason should catch it themselves and build the
 * [AppError] with a message they have vetted.
 */
inline fun <T> runCatchingOutcome(block: () -> T): Outcome<T> = try {
    Outcome.Success(block())
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (_: Throwable) {
    Outcome.Failure(AppError.Unknown("Unexpected failure"))
}
