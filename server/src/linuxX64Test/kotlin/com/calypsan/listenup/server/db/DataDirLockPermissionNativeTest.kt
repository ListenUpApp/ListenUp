@file:OptIn(ExperimentalForeignApi::class)

package com.calypsan.listenup.server.db

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.files.Path
import platform.posix.S_IRUSR
import platform.posix.S_IXUSR
import platform.posix.geteuid
import platform.posix.getpid
import platform.posix.mkdir
import platform.posix.rmdir

/**
 * Native proof that an *unwritable* data dir surfaces as a clear permission error — not the misleading
 * "another server is already using the data directory", which is reserved for a genuine `flock`
 * conflict. Regression guard for the distroless nonroot named-volume boot failure: a fresh root-owned
 * volume left the nonroot server unable to create `<dataHome>/.lock`, and `tryLockFile` reported that
 * `open()` failure as a lock conflict.
 */
class DataDirLockPermissionNativeTest :
    FunSpec({
        test("open failure on unwritable dir throws permission error not lock conflict") {
            // root bypasses directory permission bits — open() would succeed, so the test proves nothing.
            // CI typically runs as root (container), where this is skipped; it validates locally as non-root.
            if (geteuid() == 0u) return@test

            val dir = "/tmp/lu-lock-perm-${getpid()}"
            rmdir(dir) // defensive: clear a leftover from a previously-crashed run
            mkdir(dir, (S_IRUSR or S_IXUSR).toUInt()) // r-x------ : created without write permission
            try {
                val failure =
                    shouldThrow<IllegalStateException> {
                        DataDirLock.forDataHome(Path(dir)).tryAcquire()
                    }
                (failure.message ?: "") shouldContain "cannot open data-dir lock file"
            } finally {
                rmdir(dir)
            }
        }
    })
