package com.calypsan.listenup.client.data.remote.model

import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.Success
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Contract tests verifying client can parse the exact envelope format produced by the server.
// The JSON fixtures below MUST match server/testdata/envelope/*.json (the source of truth).
// If you change the envelope format, update both places and verify both test suites pass.
class EnvelopeContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val successFixture = """{
  "v": 1,
  "success": true,
  "data": {
    "id": "test-123",
    "name": "Test Item"
  }
}"""

    private val successNullDataFixture = """{
  "v": 1,
  "success": true
}"""

    private val errorSimpleFixture = """{
  "v": 1,
  "success": false,
  "error": "Resource not found"
}"""

    private val errorDetailedFixture = """{
  "v": 1,
  "code": "conflict",
  "message": "Entity already exists",
  "details": {
    "existing_id": "abc-123"
  }
}"""

    @Test
    fun clientCanParseServerSuccessEnvelope() {
        val response = json.decodeFromString<ApiResponse<Map<String, String>>>(successFixture)
        assertEquals(1, response.version, "Version field must be parsed as 1")
        assertTrue(response.success, "Success must be true")
        assertNotNull(response.data, "Data must be present")
        val result = response.toResult()
        val success = assertIs<Success<Map<String, String>>>(result)
        assertEquals("test-123", success.data["id"])
        assertEquals("Test Item", success.data["name"])
    }

    @Test
    fun clientCanParseServerSuccessNullDataEnvelope() {
        val response = json.decodeFromString<ApiResponse<Unit?>>(successNullDataFixture)
        assertEquals(1, response.version)
        assertTrue(response.success)
        val result = response.toResult()

        @Suppress("UNUSED_VARIABLE")
        val success = assertIs<Success<Unit?>>(result)
    }

    @Test
    fun clientCanParseServerSimpleErrorEnvelope() {
        val response = json.decodeFromString<ApiResponse<Unit>>(errorSimpleFixture)
        assertEquals(1, response.version)
        assertEquals(false, response.success)
        assertEquals("Resource not found", response.error)
        val result = response.toResult()
        // Body-level message convention: ApiException is mapped via ErrorMapper
        // to InternalError; the original `error` text moves to debugInfo.
        assertIs<Failure>(result)
    }

    @Test
    fun clientCanParseServerDetailedErrorEnvelope() {
        val response = json.decodeFromString<ApiResponse<Unit>>(errorDetailedFixture)
        assertEquals(1, response.version)
        assertEquals("conflict", response.code)
        assertEquals("Entity already exists", response.message)
        assertNotNull(response.details)
        val result = response.toResult()
        assertIs<Failure>(result)
    }

    @Test
    fun versionFieldMustBeNamedV() {
        val badEnvelope = """{"version": 1, "success": true}"""
        val response = json.decodeFromString<ApiResponse<Unit?>>(badEnvelope)
        assertEquals(null, response.version, "Field must be 'v', not 'version'")
    }
}
