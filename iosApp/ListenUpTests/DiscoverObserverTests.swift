import Foundation
import Testing
@preconcurrency import Shared
@testable import ListenUp

/// Pure mapping tests for the Discover observers. These construct the KMP domain/UI types
/// directly (exported to `Shared`) and exercise the testable mapping initializers and
/// helpers — no live ViewModel or flow needed.
@Suite("Discover mapping")
struct DiscoverObserverTests {

    // MARK: - DiscoverBook (New for You)

    @Test func discoverBookMapsFields() {
        let kmp = DiscoverUiBook(
            id: "book-1",
            title: "Project Hail Mary",
            authorName: "Andy Weir",
            coverPath: "/covers/phm.jpg",
            coverBlurHash: "L6Pj0^jE",
            seriesName: nil
        )
        let book = DiscoverBook(from: kmp)

        #expect(book.id == "book-1")
        #expect(book.title == "Project Hail Mary")
        #expect(book.author == "Andy Weir")
        #expect(book.coverPath == "/covers/phm.jpg")
        #expect(book.blurHash == "L6Pj0^jE")
    }

    // MARK: - RecentlyAddedBook

    @Test func recentlyAddedBookMapsFieldsAndDate() {
        // 2021-01-01T00:00:00Z = 1_609_459_200_000 ms
        let kmp = RecentlyAddedUiBook(
            id: "book-2",
            title: "The Silkworm",
            authorName: "Robert Galbraith",
            coverPath: nil,
            coverBlurHash: nil,
            createdAt: 1_609_459_200_000
        )
        let book = RecentlyAddedBook(from: kmp)

        #expect(book.id == "book-2")
        #expect(book.title == "The Silkworm")
        #expect(book.author == "Robert Galbraith")
        #expect(book.addedAt == Date(timeIntervalSince1970: 1_609_459_200))
    }
}

// MARK: - Leaderboard mapping

@Suite("Leaderboard mapping")
struct LeaderboardObserverTests {

    private func entry(rank: Int32, userId: String, name: String, seconds: Int64) -> LeaderboardEntry {
        LeaderboardEntry(
            rank: rank,
            userId: userId,
            displayName: name,
            totalSeconds: seconds,
            booksFinished: 0,
            currentStreakDays: 0,
            longestStreakDays: 0
        )
    }

    @Test func rowsTagCurrentUser() {
        let snapshot = LeaderboardSnapshot(
            time: [
                entry(rank: 1, userId: "u1", name: "Marcus Lee", seconds: 51_600),
                entry(rank: 2, userId: "u2", name: "Simon Hull", seconds: 42_480)
            ],
            books: [],
            streak: []
        )
        let rows = LeaderboardObserver.rows(from: snapshot, currentUserId: "u2")

        #expect(rows.count == 2)
        #expect(rows[0].isCurrentUser == false)
        #expect(rows[1].isCurrentUser == true)
        #expect(rows[1].displayName == "Simon Hull")
    }

    @Test func rowsHaveNoCurrentUserWhenIdIsNil() {
        let snapshot = LeaderboardSnapshot(
            time: [entry(rank: 1, userId: "u1", name: "Marcus Lee", seconds: 51_600)],
            books: [],
            streak: []
        )
        let rows = LeaderboardObserver.rows(from: snapshot, currentUserId: nil)

        #expect(rows.allSatisfy { !$0.isCurrentUser })
    }

    @Test func formatHoursRendersHoursAndMinutes() {
        #expect(LeaderboardRow.formatHours(seconds: 51_600) == "14h 20m") // 14h20m
        #expect(LeaderboardRow.formatHours(seconds: 42_480) == "11h 48m") // 11h48m
        #expect(LeaderboardRow.formatHours(seconds: 2_880) == "48m") // under an hour
        #expect(LeaderboardRow.formatHours(seconds: 0) == "0m")
    }

    @Test func initialsTakeFirstTwoWords() {
        #expect(LeaderboardRow.initials(from: "Marcus Lee") == "ML")
        #expect(LeaderboardRow.initials(from: "Priya Nair Singh") == "PN")
        #expect(LeaderboardRow.initials(from: "Madonna") == "M")
        #expect(LeaderboardRow.initials(from: "").isEmpty)
    }
}

// MARK: - Activity phrase mapping

@Suite("Activity phrase mapping")
struct ActivityFeedObserverTests {

    private func model(type: String, isReread: Bool = false, book: String? = "Dune") -> ActivityUiModel {
        ActivityUiModel(
            id: "a1",
            userId: "u1",
            type: type,
            createdAt: 1_609_459_200_000,
            userDisplayName: "Marcus Lee",
            userAvatarColor: "#2E8BFF",
            userAvatarType: "initials",
            userAvatarValue: nil,
            bookId: book == nil ? nil : "b1",
            bookTitle: book,
            bookAuthorName: "Frank Herbert",
            bookCoverPath: nil,
            isReread: isReread,
            durationMs: 0,
            milestoneValue: 30,
            milestoneUnit: "days",
            shelfId: nil,
            shelfName: nil
        )
    }

    @Test func startedBookMapsToStarted() {
        #expect(ActivityRowItem.action(for: model(type: "started_book")) == "started")
    }

    @Test func startedRereadMapsToReread() {
        #expect(ActivityRowItem.action(for: model(type: "started_book", isReread: true)) == "started re-reading")
    }

    @Test func finishedBookMapsToFinished() {
        #expect(ActivityRowItem.action(for: model(type: "finished_book")) == "finished")
    }

    @Test func streakMilestoneMapsToStreakPhrase() {
        #expect(ActivityRowItem.action(for: model(type: "streak_milestone")) == "hit a listening streak")
    }

    @Test func unknownTypeFallsBack() {
        #expect(ActivityRowItem.action(for: model(type: "wat")) == "did something awesome")
    }

    @Test func rowItemCarriesBookAndActor() {
        let item = ActivityRowItem(from: model(type: "finished_book"))
        #expect(item.who == "Marcus Lee")
        #expect(item.initials == "ML")
        #expect(item.book == "Dune")
        #expect(item.bookId == "b1")
        #expect(item.action == "finished")
    }

    @Test func bookLessActivityHasNoBook() {
        let item = ActivityRowItem(from: model(type: "user_joined", book: nil))
        #expect(item.book == nil)
        #expect(item.bookId == nil)
        #expect(item.action == "joined the server")
    }
}
