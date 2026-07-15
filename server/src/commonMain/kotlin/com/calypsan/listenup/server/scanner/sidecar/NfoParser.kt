package com.calypsan.listenup.server.scanner.sidecar

import com.calypsan.listenup.server.io.readText
import com.calypsan.listenup.server.scanner.sidecar.xml.XmlElement
import com.calypsan.listenup.server.scanner.sidecar.xml.allText
import com.calypsan.listenup.server.scanner.sidecar.xml.directText
import com.calypsan.listenup.server.scanner.sidecar.xml.firstText
import com.calypsan.listenup.server.scanner.sidecar.xml.getElementsByTagName
import com.calypsan.listenup.server.scanner.sidecar.xml.parseXml
import com.calypsan.listenup.server.logging.loggerFor
import kotlinx.io.files.Path

private val logger = loggerFor<NfoParser>()

/**
 * Parses Kodi/Plex-style `.nfo` XML metadata sidecars.
 *
 * Lenient by contract: malformed XML, an unexpected root element, or any parse
 * failure returns `null` — a bad `.nfo` is never an error, just an absent
 * enrichment source. Unknown elements are ignored.
 *
 * The root element name is irrelevant — Kodi/Plex use `<musicvideo>`, `<album>`,
 * `<movie>` interchangeably — so the parser walks descendants by tag name.
 *
 * Element mapping:
 *  - `<title>`     → [SidecarMetadata.title]
 *  - `<subtitle>`  → [SidecarMetadata.subtitle] (Kodi-style explicit subtitle)
 *  - `<plot>`      → [SidecarMetadata.description]
 *  - `<year>`      → [SidecarMetadata.publishYear]
 *  - `<publisher>` → [SidecarMetadata.publisher]
 *  - `<language>`  → [SidecarMetadata.language]
 *  - `<genre>`     → [SidecarMetadata.genres] (one string per element)
 *  - `<author>`    → contributor, role `"author"`
 *  - `<actor>` / `<actor><name>` → contributor, role `"narrator"`
 *    (Kodi reuses the video `<actor>` element for audiobook narrators)
 */
internal class NfoParser : SidecarParser {
    override val supportedFilenames: Set<String> = emptySet()
    override val supportedExtensions: Set<String> = setOf("nfo")

    override suspend fun parse(file: Path): SidecarMetadata? =
        try {
            val root = parseXml(file.readText())
            SidecarMetadata(
                title = root.firstText("title"),
                subtitle = root.firstText("subtitle"),
                description = root.firstText("plot"),
                publishYear = root.firstText("year")?.toIntOrNull(),
                publisher = root.firstText("publisher"),
                language = root.firstText("language"),
                genres = root.allText("genre"),
                contributors =
                    buildList {
                        root.allText("author").forEach { add(SidecarContributor(it, "author")) }
                        root.allActors().forEach { add(SidecarContributor(it, "narrator")) }
                    },
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // A malformed .nfo is not an error — just an absent enrichment source.
            logger.debug(e) { "Unparseable .nfo: $file — skipping" }
            null
        }
}

/**
 * Names of every `<actor>` descendant, blanks dropped. Kodi `<actor>` is either
 * `<actor>Name</actor>` or `<actor><name>Name</name><role>…</role></actor>` —
 * the nested `<name>` child is preferred, falling back to the actor's own direct text.
 */
private fun XmlElement.allActors(): List<String> = getElementsByTagName("actor").mapNotNull { it.actorName() }

/** The name of a single `<actor>` element — nested `<name>` child, else the element's own direct text. */
private fun XmlElement.actorName(): String? {
    firstText("name")?.let { return it }
    return directText().trim().ifBlank { null }
}
