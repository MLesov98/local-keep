package com.localkeep.core.common

/** Domain errors. Messages must never contain secret material. */
sealed interface AppError {
    val message: String
    val cause: Throwable?

    data class Crypto(
        override val message: String,
        override val cause: Throwable? = null,
    ) : AppError

    data class Storage(
        override val message: String,
        override val cause: Throwable? = null,
    ) : AppError

    data class Validation(
        override val message: String,
        override val cause: Throwable? = null,
    ) : AppError

    data class NotFound(
        override val message: String,
        override val cause: Throwable? = null,
    ) : AppError

    data class Unknown(
        override val message: String,
        override val cause: Throwable? = null,
    ) : AppError
}
