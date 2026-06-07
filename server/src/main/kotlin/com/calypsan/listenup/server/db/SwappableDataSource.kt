package com.calypsan.listenup.server.db

import com.zaxxer.hikari.HikariDataSource
import java.io.PrintWriter
import java.sql.Connection
import java.util.logging.Logger
import javax.sql.DataSource

/**
 * A [DataSource] facade over a swappable [HikariDataSource]. Lets the Exposed `Database`
 * bind once to a stable object while the live pool underneath is torn down and rebuilt —
 * the mechanism the restore orchestrator uses to free every SQLite file handle before
 * swapping the db file in place.
 *
 * No lock: between [closeCurrent] and [install] the delegate is a closed pool, so a
 * concurrent [getConnection] fails fast (a closed pool can never serve stale data). A
 * restore is a rare admin operation; failing a request during the brief swap window is
 * the honest, simple choice.
 */
class SwappableDataSource(
    initial: HikariDataSource,
) : DataSource {
    @Volatile
    private var delegate: HikariDataSource = initial

    /** Returns the currently active pool. */
    fun current(): HikariDataSource = delegate

    /**
     * Hard-closes the live pool ahead of an [install] — the recoverable pre-swap close.
     * Releases every fd. Expect a new pool to be installed immediately after.
     */
    fun closeCurrent() = delegate.close()

    /** Installs a freshly-built pool as the live delegate. */
    fun install(new: HikariDataSource) {
        delegate = new
    }

    /**
     * Terminal shutdown of the data source (no [install] follows) — e.g. app/test teardown.
     */
    fun close() = delegate.close()

    override fun getConnection(): Connection = delegate.connection

    override fun getConnection(
        username: String?,
        password: String?,
    ): Connection = delegate.getConnection(username, password)

    override fun getLogWriter(): PrintWriter? = delegate.logWriter

    override fun setLogWriter(out: PrintWriter?) {
        delegate.logWriter = out
    }

    override fun setLoginTimeout(seconds: Int) {
        delegate.loginTimeout = seconds
    }

    override fun getLoginTimeout(): Int = delegate.loginTimeout

    override fun getParentLogger(): Logger = delegate.parentLogger

    override fun <T : Any?> unwrap(iface: Class<T>?): T = delegate.unwrap(iface)

    override fun isWrapperFor(iface: Class<*>?): Boolean = delegate.isWrapperFor(iface)
}
