package com.calypsan.listenup.server.seed

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.math.abs

private val logger = KotlinLogging.logger {}

private val prettyJson = Json { prettyPrint = true }

private const val FFMPEG_FLAG_METADATA = "-metadata"
private const val FFMPEG_FILTER_COMPLEX = "lavfi"

/**
 * Generates the synthetic audiobook library described by [SeedLibraryDescriptor] into
 * an output directory, using `ffmpeg`. The output is disposable — regenerate any time.
 *
 * Invoked by the `:server:generateSeedLibrary` Gradle task. `main` takes one argument:
 * the absolute output directory path.
 */
object SeedLibraryGenerator {
    fun generate(outputRoot: Path) {
        require(ffmpegAvailable()) { "ffmpeg not found on PATH — required to generate the seed library" }
        if (Files.exists(outputRoot)) outputRoot.toFile().deleteRecursively()
        outputRoot.createDirectories()
        SeedLibraryDescriptor.BOOKS.forEach { book -> generateBook(outputRoot, book) }
        logger.info { "seed library generated: ${SeedLibraryDescriptor.BOOKS.size} books at $outputRoot" }
    }

    private fun generateBook(
        root: Path,
        book: SeedBook,
    ) {
        val bookDir = root.resolve(book.folderPath)
        bookDir.createDirectories()
        book.tracks.forEachIndexed { index, track ->
            val trackDir =
                if (book.discFolders) {
                    bookDir.resolve("CD${index + 1}").apply { createDirectories() }
                } else {
                    bookDir
                }
            generateAudioFile(trackDir.resolve(track.fileName), track, book)
        }
        writeSidecar(bookDir, book)
        if (book.hasCover) generateCover(bookDir.resolve("cover.jpg"), book.title)
    }

    private fun writeSidecar(
        bookDir: Path,
        book: SeedBook,
    ) {
        when (book.sidecar) {
            SeedSidecar.NONE -> Unit
            SeedSidecar.METADATA_JSON -> bookDir.resolve("metadata.json").writeText(metadataJson(book))
            SeedSidecar.NFO -> bookDir.resolve("book.nfo").writeText(nfoXml(book))
            SeedSidecar.OPF -> bookDir.resolve("metadata.opf").writeText(opfXml(book))
            SeedSidecar.READER_TXT -> bookDir.resolve("reader.txt").writeText(book.narrators.joinToString("\n"))
            SeedSidecar.DESC_TXT -> bookDir.resolve("desc.txt").writeText(book.description)
        }
    }

    private fun ffmpegAvailable(): Boolean =
        runCatching {
            ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start().waitFor() == 0
        }.getOrDefault(false)

    /** Runs ffmpeg with the given args, capturing stderr. Throws [IllegalStateException] on non-zero exit. */
    private fun runFfmpeg(args: List<String>) {
        val command = listOf("ffmpeg") + args
        val process =
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        check(exitCode == 0) {
            "ffmpeg exited with code $exitCode. Output:\n$output"
        }
    }

    /**
     * Generates one tiny audio file: a low-bitrate sine tone of [track.durationSeconds].
     * For single-file books with chapters, embeds FFMETADATA chapter atoms.
     */
    private fun generateAudioFile(
        target: Path,
        track: SeedTrack,
        book: SeedBook,
    ) {
        val extension = target.fileName.toString().substringAfterLast('.')
        val codec =
            when (extension) {
                "m4b" -> "aac"
                "mp3" -> "libmp3lame"
                else -> error("Unsupported audio extension: .$extension")
            }
        val firstAuthor = book.authors.firstOrNull() ?: ""
        val metadataArgs =
            listOf(
                FFMPEG_FLAG_METADATA,
                "title=${book.title}",
                FFMPEG_FLAG_METADATA,
                "artist=$firstAuthor",
                FFMPEG_FLAG_METADATA,
                "track=${track.trackNumber}",
            )

        val isSingleFileWithChapters = book.tracks.size == 1 && book.chapters.isNotEmpty()
        if (isSingleFileWithChapters) {
            val ffmetaFile = Files.createTempFile("listenup-ffmeta-", ".txt")
            try {
                ffmetaFile.writeText(buildFfmetadata(book, track))
                runFfmpeg(
                    listOf(
                        "-y",
                        "-f",
                        FFMPEG_FILTER_COMPLEX,
                        "-i",
                        "sine=frequency=220:duration=${track.durationSeconds}",
                        "-i",
                        ffmetaFile.toAbsolutePath().toString(),
                        "-map",
                        "0:a",
                        "-map_metadata",
                        "1",
                        "-c:a",
                        codec,
                        "-b:a",
                        "32k",
                    ) + metadataArgs + listOf(target.toAbsolutePath().toString()),
                )
            } finally {
                Files.deleteIfExists(ffmetaFile)
            }
        } else {
            runFfmpeg(
                listOf(
                    "-y",
                    "-f",
                    FFMPEG_FILTER_COMPLEX,
                    "-i",
                    "sine=frequency=220:duration=${track.durationSeconds}",
                    "-c:a",
                    codec,
                    "-b:a",
                    "32k",
                ) + metadataArgs + listOf(target.toAbsolutePath().toString()),
            )
        }
    }

