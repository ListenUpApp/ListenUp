# Finish Exposed Removal (steps 3 + 5) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the Exposed ORM and Hikari from `:server` entirely — unifying the JVM connection on a swappable SQLDelight `SqlDriver`, hardening it (effective `busy_timeout` + SQLITE_BUSY_SNAPSHOT retry), deleting the dead Exposed schema, and dropping the `exposed-*` deps — so `grep org.jetbrains.exposed server/src` = 0.

**Architecture:** JVM production reads/writes already run on a standalone `JdbcSqliteDriver`; this plan wraps that driver in a `SwappableSqlDriver` (so `ListenUpDatabase` survives a restore file-swap), feeds `MigrationRunner` a non-pooled `SQLiteDataSource` instead of Hikari, ports the transaction retry into the commonMain `suspendTransaction` helper via `expect/actual`, then deletes the Exposed bootstrap, schema objects, DAO entities, and base classes. Two phases: Phase 1 = Exposed-free + Hikari-free bootstrap; Phase 2 = delete the schema + drop deps.

**Tech Stack:** Kotlin Multiplatform (jvmMain + commonMain + linuxX64Main), SQLDelight 2.3.2 (`JdbcSqliteDriver` on JVM, `NativeSqliteDriver` on native), sqlite-jdbc 3.53.2.0 (`org.sqlite.SQLiteConfig`/`SQLiteDataSource`), Ktor, Koin, Kotest FunSpec, Gradle.

**Spec:** `docs/superpowers/specs/2026-06-21-finish-exposed-removal-steps-3-5-design.md`

---

## Conventions (read once, apply to every task)

- **Worktree:** all work in `/home/simonh/Code/lu-kn-phase1` on branch `feat/kn-port-phase1-exposed-removal`.
- **Gate command** (one invocation; judge by the FRESH test-results XML, not the piped exit code):
  ```bash
  cd /home/simonh/Code/lu-kn-phase1
  rm -rf server/build/test-tmp && find server/build/test-results/jvmTest -name '*.xml' -delete 2>/dev/null
  ./gradlew :server:compileKotlinLinuxX64 :server:jvmTest spotlessApply detekt --no-daemon --max-workers=4
  ```
  Green = `0 failed` in the XML tally. A lone `ScannerEndToEndTest` / `LibraryLessOnboardingE2ETest` / `ScannerSseRouteTest`
  failure is the known #21 mixed-engine flake **only while Hikari/Exposed coexist** (Tasks 1–5). After Task 6 (single engine)
  treat ANY scan-E2E failure as real. Confirm a suspected flake with a targeted re-run:
  `./gradlew :server:jvmTest --tests "*ScannerEndToEndTest*" --no-daemon`.
- `spotlessApply` and `detekt` are **root** tasks (`./gradlew spotlessApply detekt`), not `:server:`-scoped.
- **Commits:** subject-only, gitmoji + Conventional `type(scope):`. **NO AI attribution** (`Claude`/`Co-Authored-By`/`Generated with`) — a commit-msg hook strips it, but never add it. Verify with `git log -1 --format=%B`.
- **detekt gotcha:** detekt/spotless run after jvmTest and are skipped when a jvmTest failure fail-fasts — after a green test run, re-confirm `./gradlew detekt --rerun-tasks` is clean before committing.
- **Imports:** explicit, never wildcard (spotless can prune a wildcard-expanded name post-compile). 4-space indent.

---

## File Structure

**Phase 1 — created:**
- `server/src/jvmMain/.../db/SwappableSqlDriver.kt` — `SqlDriver` facade over a swappable `JdbcSqliteDriver`.
- `server/src/commonMain/.../db/sqldelight/RetryableSqliteError.kt` — `expect fun Throwable.isRetryableSqliteError()`.
- `server/src/jvmMain/.../db/sqldelight/RetryableSqliteError.jvm.kt` — JVM actual (SQLException codes).
- `server/src/linuxX64Main/.../db/sqldelight/RetryableSqliteError.linuxX64.kt` — native actual (message match).
- `server/src/jvmTest/.../backup/RestoreRoundTripTest.kt` — test-first proof the restore swap reaches repos.

**Phase 1 — modified:**
- `db/sqldelight/DriverFactory.jvm.kt` — connection-property PRAGMAs (effective busy_timeout/WAL; FK stays off).
- `db/sqldelight/Transactions.kt` — retry loop in `suspendTransaction`.
- `db/DatabaseFactory.kt` — drop Exposed `Database`/retry config + Hikari; build `SwappableSqlDriver` + `SQLiteDataSource`.
- `db/DatabaseHandle.kt` — hold `SwappableSqlDriver` + migration `DataSource`; drop the Exposed `database` field + Hikari.
- `db/SwappableDataSource.kt` — generalize to wrap a `DataSource` (drop the `HikariDataSource` type) **or** delete if unused after Task 6.
- `di/AuthModule.kt` — `single<SqlDriver> { get<DatabaseHandle>().sqlDriver }`; drop `single<Database>`.
- jvmTest conversions: `db/DatabaseHandleTest`, `db/DatabaseFactoryTest`, `db/TransactionRetryConcurrencyTest`, `di/AuthModuleVerifyTest`, `api/BackupServiceTest`, `backup/BackupTestSupport`, `backup/RestoreOrchestratorTest`.

**Phase 1 — deleted:** `db/SessionEntityTest.kt`, `db/UserEntityTest.kt`.

**Phase 2 — deleted:** `sync/SyncableRepository.kt`, `sync/SyncMeta.kt`, `sync/SyncableTable.kt`, the 24 `db/*Table.kt`, the 3 DAO entity files (`UserTable.kt`/`SessionTable.kt`/`InviteTable.kt` carry the entities).
**Phase 2 — modified:** `auth/AuthUser.kt` (inline `UserEntity.toContract`), `services/AuthServiceImpl.kt` (drop `UserEntity` import if present), `gradle/libs.versions.toml` + `server/build.gradle.kts` (drop `exposed-*`).

---

# PHASE 1 — Exposed-free, Hikari-free bootstrap + driver hardening

## Task 1: Harden the JVM driver (effective busy_timeout/WAL via connection properties)

**Files:**
- Modify: `server/src/jvmMain/kotlin/com/calypsan/listenup/server/db/sqldelight/DriverFactory.jvm.kt`

- [ ] **Step 1: Replace the one-time-PRAGMA driver build with connection properties**

Replace the whole `actual class DriverFactory` body:

