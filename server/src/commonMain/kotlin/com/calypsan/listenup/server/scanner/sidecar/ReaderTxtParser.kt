package com.calypsan.listenup.server.scanner.sidecar

import com.calypsan.listenup.server.io.readText
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.io.files.Path

private val logger = KotlinLogging.logger {}

/**
 * Parses a `reader.txt` sidecar — one narrator name per line.
 *
 * Each non-blank line becomes a [SidecarContributor] with role `"narrator"`.
 * Tolerant of a UTF-8 BOM, trailing whitespace, and blank lines. An empty file
 * (or one with only blank lines) yields `null` — no narrators, nothing to merge.
 */
internal class ReaderTxtParser : SidecarParser {
    override val supportedFilenames: Set<String> = setOf("reader.txt")
    override val supportedExtensions: Set<String> = emptySet()

    override suspend fun parse(file: Path): SidecarMetadata? =
        try {
            val names =
                file
                    .readText()
                    .removePrefix("﻿") // strip UTF-8 BOM
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toList()
            if (names.isEmpty()) {
                null
            } else {
                SidecarMetadata(
                    contributors = names.map { SidecarContributor(it, "narrator") },
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.debug(e) { "Unreadable reader.txt: $file — skipping" }
            null
        }
}
