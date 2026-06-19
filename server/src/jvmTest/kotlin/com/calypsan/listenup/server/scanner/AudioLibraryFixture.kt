package com.calypsan.listenup.server.scanner

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.writeText

/**
 * A test-only DSL for assembling synthetic audiobook libraries on disk.
 *
 * Phase 2 doesn't read audio file content, so audio "tracks" are zero-byte
 * placeholders with the correct extension. Phase 3 will add a separate
 * fixture that emits real test audio for the audiometa port.
 *
 * Usage:
 * ```
 * audioLibrary {
 *     book("Brandon Sanderson/Stormlight/The Way of Kings") {
 *         tracks(count = 5)
 *         cover()
 *         metadataJson("""{"title":"The Way of Kings"}""")
 *     }
 *     book("Single File Book") { audio("audiobook.m4b") }
 * }.use { fixture ->
 *     // tests that consume fixture.root
 * }
 * ```
 *
 * The fixture is [AutoCloseable]; either wrap usage in `.use { }` or call
 * [AudioLibraryFixture.close] in a `finally` block. Closing recursively
 * deletes the temp directory.
 */
fun audioLibrary(
    prefix: String = "listenup-audio-",
    configure: AudioLibraryFixture.() -> Unit = {},
): AudioLibraryFixture = AudioLibraryFixture(Files.createTempDirectory(prefix)).apply(configure)

class AudioLibraryFixture(
    val root: Path,
) : AutoCloseable {
    fun book(
        relPath: String,
        configure: BookScope.() -> Unit = {},
    ): Path {
        val bookRoot = (root / relPath).apply { createDirectories() }
        BookScope(bookRoot).configure()
        return bookRoot
    }

    fun raw(
        relPath: String,
        contents: String = "",
    ): Path = (root / relPath).writeFile(contents)

    fun audio(
        relPath: String,
        sizeBytes: Long = 0L,
    ): Path = (root / relPath).placeholder(sizeBytes)

    fun image(relPath: String): Path = (root / relPath).placeholder(0L)

    /** Marks a directory subtree as skipped via the ABS `.ignore` sentinel. */
    fun ignore(dirRelPath: String): Path {
        val dir = (root / dirRelPath).apply { createDirectories() }
        return (dir / ".ignore").placeholder(0L)
    }

    override fun close() {
        root.toFile().deleteRecursively()
    }
}

class BookScope(
    val root: Path,
) {
    fun audio(
        name: String,
        sizeBytes: Long = 0L,
    ): Path = (root / name).placeholder(sizeBytes)

    fun cover(name: String = "cover.jpg"): Path = (root / name).placeholder(0L)

    fun image(name: String): Path = (root / name).placeholder(0L)

    fun text(
        name: String,
        contents: String = "",
    ): Path = (root / name).writeFile(contents)

    fun metadataJson(
        contents: String,
        name: String = "metadata.json",
    ): Path = (root / name).writeFile(contents)

    fun tracks(
        count: Int,
        namePattern: (Int) -> String = { i -> "%02d - Track.mp3".format(i) },
    ): List<Path> = (1..count).map { audio(namePattern(it)) }

    /** A multi-disc subdirectory (`CD1/`, `Disc 2/`, etc.). */
    fun disc(
        name: String,
        configure: BookScope.() -> Unit,
    ): Path {
        val discRoot = (root / name).apply { createDirectories() }
        BookScope(discRoot).configure()
        return discRoot
    }
}

private fun Path.placeholder(sizeBytes: Long): Path {
    parent?.createDirectories()
    if (sizeBytes == 0L) {
        Files.createFile(this)
    } else {
        Files.write(this, ByteArray(sizeBytes.toInt()))
    }
    return this
}

private fun Path.writeFile(contents: String): Path {
    parent?.createDirectories()
    writeText(contents)
    return this
}