```kotlin
package com.calypsan.listenup.server.db.sqldelight

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.sqlite.SQLiteConfig

/**
 * JVM actual: opens the SQLite file at [dbPath] via [JdbcSqliteDriver] with the project-standard
 * PRAGMAs applied as JDBC connection PROPERTIES (via [SQLiteConfig.toProperties]) so they take
 * effect on EVERY connection. JdbcSqliteDriver opens a connection per operation, so a one-time
 * `driver.execute("PRAGMA …")` only configures a transient connection and is silently lost — the
 * bug this avoids.
 *
 * - `journal_mode=WAL` — concurrent readers alongside a single writer.
 * - `busy_timeout=5000` — wait up to 5 s for a write-lock before SQLITE_BUSY.
 * - foreign_keys is intentionally LEFT OFF on JVM: enabling it changes live-scan insert ordering
 *   and breaks LibraryLessOnboardingE2ETest (202→404). The native actual enforces FK; closing that
 *   JVM/native divergence (FK-clean scan + prod FK) is a separate follow-up. SQLITE_BUSY_SNAPSHOT is
 *   handled by the retry in [suspendTransaction], not here.
 *
 * [Schema.create] is intentionally NOT called — [MigrationRunner] owns the schema history.
 */
actual class DriverFactory {
    actual fun createDriver(dbPath: String): SqlDriver =
        JdbcSqliteDriver(
            "jdbc:sqlite:$dbPath",
            SQLiteConfig()
                .apply {
                    busyTimeout = 5_000
                    setJournalMode(SQLiteConfig.JournalMode.WAL)
                }.toProperties(),
        )
}
```

- [ ] **Step 2: Gate**

Run the gate command. Expected: `BUILD` with 0 real failures (scan-E2E flake allowed at this stage).

- [ ] **Step 3: Commit**

```bash
git add server/src/jvmMain/kotlin/com/calypsan/listenup/server/db/sqldelight/DriverFactory.jvm.kt
git commit -m "🔧 fix(server): apply JVM SQLite PRAGMAs as connection properties, not one-time"
```

---

## Task 2: Port the SQLITE_BUSY_SNAPSHOT retry into `suspendTransaction`

**Files:**
- Create: `server/src/commonMain/kotlin/com/calypsan/listenup/server/db/sqldelight/RetryableSqliteError.kt`
- Create: `server/src/jvmMain/kotlin/com/calypsan/listenup/server/db/sqldelight/RetryableSqliteError.jvm.kt`
- Create: `server/src/linuxX64Main/kotlin/com/calypsan/listenup/server/db/sqldelight/RetryableSqliteError.linuxX64.kt`
- Modify: `server/src/commonMain/kotlin/com/calypsan/listenup/server/db/sqldelight/Transactions.kt`
- Test: `server/src/jvmTest/kotlin/com/calypsan/listenup/server/db/sqldelight/RetryableSqliteErrorTest.kt`

- [ ] **Step 1: Write the failing JVM matcher test**

Create `server/src/jvmTest/kotlin/com/calypsan/listenup/server/db/sqldelight/RetryableSqliteErrorTest.kt`:

```kotlin
package com.calypsan.listenup.server.db.sqldelight

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.sql.SQLException

class RetryableSqliteErrorTest :
    FunSpec({
        test("SQLITE_BUSY (5) is retryable") {
            SQLException("database is locked", "SQLITE_BUSY", 5).isRetryableSqliteError() shouldBe true
        }
        test("SQLITE_BUSY_SNAPSHOT (517) is retryable") {
            SQLException("snapshot superseded", "SQLITE_BUSY_SNAPSHOT", 517).isRetryableSqliteError() shouldBe true
        }
        test("a constraint violation (19) is NOT retryable") {
            SQLException("constraint failed", "SQLITE_CONSTRAINT", 19).isRetryableSqliteError() shouldBe false
        }
        test("a busy error wrapped in a cause chain is retryable") {
            RuntimeException("wrapper", SQLException("database is locked", "SQLITE_BUSY", 5))
                .isRetryableSqliteError() shouldBe true
        }
        test("a plain non-SQL exception is NOT retryable") {
            IllegalStateException("nope").isRetryableSqliteError() shouldBe false
        }
    })
```

- [ ] **Step 2: Run it to verify it fails (unresolved reference)**

Run: `./gradlew :server:compileTestKotlinJvm --no-daemon`
Expected: FAIL — `isRetryableSqliteError` unresolved.

- [ ] **Step 3: Add the expect declaration**

Create `server/src/commonMain/kotlin/com/calypsan/listenup/server/db/sqldelight/RetryableSqliteError.kt`:

```kotlin
package com.calypsan.listenup.server.db.sqldelight

/**
 * True when [this] (or any throwable in its cause chain) is a transient SQLite busy/locked error
 * that a transaction retry can clear — `SQLITE_BUSY` (5) or `SQLITE_BUSY_SNAPSHOT` (517).
 *
 * busy_timeout (set on the driver) already waits out ordinary SQLITE_BUSY; SQLITE_BUSY_SNAPSHOT is
 * returned immediately regardless of busy_timeout (the deferred snapshot is stale, not locked) — the
 * only cure is to re-run the transaction against the current snapshot, which [suspendTransaction] does.
 *
 * A non-busy error (constraint violation, syntax error, etc.) MUST return false so real failures are
 * never silently retried.
 */
expect fun Throwable.isRetryableSqliteError(): Boolean
```

- [ ] **Step 4: Add the JVM actual**

Create `server/src/jvmMain/kotlin/com/calypsan/listenup/server/db/sqldelight/RetryableSqliteError.jvm.kt`:

```kotlin
package com.calypsan.listenup.server.db.sqldelight

import java.sql.SQLException

private const val SQLITE_BUSY = 5
private const val SQLITE_BUSY_SNAPSHOT = 517

/**
 * JVM actual: walks the cause chain for a [SQLException] whose SQLite result code is SQLITE_BUSY (5)
 * or SQLITE_BUSY_SNAPSHOT (517). sqlite-jdbc puts the primary result code in [SQLException.errorCode].
 */
actual fun Throwable.isRetryableSqliteError(): Boolean {
    var cause: Throwable? = this
    while (cause != null) {
        if (cause is SQLException && (cause.errorCode == SQLITE_BUSY || cause.errorCode == SQLITE_BUSY_SNAPSHOT)) {
            return true
        }
        cause = cause.cause
    }
    return false
}
```

- [ ] **Step 5: Add the native actual (message-based, no SQLiter import → always compiles)**

Create `server/src/linuxX64Main/kotlin/com/calypsan/listenup/server/db/sqldelight/RetryableSqliteError.linuxX64.kt`:

```kotlin
package com.calypsan.listenup.server.db.sqldelight

/**
 * Native actual: SQLiter surfaces busy/locked as an exception whose message carries the SQLite text.
 * Match on the message across the cause chain (no SQLiter type import needed, so this always compiles
 * on linuxX64). The native server runs under low write-concurrency for now; tighten to a typed match
 * if a busy-snapshot storm is ever observed natively.
 */
actual fun Throwable.isRetryableSqliteError(): Boolean {
    var cause: Throwable? = this
    while (cause != null) {
        val msg = cause.message?.uppercase().orEmpty()
        if ("SQLITE_BUSY" in msg || "DATABASE IS LOCKED" in msg || "DATABASE IS BUSY" in msg) {
            return true
        }
        cause = cause.cause
    }
    return false
}
```

