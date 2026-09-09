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
 * The URL policy, redirect handling, and byte ceiling that guard `downloadBytes` live in
 * [BoundedImageFetch] and are covered by [BoundedImageFetchTest]; this spec covers what
 * [ImageStorage] itself still owns — the disk write — plus the delegation.
 *
 * `java.nio.file.Files.createTempDirectory` is grandfathered — kotlinx-io has
 * no equivalent "create a temp directory" API.
 */
class ImageStorageTest :
    FunSpec({

        test("writeBytes writes the bytes to the destination path") {
            val tempDir = Files.createTempDirectory("imgtest-write-").toString()
            val destination = Path(tempDir, "cover.jpg")
            val bytes = byteArrayOf(1, 2, 3, 4, 5)

            storageWith(bytes).writeBytes(bytes, destination)

            SystemFileSystem.exists(destination) shouldBe true
        }

        test("the .tmp sibling is absent after a successful write") {
            val tempDir = Files.createTempDirectory("imgtest-notmp-").toString()
            val destination = Path(tempDir, "cover.jpg")
            val bytes = byteArrayOf(99)

            storageWith(bytes).writeBytes(bytes, destination)

            SystemFileSystem.exists(Path(tempDir, "cover.jpg.tmp")) shouldBe false
        }

        test("the .tmp sibling is absent after a failed write") {
            // The destination's parent does not exist, so the temp sink can't be opened. The
            // atomic-write helper must still leave nothing behind for a later reader to trip over.
            val tempDir = Files.createTempDirectory("imgtest-failtmp-").toString()
            val missingDir = Path(tempDir, "not-created-yet")
            val destination = Path(missingDir.toString(), "cover.jpg")
            val bytes = byteArrayOf(7)

            shouldThrow<Exception> { storageWith(bytes).writeBytes(bytes, destination) }

            SystemFileSystem.exists(Path(missingDir.toString(), "cover.jpg.tmp")) shouldBe false
        }

        test("downloadBytes returns the bytes from the response") {
            val expected = byteArrayOf(10, 20, 30)

            val returned = storageWith(expected).downloadBytes("https://example.com/cover.jpg")

            returned shouldBe expected
        }

        test("downloadBytes propagates a network-level failure") {
            // Network-level failure (simulates a connection refused / timeout).
            val config = MockEngineConfig()
            config.addHandler { _ -> throw IOException("simulated network failure") }
            val storage = ImageStorage(HttpClient(MockEngine(config)))

            shouldThrow<Exception> { storage.downloadBytes("https://example.com/bad.jpg") }
        }
    })

/** An [ImageStorage] whose every request answers with [bytes] as a JPEG. */
private fun storageWith(bytes: ByteArray): ImageStorage =
    ImageStorage(
        HttpClient(
            MockEngine {
                respond(
                    content = bytes,
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", ContentType.Image.JPEG.toString()),
                )
            },
        ),
    )
