import Foundation
import Shared

// Type-safe navigation destinations. Separate `Hashable` structs (not an enum)
// so each destination evolves independently and `navigationDestination(for:)`
// matching stays clean.

/// Book detail screen.
struct BookDestination: Hashable {
    let id: String
}

/// Series detail screen.
struct SeriesDestination: Hashable {
    let id: String
}

/// Contributor (author/narrator) detail screen.
struct ContributorDestination: Hashable {
    let id: String
}

/// The full "See all" list of a contributor's books for one role, reached from a truncated
/// role carousel on `ContributorDetailView`. `contributorName` and `roleDisplayName` ride the
/// route so the screen titles immediately while Room hydrates the authoritative list.
struct ContributorBooksDestination: Hashable {
    let contributorId: String
    let role: String
    let contributorName: String
    let roleDisplayName: String
}

/// The flat classification axis a `FacetDestination` browses. A small native mirror of the
/// shared `FacetKind` enum, kept `Hashable` so it can ride a `NavigationPath` (the bridged
/// Kotlin `FacetKind` isn't `Hashable`); mapped to `Shared.FacetKind` at the VM `load` call.
enum FacetBrowseKind: Hashable {
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
struct FacetDestination: Hashable {
    let kind: FacetBrowseKind
    let id: String
    let name: String
}

/// Browse-by-Genre screen — the genre hierarchy with a per-genre book list, reached by tapping a
/// genre chip on Book Detail. `genreName` rides the route so the title renders immediately while
/// Room hydrates the tree and the RPC returns the genre's books.
struct GenreDestination: Hashable {
    let genreId: String
    let genreName: String
}

/// Shelf detail screen — the books a user has curated onto one shelf.
struct ShelfDestination: Hashable {
    let id: String
}

/// Create or edit a shelf. `shelfId == nil` opens the form in create mode.
struct ShelfFormDestination: Hashable {
    let shelfId: String?
}

/// The full single-type "See all" search page, reached from a capped result group whose
/// hit count exceeds its display cap. Carries the settled query and the one type to expand.
struct SearchSeeAllDestination: Hashable {
    let query: String
    let type: SearchSeeAllType
}

/// The capped-group hit kinds that own a "See all" page. Tags render inline uncapped, so
/// they are intentionally absent. A platform-native mirror of the shared `SearchHitType`
/// (which doesn't bridge as `Hashable`), kept Hashable so it can ride a `NavigationPath`.
enum SearchSeeAllType: Hashable {
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
struct UserProfileDestination: Hashable {}

/// Another user's profile, reached by tapping their avatar in the book Readers section,
/// the Leaderboard, or the Activity feed. Keyed by `userId`; the screen renders read-only.
struct ProfileDestination: Hashable {
    let userId: String
}

/// Settings.
struct SettingsDestination: Hashable {}

/// Storage management — downloaded books, per-book delete, clear-all, and free-space usage.
/// Reached from Settings › Downloads.
struct StorageDestination: Hashable {}

/// Administration dashboard (admin / root users only).
struct AdminDestination: Hashable {}

/// The admin inbox (admin / root users only), reached from Administration › Management.
/// Displays freshly-scanned books awaiting release into the library.
struct AdminInboxDestination: Hashable {}

/// The Audiobookshelf import hub (admin / root users only), reached from Administration ›
/// Management. Lists staged imports and launches the import wizard.
struct ABSImportDestination: Hashable {}

/// The Devices screen — lists the user's active sessions and lets them revoke devices.
struct DevicesDestination: Hashable {}

/// The Open Source Licenses screen — curated list of all bundled open-source libraries.
struct LicensesDestination: Hashable {}

/// The full license text for a single open-source library.
struct LicenseDetailDestination: Hashable {
    let packageName: String
}

/// The Admin Collections list (admin / root users only), reached from Administration › Management.
struct AdminCollectionsDestination: Hashable {}

/// Admin → a specific user's detail (permissions incl. Can Share).
struct UserDetailDestination: Hashable {
    let userId: String
}

/// A single Admin Collection detail, reached from the Admin Collections list.
struct AdminCollectionDetailDestination: Hashable {
    let collectionId: String
}

/// The Library Settings screen (admin / root users only), reached from Administration ›
/// Management. Manages the single library's scan folders and triggers a rescan.
struct LibrarySettingsDestination: Hashable {}

/// The admin Backups screen (admin / root users only), reached from Administration › Management.
/// Lists server backups; creates, deletes, restores, and restores-from-file.
struct AdminBackupsDestination: Hashable {}

/// The destructive restore-confirmation flow for one staged backup.
struct RestoreBackupDestination: Hashable {
    let backupId: String
}
