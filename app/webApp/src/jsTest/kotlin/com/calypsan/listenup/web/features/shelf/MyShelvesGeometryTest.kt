package com.calypsan.listenup.web.features.shelf

import com.calypsan.listenup.client.presentation.shelf.MyShelvesUiState
import com.calypsan.listenup.web.InShell
import com.calypsan.listenup.web.PHONE
import com.calypsan.listenup.web.ViewportFrames
import com.calypsan.listenup.web.features.home.shelf
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual

/** The smallest air between the page header and the first card: the page column's own gap. */
private const val MIN_HEADER_GAP = 12.0

private const val DESKTOP = 1440

/** `/shelves` measured in the real shell: the header and the grid are separate blocks, never touching. */
class MyShelvesGeometryTest :
    FunSpec({
        val frames = ViewportFrames()
        afterTest { frames.disposeAll() }

        listOf(PHONE, DESKTOP).forEach { width ->
            test("at $width the first card sits clear of the header and its New shelf button") {
                val frame =
                    frames.mount(width) {
                        InShell {
                            MyShelvesPage(
                                MyShelvesUiState.Ready(listOf(shelf("Winter"), shelf("Mood"))),
                                onOpenShelf = {},
                                onCreateShelf = {},
                                onRetry = {},
                            )
                        }
                    }
                val header = frame.rect(frame.find(".page-h"))
                val firstCard = frame.rect(frame.find(".home-shelf"))
                withClue("header bottom ${header.bottom}, first card top ${firstCard.top}") {
                    firstCard.top - header.bottom shouldBeGreaterThanOrEqual MIN_HEADER_GAP
                }
            }
        }
    })
