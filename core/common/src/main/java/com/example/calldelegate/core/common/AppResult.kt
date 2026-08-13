package com.example.calldelegate.core.common

sealed interface AppResult<out T> {
    data class Success<T>(val value: T) : AppResult<T>
    data class Failure(val error: AppError) : AppResult<Nothing>
}

data class AppError(
    val code: String,
    val userMessage: String,
    val detail: String? = null,
    val recoverable: Boolean = true,
)

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Success -> AppResult.Success(transform(value))
    is AppResult.Failure -> this
}

fun Throwable.toAppError(
    code: String,
    userMessage: String,
    recoverable: Boolean = true,
): AppError = AppError(code, userMessage, message, recoverable)
