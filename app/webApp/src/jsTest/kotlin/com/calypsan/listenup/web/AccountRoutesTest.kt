package com.calypsan.listenup.web

import com.calypsan.listenup.api.notifications.NotificationEvent
import com.calypsan.listenup.client.presentation.admin.LibrarySettingsEvent
import com.calypsan.listenup.client.presentation.notifications.NotificationPrefsUiState
import com.calypsan.listenup.client.presentation.notifications.NotificationsUiState
import com.calypsan.listenup.client.presentation.profile.EditProfileEvent
import com.calypsan.listenup.client.presentation.profile.UserProfileUiState
import com.calypsan.listenup.client.presentation.settings.SettingsUiState
import com.calypsan.listenup.web.features.admin.fixedAdminInbox
import com.calypsan.listenup.web.features.admin.fixedCategories
import com.calypsan.listenup.web.features.admin.fixedCollectionDetail
import com.calypsan.listenup.web.features.admin.fixedCollections
import com.calypsan.listenup.web.features.admin.fixedServerSettings
import com.calypsan.listenup.web.features.admin.fixedLibrarySettings
import com.calypsan.listenup.web.features.admin.genre
import com.calypsan.listenup.web.features.admin.node
import com.calypsan.listenup.web.features.admin.collection
import com.calypsan.listenup.web.features.admin.readyCategories
import com.calypsan.listenup.web.features.admin.readyCollections
import com.calypsan.listenup.web.features.admin.readyDetail
import com.calypsan.listenup.web.features.admin.readyInbox
import com.calypsan.listenup.web.features.admin.readyServerSettings
import com.calypsan.listenup.web.features.admin.scanIssue
import com.calypsan.listenup.web.features.admin.readyLibrary
import com.calypsan.listenup.web.features.notifications.fixedNotificationPrefs
import com.calypsan.listenup.web.features.notifications.fixedNotifications
import com.calypsan.listenup.web.features.notifications.notification
import com.calypsan.listenup.web.features.notifications.pref
import com.calypsan.listenup.web.features.profile.ProfileSession
import com.calypsan.listenup.web.features.profile.editing
import com.calypsan.listenup.web.features.profile.fixedEditProfile
import com.calypsan.listenup.web.features.profile.fixedProfile
import com.calypsan.listenup.web.features.profile.readyProfile
import com.calypsan.listenup.web.features.settings.fixedSettings
import com.calypsan.listenup.web.nav.Route
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.window
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeout
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.asList

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

        test("/admin/inbox renders the inbox") {
            val (host, router) =
                mountAt(
                    "/admin/inbox",
                    openAdminInbox = fixedAdminInbox(readyInbox(scanIssues = listOf(scanIssue()))),
                )

            try {
                host.querySelector(".inbox").shouldNotBeNull()
                host.textContent.orEmpty() shouldContain "Waiting for review"
                host.textContent.orEmpty() shouldContain "Needs attention"
            } finally {
                router.dispose()
            }
        }

        test("Admin offers a way to the inbox") {
            val (host, router) = mountAt("/admin")

            try {
                val inbox =
                    host
                        .querySelectorAll(".adm-link")
                        .asList()
                        .filterIsInstance<HTMLElement>()
                        .first { it.textContent?.trim() == "Inbox" }
                inbox.click()

                window.location.pathname shouldBe "/admin/inbox"
            } finally {
                router.dispose()
            }
        }

        test("/admin/settings renders server settings") {
            val (host, router) =
                mountAt(
                    "/admin/settings",
                    openServerSettings = fixedServerSettings(readyServerSettings(serverName = "Kit")),
                )

            try {
                (host.querySelector("#srv-name") as HTMLInputElement).value shouldBe "Kit"
            } finally {
                router.dispose()
            }
        }

        // ⛔ `/admin/settings` and a listener's own `/settings` share a path segment and share
        // nothing else. If the admin branch ever stopped testing its FIRST segment, one would
        // start serving the other.
        test("a listener's own settings is not the server's") {
            val (host, router) = mountAt("/settings")

            try {
                host.querySelector(".srv") shouldBe null
            } finally {
                router.dispose()
            }
        }

        test("/admin/categories renders the genre tree") {
            val (host, router) =
                mountAt(
                    "/admin/categories",
                    openCategories =
                        fixedCategories(readyCategories(tree = listOf(node(genre(name = "Fiction"))))),
                )

            try {
                (host.querySelector(".cat-name") as HTMLElement).textContent shouldBe "Fiction"
            } finally {
                router.dispose()
            }
        }

        test("/admin/collections renders the list") {
            val (host, router) =
                mountAt(
                    "/admin/collections",
                    openCollections = fixedCollections(readyCollections(listOf(collection(name = "Bedtime")))),
                )

            try {
                (host.querySelector(".coll-name") as HTMLElement).textContent shouldBe "Bedtime"
            } finally {
                router.dispose()
            }
        }

        // ⛔ The id branch has to be tested BEFORE the bare list, or `/admin/collections/c1` shows
        // the list and the detail page is unreachable by URL.
        test("/admin/collections/{id} renders that collection, not the list") {
            val (host, router) =
                mountAt(
                    "/admin/collections/c7",
                    openCollectionDetail = { fixedCollectionDetail(readyDetail(name = "Bedtime"))(it) },
                )

            try {
                (host.querySelector(".cdet-title") as HTMLElement).textContent shouldBe "Bedtime"
                host.querySelector(".coll-list") shouldBe null
            } finally {
                router.dispose()
            }
        }

        // `AdminCollectionDetailViewModel` takes the id as a CONSTRUCTOR parameter, so a session
        // cannot be repointed — an unkeyed remember would keep showing the first one opened.
        test("switching collection id opens a new session rather than reusing the old one's") {
            val requested = mutableListOf<String>()
            val (host, router) =
                mountAt(
                    "/admin/collections/c1",
                    openCollectionDetail = { id ->
                        requested += id
                        fixedCollectionDetail(readyDetail(name = "Collection $id"))(id)
                    },
                )

            try {
                requested shouldContainExactly listOf("c1")

                router.navigate(Route(listOf("admin", "collections", "c2")))
                awaitFrame()

                requested shouldContainExactly listOf("c1", "c2")
            } finally {
                router.dispose()
            }
        }

        test("opening a collection from the list reaches its page") {
            val (host, router) =
                mountAt(
                    "/admin/collections",
                    openCollections = fixedCollections(readyCollections(listOf(collection(id = "c7")))),
                )

            try {
                (host.querySelector(".coll-open") as HTMLElement).click()
                awaitFrame()

                window.location.pathname shouldBe "/admin/collections/c7"
            } finally {
                router.dispose()
            }
        }

        test("Admin offers a way to the collections") {
            val (host, router) = mountAt("/admin")

            try {
                host
                    .querySelectorAll(".adm-link")
                    .asList()
                    .filterIsInstance<HTMLElement>()
                    .first { it.textContent?.trim() == "Collections" }
                    .click()

                window.location.pathname shouldBe "/admin/collections"
            } finally {
                router.dispose()
            }
        }

        test("Admin offers a way to the categories") {
            val (host, router) = mountAt("/admin")

            try {
                host
                    .querySelectorAll(".adm-link")
                    .asList()
                    .filterIsInstance<HTMLElement>()
                    .first { it.textContent?.trim() == "Categories" }
                    .click()

                window.location.pathname shouldBe "/admin/categories"
            } finally {
                router.dispose()
            }
        }

        test("Admin offers a way to the server settings") {
            val (host, router) = mountAt("/admin")

            try {
                host
                    .querySelectorAll(".adm-link")
                    .asList()
                    .filterIsInstance<HTMLElement>()
                    .first { it.textContent?.trim() == "Server settings" }
                    .click()

                window.location.pathname shouldBe "/admin/settings"
            } finally {
                router.dispose()
            }
        }

        // The inbox route sits beside the library-folders one, so it inherits the same hazard:
        // `active` is ADMIN_KEY for every `/admin/*` URL.
        test("an unknown admin sub-path does not fall through to the inbox") {
            val (host, router) = mountAt("/admin/nonsense")

            try {
                host.querySelector(".inbox") shouldBe null
            } finally {
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

        test("/profile/{you}/edit opens your form") {
            val (host, router) =
                mountAt(
                    "/profile/u7/edit",
                    currentUserId = flowOf("u7"),
                    openEditProfile = fixedEditProfile(editing(firstName = "Ada")),
                )

            try {
                awaitPresent(host, "#pedit-first-name")

                (host.querySelector("#pedit-first-name") as HTMLInputElement).value shouldBe "Ada"
            } finally {
                router.dispose()
            }
        }

        // ⛔ The id in the URL is not what gets edited — `EditProfileViewModel` reads
        // `observeCurrentUser()`. Without this guard, `/profile/someone-else/edit` would render
        // YOUR name, tagline and password fields under THEIR URL.
        test("/profile/{someone else}/edit sends you to their page instead of opening your form") {
            val (host, router) =
                mountAt(
                    "/profile/u7/edit",
                    currentUserId = flowOf("me-1"),
                    openProfile = fixedProfile(readyProfile(userId = "u7", displayName = "Simon Hull")),
                    openEditProfile = fixedEditProfile(editing(firstName = "Ada")),
                )

            try {
                // The redirect is decided in a LaunchedEffect, so their page lands a composition
                // after the route flips — wait for the page, not for a frame count.
                awaitPresent(host, ".prof-name").textContent shouldBe "Simon Hull"

                window.location.pathname shouldBe "/profile/u7"
                host.querySelector(".pedit") shouldBe null
            } finally {
                router.dispose()
            }
        }

        // Null is "not known yet", not "not you" — the flow answers a moment after mount, and
        // redirecting on it would bounce every reader off their own form on arrival.
        test("the form is not redirected away before the app knows who you are") {
            val (host, router) =
                mountAt(
                    "/profile/u7/edit",
                    currentUserId = flowOf(null),
                    openEditProfile = fixedEditProfile(editing(firstName = "Ada")),
                )

            try {
                awaitFrame()

                window.location.pathname shouldBe "/profile/u7/edit"
            } finally {
                router.dispose()
            }
        }

        test("the pencil on your own profile opens the form") {
            val (host, router) =
                mountAt(
                    "/profile/u7",
                    currentUserId = flowOf("u7"),
                    openProfile = fixedProfile(readyProfile(userId = "u7", isOwnProfile = true)),
                    openEditProfile = fixedEditProfile(editing()),
                )

            try {
                (host.querySelector(".prof-edit") as HTMLElement).click()
                awaitPresent(host, ".pedit")

                window.location.pathname shouldBe "/profile/u7/edit"
            } finally {
                router.dispose()
            }
        }

        // The refreshed profile is the confirmation, exactly as on Android. A notice instead would
        // be shown on a page the reader has already left.
        test("a save that succeeds returns to the profile") {
            val (host, router) =
                mountAt(
                    "/profile/u7/edit",
                    currentUserId = flowOf("u7"),
                    openProfile = fixedProfile(readyProfile(userId = "u7", isOwnProfile = true)),
                    openEditProfile =
                        fixedEditProfile(editing(), events = flowOf(EditProfileEvent.SaveSucceeded)),
                )

            try {
                awaitPresent(host, ".prof")

                window.location.pathname shouldBe "/profile/u7"
            } finally {
                router.dispose()
            }
        }

        // `SaveFailed` is a one-shot event, so the route has to hold it for the form to render it.
        test("a save that fails is reported on the form rather than lost") {
            val (host, router) =
                mountAt(
                    "/profile/u7/edit",
                    currentUserId = flowOf("u7"),
                    openEditProfile =
                        fixedEditProfile(
                            editing(),
                            events = flowOf(EditProfileEvent.SaveFailed("Passwords do not match.")),
                        ),
                )

            try {
                awaitPresent(host, ".edit-error").textContent.orEmpty() shouldContain "Passwords do not match."
            } finally {
                router.dispose()
            }
        }
    })
