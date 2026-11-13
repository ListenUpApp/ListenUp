package com.calypsan.listenup.web.features.bookdetail

import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.readers.BookReaders
import com.calypsan.listenup.client.presentation.bookdetail.BookReadersUiState
import com.calypsan.listenup.client.presentation.bookdetail.ChapterReaderFace
import com.calypsan.listenup.client.presentation.bookdetail.ChapterReaders
import com.calypsan.listenup.client.presentation.bookdetail.ChapterUiModel
import com.calypsan.listenup.client.presentation.bookdetail.ReadersPlace
import com.calypsan.listenup.client.presentation.bookdetail.groupedChapters
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.features.readers.READERS_NOW
import com.calypsan.listenup.web.features.readers.ReadersPanel
import com.calypsan.listenup.web.features.readers.reader
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.delay
import org.jetbrains.compose.web.dom.Div
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList
import org.w3c.dom.css.CSSMediaRule
import org.w3c.dom.css.CSSStyleRule
import org.w3c.dom.css.CSSStyleSheet

private const val SPAN_MS = 600_000L

internal fun face(
    name: String,
    isLive: Boolean = false,
) = ChapterReaderFace(userId = "u-$name", displayName = name, isLive = isLive)

/**
 * Readers placed by chapter id, standing in for the shared projection (which only sharedLogic can build): each
 * chapter's readers on its row, a part's on its header while closed, and everyone else on "Show all".
 */
internal class PlacedReaders(
    private val byChapter: Map<String, List<ChapterReaderFace>>,
    private val byPart: Map<String, List<ChapterReaderFace>> = emptyMap(),
) : ChapterReadersLookup {
    override fun onChapter(chapterId: String) = byChapter[chapterId]?.let { ChapterReaders(ReadersPlace.ThisChapter, it) }

    override fun inPart(partId: String) = byPart[partId]?.let { ChapterReaders(ReadersPlace.ThisPart, it) }

    override fun beyond(shownChapterIds: List<String>) =
        byChapter
            .filterKeys { it !in shownChapterIds }
            .values
            .flatten()
            .takeIf { it.isNotEmpty() }
            ?.let { ChapterReaders(ReadersPlace.FurtherOn, it) }
}

