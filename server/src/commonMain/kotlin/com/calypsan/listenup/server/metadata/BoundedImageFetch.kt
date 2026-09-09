package com.calypsan.listenup.server.metadata

import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.logging.loggerFor
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.contentLength
import io.ktor.http.contentType
import io.ktor.http.takeFrom
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

private val log = loggerFor<BoundedImageFetch>()

// Constant per outcome: the caller is told *that* the URL was refused, never what the server
// learned by trying it. A per-instance message would let a caller distinguish refused/timed-out/
// not-an-image for destinations it has no business probing. The cause is logged server-side only.
private const val REJECTED_DEBUG = "image response rejected"
private const val UNAVAILABLE_DEBUG = "image fetch failed"
private const val TOO_MANY_REDIRECTS_DEBUG = "image URL redirected too many times"
private const val FETCH_FAILURE_LOG_MESSAGE = "bounded image fetch failed"

private const val READ_CHUNK_BYTES = 8192
private val REDIRECT_STATUS_RANGE = 300..399

/**
 * The single seam every fetch of an UNTRUSTED image URL goes through — a cover URL a provider
 * returned, a cover URL a user supplied, or a contributor photo URL. It layers Tier 2 on top of the
 * shared metadata client's Tier-1 time budget:
 *
 *  1. **Destination policy.** [SafeCoverUrl] must accept the URL, or nothing is requested at all.
 *  2. **Per-hop re-validation.** Redirects are followed manually against a client with
 *     `followRedirects = false`, so each `Location` is re-checked by the same policy *before* the
 *     next request is issued. Ktor's automatic redirect handling would resolve the whole chain
 *     before this class ever saw the intermediate destinations, which would make step 1 decorative.
 *  3. **Hop budget.** At most [maxRedirects] follows; exhausting it is a typed failure.
 *  4. **Declared type.** A response declaring a content type outside the `image` family is refused.
 *     An *absent* content type is allowed through: origins that serve images without one are real,
 *     and the magic-number sniff in [com.calypsan.listenup.server.media.ImageStore] (covers and
 *     photos) or the header parser (dimension probe) is the authority on the bytes themselves.
 *  5. **Byte ceiling.** A declared length over [maxBytes] is refused before the body is read at
 *     all; otherwise the body is read as a capped stream and abandoned the moment it outgrows the
 *     ceiling. Nothing unbounded is ever buffered — on the native binary that heap is the server.
 *
 * Deliberately *not* here: any restriction on the hosts the shared client may reach. Operator-
 * configured provider base URLs (a self-hosted Audnexus mirror, a custom LAN provider) are trusted
 * destinations and Tier 1 only — see
 * [com.calypsan.listenup.server.di.installMetadataClientDefaults].
 *
 * Every outcome is an [AppResult]; only [CancellationException] escapes.
 */
