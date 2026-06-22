package com.calypsan.listenup.server.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

/**
 * Pins [DataDirLock] — the single-instance guard that stops two servers from sharing one data home
 * and racing the scan-spool (#703).
 */
class DataDirLockTest :
    FunSpec({

        test("a second lock on the same data home is rejected while the first is held") {
            val home = Files.createTempDirectory("datadir-lock-")
            val first = DataDirLock.forDataHome(home)
            val second = DataDirLock.forDataHome(home)

            first.tryAcquire() shouldBe true
            second.tryAcquire() shouldBe false // the home is already locked — fail, don't clobber

            first.close()
            // Once the holder releases, the home becomes acquirable again.
            second.tryAcquire() shouldBe true
            second.close()
        }

        test("locks on different data homes don't interfere") {
            val homeA = Files.createTempDirectory("datadir-lock-a-")
            val homeB = Files.createTempDirectory("datadir-lock-b-")
            val a = DataDirLock.forDataHome(homeA)
            val b = DataDirLock.forDataHome(homeB)

            a.tryAcquire() shouldBe true
            b.tryAcquire() shouldBe true // independent homes → independent locks

            a.close()
            b.close()
        }

        test("close is idempotent and a closed lock can be re-acquired") {
            val home = Files.createTempDirectory("datadir-lock-reuse-")
            val lock = DataDirLock.forDataHome(home)

            lock.tryAcquire() shouldBe true
            lock.close()
            lock.close() // idempotent — must not throw

            lock.tryAcquire() shouldBe true
            lock.close()
        }
    })
