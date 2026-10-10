package com.calypsan.listenup.server.media

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import com.calypsan.listenup.server.io.readBytes
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlinx.io.files.Path as IoPath

class ImageStoreTest :
    FunSpec({
        val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(16)
        val jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(16)

        test("stores a valid PNG and serves it back with content type") {
            val dir = Files.createTempDirectory("imagestore-")
            runTest {
                val store = ImageStore(IoPath(dir.toString()), maxBytes = 1_000_000)
                val stored = store.store("u1", pngBytes, "image/png")
                stored.contentType shouldBe "image/png"
                store.pathFor("u1").shouldNotBeNull()
                store.pathFor("u1")!!.readBytes() shouldBe pngBytes
            }
        }
        test("re-storing a key under a new type leaves exactly one file") {
            val dir = Files.createTempDirectory("imagestore-")
            runTest {
                val store = ImageStore(IoPath(dir.toString()), maxBytes = 1_000_000)
                store.store("u1", jpegBytes, "image/jpeg")
                store.store("u1", pngBytes, "image/png")
                Files.readAllBytes(dir.resolve("u1.png")) shouldBe pngBytes
                Files.exists(dir.resolve("u1.jpg")) shouldBe false
            }
        }
        test("a failed write leaves the previous image intact") {
            val dir = Files.createTempDirectory("imagestore-")
            runTest {
                val store = ImageStore(IoPath(dir.toString()), maxBytes = 1_000_000)
                store.store("u1", jpegBytes, "image/jpeg")
                // The atomic writer stages to `<name>.tmp`; a directory there makes opening the sink fail.
                Files.createDirectory(dir.resolve("u1.png.tmp"))
                shouldThrow<Exception> { store.store("u1", pngBytes, "image/png") }
                Files.readAllBytes(dir.resolve("u1.jpg")) shouldBe jpegBytes
            }
        }
        test("a content-addressed store never deletes a sibling extension") {
            val dir = Files.createTempDirectory("imagestore-")
            runTest {
                val store = ImageStore(IoPath(dir.toString()), maxBytes = 1_000_000)
                Files.write(dir.resolve("abc.jpg"), pngBytes)
                store.storeContentAddressed("abc", pngBytes, "image/*")
                Files.readAllBytes(dir.resolve("abc.jpg")) shouldBe pngBytes
                Files.readAllBytes(dir.resolve("abc.png")) shouldBe pngBytes
            }
        }
        test("rejects bytes whose magic number is not a supported image") {
            val dir = Files.createTempDirectory("imagestore-")
            runTest {
                val store = ImageStore(IoPath(dir.toString()), maxBytes = 1_000_000)
                shouldThrowInvalid { store.store("u1", "not an image".encodeToByteArray(), "image/png") }
                store.pathFor("u1").shouldBeNull()
            }
        }
        test("refuses a GIF, which avatars and covers never accepted") {
            val dir = Files.createTempDirectory("imagestore-")
            runTest {
                val store = ImageStore(IoPath(dir.toString()), maxBytes = 1_000_000)
                shouldThrowInvalid { store.store("u1", "GIF89a".encodeToByteArray() + ByteArray(16), "image/gif") }
                store.pathFor("u1").shouldBeNull()
            }
        }
        test("rejects oversize input") {
            val dir = Files.createTempDirectory("imagestore-")
            runTest {
                val store = ImageStore(IoPath(dir.toString()), maxBytes = 4)
                shouldThrowInvalid { store.store("u1", jpegBytes, "image/jpeg") }
            }
        }
        test("delete removes the stored image") {
            val dir = Files.createTempDirectory("imagestore-")
            runTest {
                val store = ImageStore(IoPath(dir.toString()), maxBytes = 1_000_000)
                store.store("u1", pngBytes, "image/png")
                store.delete("u1")
                store.pathFor("u1").shouldBeNull()
            }
        }
        test("pathFor returns null for a path-traversal key") {
            val dir = Files.createTempDirectory("imagestore-")
            val store = ImageStore(IoPath(dir.toString()), maxBytes = 1_000_000)
            store.pathFor("../../etc/passwd").shouldBeNull()
        }
        test("store throws InvalidImageException for a path-traversal key") {
            val dir = Files.createTempDirectory("imagestore-")
            runTest {
                val store = ImageStore(IoPath(dir.toString()), maxBytes = 1_000_000)
                shouldThrowInvalid { store.store("../x", pngBytes, "image/png") }
            }
        }
    })

private inline fun shouldThrowInvalid(block: () -> Unit) {
    try {
        block()
        throw AssertionError("expected ImageStore.InvalidImageException")
    } catch (e: ImageStore.InvalidImageException) {
        checkNotNull(e.message) { "InvalidImageException must carry a message" }
    }
}
