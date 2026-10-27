package com.calypsan.listenup.web.features.home

import androidx.compose.runtime.CompositionLocalProvider
import com.calypsan.listenup.client.domain.model.CoverThemeStyle
import com.calypsan.listenup.client.domain.model.CoverThemes
import com.calypsan.listenup.client.domain.repository.CoverThemeRepository
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.web.awaitFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import com.calypsan.listenup.web.FakeCoverThemes
import com.calypsan.listenup.web.KINGS_THEMES
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.design.LocalCoverThemes
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.TimeZone
import org.w3c.dom.HTMLElement

/** Home spec F8, §8 D3: the just-finished card sits on the cover's wash, as Book Detail does. */
class HomeCoverThemeTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        fun finished(source: CoverThemeRepository?): HTMLElement =
            mounts
                .mount {
                    CompositionLocalProvider(LocalCoverThemes provides source) {
                        HomeFinishedCard(
                            finishedSlot().card.let { it.copy(book = it.book.copy(coverHash = "h1")) },
                            nowMs = BOARD_NOW,
                            actions = HomeActions.None,
                            zone = TimeZone.UTC,
                        )
                    }
                }.querySelector(".home-hero") as HTMLElement

        test("the just-finished card takes the cover's colours") {
            val card = finished(FakeCoverThemes(known = KINGS_THEMES))
            card.classList.contains("cover-themed") shouldBe true
            card.style.getPropertyValue("--cover-wash-l") shouldBe "#E4E4E6"
        }

        test("a card whose book has no cover is never fetched") {
            val requested = mutableListOf<String?>()
            val source =
                object : CoverThemeRepository {
                    override fun cached(
                        bookId: BookId,
                        coverHash: String?,
                        style: CoverThemeStyle,
                    ): CoverThemes? = null

                    override fun observe(
                        bookId: BookId,
                        coverHash: String?,
                        style: CoverThemeStyle,
                    ): Flow<CoverThemes?> {
                        requested += coverHash
                        return flowOf(null)
                    }
                }
            mounts.mount {
                CompositionLocalProvider(LocalCoverThemes provides source) {
                    HomeFinishedCard(
                        finishedSlot().card.let { it.copy(book = it.book.copy(coverHash = null)) },
                        nowMs = BOARD_NOW,
                        actions = HomeActions.None,
                        zone = TimeZone.UTC,
                    )
                }
            }
            awaitFrame()
            requested shouldBe emptyList()
        }

        test("the just-finished card with no source is untouched") {
            val card = finished(null)
            card.classList.contains("cover-themed") shouldBe false
            card.style.getPropertyValue("--cover-wash-l") shouldBe ""
        }
    })
