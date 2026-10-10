package com.calypsan.listenup.web

import com.calypsan.listenup.core.FileUnreadableException
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrowAny
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.io.readByteArray
import org.khronos.webgl.Int8Array
import org.w3c.files.File
import kotlin.math.ceil

private const val SLICE = 64 * 1024
private const val FILE_BYTES = 300 * 1024

/** A recognisable, non-repeating-per-slice pattern, so a slice read out of order or twice is visible. */
private fun patterned(size: Int): ByteArray = ByteArray(size) { ((it * 31 + it / 251) % 251).toByte() }

private fun fileOf(bytes: ByteArray): File = File(arrayOf(bytes.unsafeCast<Int8Array>()).unsafeCast<Array<Any>>(), "book.m4b")

private suspend fun ByteReadChannel.readAll(): ByteArray = readRemaining().readByteArray()

/**
 * The streamed browser file: the property that matters is that a file is read a slice at a time and
 * never whole, because a library upload is gigabytes and a tab is not.
 */
class BrowserFileSourceTest :
    FunSpec({
        test("a channel yields the file's bytes in order") {
            val bytes = patterned(FILE_BYTES)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                BrowserFileSource(fileOf(bytes), scope, sliceBytes = SLICE).openChannel().readAll().toList() shouldContainExactly
                    bytes.toList()
            } finally {
                scope.cancel()
            }
        }

        test("each open starts from the beginning") {
            val bytes = patterned(FILE_BYTES)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val source = BrowserFileSource(fileOf(bytes), scope, sliceBytes = SLICE)

                val first = source.openChannel().readAll()
                val second = source.openChannel().readAll()

                first.toList() shouldContainExactly bytes.toList()
                second.toList() shouldContainExactly bytes.toList()
            } finally {
                scope.cancel()
            }
        }

        test("the file is read in slices, never whole") {
            val bytes = patterned(FILE_BYTES)
            val sizes = mutableListOf<Long>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val source =
                    BrowserFileSource(fileOf(bytes), scope, sliceBytes = SLICE, readSlice = { file, start, end ->
                        readFileSlice(file, start, end)?.also { sizes += it.size.toLong() }
                    })

                source.openChannel().readAll()

                sizes.max() shouldBeLessThanOrEqual SLICE.toLong()
                sizes.size shouldBe ceil(FILE_BYTES.toDouble() / SLICE).toInt()
                sizes.sum() shouldBe FILE_BYTES.toLong()
            } finally {
                scope.cancel()
            }
        }

        test("size is the file's size without reading it") {
            var reads = 0
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val source =
                    BrowserFileSource(fileOf(patterned(FILE_BYTES)), scope, sliceBytes = SLICE, readSlice = { _, _, _ ->
                        reads++
                        null
                    })

                source.size shouldBe FILE_BYTES.toLong()
                source.filename shouldBe "book.m4b"
                reads shouldBe 0
            } finally {
                scope.cancel()
            }
        }

        test("a slice that cannot be read closes the channel with a FileUnreadableException") {
            var call = 0
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val source =
                    BrowserFileSource(fileOf(patterned(FILE_BYTES)), scope, sliceBytes = SLICE, readSlice = { file, start, end ->
                        call++
                        if (call == 3) null else readFileSlice(file, start, end)
                    })

                // Ending early would hand the server a short file as though it were whole. And it must
                // say WHY: an IOException would be read as the network being down and retried.
                val failure = shouldThrowAny { source.openChannel().readAll() }
                generateSequence(failure) { it.cause }.any { it is FileUnreadableException } shouldBe true
            } finally {
                scope.cancel()
            }
        }
    })
