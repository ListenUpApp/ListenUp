package com.calypsan.listenup.client

import kotlin.test.assertIs

/**
 * Type assertion that doesn't return a value.
 *
 * Use this instead of [assertIs] when you only need to verify the type
 * without using the returned cast value. This avoids "Unused return value"
 * compiler warnings.
 *
 * @param T The expected type
 * @param value The value to check
 * @param message Optional assertion message
 */
inline fun <reified T> checkIs(
    value: Any?,
    message: String? = null,
) {
    @Suppress("UNUSED_VARIABLE")
    val result = assertIs<T>(value, message)
}
