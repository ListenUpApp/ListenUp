package com.calypsan.listenup.server.metadata.audible

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ParseProductTagsTest :
    FunSpec({
        test("extracts typed topic tags grouped by type from the product page") {
            val tags = parseProductTags(DCC_PRODUCT_TAGS_HTML)

            val byType = tags.groupBy({ it.type }, { it.name })

            byType["mood"]?.toSet() shouldBe setOf("Feel-Good", "Scary", "Witty")
            byType["theme"]?.toSet() shouldBe setOf("Fantasy", "Fiction", "LitRPG", "Survival", "Game")
            byType["genre"]?.toSet() shouldBe setOf("Science Fiction & Fantasy")
            byType["social_media"]?.toSet() shouldBe setOf("BookTok")
            byType["audible_editors"]?.toSet() shouldBe setOf("Editor's Pick")
        }

        test("tolerates ?ref tracking query params after the id segment (#551-style)") {
            val html =
                """<a href="/tag/mood/Witty-Audiobooks/adbl_rec_tag_001?ref=a_pd_Dungeo">Witty</a>"""
            val tags = parseProductTags(html)
            tags shouldBe listOf(ProductTag(type = "mood", name = "Witty"))
        }

        test("dedupes identical type+name anchors that repeat across impressions") {
            val html =
                """
                <a href="/tag/mood/Scary-Audiobooks/adbl_rec_tag_010?ref=x">Scary</a>
                <a href="/tag/mood/Scary-Audiobooks/adbl_rec_tag_010?ref=y">Scary</a>
                """.trimIndent()
            parseProductTags(html) shouldBe listOf(ProductTag(type = "mood", name = "Scary"))
        }

        test("returns empty when the page has no topic-tag anchors") {
            parseProductTags("<html><body><p>nothing here</p></body></html>") shouldBe emptyList()
        }
    })

// ─── Fixture: a small, representative slice of Audible's /pd topic-tag anchors ──

/**
 * Captured shape of Audible's `product-topictag-impression` anchors for
 * *Dungeon Crawler Carl*. A handful of `/tag/{type}/...` anchors covering each
 * observed type — NOT the full 500KB page. Tracking `?ref=...` params are
 * included to exercise query-param tolerance.
 */
private val DCC_PRODUCT_TAGS_HTML =
    """
<div class="product-topictag-impression">
  <a href="/tag/mood/Feel-Good-Audiobooks/adbl_rec_tag_001?ref=a_pd_Dungeo">Feel-Good</a>
  <a href="/tag/mood/Scary-Audiobooks/adbl_rec_tag_002?ref=a_pd_Dungeo">Scary</a>
  <a href="/tag/mood/Witty-Audiobooks/adbl_rec_tag_003?ref=a_pd_Dungeo">Witty</a>
  <a href="/tag/theme/Fantasy-Audiobooks/adbl_rec_tag_004?ref=a_pd_Dungeo">Fantasy</a>
  <a href="/tag/theme/Fiction-Audiobooks/adbl_rec_tag_005?ref=a_pd_Dungeo">Fiction</a>
  <a href="/tag/theme/LitRPG-Audiobooks/adbl_rec_tag_006?ref=a_pd_Dungeo">LitRPG</a>
  <a href="/tag/theme/Survival-Audiobooks/adbl_rec_tag_007?ref=a_pd_Dungeo">Survival</a>
  <a href="/tag/theme/Game-Audiobooks/adbl_rec_tag_008?ref=a_pd_Dungeo">Game</a>
  <a href="/tag/genre/Science-Fiction-Fantasy-Audiobooks/adbl_rec_tag_009?ref=a_pd_Dungeo">Science Fiction &amp; Fantasy</a>
  <a href="/tag/social_media/BookTok-Audiobooks/adbl_rec_tag_010?ref=a_pd_Dungeo">BookTok</a>
  <a href="/tag/audible_editors/Editors-Pick-Audiobooks/adbl_rec_tag_011?ref=a_pd_Dungeo">Editor's Pick</a>
</div>
    """.trimIndent()
