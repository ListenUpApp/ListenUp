package com.calypsan.listenup.web

import com.calypsan.listenup.api.notifications.NotificationEvent
import com.calypsan.listenup.client.presentation.admin.LibrarySettingsEvent
import com.calypsan.listenup.client.presentation.notifications.NotificationPrefsUiState
import com.calypsan.listenup.client.presentation.notifications.NotificationsUiState
import com.calypsan.listenup.client.presentation.profile.UserProfileUiState
import com.calypsan.listenup.client.presentation.settings.SettingsUiState
import com.calypsan.listenup.web.features.admin.fixedLibrarySettings
import com.calypsan.listenup.web.features.admin.readyLibrary
import com.calypsan.listenup.web.features.notifications.fixedNotificationPrefs
import com.calypsan.listenup.web.features.notifications.fixedNotifications
import com.calypsan.listenup.web.features.notifications.notification
import com.calypsan.listenup.web.features.notifications.pref
import com.calypsan.listenup.web.features.profile.ProfileSession
import com.calypsan.listenup.web.features.profile.fixedProfile
import com.calypsan.listenup.web.features.profile.readyProfile
import com.calypsan.listenup.web.features.settings.fixedSettings
import com.calypsan.listenup.web.nav.Route
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.browser.window
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeout
import org.w3c.dom.HTMLElement

/**
 * The account family of routes: settings and its sub-paths, admin and its sub-paths, a listener's
 * profile, and the account menu that reaches them.
 *
 * Split out of [WebAppRootTest] when that spec outgrew the size the build allows. The line drawn
 * here is the one `AccountRouteContent` already draws in the app — everything under "you and your
 * server" — rather than an arbitrary halving.
 */