class BoundedImageFetch(
    httpClient: HttpClient,
    private val maxBytes: Long = DEFAULT_MAX_IMAGE_BYTES,
    private val maxRedirects: Int = DEFAULT_MAX_REDIRECTS,
) {
    private val redirectlessClient = httpClient.config { followRedirects = false }

    /**
     * Fetches [url] under the full Tier-2 policy.
     *
     * @param leadingBytes when set, only the first that many bytes are wanted: a `Range` hint is
     *   sent and the read stops at that prefix even if the remote ignores the hint. A prefix that
     *   arrives is a success, not a truncation failure — the dimension probe only needs a header.
     */
    suspend fun fetch(
        url: String,
        leadingBytes: Int? = null,
    ): AppResult<ByteArray> =
        try {
            follow(url, leadingBytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { FETCH_FAILURE_LOG_MESSAGE }
            AppResult.Failure(MetadataError.ExternalUnavailable(debugInfo = UNAVAILABLE_DEBUG))
        }

    /** One request's outcome: another destination to check, or a result to hand back. */
    private sealed interface Hop {
        /** The response redirected; [location] still has to clear the policy before it is fetched. */
        data class Follow(
            val location: String,
        ) : Hop

        /** The chain ended here, for better or worse. */
        data class Finished(
            val result: AppResult<ByteArray>,
        ) : Hop
    }

    /** Validates each destination in turn and follows at most [maxRedirects] hops. */
    private suspend fun follow(
        url: String,
        leadingBytes: Int?,
    ): AppResult<ByteArray> {
        var target = url
        repeat(maxRedirects + 1) {
            SafeCoverUrl.validate(target)?.let { return AppResult.Failure(it) }
            when (val hop = requestHop(target, leadingBytes)) {
                is Hop.Finished -> return hop.result
                is Hop.Follow -> target = hop.location
            }
        }
        return AppResult.Failure(MetadataError.UnsafeUrl(debugInfo = TOO_MANY_REDIRECTS_DEBUG))
    }

    /**
     * Issues one request as a *streaming* call.
     *
     * `prepareGet { }.execute { }` rather than `get()` on purpose: a plain `get()` runs Ktor's
     * body-saving plugin, which buffers the whole response into memory before this function is
     * even resumed — the byte ceiling below would then be checking a body that had already been
     * allocated in full, which is precisely the exhaustion this class exists to prevent. `execute`
     * also releases the connection afterwards even when the body is deliberately left unread, as
     * it is for every redirect.
     */
    private suspend fun requestHop(
        target: String,
        leadingBytes: Int?,
    ): Hop =
        redirectlessClient
            .prepareGet(target) {
                if (leadingBytes != null) header(HttpHeaders.Range, "bytes=0-${leadingBytes - 1}")
            }.execute { response ->
                val location = response.headers[HttpHeaders.Location]
                if (response.status.value in REDIRECT_STATUS_RANGE && location != null) {
                    Hop.Follow(URLBuilder(target).takeFrom(location).buildString())
                } else {
                    Hop.Finished(readBounded(response, leadingBytes))
                }
            }

    /**
     * Refuses [response] on a declared non-image type or a declared length over [maxBytes], then
     * reads the body as a capped stream, stopping at [leadingBytes] when the caller asked for a
     * prefix and failing once the hard ceiling is passed.
     */
    private suspend fun readBounded(
        response: HttpResponse,
        leadingBytes: Int?,
    ): AppResult<ByteArray> {
        val declaredType = response.contentType()
        if (declaredType != null && !declaredType.match(ContentType.Image.Any)) return rejected()
        val declaredLength = response.contentLength()
        if (leadingBytes == null && declaredLength != null && declaredLength > maxBytes) return rejected()

        val channel = response.bodyAsChannel()
        val buffer = Buffer()
        val chunk = ByteArray(READ_CHUNK_BYTES)
        while (leadingBytes == null || buffer.size < leadingBytes) {
            val read = channel.readAvailable(chunk, 0, chunk.size)
            if (read == -1) break
            buffer.write(chunk, 0, read)
            if (buffer.size > maxBytes) return rejected()
        }
        val bytes = buffer.readByteArray()
        return AppResult.Success(
            if (leadingBytes != null && bytes.size > leadingBytes) bytes.copyOf(leadingBytes) else bytes,
        )
    }

    private fun rejected(): AppResult<ByteArray> =
        AppResult.Failure(MetadataError.Malformed(debugInfo = REJECTED_DEBUG))

    companion object {
        /**
         * Shared ceiling for cover and contributor-photo downloads. Matches the larger of the two
         * local upload caps (`COVER_MAX_BYTES` 10 MiB, `AVATAR_MAX_BYTES` 5 MiB) so it never
         * rejects a legitimate image before the type-specific store gets to validate it — this is
         * purely a memory-exhaustion guard against an oversized or hostile response.
         */
        const val DEFAULT_MAX_IMAGE_BYTES: Long = 10L * 1024 * 1024

        /**
         * Enough hops for the CDN and storefront redirects real cover URLs use, few enough that a
         * chain designed to never terminate is abandoned quickly.
         */
        const val DEFAULT_MAX_REDIRECTS: Int = 5
    }
}
