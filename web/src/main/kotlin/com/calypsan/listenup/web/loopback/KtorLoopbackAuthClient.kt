package com.calypsan.listenup.web.loopback

import com.calypsan.listenup.api.dto.ServerInfo
import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.RefreshRequest
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.dto.auth.RegistrationStatusEvent
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.SessionSummary
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.result.AppResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException

/**
 * Loopback implementation. The [client] is configured with a default base URL and
 * [com.calypsan.listenup.api.contractJson] content negotiation by `installWebUi`
 * (production) or supplied directly (tests).
 *
 * Auth endpoints always return an `AppResult<T>` envelope (success *and* failure), so
 * [envelope] decodes either branch. `/instance` and `/registration-status` return bare
 * bodies, so [bare] switches on the HTTP status. Loopback transport failures (effectively
 * impossible in-process) fold to [InternalError].
 */
class KtorLoopbackAuthClient(
    private val client: HttpClient,
) : LoopbackAuthClient {
    override suspend fun serverInfo(): AppResult<ServerInfo> =
        bare { client.get("/api/v1/instance") }

    override suspend fun login(request: LoginRequest): AppResult<AuthSession> =
        envelope { client.post("/api/v1/auth/login") { jsonBody(request) } }

    override suspend fun setup(request: RegisterRequest): AppResult<AuthSession> =
        envelope { client.post("/api/v1/auth/setup") { jsonBody(request) } }

    override suspend fun register(request: RegisterRequest): AppResult<RegisterResult> =
        envelope { client.post("/api/v1/auth/register") { jsonBody(request) } }

    override suspend fun refresh(request: RefreshRequest): AppResult<AuthSession> =
        envelope { client.post("/api/v1/auth/refresh") { jsonBody(request) } }

    override suspend fun logout(accessToken: AccessToken): AppResult<Unit> =
        envelope { client.post("/api/v1/auth/logout") { bearerAuth(accessToken.value) } }

    override suspend fun listSessions(accessToken: AccessToken): AppResult<List<SessionSummary>> =
        envelope { client.get("/api/v1/auth/sessions") { bearerAuth(accessToken.value) } }

    override suspend fun revokeSession(accessToken: AccessToken, id: SessionId): AppResult<Unit> =
        envelope { client.delete("/api/v1/auth/sessions/${id.value}") { bearerAuth(accessToken.value) } }

    override suspend fun registrationStatus(userId: UserId): AppResult<RegistrationStatusEvent> =
        bare { client.get("/api/v1/auth/registration-status/${userId.value}") }

    private suspend inline fun <reified T> envelope(call: () -> HttpResponse): AppResult<T> =
        runLoopback { call().body<AppResult<T>>() }

    private suspend inline fun <reified T> bare(call: () -> HttpResponse): AppResult<T> =
        runLoopback {
            val response = call()
            if (response.status.isSuccess()) {
                AppResult.Success(response.body<T>())
            } else {
                AppResult.Failure(response.body<AppError>())
            }
        }

    private inline fun <T> runLoopback(block: () -> AppResult<T>): AppResult<T> =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppResult.Failure(InternalError(debugInfo = e.message, cause = e::class.simpleName))
        }
}

private fun io.ktor.client.request.HttpRequestBuilder.jsonBody(body: Any) {
    contentType(ContentType.Application.Json)
    setBody(body)
}
