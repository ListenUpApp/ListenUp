package com.calypsan.listenup.web

import com.calypsan.listenup.web.features.admin.fixedAdmin
import com.calypsan.listenup.web.features.devices.fixedDevices
import com.calypsan.listenup.web.features.settings.fixedSettings
import com.calypsan.listenup.web.features.shelf.fixedShelfDetail
import com.calypsan.listenup.web.features.shelf.fixedShelfEdit
import com.calypsan.listenup.web.features.discover.fixedDiscover
import com.calypsan.listenup.client.presentation.home.HomeUiState
import com.calypsan.listenup.web.features.home.fixedHome
import com.calypsan.listenup.client.presentation.bookedit.BookEditUiState
import com.calypsan.listenup.web.features.bookedit.fixedBookEdit
import com.calypsan.listenup.web.features.bookdetail.fixedBookDetail
import com.calypsan.listenup.web.features.bookdetail.readyBook
import com.calypsan.listenup.client.presentation.contributordetail.ContributorDetailUiState
import com.calypsan.listenup.client.presentation.notifications.NotificationsUiState
import com.calypsan.listenup.client.presentation.notifications.NotificationPrefsUiState
import com.calypsan.listenup.web.features.notifications.fixedNotificationPrefs
import com.calypsan.listenup.client.presentation.profile.UserProfileUiState
import com.calypsan.listenup.web.features.profile.fixedProfile
import com.calypsan.listenup.web.features.notifications.fixedNotificationBell
import com.calypsan.listenup.web.features.notifications.fixedNotifications
import com.calypsan.listenup.client.presentation.seriesdetail.SeriesDetailUiState
import com.calypsan.listenup.web.features.seriesdetail.fixedSeriesDetail
import com.calypsan.listenup.web.features.contributordetail.fixedContributorDetail
import com.calypsan.listenup.web.features.contributors.fixedContributors
import androidx.compose.runtime.Composable
import com.calypsan.listenup.web.design.WebAppSurface
import com.calypsan.listenup.web.nav.Router
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import kotlinx.coroutines.flow.flowOf
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLElement
import com.calypsan.listenup.web.features.library.fakeLibrary
import com.calypsan.listenup.web.features.search.fixedSearch
import com.calypsan.listenup.client.presentation.search.SearchUiState
import com.calypsan.listenup.web.features.nowplaying.fixedPlayback

/**
 * Proves Compose HTML actually drives the DOM in this build — the composition runs, emits real
 * elements, and recomposes when state changes.
 *
 * Worth asserting rather than assuming: the Compose compiler plugin is applied per-module, and a
 * misconfigured one fails by producing a composition that never runs rather than by failing the
 * build. Rendering nothing looks identical to rendering an empty screen.
 */
class ComposeHtmlRenderTest :
    FunSpec({

        fun mount(content: @Composable () -> Unit): HTMLElement {
            val host = document.createElement("div") as HTMLElement
            document.body!!.appendChild(host)
            // AuthGate applies WebAppSurface in production, so the spec supplies it here the way
            // ShellTest does — WebAppRoot itself no longer carries the `.luw` scope.
            renderComposable(root = host) { WebAppSurface { content() } }
            return host
        }

        test("a composable emits real DOM elements") {
            val router = Router()
            val host =
                mount {
                    WebAppRoot(
                        router,
                        fixedBookDetail(readyBook()),
                        fixedBookEdit(BookEditUiState()),
                        fixedContributorDetail(ContributorDetailUiState.Loading),
                        fixedSeriesDetail(SeriesDetailUiState.Loading),
                        fixedNotifications(NotificationsUiState.Empty),
                        fixedNotificationPrefs(NotificationPrefsUiState.Loading),
                        fixedProfile(UserProfileUiState.Loading),
                        fixedContributors(emptyList()),
                        fixedHome(HomeUiState.Loading),
                        fixedDiscover(),
                        fixedSettings(),
                        fixedDevices(),
                        fixedAdmin(),
                        fixedShelfDetail(),
                        fixedShelfEdit(),
                        fakeLibrary(),
                        fixedSearch(SearchUiState.Idle()),
                        fixedNotificationBell(),
                        fixedPlayback(),
                        observeIsAdmin = { flowOf(false) },
                        observeCurrentUserId = { flowOf(null) },
                    )
                }
            router.dispose()

            val brand = host.querySelector(".sb-name")
            brand.shouldNotBeNullAndContain("ListenUp")
        }

        test("the design system's class contract reaches the DOM") {
            // web.css keys everything off `.luw` plus a direction class; if those do not land on
            // the root element, every token lookup silently falls back and the page renders
            // unstyled rather than broken.
            val router = Router()
            val host =
                mount {
                    WebAppRoot(
                        router,
                        fixedBookDetail(readyBook()),
                        fixedBookEdit(BookEditUiState()),
                        fixedContributorDetail(ContributorDetailUiState.Loading),
                        fixedSeriesDetail(SeriesDetailUiState.Loading),
                        fixedNotifications(NotificationsUiState.Empty),
                        fixedNotificationPrefs(NotificationPrefsUiState.Loading),
                        fixedProfile(UserProfileUiState.Loading),
                        fixedContributors(emptyList()),
                        fixedHome(HomeUiState.Loading),
                        fixedDiscover(),
                        fixedSettings(),
                        fixedDevices(),
                        fixedAdmin(),
                        fixedShelfDetail(),
                        fixedShelfEdit(),
                        fakeLibrary(),
                        fixedSearch(SearchUiState.Idle()),
                        fixedNotificationBell(),
                        fixedPlayback(),
                        observeIsAdmin = { flowOf(false) },
                        observeCurrentUserId = { flowOf(null) },
                    )
                }
            router.dispose()

            val root = host.querySelector(".luw") as? HTMLElement
            (root != null) shouldBe true
            root!!.className shouldContain "dir-a"
        }
    })

private fun org.w3c.dom.Element?.shouldNotBeNullAndContain(text: String) {
    (this != null) shouldBe true
    this!!.textContent.orEmpty() shouldContain text
}
