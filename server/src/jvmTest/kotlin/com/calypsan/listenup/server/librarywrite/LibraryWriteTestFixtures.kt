package com.calypsan.listenup.server.librarywrite

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/** A fresh empty temp directory to stand in for a library folder root. */
internal fun tempLibraryDir(): Path {
    val dir = Files.createTempDirectory("library-write-broker-")
    return Path(dir.toString())
}

/** Strips write permission from [dir] (POSIX only) so broker operations against it fail typed. */
internal fun makeReadOnly(dir: Path) {
    Files.setPosixFilePermissions(
        java.nio.file.Path
            .of(dir.toString()),
        setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE),
    )
}

/** POSIX permission bits (and thus [makeReadOnly]) don't apply on Windows — guard those tests with this. */
internal fun isPosix(): Boolean = !System.getProperty("os.name").lowercase().contains("windows")

/**
 * True when the test JVM runs as `root` (uid 0), for whom a `0555` directory is still writable —
 * so a [makeReadOnly] fixture proves nothing. Tests that depend on the permission actually biting
 * must skip loudly rather than assert a failure the kernel will never produce.
 */
internal fun isRootUser(): Boolean =
    System.getProperty("user.name") == "root" ||
        runCatching {
            Files.getAttribute(
                java.nio.file.Path
                    .of("/proc/self"),
                "unix:uid",
            ) == 0
        }.getOrDefault(false)

/** Exact bytes of the file at [path]. */
internal fun bytesAt(path: Path): ByteArray = SystemFileSystem.source(path).buffered().use { it.readByteArray() }

/** Writes [bytes] to [path] directly (bypassing the broker) — stands in for an external or pre-existing file. */
internal fun writeExternally(
    path: Path,
    bytes: ByteArray,
) {
    path.parent?.let { SystemFileSystem.createDirectories(it) }
    SystemFileSystem.sink(path).buffered().use { it.write(bytes) }
}

/** Names of the broker's staging temp files left behind in [dir] — must always be empty once a call returns. */
internal fun tempLitterIn(dir: Path): List<String> =
    SystemFileSystem
        .list(dir)
        .map { it.name }
        .filter { it.startsWith(".listenup-tmp") }

/** A fresh empty temp directory to stand in for `$LISTENUP_HOME/write-journal/`. */
internal fun tempJournalDir(): Path {
    val dir = Files.createTempDirectory("write-journal-")
    return Path(dir.toString())
}

/**
 * A [LibraryWriteBroker] wired to a fresh [SelfWriteRegistry] and [WriteJournal] (or the given ones).
 *
 * [roots] defaults to the system temp directory, which every fixture here builds its library under,
 * so containment is satisfied without each test having to declare it. A test that is *about*
 * containment must pass its own narrow [roots] instead — with the broad default, a path escaping
 * into a sibling temp directory would still be inside the allow-list and the test would pass while
 * proving nothing.
 */
internal fun testBroker(
    registry: SelfWriteRegistry = SelfWriteRegistry { 0L },
    journal: WriteJournal = WriteJournal(tempJournalDir()),
    roots: List<Path> = listOf(Path(System.getProperty("java.io.tmpdir"))),
): LibraryWriteBroker = LibraryWriteBroker(registry, journal, { roots })
