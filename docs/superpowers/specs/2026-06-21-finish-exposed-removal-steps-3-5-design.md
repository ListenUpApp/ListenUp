# Finish Exposed removal — steps 3 + 5

**Date:** 2026-06-21
**Branch:** `feat/kn-port-phase1-exposed-removal` (HEAD `4d34411c5` at design time)
**Umbrella:** [2026-06-19-kn-server-native-port-design.md](2026-06-19-kn-server-native-port-design.md) ·
Phase 1 SQLDelight cutover: [2026-06-19-kn-port-phase1-sqldelight-design.md](2026-06-19-kn-port-phase1-sqldelight-design.md)

## Context

The Kotlin/Native server port replaces Exposed (a JVM-only ORM) with SQLDelight so `:server`
can compile to `linuxX64`. The cutover has landed in stages:

- **Step 1–2** (merged / on-branch): every production aggregate moved to `SqlSyncableRepository` /
  plain SQLDelight. `commonMain` + `linuxX64Main` are already 0-Exposed → the native target is unblocked.
- **Step 4** (just completed, commits `a4d9b2935`…`4d34411c5`): the jvmTest *Exposed test bridge*
  (`withInMemoryDatabase` / `asSqlDatabase` / `asSqlDriver` / the `SqlTestDatabases.exposed` field /
  the Exposed seed helpers) is deleted. `grep -E 'withInMemoryDatabase|asSqlDatabase|asSqlDriver' server/src/jvmTest` = 0.

What remains is Exposed *infrastructure*: the engine bootstrap (`DatabaseFactory`/`DatabaseHandle` +
Hikari + a now-dead `single<Database>`), the Exposed `SyncableRepository`/`SyncMeta`/`SyncableTable`
base classes, the 24 `Table.kt` schema objects, the 3 DAO entities (`UserEntity`/`SessionEntity`/`InviteEntity`),
and 9 jvmTest files that still touch Exposed. `grep org.jetbrains.exposed server/src` is currently 5 (non-Table jvmMain)
+ 24 (Table.kt) + 9 (jvmTest) ≈ 38 files.

### What the survey found (load-bearing facts)

- **The prod `SqlDriver` is standalone.** `AuthModule` registers `single<SqlDriver> { DriverFactory().createDriver(path) }`
  — a `JdbcSqliteDriver` that is **not** Hikari-backed. All repositories run on it via `single<ListenUpDatabase>`.
- **Hikari now serves only:** migrations (`MigrationRunner(pool)`), restore (`DatabaseHandle.closePool/reopenPool/vacuumInto`),
  and the **dead** Exposed `Database` (`single<Database> { handle.database }`, no production consumer).
- **`DriverFactory.jvm.kt` is buggy.** It applies `busy_timeout`/`foreign_keys` via one-time
  `driver.execute("PRAGMA …")`. `JdbcSqliteDriver` opens a connection per operation, so a post-open PRAGMA
  configures only a transient connection and is silently lost (the same bug step 4 fixed in `withSqlDatabase`).
  Net effect on JVM: `busy_timeout` ≈ off, `foreign_keys` ≈ off. (WAL persists because it is a database-file
  header setting, not per-connection.)
- **`DriverFactory.linuxX64.kt` is correct.** SQLiter applies WAL + `busyTimeout=5000` + `foreignKeyConstraints=true`
  at connection-open via `DatabaseConfiguration` — always effective. → **JVM/native divergence:** native has FK **on**,
  JVM has it effectively **off**.
- **No prod retry.** The 10-attempt SQLITE_BUSY_SNAPSHOT retry lived in `DatabaseFactory.retryDatabaseConfig()`,
  applied to the Exposed `Database`. Since prod went SQLDelight-only (step 2b), prod has had **no transaction retry** —
  `suspendTransaction` (commonMain) wraps `db.transactionWithResult` with no retry.
- **Backup/restore is already Exposed-free.** `BackupArchive`/`RestoreOrchestrator`/`BackupPaths`/`MaintenanceState`/`BackupManifest`
  use only `DatabaseHandle` pool methods + `SwappableDataSource` + SQLDelight.
- **The 3 sync base classes are fully dead.** `SyncableRepository`/`SyncMeta`/`SyncableTable` are referenced only by
  each other (and step-4-already-ported test fixtures). `MigrationRunner` is pure JDBC (zero Exposed).
