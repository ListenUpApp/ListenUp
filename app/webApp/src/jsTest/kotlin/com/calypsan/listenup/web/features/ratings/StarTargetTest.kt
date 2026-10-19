package com.calypsan.listenup.web.features.ratings

import com.calypsan.listenup.client.presentation.bookdetail.BookRatingsUiState
import com.calypsan.listenup.web.PHONE
import com.calypsan.listenup.web.Pointer
import com.calypsan.listenup.web.ViewportFrames
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe

/**
 * "Your rating" keeps its slider (half stars, keyboard steps; plan Decision 7), and each star is a 44 × 44 px target on
 * a phone (spec §5.2, WCAG 2.5.8): measured, so the floor in `22-readers.css` cannot quietly go.
 */
class StarTargetTest :
    FunSpec({
        val frames = ViewportFrames()
        afterTest { frames.disposeAll() }

        test("each of your rating's stars is at least 44 by 44 px on a phone") {
            val frame =
                frames.mount(width = PHONE, pointer = Pointer.Touch) {
                    RatingsPanel(
                        state =
                            BookRatingsUiState.Ready(
                                listeners = null,
                                mine = null,
                                external = null,
                                breakdown = emptyList(),
                                canRefresh = false,
                                isRefreshingExternal = false,
                                isCheckingExternal = false,
                            ),
                        onSetStars = {},
                        onRate = { _, _ -> },
                        onClear = {},
                    )
                }
            awaitFrame()
            val stars = frame.findAll(".rt-you .rs-s")
            stars.size shouldBe 5
            stars.forEach { star ->
                val box = frame.rect(star)
                box.width shouldBeGreaterThanOrEqual 44.0
                box.height shouldBeGreaterThanOrEqual 44.0
            }
            frame.find(".rt-you [role=slider]").getAttribute("aria-valuetext") shouldBe "Not rated"
        }
    })
