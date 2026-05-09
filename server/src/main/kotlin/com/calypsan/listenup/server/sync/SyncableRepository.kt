package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.SyncEvent
import kotlin.time.Clock
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.v1.jdbc.update

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
    protected val db: Database,
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

    /**
     * Insert or update [value], bumping the global revision counter and publishing
     * a [SyncEvent.Created] or [SyncEvent.Updated] to [ChangeBus].
     *
     * Created/Updated discrimination is based on whether the row exists before the
     * write — existence is determined inside the transaction, so the decision is
     * atomic with the write.
     *
     * @param clientOpId originating client operation id for SSE echo matching; null
     *   for server-initiated writes (scanner, admin, etc.).
     */
    suspend fun upsert(
        value: T,
        clientOpId: String? = null,
    ): AppResult<T> =
        newSuspendedTransaction(Dispatchers.IO, db) {
            val rev = nextRevision(db)
            val now = clock.now().toEpochMilliseconds()
            val idStr = value.id.toString()

            val existed =
                table
                    .selectAll()
                    .where { idColumn() eq idStr }
                    .empty()
                    .not()

            if (existed) {
                table.update({ idColumn() eq idStr }) { stmt ->
                    value.writeTo(stmt)
                    stmt[table.revision] = rev
                    stmt[table.updatedAt] = now
                    stmt[table.deletedAt] = null
                    stmt[table.clientOpId] = clientOpId
                }
            } else {
                table.insert { stmt ->
                    value.writeTo(stmt)
                    stmt[table.revision] = rev
                    stmt[table.createdAt] = now
                    stmt[table.updatedAt] = now
                    stmt[table.deletedAt] = null
                    stmt[table.clientOpId] = clientOpId
                }
            }

            val saved =
                table
                    .selectAll()
                    .where { idColumn() eq idStr }
                    .single()
                    .toDto()

            if (existed) {
                bus.publish(
                    SyncEvent.Updated(
                        id = idStr,
                        revision = rev,
                        occurredAt = now,
                        clientOpId = clientOpId,
                        payload = saved,
                    ),
                )
            } else {
                bus.publish(
                    SyncEvent.Created(
                        id = idStr,
                        revision = rev,
                        occurredAt = now,
                        clientOpId = clientOpId,
                        payload = saved,
                    ),
                )
            }

            AppResult.Success(saved)
        }

    /**
     * Exposes the table's primary-key column for use in WHERE clauses.
     * Default assumes a `text("id")` column — the canonical syncable-table
     * convention. Domains using a non-`id` PK column override this.
     */
    protected open fun idColumn(): Column<String> {
        @Suppress("UNCHECKED_CAST")
        return table.columns.firstOrNull { it.name == "id" } as? Column<String>
            ?: error("Domain table ${table.tableName} has no 'id' column; override idColumn().")
    }

    // softDelete: Task 11
    // pullSince: Task 12
    // digest: Task 13
}
