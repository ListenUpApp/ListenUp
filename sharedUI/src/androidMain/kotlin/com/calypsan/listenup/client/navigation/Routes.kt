package com.calypsan.listenup.client.navigation

import androidx.navigation3.runtime.NavKey
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.client.domain.model.FacetKind
import kotlinx.serialization.Serializable

/**
 * Type-safe navigation routes for ListenUp Android app.
 *
 * Using Navigation 3 with Kotlinx Serialization for compile-time
 * safety and automatic argument passing.
 *
 * All routes must implement the sealed [Route] interface to ensure
 * type safety in the navigation graph.
 *
 * Auth-related routes (ServerSelect, ServerSetup, Setup, Login, Register)
 * are defined in commonMain/AuthRoutes.kt as AuthRoute subtypes.
 */
@Serializable
sealed interface Route : NavKey

/**
 * App shell - main authenticated container.
 *
 * Contains the bottom navigation bar with Home, Library, and Discover tabs.
 * Tab switching is handled internally within the shell.
 */
@Serializable
data object Shell : Route

/**
 * Book detail screen - displays full book info and chapters.
 *
 * @property bookId The unique ID of the book to display.
 */
@Serializable
data class BookDetail(
    val bookId: String,
) : Route

/**
 * Book readers screen - the full "See all" list of everyone reading or who has finished a book.
 *
 * Reached from the Readers section's "See all" affordance on [BookDetail]. Renders the uncapped
 * flattened reader list (the section caps at five).
 *
 * @property bookId The unique ID of the book whose readers to display.
 */
@Serializable
data class BookReaders(
    val bookId: String,
) : Route

/**
 * Book edit screen - edit book metadata and contributors.
 *
 * Allows editing title, subtitle, description, series info, publish year,
 * and managing contributors (authors/narrators) with autocomplete search.
 *
 * @property bookId The unique ID of the book to edit.
 */
@Serializable
data class BookEdit(
    val bookId: String,
) : Route

/**
 * Match preview screen - preview Audible metadata before applying.
 *
 * Shows side-by-side comparison of current book metadata vs metadata
 * from Audible. User can confirm to apply the changes.
 *
 * @property bookId The unique ID of the book to update.
 * @property asin The Audible ASIN of the matched book.
 * @property region The Audible region the match was found in, carried over from the search screen so
 *   the preview fetches in the same storefront instead of re-defaulting to US.
 */
@Serializable
data class MatchPreview(
    val bookId: String,
    val asin: String,
    val region: MetadataLocale,
) : Route

/**
 * Book metadata search screen - search Audible for metadata matches.
 *
 * Shows search results from Audible. Selecting a result navigates to
 * the MatchPreview screen.
 *
 * @property bookId The unique ID of the book to find metadata for.
 */
@Serializable
data class MetadataSearch(
    val bookId: String,
) : Route

/**
 * Series detail screen - displays series info and its books.
 *
 * @property seriesId The unique ID of the series to display.
 */
@Serializable
data class SeriesDetail(
    val seriesId: String,
) : Route

/**
 * Tag detail screen - displays tag info and books with this tag.
 *
 * @property tagId The unique ID of the tag to display.
 */
@Serializable
data class TagDetail(
    val tagId: String,
) : Route

/**
 * Series edit screen - edit series metadata and cover.
 *
 * Allows editing name, description, and cover image for a series.
 *
 * @property seriesId The unique ID of the series to edit.
 */
@Serializable
data class SeriesEdit(
    val seriesId: String,
) : Route

/**
 * Contributor detail screen - displays contributor info and books by role.
 *
 * A contributor is a person who may have multiple roles (author, narrator, etc.)
 * across different books. This screen shows all their work grouped by role.
 *
 * @property contributorId The unique ID of the contributor to display.
 */
@Serializable
data class ContributorDetail(
    val contributorId: String,
) : Route

/**
 * Contributor books screen - displays all books for a contributor in a specific role.
 *
 * Shows books grouped by series with standalone books at the bottom.
 * Accessed via "View All" from the contributor detail screen.
 *
 * @property contributorId The contributor's unique ID.
 * @property role The role to filter by (e.g., "author", "narrator").
 */
@Serializable
data class ContributorBooks(
    val contributorId: String,
    val role: String,
) : Route