- [ ] **Step 6: Run the matcher test — verify it passes**

Run: `./gradlew :server:jvmTest --tests "*RetryableSqliteErrorTest" --no-daemon`
Expected: PASS (5 tests).

- [ ] **Step 7: Add the retry loop to `suspendTransaction`**

Replace the body of `server/src/commonMain/kotlin/com/calypsan/listenup/server/db/sqldelight/Transactions.kt` with:

```kotlin
package com.calypsan.listenup.server.db.sqldelight

import app.cash.sqldelight.TransactionWithReturn
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Max attempts for a transaction that keeps hitting a transient SQLite busy/snapshot error. */
private const val MAX_TX_ATTEMPTS = 10

/** Jitter bounds (ms) between retry attempts — mirrors the former Exposed retry config. */
private const val MIN_RETRY_DELAY_MS = 10L
private const val MAX_RETRY_DELAY_MS = 500L

/**
 * Runs [body] inside a SQLDelight [TransactionWithReturn] on [sqlIoDispatcher], returning [body]'s
 * result on commit. Mirrors Exposed's `suspendTransaction`: commits on normal return (including a
 * returned [AppResult.Failure]); an exception rolls back and re-throws.
 *
 * Retries the WHOLE transaction up to [MAX_TX_ATTEMPTS] times when it fails with a transient
 * [isRetryableSqliteError] (SQLITE_BUSY / SQLITE_BUSY_SNAPSHOT), with a jittered backoff between
 * attempts. This replaces the retry Exposed's DatabaseConfig used to provide; busy_timeout on the
 * driver covers plain SQLITE_BUSY, this covers SQLITE_BUSY_SNAPSHOT. [CancellationException] is never
 * retried.
 *
 * [sqlIoDispatcher] is expect/actual: [kotlinx.coroutines.Dispatchers.IO] on JVM,
 * [kotlinx.coroutines.Dispatchers.Default] on Kotlin/Native.
 */
suspend fun <T> suspendTransaction(
    db: ListenUpDatabase,
    body: TransactionWithReturn<T>.() -> T,
): T =
    withContext(sqlIoDispatcher) {
        var lastError: Throwable? = null
        repeat(MAX_TX_ATTEMPTS) { attempt ->
            try {
                return@withContext db.transactionWithResult { body() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (!e.isRetryableSqliteError()) throw e
                lastError = e
                if (attempt < MAX_TX_ATTEMPTS - 1) {
                    delay(Random.nextLong(MIN_RETRY_DELAY_MS, MAX_RETRY_DELAY_MS + 1))
                }
            }
        }
        throw lastError ?: IllegalStateException("suspendTransaction retry loop exited without a result")
    }
```

- [ ] **Step 8: Gate**

Run the gate command. Expected: 0 real failures. (`suspendTransaction` signature is unchanged, so all callers compile.)

- [ ] **Step 9: Commit**

```bash
git add server/src/commonMain/kotlin/com/calypsan/listenup/server/db/sqldelight/RetryableSqliteError.kt \
        server/src/jvmMain/kotlin/com/calypsan/listenup/server/db/sqldelight/RetryableSqliteError.jvm.kt \
        server/src/linuxX64Main/kotlin/com/calypsan/listenup/server/db/sqldelight/RetryableSqliteError.linuxX64.kt \
        server/src/commonMain/kotlin/com/calypsan/listenup/server/db/sqldelight/Transactions.kt \
        server/src/jvmTest/kotlin/com/calypsan/listenup/server/db/sqldelight/RetryableSqliteErrorTest.kt
git commit -m "✨ feat(server): retry SQLITE_BUSY_SNAPSHOT in suspendTransaction (expect/actual)"
```

---

## Task 3: Delete the Exposed DAO-entity tests

These test Exposed `UserEntity`/`SessionEntity` DAO mechanics (`.new`, FK navigation, cascade). Auth is SQLDelight-only; `usersQueries`/`sessionsQueries` carry the real coverage. The entities themselves are deleted in Phase 2; removing their tests first is safe.

**Files:**
- Delete: `server/src/jvmTest/kotlin/com/calypsan/listenup/server/db/SessionEntityTest.kt`
- Delete: `server/src/jvmTest/kotlin/com/calypsan/listenup/server/db/UserEntityTest.kt`

- [ ] **Step 1: Confirm no other test references these classes**

Run: `grep -rn 'SessionEntityTest\|UserEntityTest' server/src` — expect only the two files themselves.

- [ ] **Step 2: Delete**

```bash
git rm server/src/jvmTest/kotlin/com/calypsan/listenup/server/db/SessionEntityTest.kt \
       server/src/jvmTest/kotlin/com/calypsan/listenup/server/db/UserEntityTest.kt
```

- [ ] **Step 3: Gate**, then **Commit**

```bash
git commit -m "🔥 test(server): drop Exposed DAO-entity tests (auth is SQLDelight-only)"
```

---

## Task 4: Introduce `SwappableSqlDriver`; route repos through it (additive, Hikari still present)

This makes the repos' driver swappable without yet changing restore or dropping Hikari — purely additive, so the suite stays green.

**Files:**
- Create: `server/src/jvmMain/kotlin/com/calypsan/listenup/server/db/SwappableSqlDriver.kt`
- Modify: `server/src/jvmMain/kotlin/com/calypsan/listenup/server/db/DatabaseFactory.kt`
- Modify: `server/src/jvmMain/kotlin/com/calypsan/listenup/server/db/DatabaseHandle.kt`
- Modify: `server/src/jvmMain/kotlin/com/calypsan/listenup/server/di/AuthModule.kt`

- [ ] **Step 1: Create `SwappableSqlDriver`**

Create `server/src/jvmMain/kotlin/com/calypsan/listenup/server/db/SwappableSqlDriver.kt`:

```kotlin
package com.calypsan.listenup.server.db

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement

/**
 * A [SqlDriver] facade over a swappable underlying driver. Lets [ListenUpDatabase] (and every
 * repository that captured it) bind once to a stable object while the live driver underneath is
 * closed and rebuilt — the mechanism the restore orchestrator uses to free every SQLite file handle
 * before swapping the db file in place, then reopen on the swapped-in file.
 *
 * The SqlDriver twin of [SwappableDataSource]. No lock: between [closeUnderlying] and [installUnderlying]
 * the delegate is a closed driver, so a concurrent query fails fast (a closed driver can never serve
 * stale data). A restore is a rare admin operation; failing a request during the brief swap window is
 * the honest, simple choice.
 */
class SwappableSqlDriver(
    initial: SqlDriver,
) : SqlDriver {
    @Volatile
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
```

