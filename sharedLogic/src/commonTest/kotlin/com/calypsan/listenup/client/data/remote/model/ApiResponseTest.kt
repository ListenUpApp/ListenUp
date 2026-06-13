package com.calypsan.listenup.client.data.remote.model

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.core.Failure
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ApiResponseTest :
    FunSpec({
        val json = Json { ignoreUnknownKeys = true }

        // region Canary Field Validation

        test("toResult_throwsEnvelopeMismatchException_whenVersionFieldMissing") {
            val response =
                ApiResponse<String>(
                    version = null,
                    success = true,
                    data = "test",
                )

            val exception =
                shouldThrow<EnvelopeMismatchException> {
                    response.toResult()
                }

            exception.message!!.contains("missing 'v' field") shouldBe true
        }

        test("toResult_throwsEnvelopeMismatchException_whenVersionIsWrong") {
            val response =
                ApiResponse<String>(
                    version = 999,
                    success = true,
                    data = "test",
                )

            val exception =
                shouldThrow<EnvelopeMismatchException> {
                    response.toResult()
                }

            exception.message!!.contains("Expected v=1") shouldBe true
            exception.message!!.contains("got v=999") shouldBe true
        }

        // endregion

        // region Success Responses

        test("toResult_returnsSuccess_whenVersionValidAndSuccessTrue") {
            val response =
                ApiResponse(
                    version = 1,
                    success = true,
                    data = "test data",
                )

            val result = response.toResult()

            val success = result.shouldBeInstanceOf<AppResult.Success<String>>()
            success.data shouldBe "test data"
        }

        test("toResult_returnsSuccessWithNullData_whenSuccessTrueAndDataNull") {
            val response =
                ApiResponse<String?>(
                    version = 1,
                    success = true,
                    data = null,
                )

            val result = response.toResult()

            val success = result.shouldBeInstanceOf<AppResult.Success<String?>>()
            success.data shouldBe null
        }

        // endregion

        // region Simple Error Responses

        test("toResult_returnsFailure_whenSuccessFalse") {
            val response =
                ApiResponse<String>(
                    version = 1,
                    success = false,
                    error = "Something went wrong",
                )

            val result = response.toResult()

            // Body-level message convention: ApiException is mapped to InternalError by
            // ErrorMapper, so the failure surfaces as a Failure (the original `error`
            // string is now in debugInfo, not message).
            result.shouldBeInstanceOf<AppResult.Failure>()
        }

        test("toResult_returnsFailureWithDefaultMessage_whenErrorIsNull") {
            val response =
                ApiResponse<String>(
                    version = 1,
                    success = false,
                    error = null,
                )

            val result = response.toResult()

            result.shouldBeInstanceOf<AppResult.Failure>()
        }

        // endregion

        // region Detailed Error Responses (code/message format)

        test("toResult_returnsFailure_whenCodeIsPresent") {
            val response =
                ApiResponse<String>(
                    version = 1,
                    code = "conflict",
                    message = "Entity already exists",
                )

            val result = response.toResult()

            result.shouldBeInstanceOf<AppResult.Failure>()
        }

        test("toResult_returnsFailureWithCode_evenWhenSuccessTrue") {
            // If code is present, treat it as an error regardless of success field
            val response =
                ApiResponse<String>(
                    version = 1,
                    success = true,
                    code = "validation_error",
                    message = "Invalid input",
                )

            val result = response.toResult()

            result.shouldBeInstanceOf<AppResult.Failure>()
        }

        test("toResult_handlesErrorEnvelopeWithDetails") {
            val details =
                buildJsonObject {
                    put("existing_id", "123")
                    put("suggestion", "Use merge instead")
                }

            val response =
                ApiResponse<String>(
                    version = 1,
                    code = "disambiguation_required",
                    message = "Multiple matches found",
                    details = details,
                )

            val result = response.toResult()

            result.shouldBeInstanceOf<AppResult.Failure>()
        }

        // endregion

        // region JSON Deserialization

        test("apiResponse_deserializesSuccessEnvelope") {
            val jsonString =
                """
                {
                    "v": 1,
                    "success": true,
                    "data": "hello world"
                }
                """.trimIndent()

            val response = json.decodeFromString<ApiResponse<String>>(jsonString)

            response.version shouldBe 1
            response.success shouldBe true
            response.data shouldBe "hello world"
        }

        test("apiResponse_deserializesSimpleErrorEnvelope") {
            val jsonString =
                """
                {
                    "v": 1,
                    "success": false,
                    "error": "Not found"
                }
                """.trimIndent()

            val response = json.decodeFromString<ApiResponse<String>>(jsonString)

            response.version shouldBe 1
            response.success shouldBe false
            response.error shouldBe "Not found"
        }

        test("apiResponse_deserializesDetailedErrorEnvelope") {
            val jsonString =
                """
                {
                    "v": 1,
                    "code": "conflict",
                    "message": "Entity already exists",
                    "details": {"id": "123"}
                }
                """.trimIndent()

            val response = json.decodeFromString<ApiResponse<String>>(jsonString)

            response.version shouldBe 1
            response.code shouldBe "conflict"
            response.message shouldBe "Entity already exists"
            response.details shouldNotBe null
        }

        test("apiResponse_deserializesWithMissingOptionalFields") {
            // Minimal valid envelope - just v and success
            val jsonString =
                """
                {
                    "v": 1,
                    "success": true
                }
                """.trimIndent()

            val response = json.decodeFromString<ApiResponse<String?>>(jsonString)

            response.version shouldBe 1
            response.success shouldBe true
            response.data shouldBe null
            response.error shouldBe null
            response.code shouldBe null
        }

        test("apiResponse_deserializesLegacyEnvelopeWithoutVersion") {
            // Old responses without v field should parse but fail on toResult()
            val jsonString =
                """
                {
                    "success": true,
                    "data": "test"
                }
                """.trimIndent()

            val response = json.decodeFromString<ApiResponse<String>>(jsonString)

            response.version shouldBe null

            shouldThrow<EnvelopeMismatchException> {
                response.toResult()
            }
        }

        // endregion
    })
