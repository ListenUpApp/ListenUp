package com.calypsan.listenup.client.automotive

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for [CoverUri].
 *
 * [android.net.Uri] is an Android type, so this uses [RobolectricTestRunner] + JUnit4 with
 * Kotest matchers — the same shape as [BrowseTreeProviderTest], for the same reason.
 */
@RunWith(RobolectricTestRunner::class)
class CoverUriTest {
    private val pkg = "com.calypsan.listenup.client"

    @Test
    fun `authority is the package name suffixed with covers`() {
        CoverUri.authority(pkg) shouldBe "com.calypsan.listenup.client.covers"
    }

    @Test
    fun `forBook builds a content uri under the covers path`() {
        CoverUri.forBook(pkg, "bk-123").toString() shouldBe
            "content://com.calypsan.listenup.client.covers/covers/bk-123"
    }

    @Test
    fun `prefixUri is the covers path root`() {
        CoverUri.prefixUri(pkg).toString() shouldBe
            "content://com.calypsan.listenup.client.covers/covers"
    }

    @Test
    fun `bookIdFrom round-trips a uri built by forBook`() {
        CoverUri.bookIdFrom(CoverUri.forBook(pkg, "bk-123")) shouldBe "bk-123"
    }

    @Test
    fun `bookIdFrom rejects a uri with the wrong path segment`() {
        CoverUri.bookIdFrom(android.net.Uri.parse("content://x.covers/avatars/bk-123")).shouldBeNull()
    }

    @Test
    fun `bookIdFrom rejects a uri with no book id`() {
        CoverUri.bookIdFrom(android.net.Uri.parse("content://x.covers/covers")).shouldBeNull()
    }

    @Test
    fun `bookIdFrom rejects extra path segments`() {
        CoverUri.bookIdFrom(android.net.Uri.parse("content://x.covers/covers/bk-123/extra")).shouldBeNull()
    }

    @Test
    fun `isSafeBookId accepts ids of the shape the app actually issues`() {
        CoverUri.isSafeBookId("bk-123") shouldBe true
        CoverUri.isSafeBookId("01JQ8Z9ABCDEF") shouldBe true
        CoverUri.isSafeBookId("a_b-c") shouldBe true
    }

    @Test
    fun `isSafeBookId rejects traversal and separators`() {
        CoverUri.isSafeBookId("..") shouldBe false
        CoverUri.isSafeBookId("../secrets") shouldBe false
        CoverUri.isSafeBookId("a/b") shouldBe false
        CoverUri.isSafeBookId("a\\b") shouldBe false
        CoverUri.isSafeBookId("/absolute") shouldBe false
        CoverUri.isSafeBookId("a.b") shouldBe false
        CoverUri.isSafeBookId("") shouldBe false
    }

    @Test
    fun `bookIdFrom rejects an unsafe id even when the path shape is right`() {
        CoverUri.bookIdFrom(android.net.Uri.parse("content://x.covers/covers/..")).shouldBeNull()
    }
}
