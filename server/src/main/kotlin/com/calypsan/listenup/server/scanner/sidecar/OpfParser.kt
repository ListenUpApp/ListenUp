package com.calypsan.listenup.server.scanner.sidecar

import io.github.oshai.kotlinlogging.KotlinLogging
import org.w3c.dom.Element
import java.nio.file.Path
import kotlin.io.path.inputStream

private val logger = KotlinLogging.logger {}

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
 *  - `<dc:description>` → [SidecarMetadata.description]
 *  - `<dc:date>`        → [SidecarMetadata.publishYear] (leading four-digit
 *    year only; free-text prose yields no year rather than a guess)
 *  - `<dc:publisher>`   → [SidecarMetadata.publisher]
 *  - `<dc:language>`    → [SidecarMetadata.language]
 *  - `<dc:creator>`     → contributor; the `opf:role` attribute maps
 *    `aut` (or absent — the OPF default) → `"author"`, `nrt` → `"narrator"`.
 *    Any other relator code (`trl`, `edt`, …) is dropped, not mis-filed
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
private fun Element.creators(): List<SidecarContributor> =
    getElementsByTagName("dc:creator").let { nodes ->
        (0 until nodes.length).mapNotNull { index ->
            val element = nodes.item(index) as Element
            val name = element.textContent?.trim()?.ifBlank { null } ?: return@mapNotNull null
            val role =
                when (element.getAttribute("opf:role")) {
                    "nrt" -> "narrator"
                    "aut", "" -> "author"
                    else -> return@mapNotNull null
                }
            SidecarContributor(name, role)
        }
    }
