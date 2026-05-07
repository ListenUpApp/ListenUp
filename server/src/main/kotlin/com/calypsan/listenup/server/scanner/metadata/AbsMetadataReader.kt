package com.calypsan.listenup.server.scanner.metadata

import com.calypsan.listenup.api.dto.scanner.SeriesEntry
import com.calypsan.listenup.api.external.abs.AbsMetadata
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * Reads ABS-authored `metadata.json` sidecar files. Two-step parse:
 *
 *  1. Read raw JSON. If the top-level object has a `metadata` field that is
 *     itself an object, unwrap it (legacy ABS schema; the modern schema is
 *     flat). See `audiobookshelf/server/utils/parsers/abmetadataGenerator.js:9-20`.
 *  2. Decode the (potentially flattened) JSON into [AbsMetadata].
 *
 * Failures are logged at warn level and return null so the scan continues
 * without the overlay — partial data beats a failed scan.
 *
 * `parseSeriesEntries` converts ABS's `["Series Name #1.5"]` string format
 * into structured [SeriesEntry] values.
 */
internal class AbsMetadataReader(
    private val json: Json,
) {
    suspend fun read(file: Path): AbsMetadata? =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!Files.isRegularFile(file)) return@runCatching null
                val raw = Files.readString(file, Charsets.UTF_8)
                val element = json.parseToJsonElement(raw)
                val flattened =
                    (element as? JsonObject)?.get("metadata")?.let { it as? JsonObject } ?: element
                json.decodeFromJsonElement<AbsMetadata>(flattened)
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                logger.warn(e) { "metadata.json parse failed at $file — skipping overlay" }
            }.getOrNull()
        }

    fun parseSeriesEntries(rawSeries: List<String>): List<SeriesEntry> =
        rawSeries.mapNotNull { raw ->
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            // ABS encodes sequence with `#` — `"Series Name #1.5"` → name="Series Name", sequence="1.5".
            val hashIndex = trimmed.lastIndexOf('#')
            if (hashIndex == -1) {
                SeriesEntry(name = trimmed)
            } else {
                val name = trimmed.substring(0, hashIndex).trim()
                val sequence = trimmed.substring(hashIndex + 1).trim().takeUnless { it.isEmpty() }
                SeriesEntry(name = name, sequence = sequence)
            }
        }
}
