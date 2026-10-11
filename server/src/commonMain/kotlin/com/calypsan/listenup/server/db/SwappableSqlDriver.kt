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
 * The wrapper itself is the gate: while closed (between [closeUnderlying] and [installUnderlying]) it
 * refuses new queries, writes and transactions on every platform, regardless of what the wrapped driver
 * does after its own `close()` (the JVM file driver, for one, keeps opening fresh connections). A single
 * statement already executing when the flag flips may still complete. There is no lock: a restore is a
 * rare admin operation, and failing a request during the brief swap window is the honest, simple choice.
 */
class SwappableSqlDriver(
    initial: SqlDriver,
) : SqlDriver {
    @kotlin.concurrent.Volatile
    private var delegate: SqlDriver = initial

    @kotlin.concurrent.Volatile
    private var open: Boolean = true

    /** True while a live driver is installed; false between a pre-swap [closeUnderlying] and the reinstall. */
    val isOpen: Boolean get() = open

    /** Hard-closes the live driver ahead of an [installUnderlying] — the recoverable pre-swap close. */
    fun closeUnderlying() {
        open = false
        delegate.close()
    }

    /** Installs a freshly-built driver as the live delegate (after a file swap). */
    fun installUnderlying(new: SqlDriver) {
        delegate = new
        open = true
    }

    private fun checkOpen() = check(open) { "database is closed for a restore" }

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        checkOpen()
        return delegate.executeQuery(
            identifier = identifier,
            sql = sql,
            mapper = mapper,
            parameters = parameters,
            binders = binders,
        )
    }

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        checkOpen()
        return delegate.execute(identifier = identifier, sql = sql, parameters = parameters, binders = binders)
    }

    override fun newTransaction(): QueryResult<Transacter.Transaction> {
        checkOpen()
        return delegate.newTransaction()
    }

    override fun currentTransaction(): Transacter.Transaction? = delegate.currentTransaction()

    override fun addListener(
        vararg queryKeys: String,
        listener: Query.Listener,
    ) {
        delegate.addListener(queryKeys = queryKeys, listener = listener)
    }

    override fun removeListener(
        vararg queryKeys: String,
        listener: Query.Listener,
    ) {
        delegate.removeListener(queryKeys = queryKeys, listener = listener)
    }

    override fun notifyListeners(vararg queryKeys: String) {
        delegate.notifyListeners(queryKeys = queryKeys)
    }

    /** Terminal close (app/test teardown). */
    override fun close() {
        delegate.close()
    }
}
