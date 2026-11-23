package com.calypsan.listenup.server.matching.person

import com.calypsan.listenup.server.io.hashBytesSha256
import com.calypsan.listenup.server.metadata.RemoteImageFetcher
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import java.nio.file.Files

/** Every contributor photo passes the image sniff and the size cap before it reaches disk, and is named by its content. */
class ContributorPhotoFilesTest :
    FunSpec({
        test("a photo response that declares a non-image type is refused and no file is written") {
            val imageHome = tempImageHome("photo-html-")
            val html = fetcherServing(ByteArray(8), ContentType.Text.Html)

            runTest {
                ContributorPhotoFiles(html, imageHome).store(PHOTO_URL).shouldBeNull()
            }
            contributorFiles(imageHome) shouldBe emptyList()
        }

        test("a photo whose bytes are not an image is rejected and no file is written") {
            val imageHome = tempImageHome("photo-notimage-")
            // Declares itself an image, but the bytes carry no image magic number. The declared type is the
            // remote's claim; the sniff is what decides.
            val liar = fetcherServing("this is not an image".encodeToByteArray())

            runTest {
                ContributorPhotoFiles(liar, imageHome).store(PHOTO_URL).shouldBeNull()
            }
            contributorFiles(imageHome) shouldBe emptyList()
        }

        test("an oversized photo is rejected and no file is written") {
            val imageHome = tempImageHome("photo-oversize-")

            runTest {
                ContributorPhotoFiles(fetcherServing(ONE_PX_PNG), imageHome, maxBytes = TINY_PHOTO_CEILING_BYTES)
                    .store(PHOTO_URL)
                    .shouldBeNull()
            }
            contributorFiles(imageHome) shouldBe emptyList()
        }

        test("a valid photo is stored under its content-addressed name") {
            val imageHome = tempImageHome("photo-valid-")
            val sha = hashBytesSha256(ONE_PX_PNG)

            runTest {
                // The SHA is the filename, not the contributor id: the path is the cache version a client keys
                // on, so a new photo must produce a new path.
                ContributorPhotoFiles(fetcherServing(ONE_PX_PNG), imageHome).store(PHOTO_URL) shouldBe
                    "contributors/$sha.png"
            }
            contributorFiles(imageHome) shouldBe listOf("$sha.png")
        }
    })

private const val PHOTO_URL = "https://img.example/p.jpg"

/** Smaller than [ONE_PX_PNG], so the store's cap is what refuses it. */
private const val TINY_PHOTO_CEILING_BYTES = 8L

/** A 1×1 PNG — the smallest input that survives the store's magic-number sniff. */
private val ONE_PX_PNG: ByteArray =
    java.util.Base64
        .getDecoder()
        .decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==",
        )

private fun tempImageHome(prefix: String): Path = Path(Files.createTempDirectory(prefix).toString())

/** A [RemoteImageFetcher] whose every request answers with [bytes], declared as [contentType]. */
private fun fetcherServing(
    bytes: ByteArray,
    contentType: ContentType = ContentType.Image.PNG,
): RemoteImageFetcher =
    RemoteImageFetcher(
        HttpClient(
            MockEngine {
                respond(
                    content = bytes,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, contentType.toString()),
                )
            },
        ),
    )

/** Every file currently sitting in `<imageHome>/contributors`, or empty when the dir is absent. */
private fun contributorFiles(imageHome: Path): List<String> {
    val dir = Path(imageHome.toString(), "contributors")
    if (!SystemFileSystem.exists(dir)) return emptyList()
    return SystemFileSystem.list(dir).map { it.name }.sorted()
}