/** Book Detail's chapter list with everyone else on it: the avatars, the sentence, the live glow, and the Readers ring. */
class ChapterReadersTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        val flat =
            (0 until 12).map { i ->
                ChapterUiModel(
                    id = "c$i",
                    title = "Chapter ${i + 1}",
                    duration = "10:00",
                    imageUrl = null,
                    isCurrent = i == 2,
                    startMs = i * SPAN_MS,
                    durationMs = SPAN_MS,
                )
            }

        fun mount(
            readers: ChapterReadersLookup,
            wide: Boolean = false,
        ): HTMLElement =
            mounts.mount {
                BookChaptersSection(
                    bookId = "42",
                    chapters = flat,
                    preview = flat.take(5),
                    canPlay = true,
                    wide = wide,
                    onPlayFromChapter = {},
                    onOpenChapterMap = {},
                    readers = readers,
                )
            }

        fun row(
            root: HTMLElement,
            number: Int,
        ) = root.querySelector("button[aria-label='Play from chapter $number: Chapter $number']") as HTMLButtonElement

        fun describedBy(
            root: HTMLElement,
            button: HTMLElement,
        ): String? = button.getAttribute("aria-describedby")?.let { id -> root.querySelector("[id='$id']")?.textContent }

        test("a chapter's readers sit between its title and its length, hidden from screen readers") {
            val root = mount(PlacedReaders(mapOf("c1" to listOf(face("Jonah")))))

            val button = row(root, 2)
            button.children
                .asList()
                .map { it.firstClass() }
                .shouldContainExactly("bd-ch-n", "bd-ch-t", "bd-chr", "bd-ch-d", "bd-ch-play")
            val stack = button.querySelector(".bd-chr") as HTMLElement
            stack.getAttribute("aria-hidden") shouldBe "true"
            stack.getAttribute("title") shouldBe "Jonah"
            (stack.querySelector(".uav") as HTMLElement).getBoundingClientRect().width shouldBe 24.0
        }

        test("the readers leave the row's height alone") {
            val bare = row(mount(PlacedReaders(emptyMap())), 2).getBoundingClientRect().height
            val withReaders =
                row(mount(PlacedReaders(mapOf("c1" to listOf(face("Jonah"), face("Priya"))))), 2)
                    .getBoundingClientRect()
                    .height

            withReaders shouldBe bare
        }

        test("on a desktop the readers follow the title in its cell") {
            val root = mount(PlacedReaders(mapOf("c1" to listOf(face("Jonah")))), wide = true)

            row(root, 2)
                .children
                .asList()
                .map { it.firstClass() }
                .shouldContainExactly("bd-ch-play", "bd-ch-t", "bd-chr")
        }

        test("three readers all show; a fourth turns the third into a +N disc, with every name on hover") {
            val three = listOf(face("Priya"), face("Tom"), face("Sam"))
            val root =
                mount(PlacedReaders(mapOf("c1" to three, "c3" to three + face("Lee") + face("Ann"))))

            row(root, 2).querySelectorAll(".bd-chr .uav").length shouldBe 3
            row(root, 2).querySelector(".bd-chr-more").shouldBeNull()

            val crowded = row(root, 4).querySelector(".bd-chr") as HTMLElement
            crowded.querySelectorAll(".uav").length shouldBe 2
            crowded.querySelector(".bd-chr-more")?.textContent shouldBe "+3"
            crowded.getAttribute("title") shouldBe "Priya, Tom, Sam, Lee and Ann"
        }

        test("the row is described by one sentence: who is listening now, then who else is on the chapter") {
            val readers = listOf(face("Maya Ross", isLive = true), face("Priya"), face("Tom"), face("Sam"), face("Lee"))
            val root = mount(PlacedReaders(mapOf("c1" to readers)))

            val button = row(root, 2)
            describedBy(root, button) shouldBe "Maya is listening now. Priya, Tom and 2 others are on this chapter."
            // Still one action: the name is the play, and the avatars are no target of their own.
            button.getAttribute("aria-label") shouldBe "Play from chapter 2: Chapter 2"
            button.querySelectorAll("button, a, [tabindex]").length shouldBe 0
        }

        test("a row nobody else is on carries no avatars and no description") {
            val root = mount(PlacedReaders(mapOf("c1" to listOf(face("Jonah")))))

            row(root, 1).querySelector(".bd-chr").shouldBeNull()
            row(root, 1).hasAttribute("aria-describedby") shouldBe false
        }

        test("a reader listening now has the still coral ring and a glow; the others have neither") {
            val root = mount(PlacedReaders(mapOf("c1" to listOf(face("Maya", isLive = true), face("Tom")))))

            val faces = row(root, 2).querySelectorAll(".bd-chr-f").asList().filterIsInstance<HTMLElement>()
            faces.map { it.classList.contains("is-live") } shouldBe listOf(true, false)
            faces.map { it.querySelector(".bd-chr-glow") != null } shouldBe listOf(true, false)
            val liveRing = window.getComputedStyle(faces[0].querySelector(".uav") as HTMLElement)
            liveRing.outlineStyle shouldBe "solid"
            liveRing.outlineOffset shouldBe "2px"
            liveRing.outlineColor shouldBe "rgb(215, 56, 18)"
        }

        test("every glow breathes on one clock, however late it appears") {
            val live = PlacedReaders(mapOf("c1" to listOf(face("Maya", isLive = true))))
            val first = mount(live)
            delay(CLOCK_OFFSET_MS)
            val second = mount(live)
            awaitFrame()

            // How far into its breath each glow is, in ms.
            fun phase(root: HTMLElement): Double {
                val glow = root.querySelector(".bd-chr-glow").asDynamic()
                return (
                    glow
                        .getAnimations()[0]
                        .effect
                        .getComputedTiming()
                        .progress as Double
                ) * GLOW_CYCLE_MS
            }
            val apart = kotlin.math.abs(phase(second) - phase(first))
            minOf(apart, GLOW_CYCLE_MS - apart) shouldBe (0.0 plusOrMinus CLOCK_TOLERANCE_MS)
        }

        test("asking for less motion stills the glow, halfway out") {
            val rules =
                document.styleSheets
                    .asList()
                    .filterIsInstance<CSSStyleSheet>()
                    .flatMap { it.cssRules.asList() }
            val still =
                rules
                    .filterIsInstance<CSSMediaRule>()
                    .filter { "prefers-reduced-motion: reduce" in it.media.mediaText }
                    .flatMap { it.cssRules.asList() }
                    .filterIsInstance<CSSStyleRule>()
                    .single { it.selectorText == ".bd-chr-glow" }
            still.style.getPropertyValue("animation-name") shouldBe "none"
            still.style.getPropertyValue("transform") shouldBe "scale(1.35)"
            still.style.getPropertyValue("opacity") shouldBe "0.22"
        }

        test("Show all carries everyone on a chapter the collapsed list leaves out, and says where they are") {
            val root =
                mount(
                    PlacedReaders(
                        mapOf("c1" to listOf(face("Jonah")), "c7" to listOf(face("Maya", isLive = true), face("Tom"))),
                    ),
                )

            val all = root.querySelector(".bd-ch-all") as HTMLButtonElement
            all.querySelector(".bd-chr")?.getAttribute("title") shouldBe "Maya (listening now) and Tom"
            describedBy(root, all) shouldBe "Maya is listening now. Tom is on a chapter further on."
            // Beside the button, so the sentence describes it without becoming its name.
            all.querySelector(".sr-only").shouldBeNull()

            all.click()
            awaitFrame()

            all.querySelector(".bd-chr").shouldBeNull()
            all.hasAttribute("aria-describedby") shouldBe false
            row(root, 8).querySelector(".bd-chr").shouldNotBeNull()
        }

        test("Show all carries nothing when everyone's row is already shown") {
            val root = mount(PlacedReaders(mapOf("c1" to listOf(face("Jonah")))))

            val all = root.querySelector(".bd-ch-all") as HTMLButtonElement
            all.querySelector(".bd-chr").shouldBeNull()
            all.hasAttribute("aria-describedby") shouldBe false
        }

        context("a marked book") {
            // Credits, then Part I (its announcement folded) with 1 to 6, then Part II (folded) with 7 to 9.
            val book =
                listOf("Credits", "Part I", "1", "2", "3", "4", "5", "6", "Part II", "7", "8", "9").mapIndexed {
                    i,
                    title,
                    ->
                    Chapter(
                        id = "c$i",
                        title = title,
                        duration = SPAN_MS,
                        startTime = i * SPAN_MS,
                        partTitle = title.takeIf { it.startsWith("Part") },
                    )
                }
            val rows =
                book.mapIndexed { i, chapter ->
                    ChapterUiModel(chapter.id, chapter.title, "10:00", null, i == 3, chapter.startTime, chapter.duration)
                }

            fun mountMarked(readers: ChapterReadersLookup): HTMLElement =
                mounts.mount {
                    BookChaptersSection(
                        bookId = "42",
                        chapters = rows,
                        preview = rows.subList(1, 6),
                        canPlay = true,
                        wide = false,
                        onPlayFromChapter = {},
                        onOpenChapterMap = {},
                        grouped = book.groupedChapters(3),
                        readers = readers,
                    )
                }

            test("a closed part's header carries everyone in it, and says so on the button that opens it") {
                val root =
                    mountMarked(
                        PlacedReaders(
                            byChapter = mapOf("c10" to listOf(face("Tom"))),
                            byPart = mapOf("c8" to listOf(face("Tom"))),
                        ),
                    )
                (root.querySelector(".bd-ch-all") as HTMLButtonElement).click()
                awaitFrame()

                val toggle = root.querySelector("button[data-part='Part II']") as HTMLButtonElement
                toggle.getAttribute("aria-expanded") shouldBe "false"
                toggle.querySelector(".bd-chr").shouldNotBeNull()
                describedBy(root, toggle) shouldBe "Tom is in this part."
            }

            test("a header carries the readers of the chapter folded into it, on its play button") {
                val root = mountMarked(PlacedReaders(byChapter = mapOf("c1" to listOf(face("Priya")))))

                val play = root.querySelector("button[aria-label='Play Part I']") as HTMLButtonElement
                describedBy(root, play) shouldBe "Priya is on this chapter."
                play.parentElement!!.querySelector(".bd-chr").shouldNotBeNull()
            }
        }

        test("the sentence takes each wording, for each place") {
            fun sentence(
                place: ReadersPlace,
                vararg readers: ChapterReaderFace,
            ) = chapterReadersSentence(ChapterReaders(place, readers.toList()))

            sentence(ReadersPlace.ThisChapter, face("Tom")) shouldBe "Tom is on this chapter."
            sentence(ReadersPlace.ThisPart, face("Tom"), face("Sam")) shouldBe "Tom and Sam are in this part."
            sentence(ReadersPlace.FurtherOn, face("Tom"), face("Sam"), face("Lee")) shouldBe
                "Tom, Sam and 1 other are on chapters further on."
            sentence(ReadersPlace.Earlier, face("Tom")) shouldBe "Tom is on an earlier chapter."
            sentence(ReadersPlace.OtherChapters, face("Tom"), face("Sam")) shouldBe "Tom and Sam are on other chapters."
            sentence(
                ReadersPlace.ThisChapter,
                face("Maya", isLive = true),
                face("Ann", isLive = true),
            ) shouldBe "Maya and Ann are listening now."
        }

        test("in the Readers panel, another reader listening now has the live ring and says so") {
            // Inside the app shell's `.luw`, where the Readers rules apply.
            val root =
                mounts.mount {
                    Div(attrs = { classes("luw") }) {
                        ReadersPanel(
                            state =
                                BookReadersUiState.Data(
                                    BookReaders(
                                        listOf(
                                            reader(userId = "u1", displayName = "Maya", progressPct = 20),
                                            reader(userId = "u2", displayName = "Tom", progressPct = 19),
                                        ),
                                    ),
                                    liveUserIds = setOf("u1"),
                                ),
                            nowMs = READERS_NOW,
                            onOpenProfile = {},
                            onSeeAll = {},
                        )
                    }
                }

            val rows = root.querySelectorAll(".rdr-row").asList().filterIsInstance<HTMLElement>()
            rows.map { it.querySelector(".rdr-s")?.textContent } shouldBe listOf("Listening now · 20%", "Listening · 19%")
            rows.map { it.classList.contains("is-live") } shouldBe listOf(true, false)
            val ring = window.getComputedStyle(rows[0].querySelector(".uav") as HTMLElement)
            ring.outlineStyle shouldBe "solid"
            ring.outlineColor shouldBe "rgb(215, 56, 18)"
            window.getComputedStyle(rows[1].querySelector(".uav") as HTMLElement).outlineStyle shouldBe "none"
        }
    })

/** An element's first class; an SVG's `className` is no string, so read the attribute. */
private fun org.w3c.dom.Element.firstClass(): String = getAttribute("class").orEmpty().split(' ').first()

private const val GLOW_CYCLE_MS = 2000.0

/** Long enough that two glows started this far apart would be visibly out of step without the shared clock. */
private const val CLOCK_OFFSET_MS = 700L

private const val CLOCK_TOLERANCE_MS = 60.0
