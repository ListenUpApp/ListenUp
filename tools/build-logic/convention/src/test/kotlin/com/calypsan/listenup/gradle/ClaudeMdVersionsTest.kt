package com.calypsan.listenup.gradle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the version numbers written into the instruction files to `gradle/libs.versions.toml`.
 *
 * `CLAUDE.md` and its siblings are loaded into every working session and followed, so a version
 * that disagrees with the build sends a reader to the wrong documentation. A written number passes
 * when it is a dot-boundary prefix of the catalog value: "Ktor 3.6" and "Ktor 3" both describe
 * `3.6.0`, "Ktor 3.5" does not.
 *
 * Deliberately narrow: six product names in three files. A number written for any other product is
 * not checked — extend [catalogKeyByProduct], or better, do not write the number.
 */
class ClaudeMdVersionsTest {
    private val repoRoot = File(requireNotNull(System.getProperty("listenup.repo.root")))

    private val catalogKeyByProduct =
        mapOf(
            "Kotlin" to "kotlin",
            "Compose Multiplatform" to "composeMultiplatform",
            "Ktor" to "ktor",
            "Koin" to "koin",
            "Room" to "room",
            "Kotest" to "kotest",
        )

    private val documents = listOf("CLAUDE.md", "app/sharedUI/CLAUDE.md", "README.md")

    @Test
    fun `every written version is a prefix of the catalog version`() {
        val catalog = catalogVersions()
        val mentions = documents.flatMap { versionMentions(it) }
        // Anchor: a regex that rots must fail loudly, not pass over nothing.
        assertTrue(
            mentions.any { it.document == "CLAUDE.md" && it.product == "Kotlin" },
            "mention parser drift — expected at least one `Kotlin <n>` in CLAUDE.md, found: $mentions",
        )

        val drifted =
            mentions.mapNotNull { mention ->
                val key = catalogKeyByProduct.getValue(mention.product)
                val pinned = requireNotNull(catalog[key]) { "libs.versions.toml has no `$key` version" }
                if (mention.written.isVersionPrefixOf(pinned)) {
                    null
                } else {
                    "${mention.document}:${mention.line} says \"${mention.product} ${mention.written}\" " +
                        "but libs.versions.toml pins $key = \"$pinned\""
                }
            }
        assertEquals(
            emptyList<String>(),
            drifted,
            "Documented versions disagree with gradle/libs.versions.toml. Write major.minor and keep it true.",
        )
    }

    @Test
    fun `a written version matches only on a dot boundary`() {
        assertTrue("3.6".isVersionPrefixOf("3.6.0"))
        assertFalse("3.5".isVersionPrefixOf("3.6.0"))
        assertFalse("3.60".isVersionPrefixOf("3.6.0"))
        assertTrue("1.12".isVersionPrefixOf("1.12.1"))
    }

    private data class Mention(
        val document: String,
        val line: Int,
        val product: String,
        val written: String,
    )

    private fun versionMentions(document: String): List<Mention> {
        val products = catalogKeyByProduct.keys.joinToString("|") { Regex.escape(it) }
        val pattern = Regex("""\b($products) (\d+(?:\.\d+)*)""")
        return repoRoot.resolve(document).readLines().flatMapIndexed { index, text ->
            pattern.findAll(text).map { Mention(document, index + 1, it.groupValues[1], it.groupValues[2]) }
        }
    }

    /** The `[versions]` table of the catalog, as `name -> value`. */
    private fun catalogVersions(): Map<String, String> {
        val entry = Regex("""^([A-Za-z0-9_.-]+)\s*=\s*"([^"]+)"""")
        return repoRoot
            .resolve("gradle/libs.versions.toml")
            .readLines()
            .dropWhile { it.trim() != "[versions]" }
            .drop(1)
            .takeWhile { !it.startsWith("[") }
            .mapNotNull { entry.find(it.trim()) }
            .associate { it.groupValues[1] to it.groupValues[2] }
    }
}

/** True when [this] equals [pinned] or is followed in it by a dot — `3.6` in `3.6.0`, never `3.60`. */
internal fun String.isVersionPrefixOf(pinned: String): Boolean = pinned == this || pinned.startsWith("$this.")