- **DAO entities:** `SessionEntity`/`InviteEntity` are 0-use in prod; `UserEntity` has one nominal prod use
  (`AuthUser.toContract` + a type import in `AuthServiceImpl`).
- **Catalog:** `exposed-core/dao/jdbc/kotlin-datetime` @1.3.0, `hikari` @7.1.0, `sqlite-jdbc` @3.53.2.0 —
  all `implementation` of `server` jvmMain (`server/build.gradle.kts` ~lines 114–119). Hikari used only in `DatabaseFactory`.

## Goal & success criteria

Remove Exposed and Hikari from `:server` entirely, preserving production transaction reliability.

- `grep org.jetbrains.exposed server/src` = **0**.
- `com.zaxxer.hikari` removed from code and the version catalog.
- The SQLDelight `SqlDriver` is the single connection authority on JVM (matching native).
- `busy_timeout` is effective on JVM; SQLITE_BUSY_SNAPSHOT retry exists on both platforms.
- Restore (close → swap db file → reopen → migrate) is correct for the repos' `SqlDriver`
  (a latent gap today, since restore never touches that driver).
- `:server:compileKotlinLinuxX64 :server:jvmTest spotlessApply detekt` green per phase.

## Decisions (locked with the user)

1. **Full driver hardening incl. retry** — fix `busy_timeout`/WAL via connection properties **and** port the
   SQLITE_BUSY_SNAPSHOT retry into `suspendTransaction` (cross-platform via expect/actual).
2. **Drop Hikari now** — rework migrations + restore off the Hikari pool; unify the repos' connection on a
   swappable `JdbcSqliteDriver`.
3. **One spec, two phases** (this doc); gate per phase.
4. **FK stays OFF on JVM** — enabling it breaks scan insert-ordering (`LibraryLessOnboardingE2ETest` 202→404).
   The JVM(FK-off)/native(FK-on) divergence is documented and accepted; making it consistent (FK-clean scan +
   prod FK enforcement) is an explicit **out-of-scope follow-up** (see below).
5. **Test-first on the restore rework** — the riskiest change; a failing restore round-trip test pins behavior first.

## Phase 1 — Exposed-free, Hikari-free bootstrap + driver hardening

### 1a. Unify the connection on a swappable `SqlDriver`; drop Hikari

Introduce **`SwappableSqlDriver`** (jvmMain, `db/` package) — a thin `SqlDriver` facade over a swappable
underlying `JdbcSqliteDriver`, mirroring the existing `SwappableDataSource` pattern:

- delegates all `SqlDriver` calls to a `@Volatile` underlying driver;
- `closeCurrent()` closes the underlying driver (releases SQLite file handles before a swap);
- `install(new)` swaps in a freshly-built driver;
- `close()` terminal-closes.

`ListenUpDatabase` binds once to the stable `SwappableSqlDriver`, so repositories keep working across a
restore swap. **This closes a latent restore bug**: today restore swaps the db file under the Hikari pool while
the repos' `JdbcSqliteDriver` is never closed/reopened.

`MigrationRunner` keeps its `javax.sql.DataSource` contract, fed by a **non-pooled `org.sqlite.SQLiteDataSource`**
(sqlite-jdbc, retained) instead of `HikariDataSource`. Migrations are sequential and rare — no pool needed; a
non-pooled source holds no persistent connections (each `getConnection().use {}` opens+closes), so it contributes
no lingering file handles to the swap.

`DatabaseFactory.init(config)` returns a `DatabaseHandle` carrying: the `SwappableSqlDriver`, the migration
`DataSource`, and the db file `Path` — **no Exposed `Database`, no Hikari**. It runs `MigrationRunner(dataSource).migrate()`
once (as today) before the driver is first used.

`AuthModule` DI is rewired so the repos' driver IS the swappable one: `single<SqlDriver> { get<DatabaseHandle>().sqlDriver }`
(replacing today's `single<SqlDriver> { DriverFactory().createDriver(path) }`, which builds a *separate*, non-swappable
driver). `single<ListenUpDatabase> { ListenUpDatabase(get<SqlDriver>()) }` is unchanged but now transitively binds the
swappable driver — so a restore swap reaches every repository. Resolving `DatabaseHandle` first still forces
`MigrationRunner.migrate()` before the driver is used.

