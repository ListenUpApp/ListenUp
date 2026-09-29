package com.calypsan.listenup.web

import androidx.compose.runtime.Composition
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.client.presentation.bookedit.BookEditUiState
import com.calypsan.listenup.client.presentation.contributordetail.ContributorDetailUiState
import com.calypsan.listenup.client.presentation.home.HomeUiState
import com.calypsan.listenup.client.presentation.library.LibraryUiState
import com.calypsan.listenup.client.presentation.profile.UserProfileUiState
import com.calypsan.listenup.client.presentation.seriesdetail.SeriesDetailUiState
import com.calypsan.listenup.web.features.bookdetail.fixedBookDetail
import com.calypsan.listenup.web.features.bookedit.fixedBookEdit
import com.calypsan.listenup.web.features.contributordetail.fixedContributorDetail
import com.calypsan.listenup.web.features.home.fixedHome
import com.calypsan.listenup.web.features.home.readyHome
import com.calypsan.listenup.web.features.library.contractLibrary
import com.calypsan.listenup.web.features.library.fakeLibrary
import com.calypsan.listenup.web.features.profile.fixedProfile
import com.calypsan.listenup.web.features.seriesdetail.fixedSeriesDetail
import com.calypsan.listenup.web.nav.Router
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.flow.flowOf
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList

/**
 * The outline a screen reader navigates by: one H1 per page naming it, headings that never skip a
 * level, named navigation landmarks, and a way past the sidebar.
 *
 * Library, Search, Contributors and the Series list all titled themselves with an H3, and the shared
 * `Panel` jumped from a page's H1 straight to H3 — so "next heading" and "list headings" described a
 * document that did not match the one on screen.
 */
