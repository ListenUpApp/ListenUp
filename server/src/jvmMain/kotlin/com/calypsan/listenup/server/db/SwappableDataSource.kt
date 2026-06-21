package com.calypsan.listenup.server.db

import java.io.PrintWriter
import java.sql.Connection
import java.util.logging.Logger
import javax.sql.DataSource

/**
 * A [DataSource] facade over a swappable delegate. Lets a long-lived consumer (the migration runner /
 * VACUUM) bind once while the live delegate underneath is replaced — used by restore to rebuild the
 * connection source on the swapped-in db file. Backed by a non-pooled [org.sqlite.SQLiteDataSource]
 * (each [getConnection] opens a fresh connection the caller closes), so there is no pool to leak; the
 * `close`/`closeCurrent` hooks close the delegate only if it is [AutoCloseable].
 *
 * No lock: between [closeCurrent] and [install] the delegate may be a stale source, but restore is a
 * rare, single-flight, drained admin operation — there is no concurrent caller during the swap window.
 */
class SwappableDataSource(
    initial: DataSource,
) : DataSource {
    @Volatile
    private var delegate: DataSource = initial

    /** The currently active delegate. */
    fun current(): DataSource = delegate

    /** Closes the live delegate (if closeable) ahead of an [install] — the recoverable pre-swap close. */
    fun closeCurrent() {
        (delegate as? AutoCloseable)?.close()
    }

    /** Installs a freshly-built delegate as the live source. */
    fun install(new: DataSource) {
        delegate = new
    }

    /** Terminal shutdown of the data source (no [install] follows) — e.g. app/test teardown. */
    fun close() {
        (delegate as? AutoCloseable)?.close()
    }

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
