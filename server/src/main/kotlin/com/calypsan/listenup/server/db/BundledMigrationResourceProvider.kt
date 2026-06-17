package com.calypsan.listenup.server.db

import org.flywaydb.core.api.Location
import org.flywaydb.core.api.ResourceProvider
import org.flywaydb.core.api.resource.LoadableResource
import org.flywaydb.core.internal.resource.classpath.ClassPathResource
import java.nio.charset.StandardCharsets

/**
 * Flyway [ResourceProvider] that loads migrations *by name* from a committed index
 * (`db/migration-index.txt`) instead of scanning the classpath.
 *
 * A GraalVM native image has no walkable classpath, so Flyway's `ClassPathScanner` can't
 * enumerate `classpath:db/migration` ("unsupported protocol: resource") and finds **zero**
 * migrations — every table is then missing at runtime. Bundled resources are still loadable
 * by name, so we list the migration filenames in the index and hand Flyway a
 * [ClassPathResource] per migration. JVM runs keep Flyway's default scanner; this provider is
 * wired only under native-image (see [DatabaseHandle]). (#647)
 *
 * The index is generated from the actual `db/migration` SQL files by the `:server:generateMigrationIndex`
 * Gradle task; a CI check fails the build if a new migration is added without regenerating it —
 * the canary that flags a DB change which would silently break the native server.
 */
class BundledMigrationResourceProvider(
    private val classLoader: ClassLoader = BundledMigrationResourceProvider::class.java.classLoader,
) : ResourceProvider {
    private val location = Location("classpath:$MIGRATION_DIR")

    private val migrationFileNames: List<String> by lazy {
        classLoader.getResourceAsStream(INDEX_RESOURCE)
            ?.bufferedReader(StandardCharsets.UTF_8)
            ?.useLines { lines -> lines.map { it.trim() }.filter { it.isNotEmpty() }.toList() }
            ?: error("Native migration index '$INDEX_RESOURCE' not bundled — cannot run Flyway under native-image.")
    }

    override fun getResource(name: String): LoadableResource? {
        val relative = if (name.startsWith("$MIGRATION_DIR/")) name else "$MIGRATION_DIR/$name"
        return if (classLoader.getResource(relative) != null) {
            ClassPathResource(location, relative, classLoader, StandardCharsets.UTF_8)
        } else {
            null
        }
    }

    override fun getResources(
        prefix: String,
        suffixes: Array<String>,
    ): Collection<LoadableResource> =
        migrationFileNames
            .filter { fileName ->
                fileName.startsWith(prefix) &&
                    (suffixes.isEmpty() || suffixes.any { fileName.endsWith(it, ignoreCase = true) })
            }.map { fileName ->
                ClassPathResource(location, "$MIGRATION_DIR/$fileName", classLoader, StandardCharsets.UTF_8)
            }

    private companion object {
        const val MIGRATION_DIR = "db/migration"
        const val INDEX_RESOURCE = "db/migration-index.txt"
    }
}
