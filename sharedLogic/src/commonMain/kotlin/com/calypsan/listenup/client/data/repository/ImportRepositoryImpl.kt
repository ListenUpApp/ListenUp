package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.ImportRoutePaths
import com.calypsan.listenup.api.ImportService
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.imports.ImportAnalysis
import com.calypsan.listenup.api.dto.imports.ImportEvent
import com.calypsan.listenup.api.dto.imports.ImportResult
import com.calypsan.listenup.api.dto.imports.ImportSummary
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.client.core.suspendRunCatching
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.data.remote.NonRpcReason
import com.calypsan.listenup.client.data.remote.NonRpcTransport
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.domain.repository.ImportRepository
import com.calypsan.listenup.core.AbsItemId
import com.calypsan.listenup.core.AbsUserId
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FileSource
import com.calypsan.listenup.core.ImportId
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.forms.ChannelProvider
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration.Companion.minutes

private val logger = KotlinLogging.logger {}

/**
 * Production implementation of [ImportRepository].
 *
 * Every RPC call dispatches through the [ImportService] [RpcChannel], which folds transport
 * faults into typed [AppResult.Failure]s, re-raises cancellation, and self-heals its own
 * connection. Analyze/apply run far past the channel's default 15s bound, so they pass an
 * explicit `timeout = 10.minutes`.
 *
 * [upload] is the one REST operation: binary multipart transfer cannot ride RPC.
 * It streams the `.audiobookshelf` zip via `submitFormWithBinaryData` to
 * [ImportRoutePaths.ABS_UPLOAD] and parses the [ImportSummary] response.
 *
 * [observeProgress] unwraps the server-pushed [Flow]<[RpcEvent]<[ImportEvent]>> into a
 * plain [Flow]<[ImportEvent]>: [RpcEvent.Data] values are emitted; [RpcEvent.Error] and
 * [RpcEvent.Complete] are silently dropped (the guard already logs errors server-side).
 *
 * Mixed transport: analyze/apply/confirm/list/delete/observe ride the [RpcChannel]; only the binary
 * [upload] archive transfer goes raw over REST — the reason tagged below.
 */
@NonRpcTransport(
    NonRpcReason.BINARY_TRANSFER,
    justification = "ABS import archive upload streams raw zip bytes; RPC analyze/apply/etc. ride the channel.",
)
internal class ImportRepositoryImpl(
    private val channel: RpcChannel<ImportService>,
    private val clientFactory: ApiClientFactory,
) : ImportRepository {
    override suspend fun upload(fileSource: FileSource): AppResult<ImportSummary> =
        suspendRunCatching {
            clientFactory
                .getClient()
                .submitFormWithBinaryData(
                    url = ImportRoutePaths.ABS_UPLOAD,
                    formData =
                        formData {
                            // ChannelProvider streams on-demand — never buffers the entire zip.
                            append(
                                key = "file",
                                value = ChannelProvider(fileSource.size) { fileSource.openChannel() },
                                headers =
                                    Headers.build {
                                        append(
                                            HttpHeaders.ContentDisposition,
                                            "filename=\"${fileSource.filename}\"",
                                        )
                                    },
                            )
                        },
                ) {
                    // Large ABS backups can take several minutes to upload.
                    timeout {
                        requestTimeoutMillis = 10 * 60 * 1_000
                        socketTimeoutMillis = 10 * 60 * 1_000
                    }
                }.body<ImportSummary>()
        }

    override suspend fun analyze(importId: ImportId): AppResult<ImportAnalysis> =
        channel.call(timeout = 10.minutes) { it.analyze(importId) }

    override suspend fun confirmMapping(
        importId: ImportId,
        userMappings: Map<AbsUserId, UserId>,
        bookOverrides: Map<AbsItemId, BookId?>,
    ): AppResult<Unit> = channel.call { it.confirmMapping(importId, userMappings, bookOverrides) }

    override suspend fun apply(importId: ImportId): AppResult<ImportResult> =
        channel.call(timeout = 10.minutes) { it.apply(importId) }

    override suspend fun listImports(): AppResult<List<ImportSummary>> =
        channel.call(idempotent = true) {
            it.listImports()
        }

    override suspend fun deleteImport(importId: ImportId): AppResult<Unit> = channel.call { it.deleteImport(importId) }

    override fun observeProgress(importId: ImportId): Flow<ImportEvent> =
        flow {
            // Subscribe at collection time so the cold flow stays truly cold.
            // The channel returns Flow<RpcEvent<ImportEvent>> (with subscription-time healing);
            // emit only the Data payload. Error events are already logged by the KSP-generated
            // guard on the server side.
            channel.stream { it.observeProgress(importId) }.collect { event ->
                when (event) {
                    is RpcEvent.Data -> emit(event.value)
                    is RpcEvent.Error -> logger.warn { "observeProgress received RpcEvent.Error: ${event.error}" }
                    is RpcEvent.Complete -> Unit
                }
            }
        }
}
