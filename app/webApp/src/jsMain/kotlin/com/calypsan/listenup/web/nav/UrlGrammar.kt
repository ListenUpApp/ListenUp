package com.calypsan.listenup.web.nav

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.domain.model.FacetKind
import com.calypsan.listenup.client.domain.model.SearchHitType
import com.calypsan.listenup.web.features.search.label
import com.calypsan.listenup.web.features.shelf.shelfRouteOf
import com.calypsan.listenup.web.shell.RouteContent

/**
 * Whether any page serves this path's shape — the whole URL grammar, in one table.
 *
 * Each page's branch reads only the segments it cares about, so without this a link that lost its
 * id (`/book`) or grew a segment nothing reads (`/book/42/nonsense`) resolved to whatever page its
 * prefix matched — the library grid, or the book — and a broken link looked like a working one.
 * The same rule `/admin/user` already followed: a path no page names is not found.
 */
internal fun Route.isServed(): Boolean = segments.isEmpty() || ROUTE_SHAPES[segments.first()]?.invoke(segments) == true

/** `/{key}` alone, or `/{key}/{section}` for one of [sections]. */
private fun List<String>.isSection(vararg sections: String): Boolean = size == 1 || (size == 2 && this[1] in sections)

/** `/{key}/{id}`, or `/{key}/{id}/{sub}` for one of [subs]. */
private fun List<String>.isIdWith(vararg subs: String): Boolean = size == 2 || (size == 3 && this[2] in subs)

/** `/settings`, `/settings/{section}`, and `/settings/hardcover/kept-off` — the one page under a section. */
private fun List<String>.isSettingsShape(): Boolean =
    isSection(DEVICES_KEY, NOTIFICATIONS_KEY, HARDCOVER_KEY, LICENCES_KEY, CATEGORIES_KEY) ||
        (size == 3 && this[1] == HARDCOVER_KEY && this[2] == KEPT_OFF_KEY)

/**
 * `/admin`, `/admin/{page}`, and the three families under it that name one thing by id — plus
 * `/admin/imports/new`, whose third segment is a literal rather than an id.
 */
private fun List<String>.isAdminShape(): Boolean =
    size <= 2 ||
        (size == 3 && (this[1] in ADMIN_ID_PAGES || (this[1] == IMPORTS_KEY && this[2] == NEW_KEY)))

/**
 * Every route the shell navigates to, by name: the constructors for the shapes [ROUTE_SHAPES] accepts.
 *
 * The larger families are nested ([Book], [Contributor], [Admin]) so no one object carries every
 * route in the app.
 */
internal object Routes {
    // Properties with getters, not stored values: `Route` compares by identity, and the router only
    // announces a navigation whose route is a different instance from the current one.
    val home: Route get() = Route(emptyList())

    val library: Route get() = Route(listOf(LIBRARY_KEY))
    val libraryContributors: Route get() = Route(listOf(LIBRARY_KEY, CONTRIBUTORS_KEY))
    val librarySeries: Route get() = Route(listOf(LIBRARY_KEY, SERIES_KEY))

    /** `/discover?section=leaderboard`: Discover with its leaderboard in view on Week (spec §6.4). */
    val discoverLeaderboard: Route get() = Route(listOf(DISCOVER_KEY), mapOf(SECTION_QUERY_KEY to LEADERBOARD_SECTION))

    /** `/library?sort=added-desc`: the Books grid, newest first, for this visit (spec §6.4, plan Q6). */
    val libraryNewestFirst: Route get() = Route(listOf(LIBRARY_KEY), mapOf(SORT_QUERY_KEY to NEWEST_SORT))

    /** `/library?status=in-progress`: the Books grid filtered to In progress, for this visit (spec §6.4). */
    val libraryInProgress: Route get() = Route(listOf(LIBRARY_KEY), mapOf(STATUS_QUERY_KEY to IN_PROGRESS_STATUS))

    /** `/shelves`: the reader's own shelves, reached from Home's See all (spec §3.2 H24). */
    val shelves: Route get() = Route(listOf(SHELVES_KEY))

    val search: Route get() = Route(listOf(SEARCH_KEY))

    val discover: Route get() = Route(listOf(DISCOVER_KEY))

    /** See all activity: the full community feed (X33). */
    val discoverActivity: Route get() = Route(listOf(DISCOVER_KEY, ACTIVITY_KEY))

