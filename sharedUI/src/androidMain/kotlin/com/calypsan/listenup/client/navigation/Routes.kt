package com.calypsan.listenup.client.navigation

import androidx.navigation3.runtime.NavKey
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
 */
@Serializable
data class MatchPreview(
    val bookId: String,
    val asin: String,
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
 * Shows side-by-side comparison of current contributor data vs Audible data.
 * User can toggle which fields to apply (name, biography, image).
 *
 * @property contributorId The unique ID of the contributor to update.
 * @property asin The Audible ASIN of the matched contributor.
 */
@Serializable
data class ContributorMetadataPreview(
    val contributorId: String,
    val asin: String,
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
 * Admin library settings screen - view and edit library settings.
 *
 * Shows library information and allows changing access mode and inbox settings.
 *
 * @property libraryId The unique ID of the library to configure.
 */
@Serializable
data class AdminLibrarySettings(
    val libraryId: String,
) : Route

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
 * ABS import list screen - shows all ABS imports with their status.
 *
 * Entry point for managing Audiobookshelf imports.
 */
@Serializable
data object ABSImportList : Route

/**
 * ABS import detail screen - shows a single import with mapping UI.
 *
 * @param importId The ID of the import to display.
 */
@Serializable
data class ABSImportDetail(
    val importId: String,
) : Route

/**
 * ABS import screen (legacy) - wizard-style import from Audiobookshelf backup.
 *
 * Multi-step flow for analyze, map users/books, and import.
 */
@Serializable
data object ABSImport : Route

/**
 * Settings screen - app preferences and configuration.
 */
@Serializable
data object Settings : Route

/**
 * Licenses screen - open source library acknowledgements.
 */
@Serializable
data object Licenses : Route

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
