package com.calypsan.listenup.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Identifies the server instance to clients on first connect.
 *
 * This is the canonical example of a wire DTO: defined once in commonMain,
 * serialized identically by client and server, never duplicated.
 */
@Serializable
data class ServerInfo(
    @SerialName("name")
    val name: String,
    val version: String,
    val apiVersion: String,
)