    val settings: Route get() = Route(listOf(SETTINGS_KEY))
    val settingsCategories: Route get() = Route(listOf(SETTINGS_KEY, CATEGORIES_KEY))
    val settingsDevices: Route get() = Route(listOf(SETTINGS_KEY, DEVICES_KEY))
    val settingsHardcover: Route get() = Route(listOf(SETTINGS_KEY, HARDCOVER_KEY))
    val settingsHardcoverKeptOff: Route get() = Route(listOf(SETTINGS_KEY, HARDCOVER_KEY, KEPT_OFF_KEY))
    val settingsLicences: Route get() = Route(listOf(SETTINGS_KEY, LICENCES_KEY))
    val settingsNotifications: Route get() = Route(listOf(SETTINGS_KEY, NOTIFICATIONS_KEY))

    val newShelf: Route get() = Route(listOf(SHELF_KEY, NEW_KEY))

    fun genre(id: String): Route = Route(listOf(GENRE_KEY, id))

    fun mood(id: String): Route = Route(listOf(MOOD_KEY, id))

    fun tag(id: String): Route = Route(listOf(TAG_KEY, id))

    fun profile(id: String): Route = Route(listOf(PROFILE_KEY, id))

    fun profileEdit(id: String): Route = Route(listOf(PROFILE_KEY, id, EDIT_KEY))

    fun series(id: String): Route = Route(listOf(SERIES_KEY, id))

    fun seriesEdit(id: String): Route = Route(listOf(SERIES_KEY, id, EDIT_KEY))

    fun shelf(id: String): Route = Route(listOf(SHELF_KEY, id))

    fun shelfEdit(id: String): Route = Route(listOf(SHELF_KEY, id, EDIT_KEY))

    /** `/search?q=…`: the search page seeded with [query]. */
    fun searchFor(query: String): Route = Route(listOf(SEARCH_KEY), mapOf(SEARCH_QUERY_KEY to query))

    /** `/search/{type}`: the single-type page, carrying [query] (the URL's own parameters). */
    fun searchType(
        slug: String,
        query: Map<String, String>,
    ): Route = Route(listOf(SEARCH_KEY, slug), query)

    /** `/books/edit?ids=…`: the bulk editor over [bookIds]. */
    fun bulkEdit(bookIds: Collection<String>): Route =
        Route(listOf(BOOKS_KEY, EDIT_KEY), mapOf(IDS_QUERY_KEY to bookIds.joinToString(",")))

    /** `/book/{id}` and the pages under it. */
    object Book {
        /** `/book/{id}`, or `/book/{id}?section=readers` with that section in view ([READERS_SECTION], spec §6.4). */
        fun detail(
            id: String,
            section: String? = null,
        ): Route = Route(listOf(BOOK_KEY, id), section?.let { mapOf(SECTION_QUERY_KEY to it) }.orEmpty())

        fun chapters(id: String): Route = Route(listOf(BOOK_KEY, id, CHAPTERS_KEY))

        /** `/book/{id}/map`: the chapter map, its selection inspector and the full chapter table. */
        fun chapterMap(id: String): Route = Route(listOf(BOOK_KEY, id, CHAPTER_MAP_KEY))

        /** `/book/{id}/files`: the audio files, their specs and the book's documents. */
        fun files(id: String): Route = Route(listOf(BOOK_KEY, id, FILES_KEY))

        fun edit(id: String): Route = Route(listOf(BOOK_KEY, id, EDIT_KEY))

        fun hardcover(id: String): Route = Route(listOf(BOOK_KEY, id, HARDCOVER_KEY))

        fun match(id: String): Route = Route(listOf(BOOK_KEY, id, MATCH_KEY))

        fun readers(id: String): Route = Route(listOf(BOOK_KEY, id, READERS_KEY))
    }

    /** `/contributor/{id}` and the pages under it. */
    object Contributor {
        fun detail(id: String): Route = Route(listOf(CONTRIBUTOR_KEY, id))

        fun edit(id: String): Route = Route(listOf(CONTRIBUTOR_KEY, id, EDIT_KEY))

        fun match(id: String): Route = Route(listOf(CONTRIBUTOR_KEY, id, MATCH_KEY))
    }

