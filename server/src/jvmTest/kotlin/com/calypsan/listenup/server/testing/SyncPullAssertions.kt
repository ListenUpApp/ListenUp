package com.calypsan.listenup.server.testing

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.SyncPage
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.KSerializer

/*
 * Assertion helpers for the sync pull surface (SyncStreamService).
 *
 * The pull returns a typed AppResult carrying a SyncPage whose envelope is typed and whose rows
 * are encoded strings. These three helpers are what a test needs to get from that shape to a list
 * of domain objects — kept here so every sync test decodes the same way, rather than each
 * re-deriving it.
 */

/** The success value, failing the test with the typed error's code when the call did not succeed. */
internal fun <T> AppResult<T>.shouldSucceed(): T =
    when (this) {
        is AppResult.Success -> data
        is AppResult.Failure -> throw AssertionError("expected success but failed with ${error.code}: ${error.message}")
    }

/** The typed failure, failing the test when the call unexpectedly succeeded. */
internal inline fun <reified E : AppError> AppResult<*>.shouldFailWith(): E =
    shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<E>()

/**
 * The page's rows, decoded with [serializer].
 *
 * Mirrors the client's own `SyncPage.toPage` decode, so a server-side assertion reads the rows
 * exactly as the client will. `SyncDomainRoundTripSpec` is what pins that the domain named by the
 * page really does select this serializer.
 */
internal fun <T : Any> SyncPage.rows(serializer: KSerializer<T>): List<T> =
    items.map { contractJson.decodeFromString(serializer, it) }