`DatabaseHandle` restore surface is re-expressed against the new components:
- `vacuumInto(target)` — raw JDBC `VACUUM INTO` via a transient `SQLiteDataSource` connection (auto-commit).
- `closePool()` → `closeUnderlying()` — closes the `SwappableSqlDriver`'s `JdbcSqliteDriver`.
- `reopenPool()` → `reopenUnderlying()` — installs a fresh `JdbcSqliteDriver` (via `DriverFactory`) on the same path.
- `migrate()` / `currentSchemaVersion()` — unchanged (`MigrationRunner(dataSource)`).

`RestoreOrchestrator` keeps its exact flow (validate → drain → safety-copy → extract → close → swap file +
delete `-wal`/`-shm` → reopen → migrate → rollback-on-failure); only the close/reopen now act on the
`SwappableSqlDriver`. **No signature change to `RestoreOrchestrator` itself** — it already speaks only `DatabaseHandle`.

> **JdbcSqliteDriver lifecycle caveat (resolve in implementation, test-first):** the exact connection model of
> SQLDelight's `JdbcSqliteDriver` (per-operation vs. retained connection; WAL `-wal`/`-shm` handle ownership)
> determines whether `closeCurrent()` is sufficient to release every handle before the file move on every OS.
> Phase 1a starts with a **failing restore round-trip test** (seed → backup → mutate → restore → assert repos
> see restored data through the *same* `ListenUpDatabase` instance) to pin this empirically before refactoring.

### 1b. Drop the dead Exposed `Database`

- `DatabaseFactory`: remove `Database.connect(...)`, `retryDatabaseConfig()`, and the
  `org.jetbrains.exposed.*` imports. (Keep the `DatabaseConfig` data class; keep `DEFAULT_MAX_TX_ATTEMPTS`-style
  constants only if reused by the new retry — otherwise move them to the retry helper.)
- `DatabaseHandle`: remove the `database: Database` field + import → Exposed-free.
- `AuthModule`: remove `single<Database> { get<DatabaseHandle>().database }` and the `Database` import.

After 1b, `DatabaseFactory` + `DatabaseHandle` + `AuthModule` are Exposed-free; the only remaining jvmMain
Exposed is the schema layer (deleted in Phase 2).

### 1c. Harden the JVM driver

`DriverFactory.jvm.kt`: build the `JdbcSqliteDriver` with `SQLiteConfig().apply { busyTimeout = 5000;
setJournalMode(WAL) }.toProperties()` (connection properties — applied at every connection open, the way
`withSqlDatabase` does it) instead of one-time `driver.execute("PRAGMA …")`. **Do not** set
`enforceForeignKeys(true)` on JVM (keeps FK off; see Decision 4). Update the KDoc to state the JVM FK-off
divergence from native and point to the follow-up.

### 1d. Port the SQLITE_BUSY_SNAPSHOT retry into `suspendTransaction`

In commonMain `db/sqldelight/Transactions.kt`, wrap the transaction in a retry loop:
- up to `MAX_TX_ATTEMPTS = 10` attempts; on a retryable failure, `kotlinx.coroutines.delay` a jittered
  10–500 ms (`kotlin.random.Random`) and retry; rethrow after the last attempt; never retry a
  `CancellationException`.
- new `expect fun Throwable.isRetryableSqliteError(): Boolean`:
  - **jvmMain actual** — true for `java.sql.SQLException` whose SQLite result code is `SQLITE_BUSY` (5) or
    `SQLITE_BUSY_SNAPSHOT` (517) (check `errorCode` and message), walking `cause` chain as needed.
  - **linuxX64Main actual** — true for the SQLiter busy/locked exception (`co.touchlab.sqliter.SQLiteException`
    family; match the busy result code), walking `cause`.
- Keep the loop body = `db.transactionWithResult { body() }` so semantics (commit-on-return, rollback-on-throw)
  are unchanged; only transient busy/snapshot failures are retried. `busy_timeout` (1c) handles plain
  SQLITE_BUSY by waiting; this retry handles SQLITE_BUSY_SNAPSHOT, which `busy_timeout` cannot.

### 1e. Test fate (the 9 residual jvmTest files)

