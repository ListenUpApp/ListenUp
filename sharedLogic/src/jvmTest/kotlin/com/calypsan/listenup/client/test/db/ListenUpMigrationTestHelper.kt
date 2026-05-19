package com.calypsan.listenup.client.test.db

import androidx.room.testing.MigrationTestHelper
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Small composition wrapper around Room 2.8's JVM [MigrationTestHelper]. Pins
 * the two methods migration tests actually need ([createDatabase],
 * [runMigrationsAndValidate]) and adds [close] so kotlin.test can clean up
 * managed connections — the underlying `finished(Description?)` override is
 * `protected` inside a `final` class, which blocks both direct invocation and
 * a subclass shim, so reflection is the only path to it.
 */
class ListenUpMigrationTestHelper internal constructor(
    private val delegate: MigrationTestHelper,
) {
    fun createDatabase(version: Int): SQLiteConnection = delegate.createDatabase(version)

    fun runMigrationsAndValidate(
        version: Int,
        migrations: List<Migration> = emptyList(),
    ): SQLiteConnection = delegate.runMigrationsAndValidate(version, migrations)

    fun close() {
        val method =
            MigrationTestHelper::class.java.getDeclaredMethod(
                "finished",
                org.junit.runner.Description::class.java,
            )
        method.isAccessible = true
        method.invoke(delegate, null)
    }
}

/**
 * Builds a [ListenUpMigrationTestHelper] pre-wired for [ListenUpDatabase] so
 * migration regression tests don't need to re-specify schema path, driver, and
 * factory on every call-site.
 *
 * Path resolution: Gradle runs `:shared:jvmTest` with working directory set to
 * the `shared/` module root, so `Paths.get("schemas")` resolves to the
 * exported-schema directory written by the Room Gradle plugin. The helper
 * appends the `@Database`-annotated class's fully-qualified name plus
 * `$version.json`, so schema files land at
 * `shared/schemas/com.calypsan.listenup.client.data.local.db.ListenUpDatabase/N.json`.
 *
 * Each call returns a fresh helper backed by a throwaway tmp file, so parallel
 * test cases never share state. Every caller MUST invoke `helper.close()` in an
 * `@AfterTest` hook so managed connections close cleanly.
 *
 * Jvm-scoped because Room 2.8's [MigrationTestHelper] only ships JVM/Android
 * and native source-set actuals; the appleMain equivalent can be added when
 * iOS/macOS migration tests are needed (see W4.5 in the restoration roadmap).
 */
fun createMigrationTestHelper(): ListenUpMigrationTestHelper {
    val schemaDirectory: Path = Paths.get("schemas").toAbsolutePath()
    check(Files.isDirectory(schemaDirectory)) {
        "Room schemas directory not found at $schemaDirectory. " +
            "Run `./gradlew :shared:kspKotlinJvm` to regenerate `shared/schemas/`, " +
            "then re-run the migration tests."
    }

    val databasePath: Path = Files.createTempFile("listenup-migration-test", ".db")
    Files.deleteIfExists(databasePath)
    databasePath.toFile().deleteOnExit()

    val delegate =
        MigrationTestHelper(
            schemaDirectoryPath = schemaDirectory,
            databasePath = databasePath,
            driver = BundledSQLiteDriver(),
            databaseClass = ListenUpDatabase::class,
        )
    return ListenUpMigrationTestHelper(delegate)
}
