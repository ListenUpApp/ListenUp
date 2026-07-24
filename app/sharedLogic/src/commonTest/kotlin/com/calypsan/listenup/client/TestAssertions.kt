package com.calypsan.listenup.client

import io.kotest.assertions.withClue
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Type assertion that doesn't return a value.
 *
 * Use this instead of [shouldBeInstanceOf] when you only need to verify the type
 * without using the returned cast value. This avoids "Unused return value"
 * compiler warnings.
 *
 * @param T The expected type
 * @param value The value to check
 * @param message Optional assertion message
 */
inline fun <reified T : Any> checkIs(
    value: Any?,
    message: String? = null,
) {
    if (message != null) {
        withClue(message) { value.shouldBeInstanceOf<T>() }
    } else {
        value.shouldBeInstanceOf<T>()
    }
}
