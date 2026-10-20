package com.calypsan.listenup.web.features.library

import com.calypsan.listenup.client.domain.model.ContributorRole
import com.calypsan.listenup.web.ViewportFrames
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.features.contributors.ContributorsPage
import com.calypsan.listenup.web.features.contributors.contributor
import com.calypsan.listenup.web.features.serieslist.SeriesListPage
import com.calypsan.listenup.web.shell.NavEntry
import com.calypsan.listenup.web.shell.NavSection
import com.calypsan.listenup.web.shell.Shell
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.Div

/**
 * On a phone every Library section reads the same way down the page: the header says "Library", then the sections,
 * then the section's own count and Sort — never Sort above the sections on one and below them on another.
 */
class LibrarySectionsPhoneOrderTest :
    FunSpec({
        val frames = ViewportFrames()
        afterTest { frames.disposeAll() }

        fun phone(page: @Composable () -> Unit) =
            frames.mount(PHONE_390) {
                Shell(
                    sections = listOf(NavSection(listOf(NavEntry("library", "Library", WebIcon.Book)))),
                    active = "library",
                    titled = true,
                ) {
                    // The page region WebAppRoot draws every page in.
                    Div(attrs = { classes("page-body") }) { page() }
                }
            }

        test("Series: the sections come before Sort") {
            val frame =
                phone {
                    SeriesListPage(
                        state = contractLibrary(books = listOf(contractBook("b1", "One"))),
                        onEvent = {},
                        onOpenSeries = {},
                        onSelectFacet = {},
                    )
                }
            awaitFrame()

            frame.rect(frame.find(".lib-sort-trigger")).top shouldBeGreaterThan frame.rect(frame.find("nav.lib-seg")).bottom
        }

        test("Authors and Narrators: the sections come before the count and Sort, and the heading names the role") {
            listOf(ContributorRole.AUTHOR to "Authors", ContributorRole.NARRATOR to "Narrators").forEach { (role, heading) ->
                val frame =
                    phone {
                        ContributorsPage(
                            state = listOf(contributor("c1", "Ann Leckie")),
                            role = role,
                            onSelectFacet = {},
                            onOpenContributor = {},
                        )
                    }
                awaitFrame()

                frame.rect(frame.find(".lib-sort-trigger")).top shouldBeGreaterThan frame.rect(frame.find("nav.lib-seg")).bottom
                frame.find("h1").textContent shouldBe heading
                frames.disposeAll()
            }
        }
    })

private const val PHONE_390 = 390