    /** Builds the FFMETADATA1 text for a single-file book with embedded chapters. */
    private fun buildFfmetadata(
        book: SeedBook,
        track: SeedTrack,
    ): String {
        val sb = StringBuilder()
        sb.appendLine(";FFMETADATA1")
        book.chapters.forEachIndexed { index, chapter ->
            val startMs = chapter.startSeconds * 1000L
            val endMs =
                if (index + 1 < book.chapters.size) {
                    book.chapters[index + 1].startSeconds * 1000L
                } else {
                    track.durationSeconds * 1000L
                }
            require(endMs >= startMs) { "chapter END ($endMs ms) precedes START ($startMs ms) — check the descriptor" }
            sb.appendLine()
            sb.appendLine("[CHAPTER]")
            sb.appendLine("TIMEBASE=1/1000")
            sb.appendLine("START=$startMs")
            sb.appendLine("END=$endMs")
            sb.appendLine("title=${chapter.title}")
        }
        return sb.toString()
    }

    /**
     * Generates a 300x300 solid-color JPEG cover image. The color is derived
     * deterministically from the book title's hash code.
     */
    private fun generateCover(
        target: Path,
        title: String,
    ) {
        // Map title hash to a pleasing HSL color; use absolute value to avoid negative hex.
        val hue = abs(title.hashCode()) % 360
        // Convert HSL(hue, 70%, 50%) → approximate RGB for ffmpeg color string.
        val color = hslToHex(hue, 0.70, 0.50)
        runFfmpeg(
            listOf(
                "-y",
                "-f",
                "lavfi",
                "-i",
                "color=c=$color:s=300x300",
                "-frames:v",
                "1",
                target.toAbsolutePath().toString(),
            ),
        )
    }

    /** Converts HSL to a 6-digit hex string for ffmpeg's color= parameter. */
    private fun hslToHex(
        hDeg: Int,
        s: Double,
        l: Double,
    ): String {
        val c = (1.0 - Math.abs(2.0 * l - 1.0)) * s
        val x = c * (1.0 - Math.abs(hDeg / 60.0 % 2.0 - 1.0))
        val m = l - c / 2.0
        val (r1, g1, b1) =
            when (hDeg / 60) {
                0 -> Triple(c, x, 0.0)
                1 -> Triple(x, c, 0.0)
                2 -> Triple(0.0, c, x)
                3 -> Triple(0.0, x, c)
                4 -> Triple(x, 0.0, c)
                else -> Triple(c, 0.0, x)
            }
        val r = ((r1 + m) * 255).toInt().coerceIn(0, 255)
        val g = ((g1 + m) * 255).toInt().coerceIn(0, 255)
        val b = ((b1 + m) * 255).toInt().coerceIn(0, 255)
        return "0x%02X%02X%02X".format(r, g, b)
    }

    /** Builds ABS-format metadata.json content using kotlinx.serialization. */
    private fun metadataJson(book: SeedBook): String {
        val obj =
            buildJsonObject {
                put("title", book.title)
                put("subtitle", JsonNull)
                put("authors", buildJsonArray { book.authors.forEach { add(it) } })
                put("narrators", buildJsonArray { book.narrators.forEach { add(it) } })
                put(
                    "series",
                    buildJsonArray {
                        if (book.series != null) add("${book.series.name} #${book.series.sequence}")
                    },
                )
                put("description", book.description)
                put("publishedYear", 2023)
            }
        return prettyJson.encodeToString(obj)
    }

    /** Builds Kodi-style book.nfo XML content. */
    private fun nfoXml(book: SeedBook): String {
        val sb = StringBuilder()
        sb.appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
        sb.appendLine("<book>")
        sb.appendLine("  <title>${xmlEscape(book.title)}</title>")
        sb.appendLine("  <plot>${xmlEscape(book.description)}</plot>")
        sb.appendLine("  <year>2023</year>")
        book.authors.forEach { sb.appendLine("  <author>${xmlEscape(it)}</author>") }
        book.narrators.forEach { sb.appendLine("  <actor>${xmlEscape(it)}</actor>") }
        sb.append("</book>")
        return sb.toString()
    }

    /** Builds Dublin Core OPF XML content. */
    private fun opfXml(book: SeedBook): String {
        val sb = StringBuilder()
        sb.appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
        sb.appendLine("""<package xmlns:opf="http://www.idpf.org/2007/opf">""")
        sb.appendLine(
            """  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">""",
        )
        sb.appendLine("    <dc:title>${xmlEscape(book.title)}</dc:title>")
        book.authors.forEach {
            sb.appendLine("""    <dc:creator opf:role="aut">${xmlEscape(it)}</dc:creator>""")
        }
        book.narrators.forEach {
            sb.appendLine("""    <dc:creator opf:role="nrt">${xmlEscape(it)}</dc:creator>""")
        }
        sb.appendLine("    <dc:description>${xmlEscape(book.description)}</dc:description>")
        sb.appendLine("    <dc:date>2023</dc:date>")
        sb.appendLine("  </metadata>")
        sb.append("</package>")
        return sb.toString()
    }

    /** Escapes the five special XML characters. */
    private fun xmlEscape(text: String): String =
        text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1) { "usage: SeedLibraryGenerator <output-dir>" }
        generate(Path.of(args[0]))
    }
}
