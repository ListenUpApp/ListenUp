package com.calypsan.listenup.server.sync

import kotlin.time.Clock
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder

/**
 * Abstract base for syncable-domain repositories. Subclasses provide:
 *  - The Exposed table (a [SyncableTable] subtype)
 *  - `ResultRow.toDto()` to render a row into the wire-shape DTO
 *  - `T.writeTo(stmt)` to render a DTO into an UpdateBuilder for write
 *  - The `T.id` projection
 *
 * The base provides `upsert`, `softDelete`, `pullSince`, `digest` operating
 * on the global revision counter and publishing to [ChangeBus] on every write.
 * Self-registers with [SyncRoutes] in its `init` block — Koin must use
 * `createdAtStart = true` so registration happens at application bootstrap.
 */
abstract class SyncableRepository<T : Any, ID : Any>(
    protected val table: SyncableTable,
    protected val bus: ChangeBus,
    val domainName: String,
    protected val clock: Clock = Clock.System,
) {
    init {
        SyncRoutes.register(domainName, this)
    }

    protected abstract fun ResultRow.toDto(): T

    protected abstract fun T.writeTo(stmt: UpdateBuilder<*>)

    protected abstract val T.id: ID

    // upsert: Task 10
    // softDelete: Task 11
    // pullSince: Task 12
    // digest: Task 13
}
