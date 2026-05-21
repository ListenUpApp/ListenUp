package com.calypsan.listenup.server.scanner.sidecar

import io.github.oshai.kotlinlogging.KotlinLogging
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.nio.file.Path
import kotlin.io.path.inputStream

private val logger = KotlinLogging.logger {}

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
 *  - `<plot>`      → [SidecarMetadata.description]
 *  - `<year>`      → [SidecarMetadata.publishYear]
 *  - `<publisher>` → [SidecarMetadata.publisher]
 *  - `<language>`  → [SidecarMetadata.language]
 *  - `<author>`    → contributor, role `"author"`
 *  - `<actor>` / `<actor><name>` → contributor, role `"narrator"`
 *    (Kodi reuses the video `<actor>` element for audiobook narrators)
 */
internal class NfoParser : SidecarParser {
    override val supportedFilenames: Set<String> = emptySet()
    override val supportedExtensions: Set<String> = setOf("nfo")

    override suspend fun parse(file: Path): SidecarMetadata? =
        try {
            val doc =
                file.inputStream().use { stream ->
                    hardenedDocumentBuilderFactory().newDocumentBuilder().parse(stream)
                }
            val root = doc.documentElement ?: return null
            SidecarMetadata(
                title = root.firstText("title"),
                description = root.firstText("plot"),
                publishYear = root.firstText("year")?.toIntOrNull(),
                publisher = root.firstText("publisher"),
                language = root.firstText("language"),
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
 * the nested `<name>` child is preferred, falling back to the actor's own text.
 */
private fun Element.allActors(): List<String> =
    getElementsByTagName("actor").let { nodes ->
        (0 until nodes.length).mapNotNull { (nodes.item(it) as Element).actorName() }
    }

/** The name of a single `<actor>` element — nested `<name>` child, else the element's own direct text. */
private fun Element.actorName(): String? {
    firstText("name")?.let { return it }
    return childNodes
        .let { children -> (0 until children.length).map { children.item(it) } }
        .filter { it.nodeType == Node.TEXT_NODE }
        .joinToString("") { it.textContent }
        .trim()
        .ifBlank { null }
}
