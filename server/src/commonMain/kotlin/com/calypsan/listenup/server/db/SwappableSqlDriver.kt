package com.calypsan.listenup.server.db

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement

/**
 * A [SqlDriver] facade over a swappable underlying driver. Lets
 * [com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase] (and every repository that captured it)
 * bind once to a stable object while the live driver underneath is closed and rebuilt — the mechanism
 * the restore orchestrator uses to free every SQLite file handle before swapping the db file in place,
 * then reopen on the swapped-in file.
 *
 * The [SqlDriver] twin of [SwappableDataSource]. No lock: between [closeUnderlying] and
 * [installUnderlying] the delegate is a closed driver, so a concurrent query fails fast (a closed
 * driver can never serve stale data). A restore is a rare admin operation; failing a request during the
 * brief swap window is the honest, simple choice.
 */
class SwappableSqlDriver(
    initial: SqlDriver,
) : SqlDriver {
    @kotlin.concurrent.Volatile
    private var delegate: SqlDriver = initial

    /** Hard-closes the live driver ahead of an [installUnderlying] — the recoverable pre-swap close. */
    fun closeUnderlying() = delegate.close()

    /** Installs a freshly-built driver as the live delegate (after a file swap). */
    fun installUnderlying(new: SqlDriver) {
        delegate = new
    }

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> = delegate.executeQuery(identifier, sql, mapper, parameters, binders)

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> = delegate.execute(identifier, sql, parameters, binders)

    override fun newTransaction(): QueryResult<Transacter.Transaction> = delegate.newTransaction()

    override fun currentTransaction(): Transacter.Transaction? = delegate.currentTransaction()

    override fun addListener(
        vararg queryKeys: String,
        listener: Query.Listener,
    ) = delegate.addListener(queryKeys = queryKeys, listener = listener)

    override fun removeListener(
        vararg queryKeys: String,
        listener: Query.Listener,
    ) = delegate.removeListener(queryKeys = queryKeys, listener = listener)

    override fun notifyListeners(vararg queryKeys: String) = delegate.notifyListeners(queryKeys = queryKeys)

    /** Terminal close (app/test teardown). */
    override fun close() = delegate.close()
}
