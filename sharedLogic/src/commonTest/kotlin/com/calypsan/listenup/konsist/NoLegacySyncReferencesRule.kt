package com.calypsan.listenup.konsist

import com.lemonappdev.konsist.api.Konsist
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * Backstops the cutover: any new code referencing the deleted legacy sync
 * classes triggers Konsist failure. Mirror of [NoLegacyAppErrorRule]'s shape.
 */
class NoLegacySyncReferencesRule :
    FunSpec({
        test("no production code references deleted legacy sync types") {
            val bannedImports =
                setOf(
                    "com.calypsan.listenup.client.data.sync.SSEManager",
                    "com.calypsan.listenup.client.data.sync.SSEManagerContract",
                    "com.calypsan.listenup.client.data.sync.SSEEvent",
                    "com.calypsan.listenup.client.data.sync.SSEChannelMessage",
                    "com.calypsan.listenup.client.data.sync.SyncCoordinator",
                    "com.calypsan.listenup.client.data.sync.SyncManager",
                    "com.calypsan.listenup.client.data.sync.SyncManagerContract",
                    "com.calypsan.listenup.client.data.sync.SyncMutex",
                    "com.calypsan.listenup.client.data.sync.conflict.ConflictDetector",
                    "com.calypsan.listenup.client.data.sync.pull.ActiveSessionsPuller",
                    "com.calypsan.listenup.client.data.sync.pull.BookPuller",
                    "com.calypsan.listenup.client.data.sync.pull.BookRelationshipBundle",
                    "com.calypsan.listenup.client.data.sync.pull.BookRelationshipWriter",
                    "com.calypsan.listenup.client.data.sync.pull.ContributorPuller",
                    "com.calypsan.listenup.client.data.sync.pull.GenrePuller",
                    "com.calypsan.listenup.client.data.sync.pull.ListeningEventPuller",
                    "com.calypsan.listenup.client.data.sync.pull.ProgressPuller",
                    "com.calypsan.listenup.client.data.sync.pull.PullSyncOrchestrator",
                    "com.calypsan.listenup.client.data.sync.pull.Puller",
                    "com.calypsan.listenup.client.data.sync.pull.ReadingSessionPuller",
                    "com.calypsan.listenup.client.data.sync.pull.SeriesPuller",
                    "com.calypsan.listenup.client.data.sync.pull.ShelfPuller",
                    "com.calypsan.listenup.client.data.sync.pull.TagPuller",
                    "com.calypsan.listenup.client.data.sync.push.OperationExecutor",
                    "com.calypsan.listenup.client.data.sync.push.OperationExecutorContract",
                    "com.calypsan.listenup.client.data.sync.push.OperationHandler",
                    "com.calypsan.listenup.client.data.sync.push.OperationHandlers",
                    "com.calypsan.listenup.client.data.sync.push.OperationPayloads",
                    "com.calypsan.listenup.client.data.sync.push.PendingOperationRepository",
                    "com.calypsan.listenup.client.data.sync.push.PendingOperationRepositoryContract",
                    "com.calypsan.listenup.client.data.sync.push.PreferencesSyncObserver",
                    "com.calypsan.listenup.client.data.sync.push.PushSyncOrchestrator",
                    "com.calypsan.listenup.client.data.sync.push.ShelfPayloads",
                    "com.calypsan.listenup.client.data.sync.sse.BookRelationshipDaos",
                    "com.calypsan.listenup.client.data.sync.sse.SSEEventProcessor",
                    "com.calypsan.listenup.client.data.sync.sse.SSEExternalServices",
                )

            val offenders =
                Konsist
                    .scopeFromProduction()
                    .files
                    .flatMap { file ->
                        file.imports
                            .filter { import -> import.name in bannedImports }
                            .map { "${file.path} -> ${it.name}" }
                    }

            offenders.shouldBeEmpty()
        }
    })
