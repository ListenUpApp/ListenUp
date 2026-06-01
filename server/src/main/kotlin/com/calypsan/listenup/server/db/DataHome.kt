package com.calypsan.listenup.server.db

import java.nio.file.Files
import java.nio.file.Path

/**
 * Name of the per-user data directory ListenUp creates for application state
 * (the SQLite database today; future app-managed files belong here too). The
 * user's audio library is a separate, independently-configured path and does
 * NOT live here.
 */
private const val DATA_DIR_NAME = "ListenUp"

private const val SQLITE_DB_FILENAME = "listenup.db"

/**
 * The default data home: a [DATA_DIR_NAME] subfolder of the user's home
 * directory (e.g. `~/ListenUp`). Pure — [userHome] is passed in so callers
 * read `System.getProperty("user.home")` at the edge.
 */
fun defaultListenupHome(userHome: String): Path = Path.of(userHome, DATA_DIR_NAME)

/**
 * Resolve the effective JDBC URL for the application database.
 *
 * - A non-blank [configuredUrl] (the `database.jdbcUrl` config / `LISTENUP_DB_URL`
 *   that tests inject) wins and is returned verbatim — no directory side effects.
 * - Otherwise the database maps to `listenup.db` inside [listenupHome], and that
 *   directory is created if it does not exist (SQLite will not create it).
 *
 * @param configuredUrl explicit JDBC URL, or blank to use the home default.
 * @param listenupHome data home to place the SQLite file in when defaulting.
 */
fun resolveDatabaseUrl(
    configuredUrl: String,
    listenupHome: Path,
): String {
    if (configuredUrl.isNotBlank()) return configuredUrl

    Files.createDirectories(listenupHome)
    val dbPath = listenupHome.resolve(SQLITE_DB_FILENAME)
    return "jdbc:sqlite:$dbPath"
}
