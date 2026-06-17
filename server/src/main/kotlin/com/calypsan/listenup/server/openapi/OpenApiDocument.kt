package com.calypsan.listenup.server.openapi

import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.RefreshRequest
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.dto.auth.SessionSummary
import com.calypsan.listenup.api.dto.auth.User
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

/**
 * DECISION (2026-06-17): code-first OpenAPI via the in-code fallback, NOT a third-party
 * library. The candidate code-first libraries (Tegral, dev.forst/Papsign ktor-openapi-generator)
 * trail Ktor's release cadence and carry version-conflict risk against Ktor 3.5 +
 * ktor-server-resources + kotlinx.serialization; per the spike's controller guidance we go
 * straight to the guaranteed path rather than fight a lagging dependency. This path needs no
 * new dependency and already achieves code-first generation: each REST endpoint is declared
 * once in [authOperations]; request/response schemas are derived from the `@Serializable`
 * DTO descriptors by [schemaFor]. To document a new endpoint, add an [ApiOperation] here —
 * the served document and the drift-guard test pick it up automatically.
 * (If a code-first library is later adopted, replace this assembly while keeping the
 * /api/openapi.json + /api/docs contract that OpenApiAuthDriftTest pins.)
 */
data class ApiOperation(
    val path: String,
    val method: String,
    val summary: String,
    val requestType: SerialDescriptor? = null,
    val responseType: SerialDescriptor,
    val authenticated: Boolean,
)

@OptIn(ExperimentalSerializationApi::class)
private inline fun <reified T> descriptorOf(): SerialDescriptor = serializer<T>().descriptor

@OptIn(ExperimentalSerializationApi::class)
val authOperations: List<ApiOperation> =
    listOf(
        ApiOperation("/api/v1/auth/login", "post", "Authenticate and start a session",
            descriptorOf<LoginRequest>(), descriptorOf<AuthSession>(), authenticated = false),
        ApiOperation("/api/v1/auth/register", "post", "Register a new user",
            descriptorOf<RegisterRequest>(), descriptorOf<RegisterResult>(), authenticated = false),
        ApiOperation("/api/v1/auth/setup", "post", "Create the first (root) user",
            descriptorOf<RegisterRequest>(), descriptorOf<AuthSession>(), authenticated = false),
        ApiOperation("/api/v1/auth/refresh", "post", "Exchange a refresh token for a new session",
            descriptorOf<RefreshRequest>(), descriptorOf<AuthSession>(), authenticated = false),
        ApiOperation("/api/v1/auth/logout", "post", "Revoke the current session",
            null, descriptorOf<Unit>(), authenticated = true),
        ApiOperation("/api/v1/auth/logout/all", "post", "Revoke all of the user's sessions",
            null, descriptorOf<Unit>(), authenticated = true),
        ApiOperation("/api/v1/auth/current-user", "get", "Get the authenticated user",
            null, descriptorOf<User>(), authenticated = true),
        ApiOperation("/api/v1/auth/sessions", "get", "List the user's active sessions",
            null, descriptorOf<List<SessionSummary>>(), authenticated = true),
        ApiOperation("/api/v1/auth/sessions/{id}", "delete", "Revoke a specific session",
            null, descriptorOf<Unit>(), authenticated = true),
    )

private const val JSON_TYPE_STRING = "string"

/** Minimal JSON-Schema for a kotlinx.serialization descriptor (objects, lists, primitives). */
@OptIn(ExperimentalSerializationApi::class)
fun schemaFor(descriptor: SerialDescriptor): JsonObject =
    when (val kind = descriptor.kind) {
        is StructureKind.LIST ->
            buildJsonObject {
                put("type", "array")
                put("items", schemaFor(descriptor.getElementDescriptor(0)))
            }
        is StructureKind.CLASS, is StructureKind.OBJECT ->
            buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        for (i in 0 until descriptor.elementsCount) {
                            put(descriptor.getElementName(i), schemaFor(descriptor.getElementDescriptor(i)))
                        }
                    },
                )
            }
        SerialKind.ENUM ->
            buildJsonObject {
                put("type", JSON_TYPE_STRING)
                put(
                    "enum",
                    buildJsonArray { for (i in 0 until descriptor.elementsCount) add(JsonPrimitive(descriptor.getElementName(i))) },
                )
            }
        is PrimitiveKind -> buildJsonObject { put("type", primitiveType(kind)) }
        else -> buildJsonObject { put("type", JSON_TYPE_STRING) }
    }

private fun primitiveType(kind: PrimitiveKind): String =
    when (kind) {
        PrimitiveKind.BOOLEAN -> "boolean"
        PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> "integer"
        PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> "number"
        PrimitiveKind.CHAR, PrimitiveKind.STRING -> JSON_TYPE_STRING
    }

/** Assemble the full OpenAPI 3.0 document from [operations]. */
fun buildOpenApiDocument(operations: List<ApiOperation> = authOperations): JsonObject =
    buildJsonObject {
        put("openapi", "3.0.3")
        put(
            "info",
            buildJsonObject {
                put("title", "ListenUp API")
                put("version", "v1")
            },
        )
        put("paths", buildPaths(operations))
        put(
            "components",
            buildJsonObject {
                put(
                    "securitySchemes",
                    buildJsonObject {
                        put(
                            "bearerAuth",
                            buildJsonObject {
                                put("type", "http")
                                put("scheme", "bearer")
                                put("bearerFormat", "JWT")
                            },
                        )
                    },
                )
            },
        )
    }

private fun buildPaths(operations: List<ApiOperation>): JsonObject =
    buildJsonObject {
        operations.groupBy { it.path }.forEach { (path, ops) ->
            put(
                path,
                buildJsonObject {
                    ops.forEach { op -> put(op.method, buildOperation(op)) }
                },
            )
        }
    }

private fun buildOperation(op: ApiOperation): JsonObject =
    buildJsonObject {
        put("summary", op.summary)
        val pathParams = Regex("""\{(\w+)\}""").findAll(op.path).map { it.groupValues[1] }.toList()
        if (pathParams.isNotEmpty()) {
            put(
                "parameters",
                buildJsonArray {
                    pathParams.forEach { name ->
                        add(
                            buildJsonObject {
                                put("name", name)
                                put("in", "path")
                                put("required", true)
                                put("schema", buildJsonObject { put("type", JSON_TYPE_STRING) })
                            },
                        )
                    }
                },
            )
        }
        if (op.requestType != null) {
            put("requestBody", jsonContent(schemaFor(op.requestType)))
        }
        put(
            "responses",
            buildJsonObject {
                put(
                    "200",
                    buildJsonObject {
                        put("description", "Success")
                        put("content", contentSchema(schemaFor(op.responseType)))
                    },
                )
            },
        )
        if (op.authenticated) {
            put("security", buildJsonArray { add(buildJsonObject { put("bearerAuth", JsonArray(emptyList())) }) })
        }
    }

private fun jsonContent(schema: JsonObject): JsonObject =
    buildJsonObject {
        put("required", true)
        put("content", contentSchema(schema))
    }

private fun contentSchema(schema: JsonObject): JsonObject =
    buildJsonObject {
        put("application/json", buildJsonObject { put("schema", schema) })
    }
