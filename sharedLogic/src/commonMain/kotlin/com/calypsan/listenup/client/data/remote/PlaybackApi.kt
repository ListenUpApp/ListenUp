package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.client.core.suspendRunCatching
import com.calypsan.listenup.client.data.remote.model.ApiResponse
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private val logger = KotlinLogging.logger {}

private const val HTTP_NOT_FOUND = 404

/**
 * Seam interface for playback API operations.
 *
 * Extracted so [DownloadAudioFile] can be tested in commonTest/jvmTest without
 * a real [ApiClientFactory]. Production callers use [PlaybackApi]; test callers
 * use a hand-rolled fake.
 */
interface PlaybackApiContract {
    suspend fun preparePlayback(
        bookId: String,
        audioFileId: String,
        capabilities: List<String>,
        spatial: Boolean,
    ): AppResult<PreparePlaybackResponse>

    /**
     * Cancel a server-side transcoding job. 204 on success; 404 if job doesn't exist
     * or already completed/cancelled (idempotent). Used by W8 Phase D's
     * cancel-during-WAITING_FOR_SERVER flow.
     */
    suspend fun cancelTranscode(jobId: String): AppResult<Unit>
}

/**
 * API client for playback-related endpoints.
 *
 * Handles format negotiation for audio streaming, ensuring the client
 * receives audio in a format it can play.
 *
 * Uses ApiClientFactory to obtain authenticated HttpClient at call time,
 * ensuring fresh tokens for each request.
 */
class PlaybackApi(
    private val clientFactory: ApiClientFactory,
) : PlaybackApiContract {
    /**
     * Prepare playback for an audio file.
     *
     * Negotiates the best audio format based on client capabilities.
     * If the client doesn't support the source codec, the server will
     * either return a transcoded version or trigger transcoding.
     *
     * @param bookId The book ID
     * @param audioFileId The audio file ID
     * @param capabilities List of codecs the client can play (e.g., ["aac", "mp3", "opus"])
     * @param spatial Whether the client prefers spatial audio
     * @return PreparePlaybackResponse with stream URL and ready status
     */
    override suspend fun preparePlayback(
        bookId: String,
        audioFileId: String,
        capabilities: List<String>,
        spatial: Boolean, // NEW parameter
    ): AppResult<PreparePlaybackResponse> =
        apiCall(errorMessage = "Prepare-playback response missing data") {
            logger.debug { "Preparing playback: book=$bookId, file=$audioFileId, caps=$capabilities, spatial=$spatial" }
            val client = clientFactory.getClient()
            val request =
                PreparePlaybackRequest(
                    bookId = bookId,
                    audioFileId = audioFileId,
                    capabilities = capabilities,
                    spatial = spatial,
                )
            client
                .post("/api/v1/playback/prepare") {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.body<ApiResponse<PreparePlaybackApiResponse>>()
        }.map { it.toDomain() }

    override suspend fun cancelTranscode(jobId: String): AppResult<Unit> =
        when (val result = suspendRunCatching { clientFactory.getClient().post("/api/v1/transcode/cancel/$jobId") }) {
            is AppResult.Success -> {
                AppResult.Success(Unit)
            }

            is AppResult.Failure -> {
                // 404 means "already cancelled / completed / never existed" — idempotent per server contract.
                val server4xx = result.error as? TransportError.Server4xx
                if (server4xx?.statusCode == HTTP_NOT_FOUND) {
                    AppResult.Success(Unit)
                } else {
                    result
                }
            }
        }
}

/**
 * Request body for the prepare playback endpoint.
 */
@Serializable
data class PreparePlaybackRequest(
    @SerialName("book_id")
    val bookId: String,
    @SerialName("audio_file_id")
    val audioFileId: String,
    val capabilities: List<String>,
    val spatial: Boolean, // NEW: Client's spatial audio preference
)

/**
 * Response from the prepare playback endpoint.
 */
data class PreparePlaybackResponse(
    /** True if audio is ready to stream */
    val ready: Boolean,
    /** URL to stream the audio (original or transcoded) */
    val streamUrl: String,
    /** Which variant is being served: "original" or "transcoded" */
    val variant: String,
    /** Codec of the stream that will be served */
    val codec: String,
    /** Transcode job ID if transcoding is in progress (ready=false) */
    val transcodeJobId: String?,
    /** Current transcoding progress (0-100) if not ready */
    val progress: Int,
)

/**
 * Internal API response model.
 */
@Serializable
internal data class PreparePlaybackApiResponse(
    val ready: Boolean,
    @SerialName("stream_url")
    val streamUrl: String,
    val variant: String,
    val codec: String,
    @SerialName("transcode_job_id")
    val transcodeJobId: String? = null,
    val progress: Int = 0,
) {
    fun toDomain(): PreparePlaybackResponse =
        PreparePlaybackResponse(
            ready = ready,
            streamUrl = streamUrl,
            variant = variant,
            codec = codec,
            transcodeJobId = transcodeJobId,
            progress = progress,
        )
}
