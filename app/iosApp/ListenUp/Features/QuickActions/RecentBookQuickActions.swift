import UIKit

/// A recently played book, as the home-screen quick actions need it: a native value, so no bridged
/// Kotlin object ever reaches the UIKit shortcut list.
struct RecentBook: Equatable, Sendable {
    let id: String
    let title: String
    let author: String
}

/// Turns recent books into the app icon's long-press items, and a tapped item back into a book id.
/// Pure — the bundle identifier is a parameter — so both directions are tested without a device.
enum RecentBookQuickActions {
    /// The most items the system shows; Android's launcher also lists four.
    static let maxItems = 4

    private static let bookIdKey = "bookId"

    /// The item type for "play this book": `<bundle id>.playBook`.
    static func playBookType(bundleIdentifier: String) -> String {
        "\(bundleIdentifier).playBook"
    }

    /// Up to ``maxItems`` items, in the order given (most recent first).
    static func items(for books: [RecentBook], bundleIdentifier: String) -> [UIApplicationShortcutItem] {
        books.prefix(maxItems).map { book in
            UIApplicationShortcutItem(
                type: playBookType(bundleIdentifier: bundleIdentifier),
                localizedTitle: book.title,
                localizedSubtitle: book.author.isEmpty ? nil : book.author,
                icon: UIApplicationShortcutIcon(systemImageName: "book.fill"),
                userInfo: [bookIdKey: book.id as NSString]
            )
        }
    }

    /// The book id a tapped `item` carries, or nil when it is not a play-book item of ours or its
    /// payload is missing, empty or the wrong kind (a stale or foreign item must never crash a launch).
    static func bookId(from item: UIApplicationShortcutItem, bundleIdentifier: String) -> String? {
        guard item.type == playBookType(bundleIdentifier: bundleIdentifier),
              let bookId = item.userInfo?[bookIdKey] as? String,
              !bookId.isEmpty
        else { return nil }
        return bookId
    }
}
