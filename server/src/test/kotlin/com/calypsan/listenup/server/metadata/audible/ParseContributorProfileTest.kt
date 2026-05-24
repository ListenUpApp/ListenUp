package com.calypsan.listenup.server.metadata.audible

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * Unit tests for [parseContributorProfile] — the internal HTML scraping function
 * that extracts an [AudibleContributorProfile] from an Audible author page.
 *
 * Tests use minimal inline HTML fixtures rather than full page snapshots to
 * stay independent of Audible's ever-changing page structure. Each test
 * exercises one extraction path.
 */
class ParseContributorProfileTest :
    FunSpec({
        val asin = "B000APZOQA"

        test("returns null when no h1.bc-heading is present") {
            val html = "<html><body><p>Not an author page</p></body></html>"
            parseContributorProfile(html, asin).shouldBeNull()
        }

        test("extracts name from h1 with class bc-heading") {
            val html =
                """
                <html><body>
                <h1 class="bc-heading">Frank Herbert</h1>
                </body></html>
                """.trimIndent()
            val profile = parseContributorProfile(html, asin).shouldNotBeNull()
            profile.name shouldBe "Frank Herbert"
        }

        test("strips HTML tags from name") {
            val html =
                """
                <html><body>
                <h1 class="bc-heading"><span>Frank Herbert</span></h1>
                </body></html>
                """.trimIndent()
            val profile = parseContributorProfile(html, asin).shouldNotBeNull()
            profile.name shouldBe "Frank Herbert"
        }

        test("extracts biography from bc-expander-content element") {
            val html =
                """
                <html><body>
                <h1 class="bc-heading">Frank Herbert</h1>
                <div class="bc-expander-content">Author of Dune.</div>
                </body></html>
                """.trimIndent()
            val profile = parseContributorProfile(html, asin).shouldNotBeNull()
            profile.biography shouldBe "Author of Dune."
        }

        test("biography is empty string when bc-expander-content is absent") {
            val html =
                """
                <html><body>
                <h1 class="bc-heading">Frank Herbert</h1>
                </body></html>
                """.trimIndent()
            val profile = parseContributorProfile(html, asin).shouldNotBeNull()
            profile.biography shouldBe ""
        }

        test("extracts imageUrl from og:image meta content attribute") {
            val html =
                """
                <html>
                <head><meta property="og:image" content="https://images.example.com/frank.jpg"></head>
                <body><h1 class="bc-heading">Frank Herbert</h1></body>
                </html>
                """.trimIndent()
            val profile = parseContributorProfile(html, asin).shouldNotBeNull()
            profile.imageUrl shouldBe "https://images.example.com/frank.jpg"
        }

        test("imageUrl is empty string when og:image contains Facebook_Placement placeholder") {
            val html =
                """
                <html>
                <head><meta property="og:image" content="https://cdn.example.com/Facebook_Placement_default.jpg"></head>
                <body><h1 class="bc-heading">Frank Herbert</h1></body>
                </html>
                """.trimIndent()
            val profile = parseContributorProfile(html, asin).shouldNotBeNull()
            // og:image filtered out, no fallback img → empty
            profile.imageUrl shouldBe ""
        }

        test("falls back to author-image-outline img src when og:image is absent") {
            val html =
                """
                <html><body>
                <h1 class="bc-heading">Frank Herbert</h1>
                <img class="author-image-outline" src="https://cdn.example.com/frank_photo.jpg">
                </body></html>
                """.trimIndent()
            val profile = parseContributorProfile(html, asin).shouldNotBeNull()
            profile.imageUrl shouldBe "https://cdn.example.com/frank_photo.jpg"
        }

        test("falls back to author-image-outline img when og:image is a placeholder") {
            val html =
                """
                <html>
                <head><meta property="og:image" content="https://cdn.example.com/Facebook_Placement.jpg"></head>
                <body>
                <h1 class="bc-heading">Frank Herbert</h1>
                <img class="author-image-outline" src="https://cdn.example.com/frank_photo.jpg">
                </body></html>
                """.trimIndent()
            val profile = parseContributorProfile(html, asin).shouldNotBeNull()
            profile.imageUrl shouldBe "https://cdn.example.com/frank_photo.jpg"
        }

        test("asin is preserved from the argument") {
            val html = "<html><body><h1 class=\"bc-heading\">Frank Herbert</h1></body></html>"
            val profile = parseContributorProfile(html, asin).shouldNotBeNull()
            profile.asin shouldBe asin
        }

        test("decodes HTML entities in name and biography") {
            val html =
                """
                <html><body>
                <h1 class="bc-heading">Frank &amp; Herbert</h1>
                <div class="bc-expander-content">Author &lt;Dune&gt; series.</div>
                </body></html>
                """.trimIndent()
            val profile = parseContributorProfile(html, asin).shouldNotBeNull()
            profile.name shouldBe "Frank & Herbert"
            profile.biography shouldBe "Author <Dune> series."
        }
    })
