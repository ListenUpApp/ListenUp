import Foundation
@preconcurrency import Shared

/// The Library's sort labels from the string catalog (spec R12), in iOS title case where the
/// catalog keeps one ("Recently Added"). Literal keys, so `verifySwiftStringKeys` sees every one.
enum SortLabels {
    /// The category's name: "Title", "Recently Added", "Length".
    static func category(_ category: SortCategory) -> String {
        switch category {
        case .title: String(localized: "library.sort_title")
        case .author: String(localized: "library.sort_author")
        case .series: String(localized: "library.sort_series")
        case .added: String(localized: "library.sort_added_ios")
        case .duration: String(localized: "library.sort_duration")
        case .year: String(localized: "library.sort_year_ios")
        case .rating: String(localized: "library.sort_rating")
        case .listenerRating: String(localized: "library.sort_listener_rating_ios")
        case .name: String(localized: "library.sort_name")
        case .bookCount: String(localized: "library.sort_book_count_ios")
        }
    }

    /// How `direction` reads for `category`: "A–Z", "Longest", "Newest first".
    static func direction(_ category: SortCategory, _ direction: SortDirection) -> String {
        let ascending = direction == .ascending
        return switch category {
        case .title, .author, .name:
            ascending ? String(localized: "library.dir_a_to_z") : String(localized: "library.dir_z_to_a")
        case .duration:
            ascending ? String(localized: "library.dir_shortest") : String(localized: "library.dir_longest")
        case .year:
            ascending ? String(localized: "library.dir_oldest") : String(localized: "library.dir_newest")
        case .bookCount:
            ascending ? String(localized: "library.dir_fewest") : String(localized: "library.dir_most")
        case .added:
            ascending ? String(localized: "library.dir_first_added") : String(localized: "library.dir_recent")
        case .rating, .listenerRating:
            ascending ? String(localized: "library.dir_lowest") : String(localized: "library.dir_highest")
        case .series:
            ascending ? String(localized: "library.dir_series_asc") : String(localized: "library.dir_series_desc")
        }
    }
}
