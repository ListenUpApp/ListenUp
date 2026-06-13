package com.calypsan.listenup.client.data.remote.model

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.core.Failure
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json

// Contract tests verifying client can parse the exact envelope format produced by the server.
// The JSON fixtures below MUST match server/testdata/envelope/*.json (the source of truth).
// If you change the envelope format, update both places and verify both test suites pass.
class EnvelopeContractTest :
    FunSpec({
        val json = Json { ignoreUnknownKeys = true }

        val successFixture = """{
  "v": 1,
  "success": true,
  "data": {
    "id": "test-123",
    "name": "Test Item"
  }
}"""

        val successNullDataFixture = """{
  "v": 1,
  "success": true
}"""

        val errorSimpleFixture = """{
  "v": 1,
  "success": false,
  "error": "Resource not found"
}"""

        val errorDetailedFixture = """{
  "v": 1,
  "code": "conflict",
  "message": "Entity already exists",
  "details": {
    "existing_id": "abc-123"
  }
}"""

        test("clientCanParseServerSuccessEnvelope") {
            val response = json.decodeFromString<ApiResponse<Map<String, String>>>(successFixture)
            withClue("Version field must be parsed as 1") { response.version shouldBe 1 }
            withClue("Success must be true") { response.success shouldBe true }
            withClue("Data must be present") { response.data shouldNotBe null }
            val result = response.toResult()
            val success = result.shouldBeInstanceOf<AppResult.Success<Map<String, String>>>()
            success.data["id"] shouldBe "test-123"
            success.data["name"] shouldBe "Test Item"
        }

        test("clientCanParseServerSuccessNullDataEnvelope") {
            val response = json.decodeFromString<ApiResponse<Unit?>>(successNullDataFixture)
            response.version shouldBe 1
            response.success shouldBe true
            val result = response.toResult()

            @Suppress("UNUSED_VARIABLE")
            val success = result.shouldBeInstanceOf<AppResult.Success<Unit?>>()
        }

        test("clientCanParseServerSimpleErrorEnvelope") {
            val response = json.decodeFromString<ApiResponse<Unit>>(errorSimpleFixture)
            response.version shouldBe 1
            response.success shouldBe false
            response.error shouldBe "Resource not found"
            val result = response.toResult()
            // Body-level message convention: ApiException is mapped via ErrorMapper
            // to InternalError; the original `error` text moves to debugInfo.
            result.shouldBeInstanceOf<AppResult.Failure>()
        }

        test("clientCanParseServerDetailedErrorEnvelope") {
            val response = json.decodeFromString<ApiResponse<Unit>>(errorDetailedFixture)
            response.version shouldBe 1
            response.code shouldBe "conflict"
            response.message shouldBe "Entity already exists"
            response.details shouldNotBe null
            val result = response.toResult()
            result.shouldBeInstanceOf<AppResult.Failure>()
        }

        test("versionFieldMustBeNamedV") {
            val badEnvelope = """{"version": 1, "success": true}"""
            val response = json.decodeFromString<ApiResponse<Unit?>>(badEnvelope)
            withClue("Field must be 'v', not 'version'") { response.version shouldBe null }
        }
    })
