package com.calypsan.listenup.client.navigation

import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategyScope
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Which back stacks the list-detail strategy pairs, and at which widths. Below the two-pane
 * breakpoint it must never claim a stack — that is what keeps compact windows exactly as they were.
 */
class ListDetailSceneStrategyTest :
    FunSpec({
        val shell = entry(Shell)
        val series = entry(SeriesDetail("s1"), ListDetailScene.listPane())
        val book = entry(BookDetail("b1"), ListDetailScene.detailPane())

        test("a book opened from a series sits beside it on a wide window") {
            val scene = calculate(width = 1280, shell, series, book).shouldBeInstanceOf<ListDetailScene<NavKey>>()

            scene.listEntry shouldBe series
            scene.detailEntry shouldBe book
            scene.entries shouldBe listOf(series, book)
            scene.previousEntries shouldBe listOf(shell, series)
        }

        test("the scene is keyed by its list so swapping the book keeps the same scene") {
            val other = entry(BookDetail("b2"), ListDetailScene.detailPane())

            val first = calculate(width = 1280, shell, series, book)!!
            val second = calculate(width = 1280, shell, series, other)!!

            first.key shouldBe series.contentKey
            second.key shouldBe first.key
        }

        test("the pairing starts at the two-pane breakpoint") {
            calculate(width = 960, shell, series, book).shouldBeInstanceOf<ListDetailScene<NavKey>>()
        }

        test("below the two-pane breakpoint nothing is paired") {
            calculate(width = 959, shell, series, book).shouldBeNull()
            calculate(width = 400, shell, series, book).shouldBeNull()
        }

        test("a list on top of the stack is not paired with anything") {
            calculate(width = 1280, shell, series).shouldBeNull()
        }

        test("a book opened from a screen that is not a list stays full screen") {
            calculate(width = 1280, shell, book).shouldBeNull()
        }

        test("a list buried under another screen never pairs with a later book") {
            val profile = entry(UserProfile("u1"))

            calculate(width = 1280, shell, series, book, profile, entry(BookDetail("b2"), ListDetailScene.detailPane()))
                .shouldBeNull()
        }
    })

private fun entry(
    key: NavKey,
    metadata: Map<String, Any> = emptyMap(),
): NavEntry<NavKey> = NavEntry(key, metadata = metadata) {}

private fun calculate(
    width: Int,
    vararg entries: NavEntry<NavKey>,
): Scene<NavKey>? =
    with(ListDetailSceneStrategy<NavKey>(windowWidth = width.dp)) {
        SceneStrategyScope<NavKey>().calculateScene(entries.toList())
    }