    /** `/admin` and the pages under it. */
    object Admin {
        val root: Route get() = Route(listOf(ADMIN_KEY))
        val backups: Route get() = Route(listOf(ADMIN_KEY, BACKUPS_KEY))
        val categories: Route get() = Route(listOf(ADMIN_KEY, CATEGORIES_KEY))
        val collections: Route get() = Route(listOf(ADMIN_KEY, COLLECTIONS_KEY))
        val imports: Route get() = Route(listOf(ADMIN_KEY, IMPORTS_KEY))
        val newImport: Route get() = Route(listOf(ADMIN_KEY, IMPORTS_KEY, NEW_KEY))
        val inbox: Route get() = Route(listOf(ADMIN_KEY, INBOX_KEY))
        val invite: Route get() = Route(listOf(ADMIN_KEY, INVITE_KEY))
        val library: Route get() = Route(listOf(ADMIN_KEY, LIBRARY_KEY))
        val organize: Route get() = Route(listOf(ADMIN_KEY, ORGANIZE_KEY))
        val serverSettings: Route get() = Route(listOf(ADMIN_KEY, SETTINGS_KEY))
        val upload: Route get() = Route(listOf(ADMIN_KEY, UPLOAD_KEY))

        /** Admin opened on the pending-requests section (`?section=pending`). */
        val pending: Route get() = Route(listOf(ADMIN_KEY), mapOf(SECTION_QUERY_KEY to PENDING_SECTION))

        fun backup(id: String): Route = Route(listOf(ADMIN_KEY, BACKUPS_KEY, id))

        fun collection(id: String): Route = Route(listOf(ADMIN_KEY, COLLECTIONS_KEY, id))

        fun user(id: String): Route = Route(listOf(ADMIN_KEY, USER_KEY, id))
    }
}

/** Every first segment the shell serves, and the shapes that may follow it. */
private val ROUTE_SHAPES: Map<String, (List<String>) -> Boolean> =
    mapOf(
        HOME_KEY to { it.size == 1 },
        DISCOVER_KEY to { it.size == 1 || (it.size == 2 && it[1] == ACTIVITY_KEY) },
        NOTIFICATIONS_KEY to { it.size == 1 },
        LIBRARY_KEY to { it.isSection(CONTRIBUTORS_KEY, SERIES_KEY) },
        SEARCH_KEY to { it.size <= 2 },
        SETTINGS_KEY to { it.isSettingsShape() },
        BOOK_KEY to
            { it.isIdWith(EDIT_KEY, CHAPTERS_KEY, CHAPTER_MAP_KEY, FILES_KEY, MATCH_KEY, READERS_KEY, HARDCOVER_KEY) },
        BOOKS_KEY to { it.size == 2 && it[1] == EDIT_KEY },
        CONTRIBUTOR_KEY to { it.isIdWith(EDIT_KEY, BOOKS_KEY, MATCH_KEY) },
        SERIES_KEY to { it.isIdWith(EDIT_KEY) },
        PROFILE_KEY to { it.isIdWith(EDIT_KEY) },
        TAG_KEY to { it.size == 2 },
        MOOD_KEY to { it.size == 2 },
        GENRE_KEY to { it.size == 2 },
        SHELF_KEY to { shelfRouteOf(it) != null },
        SHELVES_KEY to { shelfRouteOf(it) != null },
        ADMIN_KEY to { it.isAdminShape() },
    )

/**
 * `/books/edit?ids=…` — the books the bulk editor is open over, or empty when this is not that route.
 *
 * The ids ride the query rather than the path: a path segment of forty uuids is not a URL anyone
 * can read, and unlike a grid selection this one is worth keeping across a reload.
 */
internal fun Route.bulkEditIds(): List<String> {
    if (segments.firstOrNull() != BOOKS_KEY || segments.getOrNull(1) != EDIT_KEY) return emptyList()
    return query[IDS_QUERY_KEY]
        .orEmpty()
        .split(",")
        .filter { it.isNotBlank() }
}

/**
 * `/{key}/{id}` — the thing this route names, or null when the route isn't about one at all.
 */
internal fun Route.idUnder(key: String): String? = if (segments.firstOrNull() == key) segments.getOrNull(1) else null

/**
 * `/{key}/{id}/edit` — the same [id] again, but only when the route asks for the form over it.
 *
 * The form is a route of its own rather than a mode of the page beneath it, so it is linkable,
 * Back leaves it, and a half-finished edit can never be mistaken for the thing it will become.
 */
internal fun Route.editTargetOf(id: String?): String? =
    if (id != null &&
        segments.getOrNull(2) == EDIT_KEY
    ) {
        id
    } else {
        null
    }

