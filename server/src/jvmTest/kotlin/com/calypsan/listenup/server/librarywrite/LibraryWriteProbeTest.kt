package com.calypsan.listenup.server.librarywrite

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

class LibraryWriteProbeTest :
    FunSpec({
        test("probe reports Available for a writable root") {
            runTest {
                val ok = tempLibraryDir()
                val broker = testBroker()

                broker.probe(Path(ok.toString())).shouldBeInstanceOf<LibraryWriteStatus.Available>()
            }
        }

        test("probe reports Unavailable for a read-only root") {
            if (!isPosix()) return@test
            runTest {
                val ro = tempLibraryDir()
                makeReadOnly(ro)
                val broker = testBroker()

                broker.probe(Path(ro.toString())).shouldBeInstanceOf<LibraryWriteStatus.Unavailable>()
            }
        }

        test("probe reports Unavailable for a missing root and never creates it") {
            runTest {
                val missing = Path(tempLibraryDir(), "unmounted")
                val broker = testBroker()

                broker.probe(missing).shouldBeInstanceOf<LibraryWriteStatus.Unavailable>()
                SystemFileSystem.exists(missing) shouldBe false
            }
        }
    })