| File | Action | How |
| --- | --- | --- |
| `db/SessionEntityTest` | **delete** | tests Exposed DAO FK/cascade mechanics; auth is SQLDelight-only — `sessionsQueries`/`usersQueries` carry the real coverage |
| `db/UserEntityTest` | **delete** | tests Exposed `UserEntity` CRUD/defaults; superseded by SQLDelight users coverage |
| `db/DatabaseHandleTest` | **convert** | seed/assert via raw JDBC (`SQLiteDataSource`); assert vacuum + close/reopen on the new components |
| `api/BackupServiceTest`, `backup/BackupTestSupport`, `backup/RestoreOrchestratorTest` | **convert** | replace `transaction(handle.database) { … }` seeding/assertions with raw JDBC over a `SQLiteDataSource`/connection (or SQLDelight where natural) |
| `db/DatabaseFactoryTest` | **rewrite** | drop Hikari pool-exhaustion assertions; assert the new factory builds a working driver + runs migrations |
| `db/TransactionRetryConcurrencyTest` | **rewrite** | retarget from the Exposed retry config to the new `suspendTransaction` retry (busy-snapshot under WAL + concurrency); keep the invariant, change the engine |
| `di/AuthModuleVerifyTest` | **update** | remove `Database` from the Koin `verify` extra-types whitelist; confirm the graph still resolves without `single<Database>` |

## Phase 2 — Delete the Exposed schema

Pure deletion once Phase 1 lands (nothing references these in prod or tests):

- Delete `sync/SyncableRepository.kt`, `sync/SyncMeta.kt`, `sync/SyncableTable.kt` (incl. `UserScopedSyncableTable`).
- Delete the 24 `db/*Table.kt` Exposed schema objects.
- Delete the 3 DAO entities `UserEntity`/`SessionEntity`/`InviteEntity` (their `*Table.kt` files). Inline
  `UserEntity.toContract` — replace its one nominal prod use (`AuthUser.kt` + the `AuthServiceImpl` import) with
  the SQLDelight-row/enum-name conversion already used elsewhere (`UserRole.valueOf(...)` / direct mapping).
- Remove `exposed-core`, `exposed-dao`, `exposed-jdbc`, `exposed-kotlin-datetime` from `gradle/libs.versions.toml`
  and `server/build.gradle.kts`. Keep `sqlite-jdbc`. (`hikari` was already removed in Phase 1.)
- Verify: `grep org.jetbrains.exposed server/src` = 0; `grep -i hikari server/src gradle/libs.versions.toml` = 0.

## Out of scope (explicit follow-ups)

- **Production FK enforcement + FK-clean scan.** Native already enforces FK; JVM does not. Making JVM consistent
  requires reordering the scan's inserts to be FK-clean (parent rows before children) so
  `LibraryLessOnboardingE2ETest` stays 202. Tracked as the "optimize the DB layer now that we own it" follow-up.
  Until then, JVM FK stays off by deliberate choice.
- **Native restore/migration runtime.** This spec keeps `MigrationRunner` + restore JVM-shaped (javax.sql.DataSource);
  the native server's migration/restore path is a P3/P4 native-tail concern, not this spec.

## Risks & mitigations

- **Restore correctness (highest).** `SwappableSqlDriver` close/reopen must release every SQLite handle before the
  file move, and reopen cleanly. *Mitigation:* test-first (1a), `NonCancellable` swap window preserved, rollback path
  preserved, full-suite gate includes the (now converted) restore tests.
- **Retry false-positives/negatives.** Misclassifying a non-busy error as retryable would mask real failures; missing
  the busy code would lose the protection. *Mitigation:* match exact result codes (5 / 517 on JVM; SQLiter busy on
  native), never retry `CancellationException`, unit-test `isRetryableSqliteError` per platform.
- **The #21 scan-E2E flake.** Expected to *disappear* once Hikari/Exposed are gone (single engine ⇒ no cross-connection
  SQLITE_BUSY). If a scan-E2E test still fails after Phase 1, treat it as a **real** signal (this is jvmMain code), not a
  flake to excuse.
- **Hidden Exposed coupling.** A missed consumer would fail compile. *Mitigation:* the compiler + `grep` are the proof;
  gate both phases.

## Verification / gating

Per phase, from the worktree, one gradle invocation, judged by the fresh test-results XML (not the piped exit code):

```
rm -rf server/build/test-tmp
./gradlew :server:compileKotlinLinuxX64 :server:jvmTest spotlessApply detekt --no-daemon --max-workers=4
```

Green = `BUILD` with 0 real failures (every `<failure>` class must be explained; post-Phase-1 the scan-E2E set is
real signal). Commits: subject-only, gitmoji + Conventional `type(scope):`, **no AI attribution**. Native compile
(`compileKotlinLinuxX64`) must stay green; Apple targets can't build on Linux.
