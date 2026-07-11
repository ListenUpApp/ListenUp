package com.calypsan.listenup.server.librarywrite

import com.calypsan.listenup.api.error.LibraryWriteError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.io.hashBytesSha256
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

class LibraryWriteBrokerFileTest :
    FunSpec({
        test("writeFile lands atomically with exact bytes and returns the content hash") {
            runTest {
                val dir = tempLibraryDir()
                val broker = testBroker()
                val target = Path(Path(dir, "Book"), "listenup.json")
                val bytes = "{\"schemaVersion\":1}".encodeToByteArray()

                val result = broker.writeFile(target, bytes)

                result.shouldBeInstanceOf<AppResult.Success<WrittenFile>>()
                SystemFileSystem.exists(target) shouldBe true
                SystemFileSystem.source(target).buffered().use { it.readByteArray() } shouldBe bytes
                result.data.contentHashHex shouldBe hashBytesSha256(bytes)
                SystemFileSystem
                    .list(target.parent!!)
                    .none { it.name.startsWith(".listenup-tmp") } shouldBe true
            }
        }

        test("writeFile registers target in the SelfWriteRegistry before the rename") {
            runTest {
                val dir = tempLibraryDir()
                val registry = SelfWriteRegistry { 0L }
                val broker = testBroker(registry)
                val target = Path(Path(dir, "Book"), "listenup.json")

                val result = broker.writeFile(target, byteArrayOf(1))

                result.shouldBeInstanceOf<AppResult.Success<WrittenFile>>()
                registry.isSelfWrite(target) shouldBe true
            }
        }

        test("writeFile into a read-only directory returns LibraryWriteError.Unavailable, no throw") {
            if (!isPosix()) return@test
            runTest {
                val dir = tempLibraryDir()
                makeReadOnly(dir)
                val broker = testBroker()

                val result = broker.writeFile(Path(dir, "x.json"), byteArrayOf(1))

                result.shouldBeInstanceOf<AppResult.Failure>()
                result.error.shouldBeInstanceOf<LibraryWriteError.Unavailable>()
            }
        }
    })
