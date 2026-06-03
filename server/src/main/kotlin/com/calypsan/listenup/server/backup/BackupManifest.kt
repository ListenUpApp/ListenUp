package com.calypsan.listenup.server.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Describes the contents of a `.listenup.zip` backup archive.
 *
 * Stored as `manifest.json` inside the archive. Carries format/schema versions,
 * per-entry SHA-256 checksums, and lightweight statistics so operators can inspect
 * a backup without extracting the full archive.
 */
@Serializable
data class BackupManifest(
    val formatVersion: Int,
    val serverId: String,
    val createdAt: Long,
    val appVersion: String,
    val schemaVersion: String,
    val includesImages: Boolean,
    val checksums: Map<String, String>,
    val bookCount: Int,
    val userCount: Int,
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
fun sha256Of(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
