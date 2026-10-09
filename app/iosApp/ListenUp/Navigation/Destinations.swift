import Foundation
import Shared

// Type-safe navigation destinations. Separate `Hashable` structs (not an enum)
// so each destination evolves independently and `navigationDestination(for:)`
// matching stays clean. Every one is `Codable` too: a tab's `NavigationPath` is saved in
// `@SceneStorage` only when all of its destinations can be encoded (`MainShellModel.sceneState`).

/// Book detail screen.
struct BookDestination: Hashable, Codable {
    let id: String
}

/// Series detail screen.
struct SeriesDestination: Hashable, Codable {
    let id: String
}

/// Contributor (author/narrator) detail screen.
struct ContributorDestination: Hashable, Codable {
    let id: String
}

/// The full "See all" list of a contributor's books for one role, reached from a truncated
/// role carousel on `ContributorDetailView`. `contributorName` and `roleDisplayName` ride the
/// route so the screen titles immediately while Room hydrates the authoritative list.
struct ContributorBooksDestination: Hashable, Codable {
    let contributorId: String
    let role: String
    let contributorName: String
    let roleDisplayName: String
}

/// The flat classification axis a `FacetDestination` browses. A small native mirror of the
/// shared `FacetKind` enum, kept `Hashable` so it can ride a `NavigationPath` (the bridged
/// Kotlin `FacetKind` isn't `Hashable`); mapped to `Shared.FacetKind` at the VM `load` call.
enum FacetBrowseKind: Hashable, Codable {
    case tag
    case mood

    /// The shared-domain `FacetKind` this maps to, for `BrowseFacetViewModel.load`.
    /// Swift Export emits the Kotlin enum cases verbatim (`Tag` / `Mood`).
    var shared: FacetKind {
        switch self {
        case .tag: .Tag
        case .mood: .Mood
        }
    }
}

/// Facet-browse screen — every book carrying a given Tag or Mood. One parameterized
/// destination serves both axes; `kind` switches the look. `name` rides the route so the
/// hero renders immediately while Room hydrates the authoritative name and book set.
struct FacetDestination: Hashable, Codable {
    let kind: FacetBrowseKind
    let id: String
    let name: String
}

/// Browse-by-Genre screen — the genre hierarchy with a per-genre book list, reached by tapping a
/// genre chip on Book Detail. `genreName` rides the route so the title renders immediately while
/// Room hydrates the tree and the RPC returns the genre's books.
struct GenreDestination: Hashable, Codable {
    let genreId: String
    let genreName: String
}

/// Shelf detail screen — the books a user has curated onto one shelf.
struct ShelfDestination: Hashable, Codable {
    let id: String
}

/// The full single-type "See all" search page, reached from a capped result group whose
/// hit count exceeds its display cap. Carries the settled query and the one type to expand.
struct SearchSeeAllDestination: Hashable, Codable {
    let query: String
    let type: SearchSeeAllType
}

/// The capped-group hit kinds that own a "See all" page. Tags render inline uncapped, so
/// they are intentionally absent. A platform-native mirror of the shared `SearchHitType`
/// (which doesn't bridge as `Hashable`), kept Hashable so it can ride a `NavigationPath`.
enum SearchSeeAllType: Hashable, Codable {
    case book
    case contributor
    case series

    /// The shared-domain type this maps to, for `SeeAllSearchViewModel.load`.
    var hitType: SearchHitType {
        switch self {
        case .book: .book
        case .contributor: .contributor
        case .series: .series
        }
    }
}

/// The current user's profile.
struct UserProfileDestination: Hashable, Codable {}

/// Another user's profile, reached by tapping their avatar in the book Readers section,
/// the Leaderboard, or the Activity feed. Keyed by `userId`; the screen renders read-only.
struct ProfileDestination: Hashable, Codable {
    let userId: String
}

/// Settings.
struct SettingsDestination: Hashable, Codable {}

/// Storage management — downloaded books, per-book delete, clear-all, and free-space usage.
/// Reached from Settings › Downloads.
struct StorageDestination: Hashable, Codable {}

/// Administration dashboard (admin / root users only). Opened from Settings it starts at the top;
/// opened for a reason — an approvals tap — it carries a `focus` and lands on that section instead.
struct AdminDestination: Hashable, Codable {
    var focus: AdminFocus?
}

/// A section Administration can open on. Only sections someone is sent to by a notification.
enum AdminFocus: String, Hashable, Codable {
    /// The pending registrations, with their approve and deny controls.
    case pendingRegistrations
}

/// The admin inbox (admin / root users only), reached from Administration › Management.
/// Displays freshly-scanned books awaiting release into the library.
struct AdminInboxDestination: Hashable, Codable {}

/// The Audiobookshelf import hub (admin / root users only), reached from Administration ›
/// Management. Lists staged imports and launches the import wizard.
struct ABSImportDestination: Hashable, Codable {}

/// The Devices screen — lists the user's active sessions and lets them revoke devices.
struct DevicesDestination: Hashable, Codable {}

/// The notifications inbox, reached from the toolbar bell on the Home, Library, and
/// Discover tab roots (and, once routed, from a system push tap).
struct NotificationsDestination: Hashable, Codable {}

/// Per-type notification delivery preferences, reached from Settings › Account.
struct NotificationPrefsDestination: Hashable, Codable {}

/// The Hardcover connection screen, reached from Settings › Account.
struct HardcoverDestination: Hashable, Codable {}

/// The books kept off Hardcover (#1541), each with Sync Again.
struct HardcoverKeptOffDestination: Hashable, Codable {}

/// The Open Source Licenses screen — curated list of all bundled open-source libraries.
struct LicensesDestination: Hashable, Codable {}

/// The full license text for a single open-source library.
struct LicenseDetailDestination: Hashable, Codable {
    let packageName: String
}

/// The Admin Collections list (admin / root users only), reached from Administration › Management.
struct AdminCollectionsDestination: Hashable, Codable {}

/// The Categories tree, reached from Administration › Management, and from Settings › Library by
/// members with Curate library.
struct AdminCategoriesDestination: Hashable, Codable {}

/// Admin → a specific user's detail: who they are, and the way into their role and permissions.
struct UserDetailDestination: Hashable, Codable {
    let userId: String
}

/// Admin → a user → their role and permissions.
struct UserPermissionsDestination: Hashable, Codable {
    let userId: String
}

/// A single Admin Collection detail, reached from the Admin Collections list.
struct AdminCollectionDetailDestination: Hashable, Codable {
    let collectionId: String
}

/// The Library Settings screen (admin / root users only), reached from Administration ›
/// Management. Manages the single library's scan folders and triggers a rescan.
struct LibrarySettingsDestination: Hashable, Codable {}

/// The File organization screen (admin / root users only), reached from Administration ›
/// Management. Sets where books live on disk, and sweeps the existing library into that shape.
struct OrganizeSettingsDestination: Hashable, Codable {}

/// The Upload Books screen (admin / root users only), reached from Administration › Management.
/// Streams a picked folder or set of files from this device into the library.
struct UploadBooksDestination: Hashable, Codable {}

/// The admin Backups screen (admin / root users only), reached from Administration › Management.
/// Lists server backups; creates, deletes, restores, and restores-from-file.
struct AdminBackupsDestination: Hashable, Codable {}

/// The destructive restore-confirmation flow for one staged backup.
struct RestoreBackupDestination: Hashable, Codable {
    let backupId: String
}
