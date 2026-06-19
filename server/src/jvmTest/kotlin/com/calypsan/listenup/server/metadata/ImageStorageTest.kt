package com.calypsan.listenup.server.metadata

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import java.io.IOException
import java.nio.file.Files

/**
 * Tests for [ImageStorage].
 *
 * Uses [MockEngine] for HTTP and in-process temp directories for the
 * filesystem surface. Verifies the atomic-write-then-rename contract: readers
 * never see a partial write, and the `.tmp` sibling is always cleaned up.
 *
 * `java.nio.file.Files.createTempDirectory` is grandfathered — kotlinx-io has
 * no equivalent "create a temp directory" API.
 */
class ImageStorageTest :
    FunSpec({

        test("download writes the returned bytes to the destination path") {
            val tempDir = Files.createTempDirectory("imgtest-write-").toString()
            val destination = Path(tempDir, "cover.jpg")
            val mockBytes = byteArrayOf(1, 2, 3, 4, 5)

            val engine =
                MockEngine { _ ->
                    respond(
                        content = mockBytes,
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", ContentType.Image.JPEG.toString()),
                    )
                }
            val storage = ImageStorage(HttpClient(engine))

            val returned = storage.download("https://example.com/cover.jpg", destination)

            returned shouldBe mockBytes
            SystemFileSystem.exists(destination) shouldBe true
        }

        test("download returns the bytes from the response") {
            val tempDir = Files.createTempDirectory("imgtest-return-").toString()
            val destination = Path(tempDir, "cover.jpg")
            val expected = byteArrayOf(10, 20, 30)

            val engine =
                MockEngine { _ ->
                    respond(
                        content = expected,
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", ContentType.Image.JPEG.toString()),
                    )
                }
            val storage = ImageStorage(HttpClient(engine))

            val returned = storage.download("https://example.com/cover.jpg", destination)

            returned shouldBe expected
        }

        test("the .tmp sibling is absent after a successful download") {
            val tempDir = Files.createTempDirectory("imgtest-notmp-").toString()
            val destination = Path(tempDir, "cover.jpg")
            val mockBytes = byteArrayOf(99)

            val engine =
                MockEngine { _ ->
                    respond(
                        content = mockBytes,
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", ContentType.Image.JPEG.toString()),
                    )
                }
            val storage = ImageStorage(HttpClient(engine))

            storage.download("https://example.com/cover.jpg", destination)

            SystemFileSystem.exists(Path(tempDir, "cover.jpg.tmp")) shouldBe false
        }

        test("download propagates a network-level exception") {
            val tempDir = Files.createTempDirectory("imgtest-fail-").toString()
            val destination = Path(tempDir, "cover.jpg")

            // Network-level failure (simulates a connection refused / timeout).
            val config = MockEngineConfig()
            config.addHandler { _ -> throw IOException("simulated network failure") }
            val engine = MockEngine(config)
            val storage = ImageStorage(HttpClient(engine))

            shouldThrow<Exception> {
                storage.download("https://example.com/bad.jpg", destination)
            }
        }

        test("the .tmp sibling is absent after a network-level failure") {
            val tempDir = Files.createTempDirectory("imgtest-failtmp-").toString()
            val destination = Path(tempDir, "cover.jpg")

            // Network failure happens before any bytes are written to disk —
            // no .tmp is created. Verify the cleanup path handles this case.
            val config = MockEngineConfig()
            config.addHandler { _ -> throw IOException("simulated network failure") }
            val engine = MockEngine(config)
            val storage = ImageStorage(HttpClient(engine))

            runCatching { storage.download("https://example.com/bad.jpg", destination) }

            SystemFileSystem.exists(Path(tempDir, "cover.jpg.tmp")) shouldBe false
        }
    })
