package com.calypsan.listenup.client.automotive

import android.content.pm.ProviderInfo
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import io.kotest.matchers.shouldBe
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.FileNotFoundException

/**
 * Tests for [CoverContentProvider].
 *
 * Uses [RobolectricTestRunner] because [android.content.ContentProvider] and
 * [android.os.ParcelFileDescriptor] need an Android runtime. Koin is started per-test with
 * only the two seams the provider resolves, so nothing else in the graph has to exist.
 */
@RunWith(RobolectricTestRunner::class)
class CoverContentProviderTest {
    private val packageName: String get() = RuntimeEnvironment.getApplication().packageName

    private lateinit var coversDir: File

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `openFile returns a descriptor for a cached cover`() {
        val provider = provider(cached = setOf("bk-1"))

        val pfd = provider.openFile(CoverUri.forBook(packageName, "bk-1"), "r")

        pfd.statSize shouldBe COVER_BYTES.size.toLong()
        pfd.close()
    }

    @Test
    fun `openFile fetches on cache miss and returns the fetched cover`() {
        var fetched: BookId? = null
        val provider =
            provider(
                cached = emptySet(),
                onDownload = { bookId ->
                    fetched = bookId
                    writeCover("bk-2")
                    AppResult.Success(true)
                },
            )

        val pfd = provider.openFile(CoverUri.forBook(packageName, "bk-2"), "r")

        fetched shouldBe BookId("bk-2")
        pfd.statSize shouldBe COVER_BYTES.size.toLong()
        pfd.close()
    }

    @Test(expected = FileNotFoundException::class)
    fun `openFile throws when the cover is absent and the fetch fails`() {
        val provider = provider(cached = emptySet(), onDownload = { AppResult.Success(false) })

        provider.openFile(CoverUri.forBook(packageName, "bk-3"), "r")
    }

    @Test(expected = FileNotFoundException::class)
    fun `openFile rejects a traversal book id without touching the filesystem`() {
        val provider =
            provider(
                cached = emptySet(),
                onDownload = { error("must not fetch for a rejected id") },
            )

        provider.openFile(android.net.Uri.parse("content://$packageName.covers/covers/.."), "r")
    }

    @Test(expected = FileNotFoundException::class)
    fun `openFile rejects a write mode`() {
        val provider = provider(cached = setOf("bk-1"))

        provider.openFile(CoverUri.forBook(packageName, "bk-1"), "w")
    }

    @Test
    fun `getType reports jpeg`() {
        provider(cached = emptySet()).getType(CoverUri.forBook(packageName, "bk-1")) shouldBe "image/jpeg"
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private fun writeCover(bookId: String): File =
        File(coversDir, "$bookId.jpg").apply {
            parentFile?.mkdirs()
            writeBytes(COVER_BYTES)
        }

    private fun provider(
        cached: Set<String>,
        onDownload: (BookId) -> AppResult<Boolean> = { AppResult.Success(false) },
    ): CoverContentProvider {
        coversDir = File(RuntimeEnvironment.getApplication().filesDir, "covers")
        coversDir.mkdirs()
        coversDir.listFiles()?.forEach { it.delete() }
        cached.forEach { writeCover(it) }

        startKoin {
            modules(
                module {
                    single<CoverFileLocator> { CoverFileLocator { bookId -> File(coversDir, "$bookId.jpg") } }
                    single<CoverFetcher> { CoverFetcher { bookId -> onDownload(bookId) } }
                },
            )
        }

        // NOT `.apply { authority = ... }`: ProviderInfo inherits a `packageName` field from
        // PackageItemInfo, which would shadow this class's `packageName` property as apply's
        // implicit receiver and resolve to null instead — an easy trap, not a style choice.
        val info = ProviderInfo()
        info.authority = CoverUri.authority(packageName)
        return Robolectric
            .buildContentProvider(CoverContentProvider::class.java)
            .create(info)
            .get()
    }

    private companion object {
        val COVER_BYTES = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
    }
}