class PageStructureTest :
    FunSpec({
        var originalUrl = ""

        beforeTest { originalUrl = window.location.pathname + window.location.search }

        afterTest {
            window.history.replaceState(null, "", originalUrl)
            (document.activeElement as? HTMLElement)?.blur()
        }

        listOf(
            "/",
            "/library",
            "/library/contributors",
            "/library/series",
            "/search",
            "/discover",
            "/notifications",
            "/settings",
            "/admin",
        ).forEach { path ->
            test("$path has exactly one H1, and no heading skips a level") {
                val (host, router, composition) =
                    mountAt(
                        path,
                        isAdmin = flowOf(true),
                        openHome = fixedHome(readyHome()),
                        openLibrary = fakeLibrary(contractLibrary()),
                    )

                try {
                    awaitFrame()
                    val main = host.querySelector(".shell-main") as HTMLElement

                    main.querySelectorAll("h1").length shouldBe 1
                    skippedLevels(main).shouldBeEmpty()
                } finally {
                    composition.dispose()
                    router.dispose()
                }
            }
        }

        // A page that is loading, or that failed, is still a page, and still has a name. The profile
        // and shelf pages used to render no H1 at all until their data arrived, and most detail pages
        // dropped theirs on failure — so the heading a screen reader lands on, and the one focus moves
        // to on navigation, was missing exactly when the reader most needed to know where they were.
        PAGE_STATES.forEach { case ->
            test("${case.name} has exactly one H1, and no heading skips a level") {
                val (host, router, composition) = case.mount()

                try {
                    awaitFrame()
                    val main = host.querySelector(".shell-main") as HTMLElement

                    main
                        .querySelectorAll("h1")
                        .asList()
                        .map { it.textContent.orEmpty().trim() }
                        .shouldHaveSize(1)
                    (main.querySelector("h1") as HTMLElement).className shouldBe "page-t"
                    skippedLevels(main).shouldBeEmpty()
                } finally {
                    composition.dispose()
                    router.dispose()
                }
            }
        }

        test("every page title is drawn the same: one size, one weight, whatever the page") {
            // The regression this guards is the one the audit counted: 31 private title rules
            // drifting between 1.5rem and 2.1rem, and Library's title rendered at 1.17em.
            val looks =
                listOf("/library", "/search", "/discover", "/settings", "/admin").map { path ->
                    val (host, router, composition) = mountAt(path, isAdmin = flowOf(true))
                    try {
                        awaitFrame()
                        val title = host.querySelector(".shell-main h1") as HTMLElement
                        val style = window.getComputedStyle(title)
                        Triple(style.fontSize, style.fontWeight, style.letterSpacing)
                    } finally {
                        composition.dispose()
                        router.dispose()
                    }
                }

            looks.toSet().shouldHaveSize(1)
            looks.first().first shouldBe "30px"
        }

        test("the two navigation landmarks are named, so they can be told apart") {
            val (host, router, composition) = mountAt("/")

            try {
                host.querySelectorAll("nav").asList().map { (it as HTMLElement).getAttribute("aria-label") } shouldBe
                    listOf("Main", "Account")
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("the first Tab stop skips to the content, and following it lands focus there") {
            val (host, router, composition) = mountAt("/settings")

            try {
                val firstFocusable = host.querySelector("a[href], button, input, [tabindex='0']") as HTMLElement
                val skip = host.querySelector(".skip-link") as HTMLAnchorElement

                firstFocusable shouldBe skip
                skip.textContent shouldBe "Skip to content"
                val main = host.querySelector("main") as HTMLElement
                skip.getAttribute("href") shouldBe "#${main.id}"

                skip.click()

                document.activeElement shouldBe main
                // Handled in script: the router owns the URL, so no fragment lands in it.
                window.location.hash shouldBe ""
            } finally {
                composition.dispose()
                router.dispose()
            }
        }
    })

/** Each place a heading goes more than one level deeper than the one before it, e.g. "h1 → h3". */
private fun skippedLevels(root: HTMLElement): List<String> {
    val levels = root.querySelectorAll("h1, h2, h3, h4, h5, h6").asList().map { it.nodeName.drop(1).toInt() }
    return levels.zipWithNext().filter { (from, to) -> to > from + 1 }.map { (from, to) -> "h$from → h$to" }
}

/** One route in one state, mounted in the real shell. */
private class PageState(
    val name: String,
    val mount: () -> Triple<HTMLElement, Router, Composition>,
)

private val PAGE_STATES =
    listOf(
        PageState("Home, loading") { mountAt("/") },
        PageState("Home, failed") { mountAt("/", openHome = fixedHome(HomeUiState.Error("Home failed"))) },
        PageState("Library, loading") { mountAt("/library") },
        PageState("Library, failed") { mountAt("/library", openLibrary = fakeLibrary(LibraryUiState.Error("nope"))) },
        PageState("Contributors, loading") { mountAt("/library/contributors") },
        PageState("Series list, loading") { mountAt("/library/series") },
        PageState("a book, ready") { mountAt("/book/42") },
        PageState("a book, loading") { mountAt("/book/42", openBookDetail = fixedBookDetail(BookDetailUiState.Loading)) },
        PageState("a book, failed") {
            mountAt("/book/42", openBookDetail = fixedBookDetail(BookDetailUiState.Error(BookError.NotFound())))
        },
        PageState("a book's edit form, loading") {
            mountAt("/book/42/edit", openBookEdit = fixedBookEdit(BookEditUiState(isLoading = true)))
        },
        PageState("the chapter editor, loading") { mountAt("/book/42/chapters") },
        PageState("a contributor, loading") { mountAt("/contributor/c1") },
        PageState("a contributor, failed") {
            mountAt("/contributor/c1", openContributorDetail = fixedContributorDetail(ContributorDetailUiState.Error("gone")))
        },
        PageState("a contributor's books, loading") { mountAt("/contributor/c1/books?role=author") },
        PageState("a series, loading") { mountAt("/series/s1") },
        PageState("a series, failed") {
            mountAt("/series/s1", openSeriesDetail = fixedSeriesDetail(SeriesDetailUiState.Error("gone")))
        },
        PageState("a profile, loading") { mountAt("/profile/u1") },
        PageState("a profile, failed") {
            mountAt("/profile/u1", openProfile = fixedProfile(UserProfileUiState.Error("No such listener.")))
        },
        PageState("a tag, loading") { mountAt("/tag/t1") },
        PageState("a genre, loading") { mountAt("/genre/g1") },
        PageState("the bulk editor, loading") { mountAt("/books/edit?ids=a,b") },
        PageState("notification settings, loading") { mountAt("/settings/notifications") },
        PageState("library folders, loading") { mountAt("/admin/library", isAdmin = flowOf(true)) },
        PageState("a member, loading") { mountAt("/admin/user/u1", isAdmin = flowOf(true)) },
        PageState("file organization, loading") { mountAt("/admin/organize", isAdmin = flowOf(true)) },
        PageState("an unknown settings page") { mountAt("/settings/nonsense") },
    )
