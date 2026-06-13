import SwiftUI

/// Tabs available in the Library screen.
///
/// Each tab represents a different view of the user's audiobook collection.
enum LibraryTab: String, CaseIterable, Identifiable {
    case books
    case series
    case contributors

    var id: String { rawValue }

    var title: String {
        switch self {
        case .books: String(localized: "library.books")
        case .series: String(localized: "common.series")
        case .contributors: String(localized: "library.contributors")
        }
    }

    var icon: String {
        switch self {
        case .books: "book.fill"
        case .series: "books.vertical.fill"
        case .contributors: "person.2.fill"
        }
    }
}
