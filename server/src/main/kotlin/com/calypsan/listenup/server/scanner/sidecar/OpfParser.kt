package com.calypsan.listenup.server.scanner.sidecar

import io.github.oshai.kotlinlogging.KotlinLogging
import org.w3c.dom.Element
import java.nio.file.Path
import kotlin.io.path.inputStream

private val logger = KotlinLogging.logger {}

/** Matches the first run of four consecutive digits — the year inside a `dc:date`. */
private val YEAR_PATTERN = Regex("""\d{4}""")

/**
 * Parses OPF / Dublin Core XML metadata sidecars (`.opf`).
 *
 * OPF is the eBook package format; the `.opf` file is sometimes shipped
 * alongside an audiobook. The Dublin Core `<metadata>` block carries the
 * fields ListenUp cares about.
 *
 * Lenient by contract: malformed XML or any parse failure returns `null` — a
 * bad `.opf` is never an error, just an absent enrichment source. Unknown
 * elements are ignored.
 *
 * Element mapping:
 *  - `<dc:title>`       → [SidecarMetadata.title]
 *  - `<dc:description>` → [SidecarMetadata.description]
 *  - `<dc:date>`        → [SidecarMetadata.publishYear] (first four-digit run)
 *  - `<dc:publisher>`   → [SidecarMetadata.publisher]
 *  - `<dc:language>`    → [SidecarMetadata.language]
 *  - `<dc:creator>`     → contributor; the `opf:role` attribute maps
 *    `aut` → `"author"`, `nrt` → `"narrator"`, absent/other → `"author"`
 *    (the OPF default for a creator with no role is the author)
 *
 * `<dc:identifier>` (ISBN / ASIN) is intentionally NOT extracted —
 * [SidecarMetadata] carries no identifier field and the Analyzer does not
 * merge a sidecar identifier, so parsing it would be dead code.
 *
 * The parser is non-namespace-aware (see [hardenedDocumentBuilderFactory]) and
 * queries elements by their literal prefixed tag name (`dc:title`, …). It
 * therefore assumes the conventional `dc:` / `opf:` prefix bindings; an `.opf`
 * that bound the Dublin Core namespace to a different prefix would not parse.
 */
internal class OpfParser : SidecarParser {
    override val supportedFilenames: Set<String> = emptySet()
    override val supportedExtensions: Set<String> = setOf("opf")

    override suspend fun parse(file: Path): SidecarMetadata? =
        try {
            val doc =
                file.inputStream().use { stream ->
                    hardenedDocumentBuilderFactory().newDocumentBuilder().parse(stream)
                }
            val root = doc.documentElement ?: return null
            SidecarMetadata(
                title = root.firstText("dc:title"),
                description = root.firstText("dc:description"),
                publishYear = root.firstText("dc:date")?.let { YEAR_PATTERN.find(it)?.value?.toIntOrNull() },
                publisher = root.firstText("dc:publisher"),
                language = root.firstText("dc:language"),
                contributors = root.creators(),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // A malformed .opf is not an error — just an absent enrichment source.
            logger.debug(e) { "Unparseable .opf: $file — skipping" }
            null
        }
}

/** Trimmed text of the first `<tag>` descendant, or null when absent or blank. */
private fun Element.firstText(tag: String): String? =
    getElementsByTagName(tag).let { nodes ->
        if (nodes.length == 0) {
            null
        } else {
            nodes
                .item(0)
                .textContent
                ?.trim()
                ?.ifBlank { null }
        }
    }

/**
 * Every `<dc:creator>` descendant as a [SidecarContributor]. The `opf:role`
 * attribute selects the role: `nrt` → narrator, everything else (including a
 * missing attribute) → author, which is the OPF default for a bare creator.
 */
private fun Element.creators(): List<SidecarContributor> =
    getElementsByTagName("dc:creator").let { nodes ->
        (0 until nodes.length).mapNotNull { index ->
            val element = nodes.item(index) as Element
            val name = element.textContent?.trim()?.ifBlank { null } ?: return@mapNotNull null
            val role = if (element.getAttribute("opf:role") == "nrt") "narrator" else "author"
            SidecarContributor(name, role)
        }
    }
