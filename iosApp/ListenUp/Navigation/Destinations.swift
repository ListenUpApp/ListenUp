import Foundation

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

/// The current user's profile.
struct UserProfileDestination: Hashable {}

/// Settings.
struct SettingsDestination: Hashable {}