/**
 * Which shelf a browse route names, or null when the route is about something else.
 *
 * One value rather than three nullable ids, and one branch in [RouteContent] rather than three:
 * the three paths differ only in what the page reads, and [RouteContent] is meant to stay a single
 * glance of routing.
 */
internal sealed interface BrowseTarget {
    data class Facet(
        val kind: FacetKind,
        val id: String,
    ) : BrowseTarget

    data class Genre(
        val id: String,
    ) : BrowseTarget
}

internal fun Route.browseTarget(): BrowseTarget? =
    idUnder(TAG_KEY)?.let { BrowseTarget.Facet(FacetKind.Tag, it) }
        ?: idUnder(MOOD_KEY)?.let { BrowseTarget.Facet(FacetKind.Mood, it) }
        ?: idUnder(GENRE_KEY)?.let { BrowseTarget.Genre(it) }

/**
 * The URL spelling of a hit type: `/search/books`, `/search/contributors`, `/search/series`,
 * `/search/tags`.
 *
 * ⛔ Derived from the label the page already shows, not from `name`. Two reasons, and the second is
 * the one that matters: `CONTRIBUTOR.name.lowercase()` is the singular word the *database* uses,
 * and a hand-written second table would let the URL and the heading drift apart — a reader
 * following `/search/people` to a page titled "Contributors" is being shown two names for one
 * thing. One vocabulary, one source.
 */
internal fun SearchHitType.slug(): String = label().lowercase()

/** Which type `/search/{type}` names, or null when the route is not a see-all. */
internal fun Route.seeAllType(): SearchHitType? {
    if (segments.firstOrNull() != SEARCH_KEY) return null
    val slug = segments.getOrNull(1) ?: return null
    return SearchHitType.entries.firstOrNull { it.slug() == slug }
}

internal const val HOME_KEY = "home"

internal const val DISCOVER_KEY = "discover"

/** `/discover/activity` — See all activity. */
internal const val ACTIVITY_KEY = "activity"

private const val SHELF_KEY = "shelf"

/** `/shelves` — the reader's own shelves. */
internal const val SHELVES_KEY = "shelves"

internal const val NEW_KEY = "new"

internal const val BOOK_KEY = "book"

/** The trailing segment that turns a book route into its edit form. */
internal const val EDIT_KEY = "edit"

/** `/book/{id}/chapters` — the chapter editor over one book. */
internal const val CHAPTERS_KEY = "chapters"

/**
 * `/book/{id}/map` — the chapter map and table, with `?sel=` its selection. Not `chapters`: that is the editor. Book
 * Detail is one page, and its old `?tab=chapters` links land here.
 */
internal const val CHAPTER_MAP_KEY = "map"

/** `/book/{id}/files` — the audio files and documents; where Book Detail's old `?tab=files` links land. */
internal const val FILES_KEY = "files"

/** `/book/{id}?tab=…` — the retired pane parameter, read only to redirect an old link. */
internal const val LEGACY_TAB_QUERY_KEY = "tab"

/** `/book/{id}/map?sel=9,10` — the chapter map's selection. */
internal const val SELECTION_QUERY_KEY = "sel"

/** `/book/{id}/readers` — everyone on this book. */
internal const val READERS_KEY = "readers"

/**
 * The plural-books segment, in both places it appears: `/books/edit?ids=…` (the bulk editor over a
 * selection) and `/contributor/{id}/books?role=…` (one person's whole list in one role).
 */
internal const val BOOKS_KEY = "books"

/** The selection the bulk editor edits, comma-separated. */
private const val IDS_QUERY_KEY = "ids"

/** `/book/{id}/match` — Match details over one book; `/contributor/{id}/match`, over one person. */
internal const val MATCH_KEY = "match"

/** `/book/{id}/match?view=compare` — Match details' Compare editions page. */
internal const val VIEW_QUERY_KEY = "view"

internal const val COMPARE_VIEW = "compare"

/**
 * `/admin?section=pending` — Admin, landed on the people waiting to join. A query rather than a
 * path segment: it is the same page arriving somewhere in particular, so the page-focus memory that
 * keys on the path treats it as Admin, and a plain `/admin` link still opens at the top.
 */
internal const val SECTION_QUERY_KEY = "section"

/** The Library's one-visit sort: `/library?sort=added-desc`. */
internal const val SORT_QUERY_KEY = "sort"

