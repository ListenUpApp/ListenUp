package com.calypsan.listenup.server.scanner.sidecar

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import kotlin.io.path.readText

private val logger = KotlinLogging.logger {}

/**
 * Parses a `desc.txt` sidecar — the entire file is the book description.
 *
 * The raw file content (BOM stripped, surrounding whitespace trimmed) maps to
 * [SidecarMetadata.description]. Internal newlines are preserved — a
 * multi-paragraph description survives intact. An empty file yields `null`.
 */
internal class DescTxtParser : SidecarParser {
    override val supportedFilenames: Set<String> = setOf("desc.txt")
    override val supportedExtensions: Set<String> = emptySet()

    override suspend fun parse(file: Path): SidecarMetadata? =
        try {
            val text =
                file
                    .readText(Charsets.UTF_8)
                    .removePrefix("\uFEFF") // strip UTF-8 BOM
                    .trim()
            if (text.isEmpty()) null else SidecarMetadata(description = text)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.debug(e) { "Unreadable desc.txt: $file — skipping" }
            null
        }
}
