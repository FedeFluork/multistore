package com.multistore.core.common.result

/**
 * The outcome of an operation that can fail in a foreseen way.
 *
 * It exists instead of `kotlin.Result` for two concrete reasons: `kotlin.Result` forces the error
 * into a `Throwable` — and so forces building exceptions for conditions that are not exceptional,
 * like "the user cancelled" — and it cannot be used as the return type of a non-inline `suspend`
 * function. Here the error is typed data: [AppError].
 */
sealed interface Outcome<out T> {

    data class Success<out T>(val value: T) : Outcome<T>

    data class Failure(val error: AppError) : Outcome<Nothing>

    val isSuccess: Boolean get() = this is Success

    fun getOrNull(): T? = (this as? Success)?.value

    fun errorOrNull(): AppError? = (this as? Failure)?.error
}

inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Success -> Outcome.Success(transform(value))
    is Outcome.Failure -> this
}

inline fun <T, R> Outcome<T>.flatMap(transform: (T) -> Outcome<R>): Outcome<R> = when (this) {
    is Outcome.Success -> transform(value)
    is Outcome.Failure -> this
}

inline fun <T> Outcome<T>.onSuccess(action: (T) -> Unit): Outcome<T> =
    also { if (it is Outcome.Success) action(it.value) }

inline fun <T> Outcome<T>.onFailure(action: (AppError) -> Unit): Outcome<T> =
    also { if (it is Outcome.Failure) action(it.error) }

fun <T> T.asSuccess(): Outcome<T> = Outcome.Success(this)

fun AppError.asFailure(): Outcome<Nothing> = Outcome.Failure(this)
