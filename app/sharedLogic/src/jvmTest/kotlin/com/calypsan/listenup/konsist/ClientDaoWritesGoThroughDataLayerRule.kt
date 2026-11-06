package com.calypsan.listenup.konsist

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

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
 */
class ClientDaoWritesGoThroughDataLayerRule :
    FunSpec({
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
                    .associate { file -> file.path to DAO_WRITE_CALL.findAll(stripComments(file.text)).toList() }
                    .filterValues { it.isNotEmpty() }

            val unexpected =
                writersByPath
                    .filterKeys { path -> DIRECT_DAO_WRITE_ALLOWLIST.keys.none { path.endsWith(it) } }
                    .map { (path, calls) ->
                        "$path — ${calls.joinToString { "${it.groupValues[1]}.${it.groupValues[2]}(" }}"
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

        /**
         * `<something>Dao.<write>(` and `<something>Dao().<write>(`. The verb alternation is the load-bearing part —
         * a verb missing from it is a silent bypass — so [ClientDaoWritesGoThroughDataLayerRuleSelfTest] plants a
         * sample of each.
         */
        val DAO_WRITE_CALL =
            Regex(
                """\b([A-Za-z][A-Za-z0-9]*Dao)(?:\(\))?\.""" +
                    """((?:insert|update|upsert|delete|replace|mark|set|save|clear|raise)[A-Za-z]*)\s*\(""",
            )

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