> If any `addListener`/`removeListener`/`notifyListeners` override fails to compile, run
> `javap -classpath <runtime-jvm-2.3.2.jar> app.cash.sqldelight.db.SqlDriver` and match the exact
> parameter shape (verified at plan time: `addListener(vararg queryKeys: String, listener: Query.Listener)`).

- [ ] **Step 2: Build the `SwappableSqlDriver` in `DatabaseFactory.init` and expose it on `DatabaseHandle`**

In `DatabaseFactory.kt`, inside `init`, after `val dbFile = …`, build the driver and pass it to the handle. Add to the top of `init`:

```kotlin
        val sqlDriver = SwappableSqlDriver(DriverFactory().createDriver(dbFile.toString()))
```
(Place `val dbFile = Path.of(config.jdbcUrl.removePrefix("jdbc:sqlite:"))` before this line if it isn't already above.)

Add the import `import com.calypsan.listenup.server.db.sqldelight.DriverFactory` and pass `sqlDriver = sqlDriver` to the `DatabaseHandle(...)` constructor (next step adds the param).

- [ ] **Step 3: Add the `sqlDriver` field to `DatabaseHandle`**

In `DatabaseHandle.kt`, add a constructor parameter and import:

```kotlin
import app.cash.sqldelight.db.SqlDriver
```
```kotlin
class DatabaseHandle(
    val database: Database,
    val sqlDriver: SwappableSqlDriver,
    private val dataSource: SwappableDataSource,
    private val poolFactory: () -> HikariDataSource,
    val dbFilePath: Path,
) {
```
(`SqlDriver` import is for later tasks; `SwappableSqlDriver` is same-package, no import needed.)

- [ ] **Step 4: Rewire `AuthModule` to serve the swappable driver to repos**

In `di/AuthModule.kt`, replace the `single<SqlDriver>` block:

```kotlin
        // The repos' driver IS the restore-swappable one held by DatabaseHandle, so a restore swap
        // reaches every repository. Resolving DatabaseHandle first forces MigrationRunner.migrate()
        // before the driver is first used.
        single<SqlDriver> { get<DatabaseHandle>().sqlDriver }
```

- [ ] **Step 5: Gate** (additive — no behaviour change yet; restore still only touches the Hikari pool). Expected 0 real failures.

- [ ] **Step 6: Commit**

```bash
git add server/src/jvmMain/kotlin/com/calypsan/listenup/server/db/SwappableSqlDriver.kt \
        server/src/jvmMain/kotlin/com/calypsan/listenup/server/db/DatabaseFactory.kt \
        server/src/jvmMain/kotlin/com/calypsan/listenup/server/db/DatabaseHandle.kt \
        server/src/jvmMain/kotlin/com/calypsan/listenup/server/di/AuthModule.kt
git commit -m "✨ feat(server): hold the repos' SqlDriver in a SwappableSqlDriver on DatabaseHandle"
```

---

## Task 5: Make restore close/reopen the `SwappableSqlDriver` (TEST-FIRST)

This fixes the latent restore bug: today restore swaps the db file under the Hikari pool but the repos'
driver is never closed/reopened, so post-restore repos may read the old file. Hikari is still present
(removed in Task 6) — this task only adds the driver to the close/reopen flow.

**Files:**
- Test: `server/src/jvmTest/kotlin/com/calypsan/listenup/server/backup/RestoreRoundTripTest.kt`
- Modify: `server/src/jvmMain/kotlin/com/calypsan/listenup/server/db/DatabaseHandle.kt`

- [ ] **Step 1: Write the failing restore round-trip test**

Create `server/src/jvmTest/kotlin/com/calypsan/listenup/server/backup/RestoreRoundTripTest.kt`. It drives a
real `DatabaseHandle` through `closePool → swap file → reopenPool` and asserts a `ListenUpDatabase` built on
the handle's `sqlDriver` sees the swapped-in data — proving the swap reaches repos.

```kotlin
package com.calypsan.listenup.server.backup

import com.calypsan.listenup.server.db.DatabaseConfig
import com.calypsan.listenup.server.db.DatabaseFactory
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class RestoreRoundTripTest :
    FunSpec({
        test("after closePool + db-file swap + reopenPool, repos on handle.sqlDriver see the swapped-in data") {
            // Two independent migrated dbs with a distinguishable row each.
            val liveFile = Files.createTempFile("listenup-live-", ".db").toFile().apply { deleteOnExit() }
            val replacementFile = Files.createTempFile("listenup-replacement-", ".db").toFile().apply { deleteOnExit() }

            val liveHandle = DatabaseFactory.init(DatabaseConfig(jdbcUrl = "jdbc:sqlite:${liveFile.absolutePath}"))
            val replacementHandle =
                DatabaseFactory.init(DatabaseConfig(jdbcUrl = "jdbc:sqlite:${replacementFile.absolutePath}"))

            // Seed a library row into the REPLACEMENT db, then close it so its file is consistent on disk.
            ListenUpDatabase(replacementHandle.sqlDriver).seedLibrary("from-replacement")
            replacementHandle.close()

            // The live db (no such row) is what repos currently read.
            val repos = ListenUpDatabase(liveHandle.sqlDriver)
            repos.librariesQueries.selectById("from-replacement").executeAsOneOrNull() shouldBe null

            // Restore: close live handles, swap the replacement file over the live path, reopen.
            liveHandle.closePool()
            Files.copy(replacementFile.toPath(), liveFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            Files.deleteIfExists(liveFile.toPath().resolveSibling("${liveFile.name}-wal"))
            Files.deleteIfExists(liveFile.toPath().resolveSibling("${liveFile.name}-shm"))
            liveHandle.reopenPool()

            // The SAME repos instance (bound to handle.sqlDriver) must now see the swapped-in row.
            repos.librariesQueries.selectById("from-replacement").executeAsOneOrNull()?.id shouldBe "from-replacement"

            liveHandle.close()
        }
    })

/** Minimal library-row seed via the generated queries (DDL-defaulted columns supplied explicitly). */
private fun ListenUpDatabase.seedLibrary(id: String) {
    val now = System.currentTimeMillis()
    librariesQueries.insert(
        id = id,
        name = "L",
        metadata_precedence = "embedded,abs,sidecar",
        access_mode = "shared",
        created_by_user_id = null,
        created_at = now,
        revision = 0L,
        updated_at = now,
        deleted_at = null,
        client_op_id = null,
    )
}
```

- [ ] **Step 2: Run it — verify it FAILS**

Run: `./gradlew :server:jvmTest --tests "*RestoreRoundTripTest" --no-daemon`
Expected: FAIL — `reopenPool()` today rebuilds only the Hikari pool, not `sqlDriver`, so `repos` still reads the old file (returns null), OR the assertion fails because the driver held a stale connection. This proves the gap.

> **Discovery point (spec caveat):** observe HOW it fails. If `repos` returns null (stale driver), Step 3's
> close/reopen of `sqlDriver` is the fix. If it instead throws on a closed/locked file, the `JdbcSqliteDriver`
> retained a connection — Step 3 still applies (closeUnderlying releases it); confirm WAL `-wal`/`-shm`
> handling matches `RestoreOrchestrator` (it deletes them). Do not proceed until the failure mode is understood.

- [ ] **Step 3: Add `sqlDriver` close/reopen to `DatabaseHandle.closePool`/`reopenPool`**

In `DatabaseHandle.kt`, fold the driver into the close/reopen lifecycle. Add an import and a private rebuild helper:

```kotlin
import com.calypsan.listenup.server.db.sqldelight.DriverFactory
```
Change `closePool`/`reopenPool`:

```kotlin
    /**
     * Hard-closes the live Hikari pool AND the repos' SQLDelight driver, deterministically releasing
     * every SQLite file handle before the db file is swapped. MUST be paired with [reopenPool].
     */
    fun closePool() {
        sqlDriver.closeUnderlying()
        dataSource.closeCurrent()
    }

    /**
     * Rebuilds the Hikari pool AND the SQLDelight driver on the (possibly just-swapped) db file, making
     * both live again. The SwappableSqlDriver identity is preserved so repositories keep working.
     */
    fun reopenPool() {
        dataSource.install(poolFactory())
        sqlDriver.installUnderlying(DriverFactory().createDriver(dbFilePath.toAbsolutePath().toString()))
    }
```

- [ ] **Step 4: Run the round-trip test — verify it PASSES**

Run: `./gradlew :server:jvmTest --tests "*RestoreRoundTripTest" --no-daemon`
Expected: PASS.

- [ ] **Step 5: Run the existing restore tests — verify still green**

Run: `./gradlew :server:jvmTest --tests "*RestoreOrchestratorTest" --tests "*BackupServiceTest" --no-daemon`
Expected: PASS (these still seed via `transaction(handle.database)` — that's fine until Task 6).

- [ ] **Step 6: Gate**, then **Commit**

```bash
git add server/src/jvmMain/kotlin/com/calypsan/listenup/server/db/DatabaseHandle.kt \
        server/src/jvmTest/kotlin/com/calypsan/listenup/server/backup/RestoreRoundTripTest.kt
git commit -m "🐛 fix(server): close/reopen the repos' SqlDriver across a restore swap"
```

---

## Task 6: Drop Hikari + the dead Exposed `Database`; convert the dependent tests

The last Exposed-infrastructure task. Replaces the Hikari pool with a non-pooled `SQLiteDataSource` for
migrations + vacuum, removes the dead Exposed `Database`/retry config, and converts the 6 tests that touch
`handle.database`/Hikari. After this, jvmMain Exposed = only the schema layer (deleted in Phase 2).

**Files:**
- Modify: `db/MigrationRunner.kt`, `db/SwappableDataSource.kt`, `db/DatabaseFactory.kt`, `db/DatabaseHandle.kt`, `di/AuthModule.kt`
- Modify (tests): `db/DatabaseHandleTest.kt`, `db/DatabaseFactoryTest.kt`, `db/TransactionRetryConcurrencyTest.kt`, `di/AuthModuleVerifyTest.kt`, `api/BackupServiceTest.kt`, `backup/BackupTestSupport.kt`, `backup/RestoreOrchestratorTest.kt`

> **Why `MigrationRunner` changes first:** it calls `conn.commit()`/`conn.rollback()` but never sets
> `autoCommit = false` — today it relies on Hikari's `isAutoCommit = false` pool setting. A plain non-pooled
> `SQLiteDataSource` hands out `autoCommit = true` connections, so without this fix every migration statement
> would auto-commit individually and a failed migration could not roll back. Make `MigrationRunner` self-sufficient.

- [ ] **Step 0: Make `MigrationRunner` set `autoCommit = false` on its write connection(s)**

In `MigrationRunner.kt`, immediately inside each `dataSource.connection.use { conn -> … }` block that performs
writes (the migrate loop that calls `conn.commit()`/`conn.rollback()` — around lines 19 and the `applyOne` path),
add `conn.autoCommit = false` as the first statement. Read paths (`currentSchemaVersion`) may be left as-is
(they only read). Example shape:

```kotlin
dataSource.connection.use { conn ->
    conn.autoCommit = false
    // … ensureHistoryTable / applyOne … conn.commit() / conn.rollback() …
}
```

Run `./gradlew :server:jvmTest --tests "*MigrationRunnerTest" --no-daemon` — expected PASS (still on Hikari here,
so this is behaviour-preserving; it makes the runner DataSource-agnostic for Step 2's swap).

- [ ] **Step 1: Generalize `SwappableDataSource` to wrap a plain `DataSource`**

In `SwappableDataSource.kt`: change the type parameter from `HikariDataSource` to `javax.sql.DataSource`,
drop the Hikari import, and change `closeCurrent()`/`close()` to close only if the delegate is `AutoCloseable`:

```kotlin
package com.calypsan.listenup.server.db

import java.io.PrintWriter
import java.sql.Connection
import java.util.logging.Logger
import javax.sql.DataSource

/**
 * A [DataSource] facade over a swappable delegate. Lets a long-lived consumer (MigrationRunner /
 * vacuum) bind once while the live delegate underneath is replaced — used by restore to rebuild the
 * connection source on the swapped-in db file. Backed by a non-pooled [org.sqlite.SQLiteDataSource]
 * (each getConnection opens a fresh connection the caller closes), so there is no pool to leak.
 */
class SwappableDataSource(
    initial: DataSource,
) : DataSource {
    @Volatile
    private var delegate: DataSource = initial

    fun current(): DataSource = delegate

    fun closeCurrent() = (delegate as? AutoCloseable)?.close() ?: Unit

    fun install(new: DataSource) {
        delegate = new
    }

    fun close() = (delegate as? AutoCloseable)?.close() ?: Unit

    fun isClosed(): Boolean = false // non-pooled SQLiteDataSource holds no persistent connection

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
```

> Note: if `DatabaseHandle.isPoolClosed()` (used by a test) relied on Hikari's `isClosed`, it now maps to
> `SwappableDataSource.isClosed()` above (always false for a non-pooled source) — adjust that test in Step 6.

- [ ] **Step 2: Rebuild `DatabaseFactory` without Hikari or the Exposed `Database`**

Replace `DatabaseFactory.kt` with (drops `com.zaxxer.hikari.*`, `org.jetbrains.exposed.*`, `retryDatabaseConfig`,
`buildPool`; builds a non-pooled `SQLiteDataSource` with the same PRAGMAs as connection properties):

```kotlin
package com.calypsan.listenup.server.db

import com.calypsan.listenup.server.db.sqldelight.DriverFactory
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource
import java.nio.file.Path

/**
 * Database connection settings. JDBC URL is the only required input; for SQLite, username/password
 * are unused (kept for future-proofing).
 */
data class DatabaseConfig(
    val jdbcUrl: String,
    val username: String = "",
    val password: String = "",
)

/**
 * Runs schema migrations and returns a [DatabaseHandle] exposing the repos' restore-swappable
 * [SwappableSqlDriver] and a non-pooled migration/vacuum [SwappableDataSource]. No Exposed, no Hikari:
 * the SQLDelight driver is the single connection authority; migrations + VACUUM use a non-pooled
 * sqlite-jdbc [SQLiteDataSource] (each connection opened+closed per use). Idempotent migrations.
 */
object DatabaseFactory {
    /** SQLite waits up to this many ms for a write-lock before SQLITE_BUSY (matches the driver). */
    private const val BUSY_TIMEOUT_MS = 5_000

    fun init(config: DatabaseConfig): DatabaseHandle {
        val dbFile = Path.of(config.jdbcUrl.removePrefix("jdbc:sqlite:"))
        val dataSource = SwappableDataSource(buildDataSource(config))

        MigrationRunner(dataSource).migrate()

        val sqlDriver = SwappableSqlDriver(DriverFactory().createDriver(dbFile.toString()))
        return DatabaseHandle(
            sqlDriver = sqlDriver,
            dataSource = dataSource,
            dataSourceFactory = { buildDataSource(config) },
            dbFilePath = dbFile,
        )
    }

    /**
     * A non-pooled [SQLiteDataSource] with WAL + busy_timeout as connection properties (FK left off on
     * JVM — see DriverFactory.jvm.kt). Used only for migrations + VACUUM (sequential, rare) — no pool.
     */
    internal fun buildDataSource(config: DatabaseConfig): SQLiteDataSource =
        SQLiteDataSource(
            SQLiteConfig().apply {
                busyTimeout = BUSY_TIMEOUT_MS
                setJournalMode(SQLiteConfig.JournalMode.WAL)
            },
        ).apply { url = config.jdbcUrl }
}
```

> If `DatabaseFactoryTest`/`DatabaseHandleTest` referenced `DatabaseFactory.buildPool`, that name is gone —
> they are rewritten in Step 6.

- [ ] **Step 3: Rebuild `DatabaseHandle` without Hikari or the Exposed `Database`**

Replace `DatabaseHandle.kt`:

```kotlin
package com.calypsan.listenup.server.db

import com.calypsan.listenup.server.db.sqldelight.DriverFactory
import java.nio.file.Path
import javax.sql.DataSource

/**
 * Owns the repos' restore-swappable [SwappableSqlDriver], the non-pooled migration/vacuum
 * [SwappableDataSource], and the on-disk db file, so the restore orchestrator can close every handle,
 * swap the file in place, reopen, and migrate forward while keeping the [SwappableSqlDriver] (and thus
 * every repository bound to it) identity-stable.
 */
class DatabaseHandle(
    val sqlDriver: SwappableSqlDriver,
    private val dataSource: SwappableDataSource,
    private val dataSourceFactory: () -> DataSource,
    val dbFilePath: Path,
) {
    /**
     * VACUUM INTO a transactionally-consistent standalone copy at [target] (WAL-safe). SQLite requires
     * it run outside a transaction, so we use a raw auto-commit JDBC connection.
     */
    fun vacuumInto(target: Path) {
        val escapedPath = target.toAbsolutePath().toString().replace("'", "''")
        dataSource.connection.use { conn ->
            val wasAutoCommit = conn.autoCommit
            conn.autoCommit = true
            try {
                conn.createStatement().use { stmt -> stmt.execute("VACUUM INTO '$escapedPath'") }
            } finally {
                conn.autoCommit = wasAutoCommit
            }
        }
    }

    /**
     * Hard-closes the repos' SQLDelight driver and the migration data source, releasing every SQLite
     * file handle before the db file is swapped. MUST be paired with [reopenPool].
     */
    fun closePool() {
        sqlDriver.closeUnderlying()
        dataSource.closeCurrent()
    }

    /** Rebuilds the driver + data source on the (possibly just-swapped) db file; identity preserved. */
    fun reopenPool() {
        dataSource.install(dataSourceFactory())
        sqlDriver.installUnderlying(DriverFactory().createDriver(dbFilePath.toAbsolutePath().toString()))
    }

    /** Runs migrations forward against the current db file. Returns the post-migration version. */
    fun migrate(): String? = MigrationRunner(dataSource).migrate()

    /** The applied schema version, or null on a fresh/empty db. */
    fun currentSchemaVersion(): String? = MigrationRunner(dataSource).currentSchemaVersion()

    /** Terminal shutdown — closes the driver and data source; nothing reopens. Idempotent. */
    fun close() {
        sqlDriver.close()
        dataSource.close()
    }

    /** Test-only: the live DataSource, for schema-dump / raw-seed assertions. */
    internal fun dataSourceForTest(): DataSource = dataSource
}
```

- [ ] **Step 4: Drop `single<Database>` from `AuthModule`**

In `di/AuthModule.kt`, delete the line `single<Database> { get<DatabaseHandle>().database }` and remove
`import org.jetbrains.exposed.v1.jdbc.Database`. (`single<DatabaseHandle>`, `single<SqlDriver>`, `single<ListenUpDatabase>` stay.)

- [ ] **Step 5: Verify production jvmMain is Exposed-free except the schema layer**

Run: `grep -rln 'org.jetbrains.exposed' server/src/jvmMain | grep -v 'Table.kt' | grep -vE 'SyncableRepository.kt|SyncMeta.kt'`
Expected: EMPTY (only `*Table.kt` + the two sync bases remain, all deleted in Phase 2).
Run: `grep -rn 'com.zaxxer.hikari' server/src/jvmMain` → expect empty.

- [ ] **Step 6: Convert the dependent tests**

Read each file first, then apply:

- `di/AuthModuleVerifyTest.kt`: remove `import org.jetbrains.exposed.v1.jdbc.Database` and drop `Database::class`
  from the `verify { extraTypes = listOf(...) }` whitelist. Confirm the Koin graph still resolves.
- `db/DatabaseHandleTest.kt`: replace each `transaction(handle.database) { … Exposed DSL … }` with raw JDBC over
  `handle.dataSourceForTest().connection.use { conn -> conn.createStatement().use { it.execute("…") } }` (or
  `executeQuery` for asserts). Keep the vacuum + close/reopen assertions; if it asserted `isPoolClosed()`, assert
  on observable behaviour instead (e.g. a query after `closePool()` throws / after `reopenPool()` succeeds).
- `db/DatabaseFactoryTest.kt`: drop the Hikari pool-exhaustion test; keep/rewrite a test that
  `DatabaseFactory.init(...)` runs migrations (assert `currentSchemaVersion()` is non-null) and yields a working
  `sqlDriver` (run a trivial query through `ListenUpDatabase(handle.sqlDriver)`).
- `db/TransactionRetryConcurrencyTest.kt`: retarget from the Exposed retry to the new `suspendTransaction` retry —
  drive N concurrent `suspendTransaction(db) { … write … }` against one file and assert all succeed (no
  unretried SQLITE_BUSY_SNAPSHOT). Use `ListenUpDatabase(handle.sqlDriver)`; remove all Exposed imports.
- `api/BackupServiceTest.kt`, `backup/BackupTestSupport.kt`, `backup/RestoreOrchestratorTest.kt`: replace every
  `transaction(fixture.handle.database) { <ExposedTable>.insert{…} / selectAll() }` with raw JDBC over
  `fixture.handle.dataSourceForTest().connection` (INSERT via `PreparedStatement`, assert via `executeQuery`),
  or seed via the SQLDelight generated queries on `ListenUpDatabase(fixture.handle.sqlDriver)` where natural.
  Remove all `org.jetbrains.exposed` imports.

Representative seed/assert transformation (apply per occurrence):

```kotlin
// BEFORE (Exposed):
transaction(handle.database) {
    SomeTable.insert { it[SomeTable.id] = "x"; it[SomeTable.name] = "n" }
}
// AFTER (raw JDBC over the non-pooled DataSource):
handle.dataSourceForTest().connection.use { conn ->
    conn.prepareStatement("INSERT INTO some_table (id, name) VALUES (?, ?)").use { ps ->
        ps.setString(1, "x"); ps.setString(2, "n"); ps.executeUpdate()
    }
}
```

- [ ] **Step 7: Verify production Hikari is gone (test infra handled next, in Task 6b)**

Run: `grep -rn 'com.zaxxer.hikari' server/src/jvmMain` → **expect empty** (production is off Hikari).
Run: `grep -rln 'com.zaxxer.hikari' server/src/jvmTest` → expect ONLY the ~8 migration/schema/swappable test-infra
files (converted in Task 6b): `db/SwappableDataSourceTest`, `db/SqlDelightMigrationSchemaDriftTest`,
`db/MigrationRunnerTest`, `db/SchemaSnapshotMain`, `sync/SyncableTableBackfillMigrationTest`,
`sync/CollectionGrantsRenameMigrationTest`, `sync/CollectionTypeMigrationTest`, `di/AuthModuleVerifyTest`.
(`Application.kt` + `absimport/AbsBackupReader.kt` mention "Hikari" only in comments — update those comments here.)

- [ ] **Step 8: Gate** — full suite. After this task the engine is single (SQLDelight only); the #21 scan-E2E
flake should no longer appear. If a scan-E2E test fails, investigate as a REAL failure (re-run targeted; if it
reproduces, it is not a flake). Expected: `0 failed`.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "♻️ refactor(server): drop Hikari + the dead Exposed Database from the bootstrap"
```

---

## Task 6b: Convert the Hikari-using test infra off Hikari

~8 jvmTest files build a `HikariDataSource` directly to feed `MigrationRunner` (migration/schema-drift/snapshot
tests), test `SwappableDataSource` with Hikari, or whitelist `HikariDataSource` in a Koin verify. All must move to
the non-pooled `org.sqlite.SQLiteDataSource` so the `hikari` dependency can be dropped (Task 8). The transformation
is uniform.

**Files (modify):** `db/SwappableDataSourceTest.kt`, `db/SqlDelightMigrationSchemaDriftTest.kt`,
`db/MigrationRunnerTest.kt`, `db/SchemaSnapshotMain.kt`, `sync/SyncableTableBackfillMigrationTest.kt`,
`sync/CollectionGrantsRenameMigrationTest.kt`, `sync/CollectionTypeMigrationTest.kt`, `di/AuthModuleVerifyTest.kt`.

- [ ] **Step 1: (Optional but DRY) add a shared file-backed test DataSource helper**

If the migration tests share an identical `freshDataSource()`, add one helper instead of repeating the swap.
Create/extend a testing helper (e.g. append to `server/src/jvmTest/.../testing/TestDatabase.kt`):

```kotlin
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource

/**
 * A non-pooled file-backed [SQLiteDataSource] for migration/schema tests that drive [MigrationRunner]
 * directly. WAL + busy_timeout as connection properties; the temp file is the caller's to delete.
 */
fun fileBackedTestDataSource(jdbcUrl: String): SQLiteDataSource =
    SQLiteDataSource(
        SQLiteConfig().apply {
            busyTimeout = 5_000
            setJournalMode(SQLiteConfig.JournalMode.WAL)
        },
    ).apply { url = jdbcUrl }
```

- [ ] **Step 2: Convert each file — uniform transformation**

Per file: replace the Hikari construction and imports.

```kotlin
// BEFORE:
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
val ds = HikariDataSource(HikariConfig().apply {
    jdbcUrl = "jdbc:sqlite:$path"; maximumPoolSize = 1; isAutoCommit = false
    addDataSourceProperty("foreign_keys", "true"); validate()
})
// AFTER (shared helper):
import com.calypsan.listenup.server.testing.fileBackedTestDataSource
val ds = fileBackedTestDataSource("jdbc:sqlite:$path")
```

Notes:
- `MigrationRunner(ds)` is unchanged (it now sets its own `autoCommit=false` — Task 6 Step 0), so dropping the
  Hikari `isAutoCommit=false` is safe.
- Any `ds.close()` calls: `SQLiteDataSource` has no `close()` — delete those calls (a non-pooled source holds no
  connections; callers close their own via `.use {}`). If a file truly needs cleanup, close individual connections.
- `db/SwappableDataSourceTest.kt`: it tests the swap behaviour — rebuild it around two `SQLiteDataSource`s (or two
  temp files) proving `install()` swaps the delegate and `closeCurrent()` no-ops cleanly on a non-pooled source.
  Drop the Hikari imports.
- `di/AuthModuleVerifyTest.kt`: remove `HikariDataSource` (and `Database`, per Task 6 Step 6) from the Koin
  `verify { extraTypes = listOf(...) }` whitelist + drop both imports.
- `db/SchemaSnapshotMain.kt`: a `main()` utility — same swap; verify it still compiles (it may not run in the suite).

- [ ] **Step 3: Verify Hikari is gone from all of `server/src`**

Run: `grep -rn 'com.zaxxer.hikari\|HikariDataSource\|HikariConfig' server/src` → **expect 0**.
(Comment mentions in `Application.kt`/`AbsBackupReader.kt` were updated in Task 6 Step 7.)

- [ ] **Step 4: Gate**, then **Commit**

```bash
git add -A
git commit -m "🧪 test(server): migration/schema test infra off Hikari onto SQLiteDataSource"
```

---

# PHASE 2 — Delete the Exposed schema + drop the deps

## Task 7: Inline `UserEntity.toContract`, then delete the schema, entities, and sync bases

**Files:**
- Modify: `server/src/jvmMain/.../auth/AuthUser.kt` (+ `services/AuthServiceImpl.kt` if it imports `UserEntity`)
- Delete: `sync/SyncableRepository.kt`, `sync/SyncMeta.kt`, `sync/SyncableTable.kt`, the 24 `db/*Table.kt`

- [ ] **Step 1: Find `UserEntity.toContract`'s definition and uses**

Run: `grep -rn 'toContract\|UserEntity' server/src/jvmMain`
Identify the `UserEntity.toContract()` definition (in `AuthUser.kt`) and its caller(s).

- [ ] **Step 2: Replace the `UserEntity`-based conversion with a row/enum conversion**

In `AuthUser.kt`, the existing `fun UserEntity.toContract(): User = …` maps Exposed columns to the contract
`User`. Determine its current call site (run `grep -rn '.toContract()' server/src/jvmMain`). If the only caller is
itself test/dead, delete the function; if a production path calls it, replace that path with the SQLDelight
projection already used by the auth service (e.g. map a `usersQueries` row, or `UserRole.valueOf(roleString)`).
Show the concrete edit after reading the file — the conversion is a 1:1 field map (no new logic).

- [ ] **Step 3: Confirm the entities + tables are now reference-free**

Run: `grep -rn 'UserEntity\|SessionEntity\|InviteEntity' server/src` → expect 0.
Run: for each `db/*Table.kt` object name, `grep -rn '<TableName>' server/src` → expect only its own file.
Run: `grep -rn 'SyncableRepository\|SyncMeta\|SyncableTable\|UserScopedSyncableTable' server/src` → expect only their own files.

- [ ] **Step 4: Delete the dead Exposed files**

```bash
git rm server/src/jvmMain/kotlin/com/calypsan/listenup/server/sync/SyncableRepository.kt \
       server/src/jvmMain/kotlin/com/calypsan/listenup/server/sync/SyncMeta.kt \
       server/src/jvmMain/kotlin/com/calypsan/listenup/server/sync/SyncableTable.kt
git rm server/src/jvmMain/kotlin/com/calypsan/listenup/server/db/*Table.kt
```
(`*Table.kt` includes the 3 DAO-entity files `UserTable.kt`/`SessionTable.kt`/`InviteTable.kt`. If any non-Exposed
file is named `*Table.kt`, exclude it — verify each removed file imports `org.jetbrains.exposed` first:
`grep -L org.jetbrains.exposed server/src/jvmMain/kotlin/com/calypsan/listenup/server/db/*Table.kt` should be empty.)

- [ ] **Step 5: Gate.** Expected: compile clean, `0 failed`. If compile fails, an undeleted consumer remains —
fix it (it is real). 

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "🔥 refactor(server): delete the Exposed schema, DAO entities, and sync bases"
```

---

## Task 8: Drop the `exposed-*` dependencies; verify grep = 0

**Files:**
- Modify: `gradle/libs.versions.toml`, `server/build.gradle.kts`

- [ ] **Step 1: Remove the Exposed + Hikari catalog entries**

In `gradle/libs.versions.toml`, delete the `exposed` version + the `exposed-core`/`exposed-dao`/`exposed-jdbc`/`exposed-kotlin-datetime`
library aliases, AND the `hikari` version + alias (all Hikari references were removed in Tasks 6 + 6b). Keep `sqlite-jdbc`.
Sanity-check: `grep -rn 'com.zaxxer.hikari\|HikariDataSource\|HikariConfig' server/src` = 0 before removing the dep.

- [ ] **Step 2: Remove the Exposed + Hikari deps from the server build**

In `server/build.gradle.kts`, delete the `implementation(libs.exposed.core)` / `.dao` / `.jdbc` /
`.kotlin.datetime` lines and `implementation(libs.hikari)`. Keep `implementation(libs.sqlite.jdbc)`.

- [ ] **Step 3: Verify zero Exposed across the module**

Run: `grep -rn 'org.jetbrains.exposed' server/src` → **expect 0**.
Run: `grep -rn 'exposed' gradle/libs.versions.toml server/build.gradle.kts` → expect 0 (or only unrelated matches).

- [ ] **Step 4: Gate** (the dependency removal forces a clean recompile). Expected: `BUILD SUCCESSFUL`, `0 failed`,
native compile green.

- [ ] **Step 5: Commit**

```bash
git add gradle/libs.versions.toml server/build.gradle.kts
git commit -m "🔥 chore(server): drop the exposed-* dependencies — Exposed fully removed"
```

- [ ] **Step 6: Final verification + memory update**

Run the full gate one more time from clean. Confirm `grep org.jetbrains.exposed server/src` = 0. Update
`MEMORY.md` + `kn_server_native_port.md`: steps 3 + 5 complete, Exposed/Hikari gone, note the FK-clean-scan +
prod-FK follow-up remains, and the native restore/migration path remains a P3/P4 native-tail concern.

---

## Self-review notes (coverage check against the spec)

- Spec 1a (SwappableSqlDriver + non-pooled SQLiteDataSource + AuthModule rewire) → Tasks 4, 6.
- Spec 1b (drop dead Exposed Database) → Task 6 Steps 2–4.
- Spec 1c (driver hardening, FK off) → Task 1.
- Spec 1d (retry expect/actual) → Task 2.
- Spec 1e (9 test files) → Task 3 (delete 2) + Task 6 Step 6 (convert/rewrite 7).
- Spec restore-correctness risk (test-first) → Task 5.
- **Drop Hikari (Decision 2)** → Task 6 (prod: MigrationRunner autocommit + DatabaseFactory/Handle/SwappableDataSource)
  + **Task 6b (the ~8 Hikari test-infra files the survey missed)** + Task 8 (drop the dep). NOTE: the survey/spec said
  "Hikari only in DatabaseFactory" — INCORRECT; production is contained but ~8 jvmTest infra files build HikariDataSource
  directly, and `MigrationRunner` relied on Hikari's `isAutoCommit=false`. Both corrected here.
- Spec Phase 2 (delete schema/entities/bases + inline toContract + drop deps) → Tasks 7, 8.
- Out-of-scope (FK-clean scan / prod FK / native restore) → recorded in Task 1 KDoc + Task 8 Step 6 memory note.
