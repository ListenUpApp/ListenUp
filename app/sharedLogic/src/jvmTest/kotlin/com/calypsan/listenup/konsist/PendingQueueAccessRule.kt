package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty

/**
 * The pending-operation queue is reachable only through declared seams: the sync
 * engine machinery (`data/sync/`, same-package — no import), the DI modules that
 * wire it, and the two sanctioned repository consumers (positions enqueue; the
 * pending-ops UI read model). An `OnlineOnly` domain's repository importing the
 * queue would be a silent-queueing side door — exactly what the tier forbids.
 */
class PendingQueueAccessRule :
    FunSpec({
        test("only declared seams import PendingOperationQueue") {
            val allowedPaths =
                setOf(
                    "/data/repository/PlaybackPositionRepositoryImpl.kt",
                    "/data/repository/PendingOperationRepositoryImpl.kt",
                )
            val importers =
                productionScope()
                    .files
                    .filter { file ->
                        file.imports.any { it.name == "com.calypsan.listenup.client.data.sync.PendingOperationQueue" }
                    }

            // Guard against a vacuous pass: if the import FQN ever drifts (package rename),
            // the filter would silently match nothing and the rule would test nothing.
            importers.shouldNotBeEmpty()

            val offenders =
                importers
                    .filterNot { "/data/sync/" in it.path }
                    .filterNot { "/di/" in it.path }
                    .filterNot { file -> allowedPaths.any { it in file.path } }
                    .map { it.path }
            offenders.shouldBeEmpty()
        }
    })
