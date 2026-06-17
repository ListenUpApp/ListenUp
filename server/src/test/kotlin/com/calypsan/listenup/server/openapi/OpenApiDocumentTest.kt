package com.calypsan.listenup.server.openapi

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer

class OpenApiDocumentTest :
    FunSpec({
        fun JsonObject.typeOf(): String = this["type"]!!.jsonPrimitive.content

        test("nullable primitive maps to its underlying JSON type, not string") {
            schemaFor(serializer<Long?>().descriptor).typeOf() shouldBe "integer"
            schemaFor(serializer<Boolean?>().descriptor).typeOf() shouldBe "boolean"
            schemaFor(serializer<Long>().descriptor).typeOf() shouldBe "integer"
        }

        test("a list descriptor maps to a JSON array schema") {
            schemaFor(serializer<List<com.calypsan.listenup.api.dto.auth.SessionSummary>>().descriptor)
                .typeOf() shouldBe "array"
        }

        test("the document defines the bearerAuth security scheme") {
            val components = buildOpenApiDocument()["components"]!!.jsonObject
            val scheme = components["securitySchemes"]!!.jsonObject["bearerAuth"]!!.jsonObject
            scheme["type"]!!.jsonPrimitive.content shouldBe "http"
            scheme["scheme"]!!.jsonPrimitive.content shouldBe "bearer"
        }

        test("the sessions list endpoint documents an array response") {
            val doc = buildOpenApiDocument()
            val get = doc["paths"]!!.jsonObject["/api/v1/auth/sessions"]!!.jsonObject["get"]!!.jsonObject
            val schema = get["responses"]!!.jsonObject["200"]!!.jsonObject["content"]!!.jsonObject["application/json"]!!.jsonObject["schema"]!!.jsonObject
            schema["type"]!!.jsonPrimitive.content shouldBe "array"
        }

        test("a path with a path parameter documents that parameter") {
            val doc = buildOpenApiDocument()
            val delete = doc["paths"]!!.jsonObject["/api/v1/auth/sessions/{id}"]!!.jsonObject["delete"]!!.jsonObject
            val params = delete["parameters"]!!.jsonArray
            params.size shouldBe 1
            params[0].jsonObject["name"]!!.jsonPrimitive.content shouldBe "id"
            params[0].jsonObject["in"]!!.jsonPrimitive.content shouldBe "path"
        }
    })
