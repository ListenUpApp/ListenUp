package com.calypsan.listenup.web.features.admin

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.imports.AbsItemRef
import com.calypsan.listenup.api.dto.imports.AbsUserMatch
import com.calypsan.listenup.api.dto.imports.ImportAnalysis
import com.calypsan.listenup.api.dto.imports.ImportResult
import com.calypsan.listenup.api.dto.imports.MatchTier
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.presentation.admin.imports.BookSearchHit
import com.calypsan.listenup.client.presentation.admin.imports.BookSearchState
import com.calypsan.listenup.client.presentation.admin.imports.ImportFlowUiState
import com.calypsan.listenup.core.AbsItemId
import com.calypsan.listenup.core.AbsUserId
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList

private val hosts = mutableListOf<HTMLElement>()

internal fun absUser(
    id: String = "au1",
    username: String = "ada",
    email: String? = "ada@example.com",
): AbsUserMatch =
    AbsUserMatch(
        absUserId = AbsUserId(id),
        absUsername = username,
        absEmail = email,
        suggestedUserId = null,
        confidence = MatchTier.STRONG,
    )

internal fun absItem(
    id: String = "ai1",
    title: String = "Elantris",
    relPath: String? = "Sanderson/Elantris",
): AbsItemRef = AbsItemRef(absItemId = AbsItemId(id), title = title, asin = null, isbn = null, relPath = relPath)

internal fun listenupUser(
    id: String = "u1",
    displayName: String? = "Ada",
): AdminUserInfo =
    AdminUserInfo(
        id = id,
        email = "$id@example.com",
        displayName = displayName,
        firstName = null,
        lastName = null,
        isRoot = false,
        role = "member",
        status = "active",
        createdAt = "2026-01-01T00:00:00Z",
    )

@Suppress("LongParameterList")
internal fun review(
    userMatches: List<AbsUserMatch> = listOf(absUser()),
    ambiguous: List<AbsItemRef> = emptyList(),
    unmatched: List<AbsItemRef> = emptyList(),
    importableSessionCount: Int = 40,
    userMappings: Map<AbsUserId, UserId> = emptyMap(),
    skippedUsers: Set<AbsUserId> = emptySet(),
    bookOverrides: Map<AbsItemId, BookId?> = emptyMap(),
    listenupUsers: List<AdminUserInfo> = listOf(listenupUser()),
    bookSearch: BookSearchState? = null,
): ImportFlowUiState.Review =
    ImportFlowUiState.Review(
        analysis =
            ImportAnalysis(
                userMatches = userMatches,
                bookMatchCounts = emptyMap(),
                ambiguous = ambiguous,
                unmatched = unmatched,
                importableSessionCount = importableSessionCount,
            ),
        userMappings = userMappings,
        skippedUsers = skippedUsers,
        bookOverrides = bookOverrides,
        listenupUsers = listenupUsers,
        bookSearch = bookSearch,
    )

internal fun importResult(
    importedCount: Int = 120,
    sessionsImported: Int = 40,
    booksNotInLibrary: Int = 0,
): ImportResult =
    ImportResult(
        importedCount = importedCount,
        sessionsImported = sessionsImported,
        booksNotInLibrary = booksNotInLibrary,
        perUser = emptyMap(),
    )

@Suppress("LongParameterList")
private fun page(
    state: ImportFlowUiState,
    onMapUser: (AbsUserMatch, UserId) -> Unit = { _, _ -> },
    onSkipUser: (AbsUserMatch) -> Unit = {},
    onOpenBookSearch: (AbsItemId) -> Unit = {},
    onCloseBookSearch: () -> Unit = {},
    onBookSearchQuery: (String) -> Unit = {},
    onSelectBook: (AbsItemId, BookId) -> Unit = { _, _ -> },
    onSkipBook: (AbsItemId) -> Unit = {},
    onApply: () -> Unit = {},
    onReset: () -> Unit = {},
    onOpenImports: () -> Unit = {},
): HTMLElement {
    val host = document.createElement("div") as HTMLElement
    document.body!!.appendChild(host)
    hosts += host
    renderComposable(root = host) {
        ImportFlowPage(
            state = state,
            onStart = {},
            onMapUser = onMapUser,
            onSkipUser = onSkipUser,
            onOpenBookSearch = onOpenBookSearch,
            onCloseBookSearch = onCloseBookSearch,
            onBookSearchQuery = onBookSearchQuery,
            onSelectBook = onSelectBook,
            onSkipBook = onSkipBook,
            onApply = onApply,
            onReset = onReset,
            onOpenImports = onOpenImports,
        )
    }
    return host
}

