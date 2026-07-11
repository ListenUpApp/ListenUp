package com.calypsan.listenup.server.librarywrite

import kotlinx.io.files.Path
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

/** A fresh empty temp directory to stand in for `$LISTENUP_HOME/write-journal/`. */
internal fun tempJournalDir(): Path {
    val dir = Files.createTempDirectory("write-journal-")
    return Path(dir.toString())
}

/** A [LibraryWriteBroker] wired to a fresh [SelfWriteRegistry] and [WriteJournal] (or the given ones) for tests. */
internal fun testBroker(
    registry: SelfWriteRegistry = SelfWriteRegistry { 0L },
    journal: WriteJournal = WriteJournal(tempJournalDir()),
): LibraryWriteBroker = LibraryWriteBroker(registry, journal)
