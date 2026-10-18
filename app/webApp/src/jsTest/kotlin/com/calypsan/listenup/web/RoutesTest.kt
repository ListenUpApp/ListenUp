package com.calypsan.listenup.web

import com.calypsan.listenup.web.nav.Route
import com.calypsan.listenup.web.nav.Routes
import com.calypsan.listenup.web.nav.READERS_SECTION
import com.calypsan.listenup.web.nav.isServed
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
                "admin" to Routes.Admin.root,
                "adminBackups" to Routes.Admin.backups,
                "adminBackup" to Routes.Admin.backup(id),
                "adminCategories" to Routes.Admin.categories,
                "adminCollections" to Routes.Admin.collections,
                "adminCollection" to Routes.Admin.collection(id),
                "adminImports" to Routes.Admin.imports,
                "adminNewImport" to Routes.Admin.newImport,
                "adminInbox" to Routes.Admin.inbox,
                "adminInvite" to Routes.Admin.invite,
                "adminLibrary" to Routes.Admin.library,
                "adminOrganize" to Routes.Admin.organize,
                "adminServerSettings" to Routes.Admin.serverSettings,
                "adminUpload" to Routes.Admin.upload,
                "adminUser" to Routes.Admin.user(id),
                "adminPending" to Routes.Admin.pending,
                "book" to Routes.Book.detail(id),
                "bookReadersInView" to Routes.Book.detail(id, section = READERS_SECTION),
                "bookChapters" to Routes.Book.chapters(id),
                "bookEdit" to Routes.Book.edit(id),
                "bookHardcover" to Routes.Book.hardcover(id),
                "bookMatch" to Routes.Book.match(id),
                "bookReaders" to Routes.Book.readers(id),
                "bulkEdit" to Routes.bulkEdit(listOf("1", "2")),
                "contributor" to Routes.Contributor.detail(id),
                "contributorEdit" to Routes.Contributor.edit(id),
                "contributorMatch" to Routes.Contributor.match(id),
                "genre" to Routes.genre(id),
                "discoverLeaderboard" to Routes.discoverLeaderboard,
                "library" to Routes.library,
                "libraryNewestFirst" to Routes.libraryNewestFirst,
                "libraryInProgress" to Routes.libraryInProgress,
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
                "shelves" to Routes.shelves,
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

        test("the leaderboard landing and the newest-first library are served URLs") {
            Routes.discoverLeaderboard.toUrl() shouldBe "/discover?section=leaderboard"
            Routes.libraryNewestFirst.toUrl() shouldBe "/library?sort=added-desc"
        }

        test("Home's three landings are served URLs: My shelves, In progress, and a book's readers") {
            Routes.shelves.toUrl() shouldBe "/shelves"
            Routes.libraryInProgress.toUrl() shouldBe "/library?status=in-progress"
            Routes.Book.detail("wok", section = READERS_SECTION).toUrl() shouldBe "/book/wok?section=readers"
            Routes.Book.detail("wok").toUrl() shouldBe "/book/wok"
        }

        test("/shelves takes nothing after it") {
            Route.parse("/shelves/extra").isServed() shouldBe false
        }
    })
