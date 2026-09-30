package com.calypsan.listenup.web

import com.calypsan.listenup.client.presentation.bulkedit.BulkEditUiState
import com.calypsan.listenup.client.presentation.home.HomeUiState
import com.calypsan.listenup.web.features.admin.fixedRestore
import com.calypsan.listenup.web.features.bulkedit.fixedBulkEdit
import com.calypsan.listenup.web.features.devices.fixedDevices
import com.calypsan.listenup.web.features.discover.fixedDiscover
import com.calypsan.listenup.web.features.home.fixedHome
import com.calypsan.listenup.web.features.licences.LicencesUiState
import com.calypsan.listenup.web.features.licences.fixedLicences
import com.calypsan.listenup.web.features.shelf.fixedShelfDetail
import com.calypsan.listenup.web.features.shelf.fixedShelfEdit
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.browser.window
import kotlinx.coroutines.flow.flowOf
import org.w3c.dom.HTMLElement

/**
 * The route families nothing else asked the router to find.
 *
 * Every other route has a spec that mounts the shell at its URL and asserts on what came up. These
 * did not: their pages had specs of their own, which mount the composable directly and never ask
 * the router — so `/book/{id}/edit` could be broken and nothing failed until #1431. Each test here
 * mounts the URL, and asserts both halves of resolution: the right page is on screen, and the
 * session behind it was opened over the parameters the URL names.
 */
class RouteResolutionTest :
    FunSpec({
        var originalUrl = ""

        beforeTest { originalUrl = window.location.pathname + window.location.search }

        afterTest { window.history.replaceState(null, "", originalUrl) }

        fun heading(host: HTMLElement): String =
            (host.querySelector(".shell-main .page-t") as HTMLElement)
                .textContent
                .orEmpty()
                .trim()

        // "The root URL is Home" pins the sidebar. This pins the page: a branch order that let the
        // root fall through to another page would still light Home in the sidebar.
        test("/ opens Home's session and shows Home") {
            var opened = 0
            val (host, router, composition) =
                mountAt("/", openHome = {
                    opened++
                    fixedHome(HomeUiState.Loading)()
                })

            try {
                opened shouldBe 1
                heading(host) shouldBe "Home"
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("/discover opens Discover's session and shows Discover") {
            var opened = 0
            val (host, router, composition) =
                mountAt("/discover", openDiscover = {
                    opened++
                    fixedDiscover()()
                })

            try {
                opened shouldBe 1
                heading(host) shouldBe "Discover"
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("/settings/devices opens the devices page, not Settings") {
            var opened = 0
            val (host, router, composition) =
                mountAt("/settings/devices", openDevices = {
                    opened++
                    fixedDevices()()
                })

            try {
                opened shouldBe 1
                heading(host) shouldBe "Devices"
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("/settings/licences opens the licence list, not Settings") {
            var opened = 0
            val (host, router, composition) =
                mountAt(
                    "/settings/licences",
                    openLicences = {
                        opened++
                        fixedLicences(LicencesUiState.Loading)()
                    },
                )

            try {
                opened shouldBe 1
                heading(host) shouldBe "Open source licenses"
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        // The existing spec asserts "Edit 3 books" — text the fixture's state supplies whatever ids
        // the route parsed. The ids themselves are the contract.
        test("/books/edit?ids=… opens the bulk editor over exactly the ids in the query, in order") {
            val asked = mutableListOf<List<String>>()
            val (_, router, composition) =
                mountAt(
                    "/books/edit?ids=b1,b2,b3",
                    openBulkEdit = { ids ->
                        asked += ids
                        fixedBulkEdit(BulkEditUiState.Loading)(ids)
                    },
                )

            try {
                asked shouldBe listOf(listOf("b1", "b2", "b3"))
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("/admin/backups/{id} opens the restore flow over that backup") {
            val asked = mutableListOf<String>()
            val (host, router, composition) =
                mountAt(
                    "/admin/backups/bk7",
                    isAdmin = flowOf(true),
                    openRestore = { id ->
                        asked += id
                        fixedRestore()(id)
                    },
                )

            try {
                asked shouldBe listOf("bk7")
                host.querySelector(".rst").shouldNotBeNull()
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        // `shelfRouteOf` has its own spec, but that spec never asks the shell which screen each
        // parsed route opens. These three do.
        test("/shelf/new opens the form in create mode, over no shelf") {
            val asked = mutableListOf<String?>()
            val (host, router, composition) =
                mountAt(
                    "/shelf/new",
                    openShelfEdit = { id ->
                        asked += id
                        fixedShelfEdit()(id)
                    },
                )

            try {
                asked shouldBe listOf(null)
                heading(host) shouldBe "New shelf"
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("/shelf/{id}/edit opens the form over that shelf") {
            val asked = mutableListOf<String?>()
            val (host, router, composition) =
                mountAt(
                    "/shelf/s-bedtime/edit",
                    openShelfEdit = { id ->
                        asked += id
                        fixedShelfEdit()(id)
                    },
                )

            try {
                asked shouldBe listOf("s-bedtime")
                heading(host) shouldBe "Edit shelf"
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("/shelf/{id} opens that shelf's page, not its form") {
            val asked = mutableListOf<String>()
            val (host, router, composition) =
                mountAt(
                    "/shelf/s-bedtime",
                    openShelfDetail = { id ->
                        asked += id
                        fixedShelfDetail()(id)
                    },
                )

            try {
                asked shouldBe listOf("s-bedtime")
                heading(host) shouldBe "Shelf"
            } finally {
                composition.dispose()
                router.dispose()
            }
        }
    })
