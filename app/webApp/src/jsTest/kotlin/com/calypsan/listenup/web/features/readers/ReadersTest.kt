package com.calypsan.listenup.web.features.readers

import com.calypsan.listenup.client.domain.readers.BookReaders
import com.calypsan.listenup.client.domain.readers.Reader
import com.calypsan.listenup.client.domain.readers.ReaderLineKind
import com.calypsan.listenup.client.presentation.bookdetail.BookReadersUiState
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList

private const val DAY = 86_400_000L

internal const val READERS_NOW = 1_800_000_000_000L

internal fun reader(
    userId: String = "u1",
    displayName: String = "Ada Lovelace",
    isYou: Boolean = false,
    progressPct: Int? = null,
    finishes: List<Long> = emptyList(),
) = Reader(
    userId = userId,
    displayName = displayName,
    isYou = isYou,
    currentProgressPct = progressPct,
    finishes = finishes,
)

internal fun readersData(vararg readers: Reader) = BookReadersUiState.Data(BookReaders(readers.toList()))

private fun rows(host: HTMLElement): List<HTMLElement> = host.querySelectorAll(".rdr-row").asList().filterIsInstance<HTMLElement>()

private fun text(
    host: HTMLElement,
    selector: String,
): String? = (host.querySelector(selector) as? HTMLElement)?.textContent?.trim()

private fun button(
    host: HTMLElement,
    label: String,
): HTMLButtonElement? =
    host
        .querySelectorAll("button")
        .asList()
        .filterIsInstance<HTMLButtonElement>()
        .firstOrNull { it.textContent?.trim() == label }

/**
 * The Readers surfaces — the Book Detail side panel and the full `/book/{id}/readers` list.
 *
 * What these pin: the panel is **silent** when it has nothing to say (Loading and Error draw
 * nothing, because a slow readership call must not make a book's page look broken) while the page
 * — which someone navigated to on purpose — answers for every state; a reader on their third pass
 * is three lines, not one with a tally; and "You" is what the current user is called.
 */
class ReadersTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        fun panel(
            state: BookReadersUiState,
            onOpenProfile: (String) -> Unit = {},
            onSeeAll: () -> Unit = {},
        ): HTMLElement =
            mounts.mount {
                ReadersPanel(state = state, nowMs = READERS_NOW, onOpenProfile = onOpenProfile, onSeeAll = onSeeAll)
            }

        fun page(
            state: BookReadersUiState,
            onOpenProfile: (String) -> Unit = {},
            onOpenBook: () -> Unit = {},
        ): HTMLElement =
            mounts.mount {
                ReadersPage(
                    state = state,
                    bookTitle = "The Way of Kings",
                    nowMs = READERS_NOW,
                    onOpenProfile = onOpenProfile,
                    onOpenBook = onOpenBook,
                )
            }

        test("the panel names each reader and what they are doing") {
            val host =
                panel(
                    readersData(
                        reader(userId = "u1", displayName = "Ada Lovelace", progressPct = 42),
                        reader(userId = "u2", displayName = "Grace Hopper", finishes = listOf(READERS_NOW - DAY)),
                    ),
                )

            rows(host).map { it.textContent?.trim() } shouldContainExactly
                listOf("Ada Lovelace42% through", "Grace HopperFinished yesterday")
        }

        test("a reader with no progress and a finish is shown as finished, not as both") {
            // `flattenToLines` keys a Reading line on a non-null percentage, so this reader has
            // only the finish. The no-percentage Reading case is pinned directly below — it cannot
            // be reached through the panel, and that is exactly why it needs its own spec.
            val host = panel(readersData(reader(progressPct = null, finishes = listOf(READERS_NOW - DAY))))

            rows(host).map { it.textContent?.trim() } shouldContainExactly listOf("Ada LovelaceFinished yesterday")
        }

        test("reading with no known position still says so") {
            // ⛔ Presence says they are on the book; the position simply has not synced. An empty
            // second line would read as missing data rather than as a reader we know less about.
            //
            // Asserted on the helper, not through the panel: `flattenToLines` only builds a Reading
            // line from a non-null percentage today, so no fixture can drive this through the DOM.
            // The arm is still live — the type says `Int?` — and a spec that could not fail here
            // would be no spec at all.
            stateLine(ReaderLineKind.Reading(null), READERS_NOW) shouldBe "Listening now"
            stateLine(ReaderLineKind.Reading(42), READERS_NOW) shouldBe "42% through"
            stateLine(ReaderLineKind.Finished(READERS_NOW - DAY), READERS_NOW) shouldBe "Finished yesterday"
        }

        test("the current user is You, wherever they sit in the list") {
            val host =
                panel(
                    readersData(
                        reader(userId = "u2", displayName = "Grace Hopper", progressPct = 10),
                        reader(userId = "u1", displayName = "Ada Lovelace", isYou = true, progressPct = 80),
                    ),
                )

            rows(host).map { it.textContent?.trim() } shouldContainExactly
                listOf("Grace Hopper10% through", "You80% through")
        }

        test("someone reading now is counted, and nobody reading says nothing at all") {
            val reading =
                panel(readersData(reader(userId = "u1", progressPct = 10), reader(userId = "u2", progressPct = 90)))
            text(reading, ".rdr-now") shouldBe "2 listening now"

            val finished = panel(readersData(reader(finishes = listOf(READERS_NOW - DAY))))
            finished.querySelector(".rdr-now").shouldBeNull()
        }

        test("a re-read is a line per finish, newest first — not a tally") {
            // ⛔ "Finished twice" collapses a re-read into a statistic. The dates are the part
            // worth showing, which is why `flattenToLines` flattens finishes individually.
            val host =
                panel(
                    readersData(
                        reader(
                            displayName = "Ada Lovelace",
                            finishes = listOf(READERS_NOW - DAY, READERS_NOW - 40 * DAY),
                        ),
                    ),
                )

            rows(host).map { it.textContent?.trim() } shouldContainExactly
                listOf("Ada LovelaceFinished yesterday", "Ada LovelaceFinished December 2026")
        }

        test("the panel caps at five and offers the rest") {
            var sawAll = 0
            val many = (1..7).map { reader(userId = "u$it", displayName = "Reader $it", progressPct = it) }
            val host = panel(readersData(*many.toTypedArray()), onSeeAll = { sawAll++ })

            rows(host).size shouldBe 5
            button(host, "See all").shouldNotBeNull().click()
            awaitFrame()

            sawAll shouldBe 1
        }

        test("five or fewer is the whole list, and offers nothing more") {
            val host = panel(readersData(*(1..5).map { reader(userId = "u$it", progressPct = it) }.toTypedArray()))

            rows(host).size shouldBe 5
            button(host, "See all").shouldBeNull()
        }

        test("clicking a reader opens that person") {
            val opened = mutableListOf<String>()
            val host = panel(readersData(reader(userId = "u-ada", progressPct = 3)), onOpenProfile = { opened += it })

            rows(host).first().click()
            awaitFrame()

            opened shouldContainExactly listOf("u-ada")
        }

        // ⛔ The panel is non-critical: a book's page is not broken because the readership call is
        // slow or the mirror is unreadable, and a panel saying so would make it look like it was.
        test("the panel says nothing at all while loading, on error, or with nobody reading") {
            panel(BookReadersUiState.Loading).querySelector(".rdr-row").shouldBeNull()
            panel(BookReadersUiState.Loading).textContent?.trim() shouldBe ""
            panel(BookReadersUiState.Error(isRetryable = true)).textContent?.trim() shouldBe ""
            panel(BookReadersUiState.NoReaders).textContent?.trim() shouldBe ""
            panel(readersData()).textContent?.trim() shouldBe ""
        }

        // ⛔ The page is the opposite: someone followed a link asking for exactly this list, so an
        // empty page would be the lie the panel's silence is not.
        test("the page answers for every state the panel stays quiet about") {
            page(BookReadersUiState.Loading).querySelector(".rdr-skel").shouldNotBeNull()
            text(page(BookReadersUiState.NoReaders), ".rdr-none") shouldBe "Nobody has started this book yet."
            text(page(readersData()), ".rdr-none") shouldBe "Nobody has started this book yet."
            text(page(BookReadersUiState.Error(isRetryable = true)), ".rdr-none") shouldBe
                "Couldn't load who is reading this. Try again in a moment."
        }

        test("the page is uncapped, and leads back to the book it belongs to") {
            var back = 0
            val many = (1..7).map { reader(userId = "u$it", displayName = "Reader $it", progressPct = it) }
            val host = page(readersData(*many.toTypedArray()), onOpenBook = { back++ })

            rows(host).size shouldBe 7
            (host.querySelector(".crumb a") as HTMLElement).textContent?.trim() shouldBe "The Way of Kings"
            (host.querySelector(".crumb a") as HTMLElement).click()
            awaitFrame()

            back shouldBe 1
        }
    })
