package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.BackupRoutePaths
import com.calypsan.listenup.api.ImportRoutePaths
import com.calypsan.listenup.api.dto.backup.BackupSummary
import com.calypsan.listenup.api.dto.imports.ImportSummary
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.core.suspendRunCatching
import com.calypsan.listenup.core.FileSource
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.forms.ChannelProvider
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders

/** A large archive over a slow LAN can take several minutes to upload. */
private const val ARCHIVE_TRANSFER_TIMEOUT_MS = 10L * 60 * 1_000

/**
 * Raw-HTTP implementation of [ArchiveUploadApiContract].
 *
 * On Android/JVM (OkHttp) and Apple (Darwin) bytes never buffer: [ChannelProvider] opens the
 * [FileSource] on demand as the request body drains, so a large archive streams through a
 * constant-size window rather than through the client's heap.
 *
 * ⛔ **Not in a browser.** Ktor's JS engine drains a request body into one `ByteArray` and copies it
 * again before calling `fetch`, so a large archive dies inside the engine with `RangeError: Invalid
 * array length`. The browser binds `BrowserArchiveUploadApi` instead, which sends a picked file
 * through the browser's own XMLHttpRequest.
 */
@NonRpcTransport(
    NonRpcReason.BINARY_TRANSFER,
    justification = "Backup and Audiobookshelf archives are multipart zip uploads; they cannot ride a JSON-RPC frame.",
)
internal class ArchiveUploadApi(
    private val clientFactory: ApiClientFactory,
) : ArchiveUploadApiContract {
    override suspend fun uploadBackup(source: FileSource): AppResult<BackupSummary> =
        suspendRunCatching {
            clientFactory
                .getClient()
                .submitFormWithBinaryData(
                    url = BackupRoutePaths.UPLOAD,
                    formData = archiveForm(partName = "backup", source = source),
                ) {
                    timeout {
                        requestTimeoutMillis = ARCHIVE_TRANSFER_TIMEOUT_MS
                        socketTimeoutMillis = ARCHIVE_TRANSFER_TIMEOUT_MS
                    }
                }.body<BackupSummary>()
        }

    override suspend fun uploadAbsBackup(source: FileSource): AppResult<ImportSummary> =
        suspendRunCatching {
            clientFactory
                .getClient()
                .submitFormWithBinaryData(
                    url = ImportRoutePaths.ABS_UPLOAD,
                    formData = archiveForm(partName = "file", source = source),
                ) {
                    timeout {
                        requestTimeoutMillis = ARCHIVE_TRANSFER_TIMEOUT_MS
                        socketTimeoutMillis = ARCHIVE_TRANSFER_TIMEOUT_MS
                    }
                }.body<ImportSummary>()
        }

    /** The archive as the form's only part, named [partName], with its filename on the part. */
    private fun archiveForm(
        partName: String,
        source: FileSource,
    ) = formData {
        // ChannelProvider streams on-demand — never buffers the entire zip.
        append(
            key = partName,
            value = ChannelProvider(source.size) { source.openChannel() },
            headers =
                Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"${source.filename}\"")
                },
        )
    }
}