/**
 * Contributor edit screen - edit contributor metadata and manage aliases.
 *
 * Allows editing name, biography, website, dates, and adding/removing aliases.
 * Adding an alias from search results triggers a merge operation.
 *
 * @property contributorId The unique ID of the contributor to edit.
 */
@Serializable
data class ContributorEdit(
    val contributorId: String,
) : Route

/**
 * Contributor metadata search screen - search Audible for contributor.
 *
 * Shows search field, region selector, and results list.
 * Selecting a result navigates to the preview screen.
 *
 * @property contributorId The unique ID of the contributor to match.
 */
@Serializable
data class ContributorMetadataSearch(
    val contributorId: String,
) : Route

/**
 * Contributor metadata preview screen - preview Audible metadata before applying.
 *
 * Shows side-by-side comparison of current contributor data vs the fetched
 * profile. The server applies asin + biography + photo; there is no per-field
 * selection (matching the server's behavior).
 *
 * @property contributorId The unique ID of the contributor to update.
 * @property asin The Audible ASIN of the matched contributor.
 * @property region The region the match was found in, carried over from the search screen so the
 *   preview fetches from the same catalog instead of re-defaulting to US — profiles are
 *   region-localized (mirrors [MatchPreview.region]).
 */
@Serializable
data class ContributorMetadataPreview(
    val contributorId: String,
    val asin: String,
    val region: MetadataLocale,
) : Route

/**
 * Invite registration screen - claim an invite and create account.
 *
 * Shown when the app is opened via an invite deep link.
 * User only needs to set a password; other details come from the invite.
 *
 * @property serverUrl The server URL from the invite link.
 * @property inviteCode The invite code from the URL.
 */
@Serializable
data class InviteRegistration(
    val serverUrl: String,
    val inviteCode: String,
) : Route

// Admin Routes

/**
 * Admin screen - combined users and invites management.
 *
 * Shows users list, pending invites, and invite action.
 * Only accessible to admin users (root or role=admin).
 */
@Serializable
data object Admin : Route

/**
 * Create invite screen - create a new invite.
 *
 * Form for name, email, role, and expiration.
 */
@Serializable
data object CreateInvite : Route

/**
 * Admin collections screen - manage collections.
 *
 * Shows list of collections with create/delete functionality.
 * Only accessible to admin users (root or role=admin).
 */
@Serializable
data object AdminCollections : Route

/**
 * Admin collection detail screen - view and edit a collection.
 *
 * Shows collection details, allows name editing, and displays books.
 *
 * @property collectionId The unique ID of the collection to display.
 */
@Serializable
data class AdminCollectionDetail(
    val collectionId: String,
) : Route

/**
 * Admin inbox screen - review newly scanned books.
 *
 * Shows books waiting for admin review before becoming visible.
 * Supports batch selection and release operations.
 */
@Serializable
data object AdminInbox : Route

/**
 * Admin categories screen - view the genre hierarchy tree.
 *
 * Shows all system genres in a hierarchical tree view with
 * expandable/collapsible nodes and book counts.
 */
@Serializable
data object AdminCategories : Route

/**
 * Browse-by-Genre screen — tree of genres with a per-genre book list and an
 * `includeDescendants` toggle for subtree expansion.
 *
 * @property genreId Optional genre to pre-select on open (e.g. the genre chip tapped on Book
 *   Detail). `null` opens the screen on the full tree with no genre selected.
 */
@Serializable
data class BrowseGenre(
    val genreId: String? = null,
) : Route

/**
 * Facet-browse screen — every book carrying a flat facet (a Tag or a Mood), reached by tapping a
 * tag/mood chip on Book Detail. One parameterized route over both flat axes; genres keep
 * [BrowseGenre]. The [facetName] travels on the route so the hero renders immediately while the
 * Room observation hydrates the authoritative name and book set.
 *
 * @property kind Which flat classification axis this browse lists.
 * @property facetId The tag or mood ID to list books for.
 * @property facetName The facet's display name, for the immediate hero label.
 */
@Serializable
data class BrowseFacet(
    val kind: FacetKind,
    val facetId: String,
    val facetName: String,
) : Route

/**
 * Admin user detail screen - view and edit a user's details and permissions.
 *
 * Shows user information and allows toggling canShare permission.
 *
 * @property userId The unique ID of the user to display.
 */
@Serializable
data class AdminUserDetail(
    val userId: String,
) : Route