/** [SORT_QUERY_KEY]'s value for "Added, newest first". */
internal const val NEWEST_SORT = "added-desc"

internal const val PENDING_SECTION = "pending"

/** [SECTION_QUERY_KEY]'s value for Discover's leaderboard landing. */
internal const val LEADERBOARD_SECTION = "leaderboard"

/** [SECTION_QUERY_KEY]'s value for Book Detail's Readers landing: Home's finishers line. */
internal const val READERS_SECTION = "readers"

/** The Library's one-visit reading-state filter: `/library?status=in-progress`. */
internal const val STATUS_QUERY_KEY = "status"

/** [STATUS_QUERY_KEY]'s value for "In progress". */
internal const val IN_PROGRESS_STATUS = "in-progress"

internal const val LIBRARY_KEY = "library"

/** `/tag/{id}` — one tag's shelf. */
private const val TAG_KEY = "tag"

/** `/mood/{id}` — one mood's shelf. */
private const val MOOD_KEY = "mood"

/** `/genre/{id}` — one genre, its sub-genres, and everything filed under it. */
private const val GENRE_KEY = "genre"

/** The trailing segment that turns the Library route into the Contributors list. */
internal const val CONTRIBUTORS_KEY = "contributors"

/** The path segment that opens a contributor's own page — `/contributor/{id}`. */
internal const val CONTRIBUTOR_KEY = "contributor"

/** The path segment that opens a series in reading order — `/series/{id}`. */
internal const val SERIES_KEY = "series"

/**
 * The routes reached FROM the library that still belong to it, though none of them is a `/library`
 * URL. Each keeps Library lit in the sidebar; leaving no entry highlighted reads as having
 * navigated out of the app.
 */
internal val LIBRARY_DEEP_LINKS = setOf(BOOK_KEY, CONTRIBUTOR_KEY, SERIES_KEY)

/** The routes reached FROM Home that still belong to it: `/shelves`, its My shelves See all. Each keeps Home lit. */
internal val HOME_DEEP_LINKS = setOf(SHELVES_KEY)

internal const val ROLE_QUERY_KEY = "role"

internal const val NARRATOR_ROLE_VALUE = "narrator"

internal const val SEARCH_KEY = "search"

/** The query-string key a search lives under: `/search?q=dune`. */
internal const val SEARCH_QUERY_KEY = "q"

internal const val ADMIN_KEY = "admin"

/** The path segment that opens the Audiobookshelf imports — `/admin/imports`. */
internal const val IMPORTS_KEY = "imports"

/** The path segment that opens the archives — `/admin/backups`. */
internal const val BACKUPS_KEY = "backups"

/** The path segment that opens the curated groups — `/admin/collections`. */
internal const val COLLECTIONS_KEY = "collections"

/** The path segment that opens the genre tree — `/admin/categories`. */
internal const val CATEGORIES_KEY = "categories"

/** `/admin/invite` — minting one. */
internal const val INVITE_KEY = "invite"

/** `/admin/user/{id}` — one member and what they may do. */
internal const val USER_KEY = "user"

/** The admin pages that name one thing by the segment after them: `/admin/{page}/{id}`. */
private val ADMIN_ID_PAGES = setOf(COLLECTIONS_KEY, BACKUPS_KEY, USER_KEY)

/** `/admin/upload` — putting books in from this machine. */
internal const val UPLOAD_KEY = "upload"

/** `/admin/organize` — where books live on disk. */
internal const val ORGANIZE_KEY = "organize"

/** The path segment that opens the scanner's triage queue — `/admin/inbox`. */
internal const val INBOX_KEY = "inbox"

internal const val SETTINGS_KEY = "settings"

/** `/settings/licences` — the open-source acknowledgements for what the browser actually loads. */
internal const val LICENCES_KEY = "licences"

internal const val DEVICES_KEY = "devices"

/** `/settings/hardcover` — connecting a Hardcover account. */
internal const val HARDCOVER_KEY = "hardcover"

/** `/settings/hardcover/kept-off` — the books kept off Hardcover (#1541). */
internal const val KEPT_OFF_KEY = "kept-off"

/** The path segment that opens a listener's own page — `/profile/{userId}`. */
internal const val PROFILE_KEY = "profile"

/** The path segment that opens the notification inbox — `/notifications`. */
internal const val NOTIFICATIONS_KEY = "notifications"
