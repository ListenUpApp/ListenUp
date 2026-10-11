import Testing
import UIKit
@testable import ListenUp

/// The pure halves of the home-screen quick actions: recent books -> `UIApplicationShortcutItem`s,
/// and a tapped item -> the book id it carries.
@Suite("RecentBookQuickActions")
struct RecentBookQuickActionsTests {
    private let bundleID = "com.example.listenup"

    private func books(_ count: Int) -> [RecentBook] {
        (0..<count).map { RecentBook(id: "book-\($0 + 1)", title: "Title \($0 + 1)", author: "Author \($0 + 1)") }
    }

    @Test func eachBookBecomesOneItemCarryingItsIdTitleAndAuthor() {
        let items = RecentBookQuickActions.items(for: books(2), bundleIdentifier: bundleID)

        #expect(items.count == 2)
        #expect(items[0].type == "com.example.listenup.playBook")
        #expect(items[0].localizedTitle == "Title 1")
        #expect(items[0].localizedSubtitle == "Author 1")
        #expect(items[0].userInfo?["bookId"] as? String == "book-1")
        #expect(items[1].userInfo?["bookId"] as? String == "book-2")
    }

    @Test func atMostFourBooksBecomeItemsInTheOrderGiven() {
        let items = RecentBookQuickActions.items(for: books(6), bundleIdentifier: bundleID)

        #expect(items.map { $0.userInfo?["bookId"] as? String } == ["book-1", "book-2", "book-3", "book-4"])
    }

    @Test func noBooksMeansNoItems() {
        #expect(RecentBookQuickActions.items(for: [], bundleIdentifier: bundleID).isEmpty)
    }

    @Test func aBookWithoutAnAuthorHasNoSubtitle() {
        let item = RecentBookQuickActions.items(
            for: [RecentBook(id: "b", title: "T", author: "")],
            bundleIdentifier: bundleID
        )[0]

        #expect(item.localizedSubtitle == nil)
    }

    @Test func aTappedItemParsesBackToItsBookId() {
        let item = RecentBookQuickActions.items(for: books(1), bundleIdentifier: bundleID)[0]

        #expect(RecentBookQuickActions.bookId(from: item, bundleIdentifier: bundleID) == "book-1")
    }

    @Test func anItemWithoutUserInfoParsesToNil() {
        let item = UIApplicationShortcutItem(type: "com.example.listenup.playBook", localizedTitle: "T")

        #expect(RecentBookQuickActions.bookId(from: item, bundleIdentifier: bundleID) == nil)
    }

    @Test func anItemWhoseBookIdIsTheWrongKindOrEmptyParsesToNil() {
        let wrongKind = UIApplicationShortcutItem(
            type: "com.example.listenup.playBook",
            localizedTitle: "T",
            localizedSubtitle: nil,
            icon: nil,
            userInfo: ["bookId": NSNumber(value: 7)]
        )
        let empty = UIApplicationShortcutItem(
            type: "com.example.listenup.playBook",
            localizedTitle: "T",
            localizedSubtitle: nil,
            icon: nil,
            userInfo: ["bookId": "" as NSString]
        )

        #expect(RecentBookQuickActions.bookId(from: wrongKind, bundleIdentifier: bundleID) == nil)
        #expect(RecentBookQuickActions.bookId(from: empty, bundleIdentifier: bundleID) == nil)
    }

    @Test func anItemOfAnotherTypeParsesToNil() {
        let item = UIApplicationShortcutItem(
            type: "com.example.listenup.somethingElse",
            localizedTitle: "T",
            localizedSubtitle: nil,
            icon: nil,
            userInfo: ["bookId": "book-1" as NSString]
        )

        #expect(RecentBookQuickActions.bookId(from: item, bundleIdentifier: bundleID) == nil)
    }
}
