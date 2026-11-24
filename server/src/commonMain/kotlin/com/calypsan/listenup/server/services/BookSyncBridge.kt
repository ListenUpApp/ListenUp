package com.calypsan.listenup.server.services

import app.cash.sqldelight.TransactionWithReturn
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.sync.FrameCapture

/**
 * Narrow bridge exposing [com.calypsan.listenup.server.sync.SqlSyncableRepository] protected
 * substrate hooks to [BookRepository]'s internal collaborators without widening the repository's
 * public API.
 */
internal interface BookSyncBridge {
    val domainName: String

    fun nextRevision(): Long

    fun readPayloadInTransaction(idStr: String): BookSyncPayload?

    suspend fun findById(id: BookId): BookSyncPayload?

    suspend fun findAllByIds(bookIds: List<String>): List<BookSyncPayload>

    suspend fun upsert(
        value: BookSyncPayload,
        clientOpId: String? = null,
    ): AppResult<BookSyncPayload>

    fun TransactionWithReturn<*>.upsertBookInOpenTransaction(
        value: BookSyncPayload,
        suppressed: Boolean,
        clientOpId: String? = null,
    ): BookSyncPayload

    fun TransactionWithReturn<*>.emitBookUpdatedAfterCommit(
        idStr: String,
        rev: Long,
        now: Long,
        capture: FrameCapture? = null,
    )

    suspend fun softDeleteRow(
        id: BookId,
        clientOpId: String?,
        userId: String?,
    ): AppResult<Unit>
}
