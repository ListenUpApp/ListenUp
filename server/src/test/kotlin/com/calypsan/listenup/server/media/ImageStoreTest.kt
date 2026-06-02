package com.calypsan.listenup.server.media

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.nio.file.Files

class ImageStoreTest :
    FunSpec({
        val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(16)
        val jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(16)

        test("stores a valid PNG and serves it back with content type") {
            val dir = Files.createTempDirectory("imagestore-")
            runTest {
                val store = ImageStore(dir, maxBytes = 1_000_000)
                val stored = store.store("u1", pngBytes, "image/png")
                stored.contentType shouldBe "image/png"
                store.pathFor("u1").shouldNotBeNull()
                Files.readAllBytes(store.pathFor("u1")!!) shouldBe pngBytes
            }
        }
        test("rejects bytes whose magic number is not a supported image") {
            val dir = Files.createTempDirectory("imagestore-")
            runTest {
                val store = ImageStore(dir, maxBytes = 1_000_000)
                shouldThrowInvalid { store.store("u1", "not an image".encodeToByteArray(), "image/png") }
                store.pathFor("u1").shouldBeNull()
            }
        }
        test("rejects oversize input") {
            val dir = Files.createTempDirectory("imagestore-")
            runTest {
                val store = ImageStore(dir, maxBytes = 4)
                shouldThrowInvalid { store.store("u1", jpegBytes, "image/jpeg") }
            }
        }
        test("delete removes the stored image") {
            val dir = Files.createTempDirectory("imagestore-")
            runTest {
                val store = ImageStore(dir, maxBytes = 1_000_000)
                store.store("u1", pngBytes, "image/png")
                store.delete("u1")
                store.pathFor("u1").shouldBeNull()
            }
        }
        test("pathFor returns null for a path-traversal key") {
            val dir = Files.createTempDirectory("imagestore-")
            val store = ImageStore(dir, maxBytes = 1_000_000)
            store.pathFor("../../etc/passwd").shouldBeNull()
        }
        test("store throws InvalidImageException for a path-traversal key") {
            val dir = Files.createTempDirectory("imagestore-")
            runTest {
                val store = ImageStore(dir, maxBytes = 1_000_000)
                shouldThrowInvalid { store.store("../x", pngBytes, "image/png") }
            }
        }
    })

private inline fun shouldThrowInvalid(block: () -> Unit) {
    try {
        block()
        throw AssertionError("expected ImageStore.InvalidImageException")
    } catch (e: ImageStore.InvalidImageException) {
        check(e.message != null) { "InvalidImageException must carry a message" }
    }
}
