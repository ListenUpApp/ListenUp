package com.calypsan.listenup.client.deeplink

import android.content.Intent
import android.net.Uri
import com.calypsan.listenup.client.data.repository.InviteDeepLink
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * Tests for [DeepLinkParser].
 *
 * [DeepLinkParser] accepts [android.net.Uri] instances whose parsing semantics
 * (scheme, host, query-parameter extraction) are implemented in native Android
 * code. A plain JVM unit test would receive stubbed [Uri] methods that always
 * throw [UnsupportedOperationException]. Robolectric provides a full
 * [android.net.Uri] implementation backed by real Java code, so tests can
 * construct URIs via [Uri.parse] exactly as production code does.
 *
 * JUnit4 + [RobolectricTestRunner] is used here (consistent with
 * [com.calypsan.listenup.client.navigation.PhaseABoundarySuiteTest]); the
 * `junit-vintage-engine` on the classpath means the JUnit5 platform runs these
 * transparently alongside Kotest specs in `androidHostTest`.
 *
 * `@Config(sdk = [35])` pins Robolectric to the highest SDK its 4.15.1 release
 * ships shadows for; bump in lockstep when Robolectric ships SDK 37 shadows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeepLinkParserTest {
    // ── parseUri — happy paths ────────────────────────────────────────────────

    @Test
    fun `parseUri returns InviteDeepLink for valid join URI`() {
        val uri = Uri.parse("listenup://join?server=https://audiobooks.example.com&code=ABC123")
        val result = DeepLinkParser.parseUri(uri).shouldNotBeNull()
        result.serverUrl shouldBe "https://audiobooks.example.com"
        result.code shouldBe "ABC123"
    }

    @Test
    fun `parseUri decodes URL-encoded server param`() {
        val uri = Uri.parse("listenup://join?server=https%3A%2F%2Faudiobooks.example.com&code=XY9")
        val result = DeepLinkParser.parseUri(uri).shouldNotBeNull()
        result.serverUrl shouldBe "https://audiobooks.example.com"
        result.code shouldBe "XY9"
    }

    // ── parseUri — reject paths ───────────────────────────────────────────────

    @Test
    fun `parseUri returns null for wrong scheme`() {
        val uri = Uri.parse("https://join?server=https://audiobooks.example.com&code=ABC123")
        DeepLinkParser.parseUri(uri) shouldBe null
    }

    @Test
    fun `parseUri returns null for wrong host`() {
        val uri = Uri.parse("listenup://book?server=https://audiobooks.example.com&code=ABC123")
        // host is "book", not "join" — parseCustomScheme rejects it
        DeepLinkParser.parseUri(uri) shouldBe null
    }

    @Test
    fun `parseUri returns null when server param is missing`() {
        val uri = Uri.parse("listenup://join?code=ABC123")
        DeepLinkParser.parseUri(uri) shouldBe null
    }

    @Test
    fun `parseUri returns null when code param is missing`() {
        val uri = Uri.parse("listenup://join?server=https://audiobooks.example.com")
        DeepLinkParser.parseUri(uri) shouldBe null
    }

    @Test
    fun `parseUri returns null when server param is blank`() {
        val uri = Uri.parse("listenup://join?server=&code=ABC123")
        DeepLinkParser.parseUri(uri) shouldBe null
    }

    @Test
    fun `parseUri returns null when code param is blank`() {
        val uri = Uri.parse("listenup://join?server=https://audiobooks.example.com&code=")
        DeepLinkParser.parseUri(uri) shouldBe null
    }

    // ── parseBookDeepLink — happy path ────────────────────────────────────────

    @Test
    fun `parseBookDeepLink returns BookDeepLink for valid book URI`() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("listenup://book/book-abc-123"))
        val result = DeepLinkParser.parseBookDeepLink(intent).shouldNotBeNull()
        result.bookId shouldBe "book-abc-123"
    }

    // ── parseBookDeepLink — reject paths ─────────────────────────────────────

    @Test
    fun `parseBookDeepLink returns null for wrong action`() {
        val intent = Intent(Intent.ACTION_SEND, Uri.parse("listenup://book/book-abc-123"))
        DeepLinkParser.parseBookDeepLink(intent) shouldBe null
    }

    @Test
    fun `parseBookDeepLink returns null for wrong scheme`() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://book/book-abc-123"))
        DeepLinkParser.parseBookDeepLink(intent) shouldBe null
    }

    @Test
    fun `parseBookDeepLink returns null for wrong host`() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("listenup://join/book-abc-123"))
        DeepLinkParser.parseBookDeepLink(intent) shouldBe null
    }

    @Test
    fun `parseBookDeepLink returns null when book id path segment is missing`() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("listenup://book/"))
        DeepLinkParser.parseBookDeepLink(intent) shouldBe null
    }

    @Test
    fun `parseBookDeepLink returns null for null intent`() {
        DeepLinkParser.parseBookDeepLink(null) shouldBe null
    }

    @Test
    fun `parseBookDeepLink returns null for intent with no data`() {
        val intent = Intent(Intent.ACTION_VIEW)
        DeepLinkParser.parseBookDeepLink(intent) shouldBe null
    }

    // ── parse(Intent?) — invite path ─────────────────────────────────────────

    @Test
    fun `parse returns InviteDeepLink for valid ACTION_VIEW intent`() {
        val intent =
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("listenup://join?server=https://example.com&code=ZZ1"),
            )
        val result: InviteDeepLink = DeepLinkParser.parse(intent).shouldNotBeNull()
        result.serverUrl shouldBe "https://example.com"
        result.code shouldBe "ZZ1"
    }

    @Test
    fun `parse returns null for null intent`() {
        DeepLinkParser.parse(null) shouldBe null
    }

    @Test
    fun `parse returns null for intent with wrong action`() {
        val intent =
            Intent(
                Intent.ACTION_SEND,
                Uri.parse("listenup://join?server=https://example.com&code=ZZ1"),
            )
        DeepLinkParser.parse(intent) shouldBe null
    }

    @Test
    fun `parse returns null for intent with no URI data`() {
        val intent = Intent(Intent.ACTION_VIEW)
        DeepLinkParser.parse(intent) shouldBe null
    }
}