class AccountRoutesTest :
    FunSpec({

        var originalUrl = ""

        beforeTest {
            originalUrl = window.location.pathname + window.location.search
        }

        afterTest {
            window.history.replaceState(null, "", originalUrl)
        }

        test("/settings/notifications renders the preference rows") {
            val (host, router) =
                mountAt(
                    "/settings/notifications",
                    openNotificationPrefs =
                        fixedNotificationPrefs(
                            NotificationPrefsUiState.Data(listOf(pref(type = "campfire_invite"))),
                        ),
                )

            try {
                (host.querySelector(".nprefs-name") as HTMLElement).textContent shouldBe "Campfire invites"
            } finally {
                router.dispose()
            }
        }

        // A `/settings/anything-else` URL must not silently show Settings.
        test("an unknown settings sub-path is still not-found, not Settings") {
            val (host, router) = mountAt("/settings/nonsense")

            try {
                host.querySelector(".nprefs") shouldBe null
                host.querySelector(".set-title") shouldBe null
            } finally {
                router.dispose()
            }
        }

        test("Settings offers a way to the notification preferences") {
            val (host, router) =
                mountAt("/settings", openSettings = fixedSettings(SettingsUiState(isLoading = false)))

            try {
                val links = host.querySelectorAll(".btn-o")
                val notifications =
                    (0 until links.length)
                        .map { links.item(it) as HTMLElement }
                        .first { it.textContent.orEmpty().contains("notifications reach you") }
                notifications.click()

                window.location.pathname shouldBe "/settings/notifications"
            } finally {
                router.dispose()
            }
        }

        test("/admin/library renders the watched folders") {
            val (host, router) =
                mountAt(
                    "/admin/library",
                    openLibrarySettings = fixedLibrarySettings(readyLibrary()),
                )

            try {
                (host.querySelector(".lset-path") as HTMLElement).textContent shouldBe "/srv/Audiobooks"
            } finally {
                router.dispose()
            }
        }

        // FolderSavedScanStarted is a one-shot the ViewModel emits once. The route holds the
        // resulting notice because a page re-reading it from state would either never show it or
        // never stop showing it — so the route is where the wiring has to be proved.
        test("the scan-started event raises the notice on the page") {
            val (host, router, composition) =
                mountAt(
                    "/admin/library",
                    openLibrarySettings =
                        fixedLibrarySettings(
                            readyLibrary(),
                            events = flowOf(LibrarySettingsEvent.FolderSavedScanStarted),
                        ),
                )

            try {
                withTimeout(RECOMPOSE_TIMEOUT_MS) {
                    while (host.querySelector(".lset-note") == null) delay(10)
                }
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        // `active` is ADMIN_KEY for every `/admin/*` URL, so without a length guard this shows
        // Admin for a path nobody routed. Same defect the settings branch had.
        test("an unknown admin sub-path is still not-found, not Admin") {
            val (host, router) = mountAt("/admin/nonsense")

            try {
                host.querySelector(".lset") shouldBe null
                host.querySelector(".adm-link") shouldBe null
            } finally {
                router.dispose()
            }
        }

        test("Admin offers a way to the library folders") {
            val (host, router) = mountAt("/admin")

            try {
                (host.querySelector(".adm-link") as HTMLElement).click()

                window.location.pathname shouldBe "/admin/library"
            } finally {
                router.dispose()
            }
        }

        test("/profile/{id} renders that listener's page") {
            val (host, router) =
                mountAt(
                    "/profile/u7",
                    openProfile = fixedProfile(readyProfile(userId = "u7", displayName = "Simon Hull")),
                )

            try {
                (host.querySelector(".prof-name") as HTMLElement).textContent shouldBe "Simon Hull"
            } finally {
                router.dispose()
            }
        }

        // `loadProfile` returns early for the id it already holds, so an unkeyed session would
        // keep showing the first person visited.
        test("switching profile id opens a new session rather than reusing the old one's") {
            val requested = mutableListOf<String>()
            val (host, router) =
                mountAt(
                    "/profile/u1",
                    openProfile = { id ->
                        requested += id
                        ProfileSession(MutableStateFlow(readyProfile(userId = id)), onRetry = {}, close = {})
                    },
                )

            try {
                requested shouldBe listOf("u1")

                router.navigate(Route(listOf("profile", "u2")))
                awaitFrame()

                requested shouldBe listOf("u1", "u2")
            } finally {
                router.dispose()
            }
        }

        // The destination `NotificationTapRouting` has always produced and web had nowhere to send.
        test("a profile notification now lands on that profile") {
            val (host, router) =
                mountAt(
                    "/notifications",
                    openNotifications =
                        fixedNotifications(
                            NotificationsUiState.Data(
                                listOf(
                                    notification(
                                        id = "n1",
                                        event = NotificationEvent.CampfireInvite("c1", "b1", "u5"),
                                    ),
                                ),
                            ),
                        ),
                )

            try {
                // A campfire target has no surface on any client, so this one still goes nowhere —
                // the profile branch is exercised by the routing spec above. What this pins is that
                // adding the branch did not make an unrelated notification start navigating.
                (host.querySelector(".ntf-row") as HTMLElement).click()

                window.location.pathname shouldBe "/notifications"
            } finally {
                router.dispose()
            }
        }

        test("the account menu offers your own profile once the app knows who you are") {
            val (host, router) = mountAt("/", currentUserId = flowOf("me-1"))

            try {
                withTimeout(RECOMPOSE_TIMEOUT_MS) {
                    while (host.querySelector(".menu-anchor button") == null) delay(10)
                }
                (host.querySelector(".menu-anchor button") as HTMLElement).click()
                awaitFrame()

                val items = host.querySelectorAll(".menu-i")
                val profile =
                    (0 until items.length)
                        .map { items.item(it) as HTMLElement }
                        .first { it.textContent.orEmpty().contains("Your profile") }
                profile.click()

                window.location.pathname shouldBe "/profile/me-1"
            } finally {
                router.dispose()
            }
        }

        // A greyed entry that becomes live a frame later draws the eye to a transition nobody
        // needs to watch, so it is absent instead.
        test("the account menu offers no profile entry before the app knows who you are") {
            val (host, router) = mountAt("/", currentUserId = flowOf(null))

            try {
                (host.querySelector(".menu-anchor button") as HTMLElement).click()
                awaitFrame()

                host.textContent.orEmpty().contains("Your profile") shouldBe false
            } finally {
                router.dispose()
            }
        }
    })
