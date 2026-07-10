package com.calypsan.listenup.server.librarywrite

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path

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
    })
