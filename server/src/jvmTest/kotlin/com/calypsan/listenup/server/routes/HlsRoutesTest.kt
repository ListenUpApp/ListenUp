package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.audio.AudioUrlSigner
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.testing.FfmpegTestSupport
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import com.calypsan.listenup.server.transcode.SegmentCache
import com.calypsan.listenup.server.transcode.TranscoderAvailability
import com.calypsan.listenup.server.transcode.TranscoderStatus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.client.HttpClient
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.koin.ktor.ext.inject
import java.nio.file.Files

/** 25s at 44.1 kHz is three frame-aligned segments — short to encode, long enough to have a tail. */
private const val BOOK_SECONDS = 25

/** `ceil(10 * 44100 / 1024)` — one whole segment, as [com.calypsan.listenup.server.transcode.HlsPlaylist] plans it. */
private const val FRAMES_PER_SEGMENT = 431

private val TEST_JWT_SECRET = "x".repeat(32) // must match the value in useIsolatedTestConfig
private val TEST_SIGNING_KEY = AudioUrlSigner.deriveSigningKey(TEST_JWT_SECRET)

/**
 * Integration tests for the signed HLS surface.
 *
 * Boots the full `Application.module()`, exactly as `AudioRoutesTest` does, because the signature
 * verification and the access gate are the point — they cannot be exercised against a route mounted
 * in isolation.
 *
 * ⛔ **Availability is published by the test, never probed.** `useIsolatedTestConfig` turns the boot
 * probe off, so these assertions do not depend on whether the machine running them has FFmpeg.
 *
 * Most cases here pre-write the segment the encoder would have produced — the path a re-listening
 * user takes, and the one that needs no encoder at all. The single cold-cache case is the exception
 * and drives a real FFmpeg end to end; it skips where there is none.
 */
