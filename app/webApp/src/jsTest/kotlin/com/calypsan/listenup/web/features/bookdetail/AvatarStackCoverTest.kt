package com.calypsan.listenup.web.features.bookdetail

import com.calypsan.listenup.client.presentation.bookdetail.ChapterReaders
import com.calypsan.listenup.client.presentation.bookdetail.ReaderRef
import com.calypsan.listenup.client.presentation.bookdetail.ReadersPlace
import com.calypsan.listenup.client.presentation.bookdetail.ReadersSummary
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.design.AvatarCover
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList

/** Text measures in fractions of a pixel; a glyph's side bearing can sit a hair past its box. */
private const val SUBPIXEL = 0.5

private suspend fun awaitUntil(
    what: String,
    condition: () -> Boolean,
) {
    try {
        withTimeout(6_000) { while (!condition()) delay(16) }
    } catch (timeout: TimeoutCancellationException) {
        throw AssertionError("timed out waiting for: $what", timeout)
    }
}

private fun HTMLElement.all(selector: String): List<HTMLElement> = querySelectorAll(selector).asList().map { it as HTMLElement }

/**
 * A face tucked under its neighbour keeps its whole initials: they sit in the part of the circle left showing, not
 * centred under the circle on top, where a letter was lost.
 */
class AvatarStackCoverTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        test("the first face is uncovered; the next loses the overlap and cut, or the tuck and live ring") {
            val plain = listOf(face("Maya Wren"), face("Tom Okafor"))
            coverAt(plain, 0) shouldBe AvatarCover.None
            coverAt(plain, 1) shouldBe AvatarCover(start = 8)
            coverAt(listOf(face("Priya Shah", isLive = true), face("Tom Okafor")), 1) shouldBe AvatarCover(start = 6)
            // The +N disc after the last face.
            coverAt(plain, 2) shouldBe AvatarCover(start = 8)
        }

        test("in a chapter row's stack, each tucked label sits clear of the circle on top of it") {
            val root =
                mounts.mount {
                    ChapterReadersStack(
                        ChapterReaders(
                            ReadersPlace.ThisChapter,
                            listOf(face("Walt Moss"), face("Maya Wren"), face("Tom Okafor"), face("Ann Lee")),
                        ),
                    )
                }
            // Four readers draw two faces and a +2 disc: the second face and the disc are tucked.
            awaitUntil("the tucked face's monogram") { root.all(".uav-covered").size == 2 }
            val circles = root.all(".bd-chr-f > .uav, .bd-chr-more")
            circles.size shouldBe 3
            root.all(".uav-covered > span").forEachIndexed { i, label ->
                val text = label.getBoundingClientRect()
                // The 2px cut round the circle on top reaches past its edge.
                text.left.shouldBeGreaterThanOrEqual(circles[i].getBoundingClientRect().right + 2 - SUBPIXEL)
                text.right.shouldBeLessThanOrEqual(circles[i + 1].getBoundingClientRect().right + SUBPIXEL)
            }
        }

        test("on the hero's social line, each label sits clear of the face over its end") {
            val refs = listOf("Dan Reyes", "Hal Burke", "Walt Moss").map { ReaderRef(it.lowercase(), it) }
            val root = mounts.mount { HeroSocialLine(ReadersSummary(refs.take(2), refs.drop(2), refs, 0)) }
            awaitUntil("the covered faces' monograms") { root.all(".uav-covered").size == 2 }
            val faces = root.all(".bd-social-avatars > .uav")
            root.all(".uav-covered > span").forEachIndexed { i, label ->
                val text = label.getBoundingClientRect()
                // The next face's 2px ring reaches past its edge.
                text.right.shouldBeLessThanOrEqual(faces[i + 1].getBoundingClientRect().left - 2 + SUBPIXEL)
                text.left.shouldBeGreaterThanOrEqual(faces[i].getBoundingClientRect().left - SUBPIXEL)
            }
        }
    })
