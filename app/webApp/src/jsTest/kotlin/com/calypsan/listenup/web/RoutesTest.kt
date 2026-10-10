package com.calypsan.listenup.web

import com.calypsan.listenup.web.nav.Route
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The two statements of the URL grammar agreeing: every route the shell can build by name is one
 * the shell will serve. [Routes] constructs; `ROUTE_SHAPES` (through `isServed`) validates incoming
 * URLs — a member the validator refused would be a link the app hands out and then 404s on.
 */
class RoutesTest :
    FunSpec({
        val id = "42"

        val built: Map<String, Route> =
            mapOf(
                "home" to Routes.home,
                "admin" to Routes.admin,
                "adminBackups" to Routes.adminBackups,
                "adminBackup" to Routes.adminBackup(id),
                "adminCategories" to Routes.adminCategories,
                "adminCollections" to Routes.adminCollections,
                "adminCollection" to Routes.adminCollection(id),
                "adminImports" to Routes.adminImports,
                "adminNewImport" to Routes.adminNewImport,
                "adminInbox" to Routes.adminInbox,
                "adminInvite" to Routes.adminInvite,
                "adminLibrary" to Routes.adminLibrary,
                "adminOrganize" to Routes.adminOrganize,
                "adminServerSettings" to Routes.adminServerSettings,
                "adminUpload" to Routes.adminUpload,
                "adminUser" to Routes.adminUser(id),
                "adminPending" to Routes.adminPending,
                "book" to Routes.book(id),
                "bookChapters" to Routes.bookChapters(id),
                "bookEdit" to Routes.bookEdit(id),
                "bookHardcover" to Routes.bookHardcover(id),
                "bookMatch" to Routes.bookMatch(id),
                "bookReaders" to Routes.bookReaders(id),
                "bulkEdit" to Routes.bulkEdit(listOf("1", "2")),
                "contributor" to Routes.contributor(id),
                "contributorEdit" to Routes.contributorEdit(id),
                "contributorMatch" to Routes.contributorMatch(id),
                "genre" to Routes.genre(id),
                "library" to Routes.library,
                "libraryContributors" to Routes.libraryContributors,
                "librarySeries" to Routes.librarySeries,
                "mood" to Routes.mood(id),
                "profile" to Routes.profile(id),
                "profileEdit" to Routes.profileEdit(id),
                "search" to Routes.search,
                "searchFor" to Routes.searchFor("dune"),
                "searchType" to Routes.searchType("books", mapOf("q" to "dune")),
                "series" to Routes.series(id),
                "seriesEdit" to Routes.seriesEdit(id),
                "settings" to Routes.settings,
                "settingsCategories" to Routes.settingsCategories,
                "settingsDevices" to Routes.settingsDevices,
                "settingsHardcover" to Routes.settingsHardcover,
                "settingsHardcoverKeptOff" to Routes.settingsHardcoverKeptOff,
                "settingsLicences" to Routes.settingsLicences,
                "settingsNotifications" to Routes.settingsNotifications,
                "newShelf" to Routes.newShelf,
                "shelf" to Routes.shelf(id),
                "shelfEdit" to Routes.shelfEdit(id),
                "tag" to Routes.tag(id),
            )

        built.forEach { (name, route) ->
            test("Routes.$name is a route the shell serves") {
                route.isServed() shouldBe true
            }
        }

        test("a route read back from its own URL is still served") {
            built.forEach { (name, route) ->
                Route.parse(route.toUrl()).isServed() shouldBe true
            }
        }
    })
