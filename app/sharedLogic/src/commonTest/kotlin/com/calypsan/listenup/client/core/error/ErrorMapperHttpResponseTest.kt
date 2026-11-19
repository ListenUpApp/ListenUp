package com.calypsan.listenup.client.core.error

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.ImportError
import com.calypsan.listenup.api.error.TransportError
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * [ErrorMapper.forHttpResponse]: a refusal the server explained with a typed error reaches the user
 * as that error, and every reply it cannot explain keeps today's status-based mapping.
 */
class ErrorMapperHttpResponseTest :
    FunSpec({
        val refusal = ImportError.UploadFailed(correlationId = "req-1", debugInfo = "missing file part")
        val typedBody = contractJson.encodeToString(AppError.serializer(), refusal)

        test("a refusal the server typed is that error") {
            ErrorMapper.forHttpResponse(status = 422, body = typedBody, debugInfo = "POST /x answered 422") shouldBe
                refusal
        }

        test("a refusal with a body that is not JSON is mapped by its status") {
            ErrorMapper
                .forHttpResponse(status = 404, body = "<html>Not Found</html>", debugInfo = null)
                .shouldBeInstanceOf<TransportError.Server4xx>()
                .statusCode shouldBe 404
        }

        test("a refusal with JSON that is not an error is mapped by its status") {
            ErrorMapper
                .forHttpResponse(status = 400, body = """{"status":"ok"}""", debugInfo = null)
                .shouldBeInstanceOf<TransportError.Server4xx>()
                .statusCode shouldBe 400
        }

        test("a 401 is always the session ending, whatever the body says") {
            ErrorMapper
                .forHttpResponse(status = 401, body = typedBody, debugInfo = null)
                .shouldBeInstanceOf<AuthError.SessionExpired>()
        }

        test("a server failure stays a retryable server error, whatever the body says") {
            ErrorMapper
                .forHttpResponse(status = 503, body = typedBody, debugInfo = null)
                .shouldBeInstanceOf<TransportError.Server5xx>()
                .statusCode shouldBe 503
        }
    })