/**
 * Admin library settings screen — manage the library's scan folders.
 *
 * Operates on THE singleton library — no id parameter required. (Inbox quarantine
 * is a server-wide Admin Setting, not a per-library toggle.)
 */
@Serializable
data object AdminLibrarySettings : Route

// Admin Backup Routes

/**
 * Admin backups screen - manage server backups.
 *
 * Shows list of backups with create/delete/restore functionality.
 * Only accessible to admin users (root or role=admin).
 */
@Serializable
data object AdminBackups : Route

/**
 * Create backup screen - create a new server backup.
 *
 * Form for backup options (include images, include events).
 */
@Serializable
data object CreateBackup : Route

/**
 * Restore backup screen - restore from a specific backup.
 *
 * Multi-step flow for choosing mode, strategy, and confirmation.
 *
 * @property backupId The ID of the backup to restore from.
 */
@Serializable
data class RestoreBackup(
    val backupId: String,
) : Route

/**
 * Restore-from-file screen — pick a `.listenup.zip`, upload it, then continue into [RestoreBackup].
 *
 * The upload step lives here; the destructive restore-confirmation flow is handled by [RestoreBackup].
 */
@Serializable
data object RestoreFromFile : Route

/**
 * Rebuilt ABS import flow screen — single-screen host for the full import pipeline.
 *
 * Drives [com.calypsan.listenup.client.presentation.admin.imports.ImportFlowViewModel] through
 * Idle → Uploading → Analyzing → Review → Applying → Done (or Error). The file picker lives
 * inside the screen's Idle state, so no [FileSource] argument is needed here.
 */
@Serializable
data object ImportFlow : Route

/**
 * Settings screen - app preferences and configuration.
 */
@Serializable
data object Settings : Route

/**
 * Devices screen - lists the caller's active sessions with per-device sign-out.
 */
@Serializable
data object Devices : Route

/**
 * Licenses screen - open source library acknowledgements.
 */
@Serializable
data object Licenses : Route

/**
 * License detail screen - displays license text or link for a specific library.
 *
 * @property uniqueId The AboutLibraries unique ID of the library.
 */
@Serializable
data class LicenseDetail(
    val uniqueId: String,
) : Route

/**
 * Storage screen - manage downloaded audiobook files.
 */
@Serializable
data object Storage : Route

// Shelf Routes

/**
 * Shelf detail screen - displays shelf info and its books.
 *
 * Shows the shelf name, description, owner info, and list of books.
 * Owners can edit the shelf, add/remove books.
 *
 * @property shelfId The unique ID of the shelf to display.
 */
@Serializable
data class ShelfDetail(
    val shelfId: String,
) : Route

/**
 * Create shelf screen - create a new personal shelf.
 *
 * Form for name and optional description.
 */
@Serializable
data object CreateShelf : Route

/**
 * Edit shelf screen - edit an existing shelf.
 *
 * Form for name and description. Owner only.
 *
 * @property shelfId The unique ID of the shelf to edit.
 */
@Serializable
data class ShelfEdit(
    val shelfId: String,
) : Route

// Library Setup Route

/**
 * Library setup screen - configure library scan paths.
 *
 * Shown to admin users after login when the server needs a library configured.
 * Admin browses the server filesystem to select audiobook folders.
 */
@Serializable
data object LibrarySetup : Route

// Profile Routes

/**
 * User profile screen - displays a user's full profile with stats and activity.
 *
 * Shows avatar, display name, tagline, listening stats, recent books,
 * and public shelves. If viewing own profile, shows edit option.
 *
 * @property userId The unique ID of the user to display.
 */
@Serializable
data class UserProfile(
    val userId: String,
) : Route

/**
 * Edit profile screen - edit own profile settings.
 *
 * Allows changing tagline and avatar (upload image or revert to auto).
 */
@Serializable
data object EditProfile : Route

/**
 * In-app PDF document viewer — renders a local file using [android.graphics.pdf.PdfRenderer].
 *
 * [localPath] is the absolute filesystem path to the cached PDF file. It is carried as a
 * [Route] property (Navigation 3 serialisation) rather than URL-encoded in a string template,
 * so the path survives round-trips through the back-stack without encoding issues.
 *
 * @property localPath Absolute path to the local PDF file to display.
 */
@Serializable
data class DocumentViewer(
    val localPath: String,
) : Route
