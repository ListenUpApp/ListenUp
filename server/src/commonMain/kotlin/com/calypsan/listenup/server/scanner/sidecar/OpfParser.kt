package com.calypsan.listenup.server.scanner.sidecar

import com.calypsan.listenup.api.dto.scanner.SeriesEntry
import com.calypsan.listenup.server.io.readText
import com.calypsan.listenup.server.scanner.sidecar.xml.XmlElement
import com.calypsan.listenup.server.scanner.sidecar.xml.allText
import com.calypsan.listenup.server.scanner.sidecar.xml.firstText
import com.calypsan.listenup.server.scanner.sidecar.xml.getAttribute
import com.calypsan.listenup.server.scanner.sidecar.xml.getElementsByTagName
import com.calypsan.listenup.server.scanner.sidecar.xml.parseXml
import com.calypsan.listenup.server.scanner.sidecar.xml.textContent
import com.calypsan.listenup.server.logging.loggerFor
import kotlinx.io.files.Path

private val logger = loggerFor<OpfParser>()

/**
 * Captures a leading four-digit year — either a bare year (`2014`) or the year
 * of an ISO-8601 date (`2014-03-01`). Start-anchored on purpose: free-text
 * prose that does not begin with a year yields no match, so the parser refuses
 * to guess rather than picking the wrong four-digit run.
 */
private val YEAR_PATTERN = Regex("""^\s*(\d{4})""")

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
 *  - `<dc:subtitle>`    → [SidecarMetadata.subtitle]
 *  - `<dc:description>` → [SidecarMetadata.description]
 *  - `<dc:date>`        → [SidecarMetadata.publishYear] (leading four-digit
 *    year only; free-text prose yields no year rather than a guess)
 *  - `<dc:publisher>`   → [SidecarMetadata.publisher]
 *  - `<dc:language>`    → [SidecarMetadata.language]
 *  - `<dc:subject>`     → [SidecarMetadata.genres] (one string per element)
 *  - `<dc:creator>`     → contributor; the `opf:role` attribute maps
 *    `aut` (or absent — the OPF default) → `"author"`, `nrt` → `"narrator"`.
 *    Any other relator code (`trl`, `edt`, …) is dropped, not mis-filed
 *  - `<meta name="calibre:series" content="…">` + `<meta name="calibre:series_index"
 *    content="…">` → [SidecarMetadata.series] (Calibre-authored `.opf` files only)
 *
 * `<dc:identifier>` (ISBN / ASIN) is intentionally NOT extracted —
 * [SidecarMetadata] carries no identifier field and the Analyzer does not
 * merge a sidecar identifier, so parsing it would be dead code.
 *
 * The parser is non-namespace-aware and queries elements by their literal
 * prefixed tag name (`dc:title`, …). It therefore assumes the conventional
 * `dc:` / `opf:` prefix bindings; an `.opf` that bound the Dublin Core
 * namespace to a different prefix would not parse.
 */
internal class OpfParser : SidecarParser {
    override val supportedFilenames: Set<String> = emptySet()
    override val supportedExtensions: Set<String> = setOf("opf")

    override suspend fun parse(file: Path): SidecarMetadata? =
        try {
            val root = parseXml(file.readText())
            SidecarMetadata(
                title = root.firstText("dc:title"),
                subtitle = root.firstText("dc:subtitle"),
                description = root.firstText("dc:description"),
                publishYear =
                    root
                        .firstText(
                            "dc:date",
                        )?.let {
                            YEAR_PATTERN
                                .find(it)
                                ?.groupValues
                                ?.get(1)
                                ?.toIntOrNull()
                        },
                publisher = root.firstText("dc:publisher"),
                language = root.firstText("dc:language"),
                series =
                    root
                        .metaContent("calibre:series")
                        ?.let { seriesName ->
                            // calibre:series_index is optional; absent → null sequence (valid)
                            listOf(
                                SeriesEntry(
                                    name = seriesName,
                                    sequence = root.metaContent("calibre:series_index"),
                                ),
                            )
                        }
                        ?: emptyList(),
                genres = root.allText("dc:subject"),
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

/**
 * Every `<dc:creator>` descendant that maps to a contributor ListenUp models.
 * The `opf:role` attribute selects the role: `aut` (or a missing attribute,
 * the OPF default for a bare creator) → author, `nrt` → narrator. Any other
 * MARC relator code — `trl`, `edt`, … — is dropped rather than silently
 * mis-filed as an author.
 */
private fun XmlElement.creators(): List<SidecarContributor> =
    getElementsByTagName("dc:creator").mapNotNull { element ->
        val name = element.textContent.trim().ifBlank { null } ?: return@mapNotNull null
        val role =
            when (element.getAttribute("opf:role")) {
                "nrt" -> "narrator"
                "aut", "" -> "author"
                else -> return@mapNotNull null
            }
        SidecarContributor(name, role)
    }

/** `content` of the first `<meta name="[name]">`, trimmed; null if absent or blank. */
private fun XmlElement.metaContent(name: String): String? {
    for (el in getElementsByTagName("meta")) {
        if (el.getAttribute("name") == name) {
            return el.getAttribute("content").trim().ifBlank { null }
        }
    }
    return null
}
