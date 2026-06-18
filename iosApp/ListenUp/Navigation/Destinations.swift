import Foundation
@preconcurrency import Shared

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

/// Tag detail screen — the books carrying a given tag.
struct TagDestination: Hashable {
    let id: String
}

/// Shelf detail screen — the books a user has curated onto one shelf.
struct ShelfDestination: Hashable {
    let id: String
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

/// Settings.
struct SettingsDestination: Hashable {}

/// Administration dashboard (admin / root users only).
struct AdminDestination: Hashable {}

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
