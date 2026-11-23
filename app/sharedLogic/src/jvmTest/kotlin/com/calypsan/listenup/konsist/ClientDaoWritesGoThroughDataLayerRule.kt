package com.calypsan.listenup.konsist

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual

/**
 * Client code writes a DAO only from the data layer: `client/data/repository/`, `client/data/local/` and
 * `client/data/sync/`. Everything else — ViewModels, playback, platform services — goes through a repository.
 *
 * A write that skips the repository skips what the repository owns: the transaction seam, the pending-operation
 * enqueue, the sync bookkeeping. The row changes in Room, the UI redraws, and the server never hears of it.
 *
 * Polices `:app:sharedLogic` in every production source set. The server's counterpart is
 * [ServerSyncWritesGoThroughRepositoryRule]. Files that still write directly are named in
 * [DIRECT_DAO_WRITE_ALLOWLIST] with a reason; an entry that no longer matches fails the rule too, so the list
 * can only shrink.
 *
 * What counts as a write is read from the DAOs, not guessed from a verb: every function of a `@Dao` interface
 * annotated `@Insert` / `@Update` / `@Delete` / `@Upsert`, or `@Query` whose SQL starts with `INSERT`, `UPDATE`,
 * `DELETE` or `REPLACE`, plus every `@Transaction` function whose body calls one of those. A verb list once let
 * `bookDao.softDelete(id)` — a local tombstone that never reaches the outbox — pass from a ViewModel.
 */
class ClientDaoWritesGoThroughDataLayerRule :
    FunSpec({
        test("the DAO write methods are read from the DAOs, and discovery has not collapsed") {
            withClue("derived DAO write methods (${DAO_WRITE_DECLARATIONS.size} declarations): $DAO_WRITE_METHODS") {
                // Counted per declaration: the ~45 DAOs share names (`upsert`, `softDelete`) across tables.
                DAO_WRITE_DECLARATIONS.size shouldBeGreaterThanOrEqual 150
                DAO_WRITE_METHODS shouldContainAll setOf("upsert", "softDelete", "tombstoneByIds", "deleteById")
            }
        }

        test("no :app:sharedLogic file outside the data layer writes a DAO, except the reviewed allowlist") {
            val files =
                productionScope()
                    .files
                    .filter { "/app/sharedLogic/src/" in it.path }
                    .filterNot { file -> DATA_LAYER_WRITE_HOMES.any { it in file.path } }

            assertScopeNotEmpty(
                files,
                expectedMin = 500,
                why = "every :app:sharedLogic production file outside the data layer's write homes",
            )

            val writersByPath =
                files
                    .associate { file -> file.path to daoWriteCalls(file.text) }
                    .filterValues { it.isNotEmpty() }

            val unexpected =
                writersByPath
                    .filterKeys { path -> DIRECT_DAO_WRITE_ALLOWLIST.keys.none { path.endsWith(it) } }
                    .map { (path, calls) ->
                        "$path — ${calls.joinToString { "$it(" }}"
                    }
            val stale =
                DIRECT_DAO_WRITE_ALLOWLIST.keys
                    .filter { entry -> writersByPath.keys.none { it.endsWith(entry) } }
                    .map { "$it — allowlisted but no longer writes a DAO directly; remove the entry" }

            withClue((unexpected + stale).joinToString("\n", prefix = "\n")) { (unexpected + stale).shouldBeEmpty() }
        }
    }) {
    companion object {
        /** The only places client code may write a DAO. */
        val DATA_LAYER_WRITE_HOMES = listOf("/client/data/repository/", "/client/data/local/", "/client/data/sync/")

        /** `<something>Dao.<method>(` and `<something>Dao().<method>(` — a write when the method is in [DAO_WRITE_METHODS]. */
        private val DAO_CALL = Regex("""\b([A-Za-z][A-Za-z0-9]*Dao)(?:\(\))?\.([A-Za-z][A-Za-z0-9]*)\s*\(""")

        /** The SQL verbs that make a `@Query` a write. */
        private val WRITE_SQL = Regex("""^(?:INSERT|UPDATE|DELETE|REPLACE)\b""", RegexOption.IGNORE_CASE)

        private val WRITE_ANNOTATIONS = setOf("Insert", "Update", "Delete", "Upsert")

        /** Every write method of every `@Dao`, as `Dao.method` — one entry per declaration, for the discovery floor. */
        val DAO_WRITE_DECLARATIONS: List<String> by lazy {
            val daoFunctions =
                productionScope()
                    .interfaces()
                    .filter { "/app/sharedLogic/src/" in it.path && it.hasAnnotationWithName("Dao") }
                    .flatMap { dao -> dao.functions().map { dao.name to it } }
            val direct =
                daoFunctions.filter { (_, fn) ->
                    fn.annotations.any { it.name in WRITE_ANNOTATIONS } ||
                        fn.annotations.any { it.name == "Query" && isWriteSql(it.text) }
                }
            val directNames = direct.map { (_, fn) -> fn.name }.toSet()
            val transactional =
                daoFunctions
                    .filter { (_, fn) -> fn.hasAnnotationWithName("Transaction") }
                    .filter { (_, fn) ->
                        directNames.any { Regex("""\b$it\s*\(""").containsMatchIn(stripComments(fn.text)) }
                    }
            (direct + transactional).map { (dao, fn) -> "$dao.${fn.name}" }.distinct()
        }

        /**
         * Every write method declared by a `@Dao` interface in `:app:sharedLogic` — see the class KDoc for what
         * counts. Read once from the production scope, so a DAO write added tomorrow is covered tomorrow.
         */
        val DAO_WRITE_METHODS: Set<String> by lazy { DAO_WRITE_DECLARATIONS.map { it.substringAfter('.') }.toSet() }

        /** True when the `@Query(…)` annotation [annotationText]'s SQL starts with a write verb. */
        internal fun isWriteSql(annotationText: String): Boolean =
            WRITE_SQL.containsMatchIn(
                annotationText
                    .substringAfter('(')
                    .trimStart()
                    .trimStart('"')
                    .trimStart(),
            )

        /** `dao.method` for every DAO write call in [source], comments stripped. */
        fun daoWriteCalls(
            source: String,
            writeMethods: Set<String> = DAO_WRITE_METHODS,
        ): List<String> =
            DAO_CALL
                .findAll(stripComments(source))
                .filter { it.groupValues[2] in writeMethods }
                .map { "${it.groupValues[1]}.${it.groupValues[2]}" }
                .toList()

        /** Reviewed direct writers, keyed by path suffix. Shrinking this list is welcome; growing it needs a reason. */
        val DIRECT_DAO_WRITE_ALLOWLIST: Map<String, String> =
            mapOf(
                "/client/download/AppleDownloadService.kt" to "a platform download service that predates the repository seam",
                "/client/download/DownloadManager.kt" to "a platform download service that predates the repository seam",
                "/client/playback/ListeningEventRecorder.kt" to "writes inside TransactionRunner.atomically",
                "/client/diagnostics/BrowserStoreProbe.kt" to "a storage self-test, not product data",
            )
    }
}
