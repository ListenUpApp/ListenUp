package com.calypsan.listenup.server.backup

import com.calypsan.listenup.server.io.hashFileSha256
import kotlinx.io.files.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Describes the contents of a `.listenup.zip` backup archive.
 *
 * Stored as `manifest.json` inside the archive. Carries format/schema versions,
 * per-entry SHA-256 checksums, and lightweight statistics so operators can inspect
 * a backup without extracting the full archive.
 */
@Serializable
data class BackupManifest(
    @SerialName("formatVersion") val formatVersion: Int,
    @SerialName("serverId") val serverId: String,
    @SerialName("createdAt") val createdAt: Long,
    @SerialName("appVersion") val appVersion: String,
    @SerialName("schemaVersion") val schemaVersion: String,
    @SerialName("includesImages") val includesImages: Boolean,
    @SerialName("checksums") val checksums: Map<String, String>,
    @SerialName("bookCount") val bookCount: Int,
    @SerialName("userCount") val userCount: Int,
) {
    /** Serializes this manifest to pretty-printed JSON. */
    fun toJson(): String = JSON.encodeToString(this)

    companion object {
        /** Current archive format version. Increment when the zip layout changes incompatibly. */
        const val FORMAT_VERSION = 1

        private val JSON =
            Json {
                prettyPrint = true
                ignoreUnknownKeys = true
            }

        /** Deserializes a manifest from JSON. Unknown keys are silently ignored for forward compatibility. */
        fun fromJson(s: String): BackupManifest = JSON.decodeFromString(s)
    }
}

/** Computes the SHA-256 hex digest of a file's bytes, streamed in 64 KiB chunks. */
fun sha256Of(path: Path): String = hashFileSha256(path)
