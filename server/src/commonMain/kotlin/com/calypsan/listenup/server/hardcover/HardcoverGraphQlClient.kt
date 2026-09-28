package com.calypsan.listenup.server.hardcover

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Who a token belongs to on Hardcover. */
data class HardcoverMe(
    val id: Long,
    val username: String,
)

/** Outcome of [HardcoverGraphQlClient.me]. */
sealed interface MeResult {
    /** The token's owner. */
    data class Found(
        val me: HardcoverMe,
    ) : MeResult

    /** Hardcover rejected the token. */
    data object Unauthorized : MeResult

    /** Transient failure. */
    data class Unavailable(
        val detail: String,
    ) : MeResult
}

/**
 * Hardcover's GraphQL API. Only `me` so far; the sync PRs add the library queries. Every failure is
 * a typed [MeResult] — a transport error never escapes, and [CancellationException] is rethrown.
 */
class HardcoverGraphQlClient(
    private val http: HttpClient,
    private val apiBaseUrl: String = HARDCOVER_API_BASE_URL,
) {
    /** The account [accessToken] belongs to. Hardcover answers `me` as a list; the first entry is the owner. */
    suspend fun me(accessToken: String): MeResult =
        try {
            val response =
                http.post("$apiBaseUrl/v1/graphql") {
                    bearerAuth(accessToken)
                    header(HttpHeaders.UserAgent, HARDCOVER_USER_AGENT)
                    setBody(
                        TextContent(
                            hardcoverJson.encodeToString(GraphQlRequest(query = ME_QUERY)),
                            ContentType.Application.Json,
                        ),
                    )
                }
            when {
                response.status == HttpStatusCode.Unauthorized -> {
                    MeResult.Unauthorized
                }

                !response.status.isSuccess() -> {
                    MeResult.Unavailable("me ${response.status}")
                }

                else -> {
                    hardcoverJson
                        .decodeFromString<MeResponse>(response.bodyAsText())
                        .data
                        ?.me
                        ?.firstOrNull()
                        ?.let { MeResult.Found(HardcoverMe(it.id, it.username)) }
                        ?: MeResult.Unavailable("me: empty response")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            MeResult.Unavailable(e.message ?: e::class.simpleName.orEmpty())
        }

    private companion object {
        const val ME_QUERY = "{ me { id username } }"
    }
}

@Serializable
private data class GraphQlRequest(
    @SerialName("query") val query: String,
)

@Serializable
private data class MeResponse(
    @SerialName("data") val data: MeData? = null,
)

@Serializable
private data class MeData(
    @SerialName("me") val me: List<MeWire> = emptyList(),
)

@Serializable
private data class MeWire(
    @SerialName("id") val id: Long,
    @SerialName("username") val username: String,
)
