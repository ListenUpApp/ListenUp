package com.calypsan.listenup.client.diagnostics

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.core.BlobFileSource
import com.calypsan.listenup.client.data.remote.UploadApiContract
import kotlinx.coroutines.CancellationException
import org.khronos.webgl.Uint8Array
import org.w3c.dom.Worker
import org.w3c.files.File

/**
 * What an end-to-end browser upload observed. Plain values, for the same reason [AuthArcProbe] uses
 * them: the Koin graph and the upload API stay `internal` to this module.
 */
data class BlobUploadProbe(
    /** The class the browser graph bound as its upload API — the production wiring, by name. */
    val boundApi: String?,
    /** Files the server reported staged after the send; -1 if the send never answered a summary. */
    val stagedFiles: Int,
    /** Bytes the server reported staged after the send; -1 if the send never answered a summary. */
    val stagedBytes: Long,
    /** The last byte count the send reported through its progress callback. */
    val lastReportedBytes: Long,
    /** Message from whatever failed, or null when the upload completed. */
    val failure: String?,
)

/**
 * Sends a picked-file-shaped [File] of [byteCount] bytes to a real server through the browser
 * graph's own upload API, then abandons the session so the library is untouched.
 *
 * The server's answer is the point. A fake request proves what the browser is asked to send; only a
 * real server proves it accepts what the browser actually sends — the multipart part, the `relPath`
 * query, the credential — with the file going out from a [File] rather than a copy of its bytes.
 *
 * Never throws: whatever fails is reported, so a failure reads as a failed assertion.
 */
@Suppress("TooGenericExceptionCaught")
suspend fun probeBlobUpload(
    worker: Worker,
    dbName: String,
    email: String,
    password: String,
    byteCount: Int,
): BlobUploadProbe {
    val app = browserGraph(worker, dbName)
    var lastReported = 0L

    fun failed(
        api: UploadApiContract?,
        why: String,
    ) = BlobUploadProbe(api?.let { it::class.simpleName }, -1, -1, lastReported, why)

    return try {
        if (!app.koin.signInProbeAdmin(email, password)) return failed(null, "never reached Authenticated")

        val api = app.koin.get<UploadApiContract>()
        val sessionId =
            when (val created = api.createSession()) {
                is AppResult.Failure -> return failed(api, "createSession failed: ${created.error}")
                is AppResult.Success -> created.data.sessionId
            }

        val file = File(arrayOf(Uint8Array(byteCount)), "probe.m4b")
        val sent =
            api.uploadFile(sessionId, "Upload Probe/probe.m4b", BlobFileSource(file)) { bytes, _ ->
                lastReported = bytes
            }
        api.abandon(sessionId)

        when (sent) {
            is AppResult.Failure -> {
                failed(api, "uploadFile failed: ${sent.error}")
            }

            is AppResult.Success -> {
                BlobUploadProbe(
                    boundApi = api::class.simpleName,
                    stagedFiles = sent.data.fileCount,
                    stagedBytes = sent.data.totalBytes,
                    lastReportedBytes = lastReported,
                    failure = null,
                )
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        failed(null, "probe threw: $e")
    } finally {
        app.close()
    }
}