private fun labelled(
    host: HTMLElement,
    label: String,
) = host
    .querySelectorAll("button[aria-label]")
    .asList()
    .filterIsInstance<HTMLElement>()
    .firstOrNull { it.getAttribute("aria-label") == label }

private fun button(
    host: HTMLElement,
    label: String,
) = host
    .querySelectorAll("button")
    .asList()
    .filterIsInstance<HTMLElement>()
    .firstOrNull { it.textContent?.trim() == label }

/**
 * The import flow.
 *
 * What these pin: there is no way off the page once a file is in flight, because the ViewModel has
 * no step-back transition to offer; an undecided listener is named as skipped rather than left
 * implicit, since the ViewModel treats it that way and an admin who missed a row would otherwise
 * find out afterwards; and every phase says something different, including the two that carry live
 * counts.
 */
class ImportFlowPageTest :
    FunSpec({

        afterSpec {
            hosts.forEach { it.remove() }
            hosts.clear()
        }

        test("the idle page explains itself before asking for a file") {
            val host = page(ImportFlowUiState.Idle)

            host.textContent.orEmpty() shouldContain "you decide what is written before anything is"
            host.querySelector("#iflow-file-input").shouldNotBeNull()
        }

        // ⛔ The ViewModel's own KDoc: "a destructive pipeline, not a wizard with a back button".
        // Leaving mid-flight is the one thing the states cannot recover from.
        test("the way out exists only where leaving is harmless") {
            val harmless =
                listOf(
                    ImportFlowUiState.Idle,
                    ImportFlowUiState.Done(importResult()),
                    ImportFlowUiState.Error(InternalError(debugInfo = "boom")),
                )
            val inFlight =
                listOf(
                    ImportFlowUiState.Uploading("backup.zip"),
                    ImportFlowUiState.Analyzing(1, 10, null, 0, 0),
                    review(),
                    ImportFlowUiState.Applying(1, 10, null, 0),
                )

            harmless.forEach { page(it).querySelector(".iflow-back").shouldNotBeNull() }
            inFlight.forEach { page(it).querySelector(".iflow-back").shouldBeNull() }
        }

        // ⛔ Distinctness is not correctness, and `aria-live` is not the whole announcement.
        // Sabotage proved both: a headline can be WRONG and still differ from its neighbours, and
        // dropping `role="status"` left the `aria-live` assertion perfectly green.
        test("each phase says something different, and says it politely") {
            val phases =
                listOf(
                    ImportFlowUiState.Uploading("backup.zip"),
                    ImportFlowUiState.Analyzing(3, 10, "Elantris", 2, 8),
                    ImportFlowUiState.Applying(4, 10, null, 25),
                )
            val headlines =
                phases.map { phase ->
                    val host = page(phase)
                    val live = host.querySelector(".iflow-live").shouldNotBeNull()
                    live.getAttribute("role") shouldBe "status"
                    live.getAttribute("aria-live") shouldBe "polite"
                    host.querySelector(".iflow-phase")?.textContent.orEmpty()
                }

            headlines.toSet().size shouldBe phases.size
        }

        // Each phase is pinned to what it should actually say, not merely to being different from
        // the others — the uploading one names the file, which is the only thing it knows.
        test("uploading names the file it is sending") {
            val host = page(ImportFlowUiState.Uploading("backup.zip"))

            host.querySelector(".iflow-phase")?.textContent.orEmpty() shouldContain "Uploading backup.zip"
        }

        test("analysing names the item it is on when the server sent one") {
            val named = page(ImportFlowUiState.Analyzing(3, 10, "Elantris", 0, 0))
            val unnamed = page(ImportFlowUiState.Analyzing(3, 10, null, 0, 0))

            named.querySelector(".iflow-phase")?.textContent.orEmpty() shouldContain "Elantris"
            unnamed.querySelector(".iflow-phase")?.textContent.orEmpty() shouldContain "3 of 10"
        }

        test("applying counts the sessions it has written") {
            val host = page(ImportFlowUiState.Applying(4, 10, null, 25))

            host.querySelector(".iflow-phase")?.textContent.orEmpty() shouldContain "25 sessions so far"
        }

        test("review lists every ABS listener") {
            val host = page(review(userMatches = listOf(absUser(username = "ada"), absUser(id = "au2", username = "grace"))))

            host
                .querySelectorAll(".iflow-row-n")
                .asList()
                .mapNotNull { (it as HTMLElement).textContent } shouldContainExactly listOf("ada", "grace")
        }

        test("mapping a listener reports the pair") {
            val mapped = mutableListOf<Pair<String, String>>()
            val host =
                page(
                    review(userMatches = listOf(absUser(id = "au7"))),
                    onMapUser = { match, userId -> mapped += match.absUserId.value to userId.value },
                )

            val select = host.querySelector("#iflow-user-au7") as org.w3c.dom.HTMLSelectElement
            select.value = "u1"
            select.dispatchEvent(
                org.w3c.dom.events
                    .Event("change", org.w3c.dom.EventInit(bubbles = true)),
            )
            awaitFrame()

            mapped shouldContainExactly listOf("au7" to "u1")
        }

        test("skipping a listener reports which one, and the button says it is done") {
            val skipped = mutableListOf<String>()
            val host =
                page(
                    review(userMatches = listOf(absUser(id = "au7", username = "ada"))),
                    onSkipUser = { skipped += it.absUserId.value },
                )
            val already = page(review(userMatches = listOf(absUser(id = "au7")), skippedUsers = setOf(AbsUserId("au7"))))

            labelled(host, "Skip ada").shouldNotBeNull().click()
            awaitFrame()

            skipped shouldContainExactly listOf("au7")
            button(already, "Skipped").shouldNotBeNull().hasAttribute("disabled") shouldBe true
        }

        // ⛔ The ViewModel treats an unresolved user as skipped. An admin who missed a row would
        // otherwise import nothing for that person and find out afterwards.
        test("undecided listeners are counted and named as skipped") {
            val undecided = page(review(userMatches = listOf(absUser(id = "au1"), absUser(id = "au2"))))
            val settled =
                page(
                    review(
                        userMatches = listOf(absUser(id = "au1")),
                        userMappings = mapOf(AbsUserId("au1") to UserId("u1")),
                    ),
                )

            undecided.querySelector(".iflow-tally")?.textContent.orEmpty() shouldContain "2 still undecided and will be skipped"
            settled.querySelector(".iflow-tally")?.textContent.orEmpty() shouldContain "1 listener mapped"
        }

        test("the page says so when the listener list could not be loaded") {
            val host = page(review(listenupUsers = emptyList()))

            host.textContent.orEmpty() shouldContain "could not be loaded"
        }

        test("only the books needing a decision are listed") {
            val none = page(review(ambiguous = emptyList(), unmatched = emptyList()))
            val some = page(review(ambiguous = listOf(absItem(title = "Elantris"))))

            none.textContent.orEmpty().contains("Books needing a decision") shouldBe false
            some.textContent.orEmpty() shouldContain "Books needing a decision"
        }

        test("both ambiguous and unmatched items need a decision") {
            val host =
                page(
                    review(
                        ambiguous = listOf(absItem(id = "ai1", title = "Elantris")),
                        unmatched = listOf(absItem(id = "ai2", title = "Warbreaker")),
                    ),
                )

            host.textContent.orEmpty() shouldContain "Elantris"
            host.textContent.orEmpty() shouldContain "Warbreaker"
        }

        // Undecided, skipped and matched are three different states and the row has to say which.
        test("a book row says where its decision stands") {
            val undecided = page(review(ambiguous = listOf(absItem(id = "ai1"))))
            val skipped = page(review(ambiguous = listOf(absItem(id = "ai1")), bookOverrides = mapOf(AbsItemId("ai1") to null)))
            val matched =
                page(
                    review(
                        ambiguous = listOf(absItem(id = "ai1")),
                        bookOverrides = mapOf(AbsItemId("ai1") to BookId("b1")),
                    ),
                )

            undecided.querySelector(".iflow-decision")?.textContent shouldBe "Undecided"
            skipped.querySelector(".iflow-decision")?.textContent shouldBe "Skipped"
            matched.querySelector(".iflow-decision")?.textContent shouldBe "Matched"
        }

        test("Find opens the search for that item; Skip skips that item") {
            val opened = mutableListOf<String>()
            val skipped = mutableListOf<String>()
            val host =
                page(
                    review(ambiguous = listOf(absItem(id = "ai7", title = "Elantris"))),
                    onOpenBookSearch = { opened += it.value },
                    onSkipBook = { skipped += it.value },
                )

            labelled(host, "Find the book for Elantris").shouldNotBeNull().click()
            labelled(host, "Skip Elantris").shouldNotBeNull().click()
            awaitFrame()

            opened shouldContainExactly listOf("ai7")
            skipped shouldContainExactly listOf("ai7")
        }

        test("the search panel is ViewModel state, so the page does not open it alone") {
            val closed = page(review(ambiguous = listOf(absItem())))
            val open =
                page(
                    review(
                        ambiguous = listOf(absItem()),
                        bookSearch = BookSearchState(AbsItemId("ai1"), "", emptyList(), false),
                    ),
                )

            closed.querySelector("dialog.dlg").shouldBeNull()
            open.querySelector("dialog.dlg").shouldNotBeNull()
        }

        test("the search panel tells nothing-typed apart from nothing-found") {
            fun panel(
                query: String,
                results: List<BookSearchHit>,
                searching: Boolean,
            ) = page(review(bookSearch = BookSearchState(AbsItemId("ai1"), query, results, searching)))
                .querySelector("dialog.dlg")
                ?.textContent
                .orEmpty()

            panel("", emptyList(), false) shouldContain "Type a title or an author"
            panel("zzz", emptyList(), false) shouldContain "Nothing matched"
            panel("zzz", emptyList(), true) shouldContain "Searching…"
        }

        test("picking a result maps that item to that book") {
            val picked = mutableListOf<Pair<String, String>>()
            val host =
                page(
                    review(
                        bookSearch =
                            BookSearchState(
                                AbsItemId("ai7"),
                                "elantris",
                                listOf(BookSearchHit(BookId("b9"), "Elantris", "Brandon Sanderson")),
                                false,
                            ),
                    ),
                    onSelectBook = { item, book -> picked += item.value to book.value },
                )

            (host.querySelector(".iflow-result") as HTMLElement).click()
            awaitFrame()

            picked shouldContainExactly listOf("ai7" to "b9")
        }

        test("Import applies") {
            var applied = 0
            val host = page(review(), onApply = { applied++ })

            button(host, "Import").shouldNotBeNull().click()
            awaitFrame()

            applied shouldBe 1
        }

        test("the tally says what will be written") {
            val host = page(review(userMappings = mapOf(AbsUserId("au1") to UserId("u1")), importableSessionCount = 40))

            host.querySelector(".iflow-tally")?.textContent.orEmpty() shouldContain "40 sessions to import"
        }

        test("a finished import counts what it wrote") {
            val host = page(ImportFlowUiState.Done(importResult(importedCount = 120, sessionsImported = 40)))

            host.textContent.orEmpty() shouldContain "120 records written"
            host.textContent.orEmpty() shouldContain "40 sessions"
        }

        // The books it could not place are why a number looks lower than expected.
        test("books that are not in the library are named, and only when there are some") {
            val missing = page(ImportFlowUiState.Done(importResult(booksNotInLibrary = 3)))
            val none = page(ImportFlowUiState.Done(importResult(booksNotInLibrary = 0)))

            missing.querySelector(".iflow-note")?.textContent.orEmpty() shouldContain "3 books"
            none.querySelector(".iflow-note").shouldBeNull()
        }

        test("a stopped import is announced and offers a fresh start") {
            var reset = 0
            val host = page(ImportFlowUiState.Error(InternalError(debugInfo = "boom")), onReset = { reset++ })

            host.querySelector("[role=alert]").shouldNotBeNull()
            button(host, "Start again").shouldNotBeNull().click()
            awaitFrame()

            reset shouldBe 1
        }
    })
