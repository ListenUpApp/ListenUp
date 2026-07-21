package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * Backstops the cutovers: any new code referencing the deleted legacy sync classes — or the
 * retired SSE transport — triggers Konsist failure. Mirror of [NoLegacyAppErrorRule]'s shape.
 *
 * SSE is fully retired (2026-07): every server push rides kotlinx.rpc streaming
 * (`Flow<RpcEvent<T>>`) — the firehose, scanner progress, and the pre-auth registration
 * status/policy watches alike. The ban is therefore total and symmetrical: the Ktor CLIENT SSE
 * plugin package and the Ktor SERVER SSE plugin package are both banned across ALL production
 * source sets, alongside the retired client-side class names ([bannedImports], which include the
 * deleted `SseConnection` engine and its `ParsedSseFrame`). No production code may re-grow an SSE
 * transport under any name.
 */
class NoLegacySyncReferencesRule :
    FunSpec({
        test("no production code references deleted legacy sync types") {
            // Banned outright in every production source set: the Ktor CLIENT SSE plugin —
            // the retired transport cannot return under a new class name.
            val clientSsePrefix = "io.ktor.client.plugins.sse"
            // Equally banned in every production source set (server included): zero SSE means no
            // server route may serve it either.
            val serverSsePrefix = "io.ktor.server.sse"
            val bannedImports =
                setOf(
                    "com.calypsan.listenup.client.data.sync.SyncSseClient",
                    "com.calypsan.listenup.client.data.sync.SseConnection",
                    "com.calypsan.listenup.client.data.sync.SseEvent",
                    "com.calypsan.listenup.client.data.sync.ParsedSseFrame",
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
                    "com.calypsan.listenup.client.data.sync.EditableDomain",
                    "com.calypsan.listenup.client.data.sync.BookEdit",
                    "com.calypsan.listenup.client.data.sync.SeriesEdit",
                    "com.calypsan.listenup.client.data.sync.ContributorEdit",
                    "com.calypsan.listenup.client.data.sync.ProfileEdit",
                    "com.calypsan.listenup.client.data.sync.PreferencesEdit",
                )

            val offenders =
                productionScope()
                    .files
                    .flatMap { file ->
                        file.imports
                            .filter { import ->
                                import.name in bannedImports ||
                                    import.name.startsWith(clientSsePrefix) ||
                                    import.name.startsWith(serverSsePrefix)
                            }.map { "${file.path} -> ${it.name}" }
                    }

            offenders.shouldBeEmpty()
        }
    })
