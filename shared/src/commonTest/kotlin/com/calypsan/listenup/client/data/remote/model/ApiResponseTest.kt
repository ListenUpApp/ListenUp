package com.calypsan.listenup.client.data.remote.model

import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.Success
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApiResponseTest {
    private val json = Json { ignoreUnknownKeys = true }

    // region Canary Field Validation

    @Test
    fun toResult_throwsEnvelopeMismatchException_whenVersionFieldMissing() {
        val response =
            ApiResponse<String>(
                version = null,
                success = true,
                data = "test",
            )

        val exception =
            assertFailsWith<EnvelopeMismatchException> {
                response.toResult()
            }

        assertTrue(exception.message!!.contains("missing 'v' field"))
    }

    @Test
    fun toResult_throwsEnvelopeMismatchException_whenVersionIsWrong() {
        val response =
            ApiResponse<String>(
                version = 999,
                success = true,
                data = "test",
            )

        val exception =
            assertFailsWith<EnvelopeMismatchException> {
                response.toResult()
            }

        assertTrue(exception.message!!.contains("Expected v=1"))
        assertTrue(exception.message!!.contains("got v=999"))
    }

    // endregion

    // region Success Responses

    @Test
    fun toResult_returnsSuccess_whenVersionValidAndSuccessTrue() {
        val response =
            ApiResponse(
                version = 1,
                success = true,
                data = "test data",
            )

        val result = response.toResult()

        val success = assertIs<Success<String>>(result)
        assertEquals("test data", success.data)
    }

    @Test
    fun toResult_returnsSuccessWithNullData_whenSuccessTrueAndDataNull() {
        val response =
            ApiResponse<String?>(
                version = 1,
                success = true,
                data = null,
            )

        val result = response.toResult()

        val success = assertIs<Success<String?>>(result)
        assertNull(success.data)
    }

    // endregion

    // region Simple Error Responses

    @Test
    fun toResult_returnsFailure_whenSuccessFalse() {
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
        assertIs<Failure>(result)
    }

    @Test
    fun toResult_returnsFailureWithDefaultMessage_whenErrorIsNull() {
        val response =
            ApiResponse<String>(
                version = 1,
                success = false,
                error = null,
            )

        val result = response.toResult()

        assertIs<Failure>(result)
    }

    // endregion

    // region Detailed Error Responses (code/message format)

    @Test
    fun toResult_returnsFailure_whenCodeIsPresent() {
        val response =
            ApiResponse<String>(
                version = 1,
                code = "conflict",
                message = "Entity already exists",
            )

        val result = response.toResult()

        assertIs<Failure>(result)
    }

    @Test
    fun toResult_returnsFailureWithCode_evenWhenSuccessTrue() {
        // If code is present, treat it as an error regardless of success field
        val response =
            ApiResponse<String>(
                version = 1,
                success = true,
                code = "validation_error",
                message = "Invalid input",
            )

        val result = response.toResult()

        assertIs<Failure>(result)
    }

    @Test
    fun toResult_handlesErrorEnvelopeWithDetails() {
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

        assertIs<Failure>(result)
    }

    // endregion

    // region JSON Deserialization

    @Test
    fun apiResponse_deserializesSuccessEnvelope() {
        val jsonString =
            """
            {
                "v": 1,
                "success": true,
                "data": "hello world"
            }
            """.trimIndent()

        val response = json.decodeFromString<ApiResponse<String>>(jsonString)

        assertEquals(1, response.version)
        assertTrue(response.success)
        assertEquals("hello world", response.data)
    }

    @Test
    fun apiResponse_deserializesSimpleErrorEnvelope() {
        val jsonString =
            """
            {
                "v": 1,
                "success": false,
                "error": "Not found"
            }
            """.trimIndent()

        val response = json.decodeFromString<ApiResponse<String>>(jsonString)

        assertEquals(1, response.version)
        assertEquals(false, response.success)
        assertEquals("Not found", response.error)
    }

    @Test
    fun apiResponse_deserializesDetailedErrorEnvelope() {
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

        assertEquals(1, response.version)
        assertEquals("conflict", response.code)
        assertEquals("Entity already exists", response.message)
        assertNotNull(response.details)
    }

    @Test
    fun apiResponse_deserializesWithMissingOptionalFields() {
        // Minimal valid envelope - just v and success
        val jsonString =
            """
            {
                "v": 1,
                "success": true
            }
            """.trimIndent()

        val response = json.decodeFromString<ApiResponse<String?>>(jsonString)

        assertEquals(1, response.version)
        assertTrue(response.success)
        assertNull(response.data)
        assertNull(response.error)
        assertNull(response.code)
    }

    @Test
    fun apiResponse_deserializesLegacyEnvelopeWithoutVersion() {
        // Old responses without v field should parse but fail on toResult()
        val jsonString =
            """
            {
                "success": true,
                "data": "test"
            }
            """.trimIndent()

        val response = json.decodeFromString<ApiResponse<String>>(jsonString)

        assertNull(response.version)

        assertFailsWith<EnvelopeMismatchException> {
            response.toResult()
        }
    }

    // endregion
}
