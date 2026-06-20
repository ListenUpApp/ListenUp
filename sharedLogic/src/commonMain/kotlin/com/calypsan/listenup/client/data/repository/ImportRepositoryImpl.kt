package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.ImportRoutePaths
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.imports.ImportAnalysis
import com.calypsan.listenup.api.dto.imports.ImportEvent
import com.calypsan.listenup.api.dto.imports.ImportResult
import com.calypsan.listenup.api.dto.imports.ImportSummary
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.AppResult as WireAppResult
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.client.core.error.ErrorMapper
import com.calypsan.listenup.client.core.suspendRunCatching
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.data.remote.ImportRpcFactory
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private val logger = KotlinLogging.logger {}

/**
 * Production implementation of [ImportRepository].
 *
 * Every suspend call forwards to the [com.calypsan.listenup.api.ImportService] RPC proxy
 * obtained from [ImportRpcFactory.get] and converts the wire [WireAppResult] to the
 * client-layer [AppResult] at this boundary. [CancellationException] is always re-raised.
 *
 * [upload] is the one REST operation: binary multipart transfer cannot ride RPC.
 * It streams the `.audiobookshelf` zip via `submitFormWithBinaryData` to
 * [ImportRoutePaths.ABS_UPLOAD] and parses the [ImportSummary] response.
 *
 * [observeProgress] unwraps the server-pushed [Flow]<[RpcEvent]<[ImportEvent]>> into a
 * plain [Flow]<[ImportEvent]>: [RpcEvent.Data] values are emitted; [RpcEvent.Error] and
 * [RpcEvent.Complete] are silently dropped (the guard already logs errors server-side).
 */
internal class ImportRepositoryImpl(
    private val rpcFactory: ImportRpcFactory,
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
        rpcCall { rpcFactory.get().analyze(importId) }

    override suspend fun confirmMapping(
        importId: ImportId,
        userMappings: Map<AbsUserId, UserId>,
        bookOverrides: Map<AbsItemId, BookId?>,
    ): AppResult<Unit> = rpcCall { rpcFactory.get().confirmMapping(importId, userMappings, bookOverrides) }

    override suspend fun apply(importId: ImportId): AppResult<ImportResult> =
        rpcCall { rpcFactory.get().apply(importId) }

    override suspend fun listImports(): AppResult<List<ImportSummary>> = rpcCall { rpcFactory.get().listImports() }

    override suspend fun deleteImport(importId: ImportId): AppResult<Unit> =
        rpcCall { rpcFactory.get().deleteImport(importId) }

    override fun observeProgress(importId: ImportId): Flow<ImportEvent> =
        flow {
            // Acquire the proxy at collection time so the cold flow stays truly cold.
            // The service returns Flow<RpcEvent<ImportEvent>>; emit only the Data payload.
            // Error events are already logged by the KSP-generated guard on the server side.
            rpcFactory.get().observeProgress(importId).collect { event ->
                when (event) {
                    is RpcEvent.Data -> emit(event.value)
                    is RpcEvent.Error -> logger.warn { "observeProgress received RpcEvent.Error: ${event.error}" }
                    is RpcEvent.Complete -> Unit
                }
            }
        }

    /**
     * Runs an RPC call that returns a wire [WireAppResult] and converts it to the
     * client-layer [AppResult]. [CancellationException] is re-raised; all other
     * throwables become [AppResult.Failure] via [ErrorMapper].
     */
    private suspend fun <T> rpcCall(block: suspend () -> WireAppResult<T>): AppResult<T> =
        try {
            when (val result = block()) {
                is WireAppResult.Success -> AppResult.Success(result.data)
                is WireAppResult.Failure -> AppResult.Failure(result.error)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppResult.Failure(ErrorMapper.map(e))
        }
}