class HlsRoutesTest :
    FunSpec({

        test("an unsigned playlist request is refused") {
            withHlsFixture { client, _ ->
                client.get("/api/v1/hls/b1/af1/media.m3u8").status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("a tampered signature is refused") {
            withHlsFixture { client, query ->
                // Flip one hex character of the signature; everything else stays valid.
                val tampered =
                    query.replace(Regex("sig=([0-9a-f])")) { m ->
                        "sig=" + if (m.groupValues[1] == "a") "b" else "a"
                    }

                client.get("/api/v1/hls/b1/af1/media.m3u8?$tampered").status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("an expired signature is refused") {
            withHlsFixture { client, _ ->
                val expired = "u=user1&exp=1&sig=${"0".repeat(64)}"

                client.get("/api/v1/hls/b1/af1/media.m3u8?$expired").status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("a signed playlist request returns a complete VOD playlist") {
            withHlsFixture { client, query ->
                val response = client.get("/api/v1/hls/b1/af1/media.m3u8?$query")

                response.status shouldBe HttpStatusCode.OK
                val body = response.bodyAsText()
                body shouldContain "#EXTM3U"
                body shouldContain "#EXT-X-PLAYLIST-TYPE:VOD"
                // Frame-aligned, not the round 10s that would drift — see HlsPlaylistTest.
                body shouldContain "#EXTINF:10.007800,"
                // The whole timeline is written up front, so the seek bar is whole immediately.
                body shouldContain "#EXT-X-ENDLIST"
                // The caller's own signature is forwarded onto every segment URL, which is what
                // lets a player that only follows links stay authorized.
                body shouldContain "seg/0.aac?$query"
            }
        }

        test("the master playlist points at the media playlist, signature carried over") {
            withHlsFixture { client, query ->
                val body = client.get("/api/v1/hls/b1/af1/master.m3u8?$query").bodyAsText()

                body shouldContain "#EXT-X-STREAM-INF:"
                body shouldContain "media.m3u8?$query"
            }
        }

        test("a segment already in cache is served without starting an encode") {
            withHlsFixture { client, query ->
                val cache by application.inject<SegmentCache>()
                val segmentBytes = ByteArray(64) { it.toByte() }
                cache.prepareDir("b1", "af1")
                Files.write(
                    java.nio.file.Path
                        .of(cache.segmentPath("b1", "af1", 0).toString()),
                    segmentBytes,
                )
                // The encoder's own record that it finished this one — without it the segment is
                // treated as still being written, which is the point of SegmentCache.isComplete.
                Files.write(
                    java.nio.file.Path
                        .of(cache.runListPath("b1", "af1", 0).toString()),
                    "seg00000.aac\n".toByteArray(),
                )

                val response = client.get("/api/v1/hls/b1/af1/seg/0.aac?$query")

                response.status shouldBe HttpStatusCode.OK
                response.bodyAsBytes().toList() shouldBe segmentBytes.toList()
            }
        }

        // First play, cold cache — the exact path a browser takes, with nothing faked between the
        // signed request and the bytes. Everything else in this file pre-writes its segments, so
        // without this nothing proved a cache MISS actually wakes the encoder and answers.
        //
        // The response is measured, not merely counted. It is also the regression test for serving
        // a segment FFmpeg is still writing: against the old existence check this case came back
        // with so little of the segment that ffprobe could not read one frame from it.
        test("a cold request encodes on demand and returns a whole, aligned segment")
            .config(enabled = FfmpegTestSupport.isAvailable) {
                withHlsFixture(encoder = FfmpegTestSupport.ffmpeg) { client, query ->
                    val response = client.get("/api/v1/hls/b1/af1/seg/0.aac?$query")

                    response.status shouldBe HttpStatusCode.OK
                    val served = Files.createTempFile("listenup-served-", ".aac")
                    try {
                        Files.write(served, response.bodyAsBytes())
                        FfmpegTestSupport.frameCount(served.toString()) shouldBe FRAMES_PER_SEGMENT
                    } finally {
                        Files.deleteIfExists(served)
                    }
                }
            }

        // A server with no encoder must say so, not hand out a playlist whose segments can never
        // be produced. 501 rather than 503: retrying changes nothing until an operator acts.
        test("with no encoder available the playlist is refused as unavailable") {
            withHlsFixture(available = false) { client, query ->
                client.get("/api/v1/hls/b1/af1/media.m3u8?$query").status shouldBe
                    HttpStatusCode.NotImplemented
            }
        }
    })

/**
 * Boots the app with one reachable book (`b1`/`af1`), publishes [available] as the encoder's state,
 * and hands the block a client plus a valid signed query for that file.
 *
 * The block runs with the [ApplicationTestBuilder] as its receiver so it can reach into the Koin
 * graph — the cached-segment case needs [SegmentCache] to know where to pre-write.
 */
private suspend fun withHlsFixture(
    available: Boolean = true,
    encoder: String? = null,
    block: suspend ApplicationTestBuilder.(HttpClient, String) -> Unit,
) {
    val libraryRoot = Files.createTempDirectory("listenup-hls-")
    val home = Files.createTempDirectory("listenup-hls-home-")
    try {
        testApplication {
            useIsolatedTestConfig(
                libraryPath = libraryRoot.toString(),
                homeDir = home.toString(),
                rescanOnStartup = false,
            )
            application { module() }
            val client = createClient { install(ContentNegotiation) { json(contractJson) } }

            // Triggers application startup before any inject, as AudioRoutesTest does.
            client.get("/healthz")
            seedTestLibraryAndFolder(folderPath = libraryRoot.toString())

            val bookDir = Files.createDirectories(libraryRoot.resolve("books/b1"))
            if (encoder == null) {
                Files.write(bookDir.resolve("01.m4b"), ByteArray(256) { it.toByte() })
            } else {
                // A real encode needs real audio, at the rate and length the payload below declares.
                FfmpegTestSupport.generateSine(bookDir.resolve("01.m4b"), BOOK_SECONDS)
            }

            val repo by application.inject<BookRepository>()
            repo.upsert(hlsFixture())

            // Reachable the simplest pure-union way: a collection user1 owns.
            val sql by application.inject<ListenUpDatabase>()
            sql.seedTestUser("user1")
            val collectionRepo by application.inject<CollectionRepository>()
            val collectionBookRepo by application.inject<CollectionBookRepository>()
            collectionRepo.upsert(privateCollection("owned-col", owner = "user1"))
            collectionBookRepo.upsert(membership("owned-col", "b1"))

            val availability by application.inject<TranscoderAvailability>()
            availability.publish(
                if (available) {
                    // A path that does not exist is fine for every test that pre-writes its
                    // segments; only the first-play test needs a real binary here.
                    TranscoderStatus.Available(path = encoder ?: "/nonexistent/ffmpeg", version = "test")
                } else {
                    TranscoderStatus.Unavailable("no encoder in this test")
                },
            )

            val query = AudioUrlSigner(signingKey = TEST_SIGNING_KEY).signedQuery("user1", "b1", "af1")
            block(client, query)
        }
    } finally {
        libraryRoot.toFile().deleteRecursively()
        home.toFile().deleteRecursively()
    }
}

private fun hlsFixture(): BookSyncPayload =
    BookSyncPayload(
        id = "b1",
        libraryId = LibraryId("test-library"),
        folderId = FolderId("test-folder"),
        title = "HLS Test Book",
        sortTitle = "HLS Test Book",
        subtitle = null,
        description = null,
        publishYear = null,
        publisher = null,
        language = null,
        isbn = null,
        asin = null,
        abridged = false,
        explicit = false,
        totalDuration = BOOK_SECONDS * 1000L,
        cover = null,
        rootRelPath = "books/b1",
        inode = null,
        scannedAt = 1_730_000_000_000L,
        contributors = emptyList(),
        series = emptyList(),
        audioFiles =
            listOf(
                BookAudioFilePayload(
                    id = "af1",
                    index = 0,
                    filename = "01.m4b",
                    format = "m4b",
                    codec = "aac",
                    // 25s at 44.1 kHz is three frame-aligned segments — small enough to assert on
                    // whole, long enough that the final short segment exists.
                    duration = BOOK_SECONDS * 1000L,
                    size = 256L,
                    codecProfile = "xhe",
                    sampleRate = 44_100,
                ),
            ),
        chapters =
            listOf(
                BookChapterPayload(id = "ch-b1", title = "Prologue", duration = BOOK_SECONDS * 1000L, startTime = 0L),
            ),
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
    )

private fun privateCollection(
    id: String,
    owner: String,
): CollectionSyncPayload =
    CollectionSyncPayload(
        id = id,
        libraryId = "test-library",
        ownerId = owner,
        name = id,
        isInbox = false,
        revision = 0L,
        updatedAt = 0L,
    )

private fun membership(
    collectionId: String,
    bookId: String,
): CollectionBookSyncPayload =
    CollectionBookSyncPayload(
        id = "$collectionId:$bookId",
        collectionId = collectionId,
        bookId = bookId,
        createdAt = 0L,
        revision = 0L,
    )
