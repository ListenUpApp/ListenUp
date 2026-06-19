package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.BackupRoutePaths
import com.calypsan.listenup.api.dto.backup.BackupEvent
import com.calypsan.listenup.api.dto.backup.BackupSummary
import com.calypsan.listenup.api.dto.backup.RestoreResult
import com.calypsan.listenup.api.result.AppResult as WireAppResult
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BackupId
import com.calypsan.listenup.core.FileSource
import com.calypsan.listenup.core.IODispatcher
import com.calypsan.listenup.client.core.error.ErrorMapper
import com.calypsan.listenup.client.core.suspendRunCatching
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.data.remote.BackupRpcFactory
import com.calypsan.listenup.client.domain.repository.BackupRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.forms.ChannelProvider
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.io.RawSink
import kotlinx.io.buffered

private val logger = KotlinLogging.logger {}

private const val DOWNLOAD_BUFFER_SIZE = 64 * 1024
private const val DOWNLOAD_TIMEOUT_MS = 10L * 60 * 1_000 // large image-bearing backups can take minutes

/**
 * Production implementation of [BackupRepository].
 *
 * Every suspend call forwards to the [BackupService] RPC proxy obtained from
 * [BackupRpcFactory.get] and converts the wire [WireAppResult] to the client-layer
 * [AppResult] at this boundary. [CancellationException] is always re-raised.
 *
 * [uploadBackup] is the one REST operation: binary multipart transfer cannot ride RPC.
 * It streams the `.listenup.zip` via `submitFormWithBinaryData` to
 * [BackupRoutePaths.UPLOAD] and parses the [BackupSummary] response.
 *
 * [observeProgress] unwraps the server-pushed [Flow]<[RpcEvent]<[BackupEvent]>> into a
 * plain [Flow]<[BackupEvent]>: [RpcEvent.Data] values are emitted; [RpcEvent.Error] and
 * [RpcEvent.Complete] are silently dropped (the guard already logs errors server-side).
 */
class BackupRepositoryImpl(
    private val rpcFactory: BackupRpcFactory,
    private val clientFactory: ApiClientFactory,
) : BackupRepository {
    override suspend fun uploadBackup(fileSource: FileSource): AppResult<BackupSummary> =
        suspendRunCatching {
            clientFactory
                .getClient()
                .submitFormWithBinaryData(
                    url = BackupRoutePaths.UPLOAD,
                    formData =
                        formData {
                            // ChannelProvider streams on-demand — never buffers the entire zip.
                            append(
                                key = "backup",
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
                    // Large backups can take several minutes to upload.
                    timeout {
                        requestTimeoutMillis = 10 * 60 * 1_000
                        socketTimeoutMillis = 10 * 60 * 1_000
                    }
                }.body<BackupSummary>()
        }

    override suspend fun downloadBackup(
        id: BackupId,
        sink: RawSink,
    ): AppResult<Unit> =
        suspendRunCatching {
            withContext(IODispatcher) {
                val buffered = sink.buffered()
                clientFactory
                    .getClient()
                    .prepareGet(BackupRoutePaths.downloadFor(id.value)) {
                        timeout {
                            requestTimeoutMillis = DOWNLOAD_TIMEOUT_MS
                            socketTimeoutMillis = DOWNLOAD_TIMEOUT_MS
                        }
                    }.execute { response ->
                        // ApiClientFactory installs HttpResponseValidator (expectSuccess); a non-2xx
                        // raises a typed exception that suspendRunCatching routes to AppResult.Failure.
                        val channel = response.bodyAsChannel()
                        val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                        while (!channel.isClosedForRead) {
                            val read = channel.readAvailable(buffer, 0, buffer.size)
                            if (read <= 0) continue
                            buffered.write(buffer, 0, read)
                        }
                        buffered.flush()
                    }
            }
        }

    override suspend fun createBackup(includeImages: Boolean): AppResult<BackupSummary> =
        rpcCall { rpcFactory.get().createBackup(includeImages) }

    override suspend fun listBackups(): AppResult<List<BackupSummary>> = rpcCall { rpcFactory.get().listBackups() }

    override suspend fun deleteBackup(id: BackupId): AppResult<Unit> = rpcCall { rpcFactory.get().deleteBackup(id) }

    override suspend fun restoreBackup(id: BackupId): AppResult<RestoreResult> =
        rpcCall { rpcFactory.get().restoreBackup(id) }

    override fun observeProgress(): Flow<BackupEvent> =
        flow {
            // Acquire the proxy at collection time so the cold flow stays truly cold.
            // The service returns Flow<RpcEvent<BackupEvent>>; emit only the Data payload.
            // Error events are already logged by the KSP-generated guard on the server side.
            rpcFactory.get().observeProgress().collect { event ->
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
