import Foundation
import Testing
import Shared
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
            coverHash: nil,
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
            coverHash: nil,
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

    private func model(
        type: String,
        isReread: Bool = false,
        book: String? = "Dune",
        durationMs: Int64 = 0
    ) -> ActivityUiModel {
        ActivityUiModel(
            id: "a1",
            userId: "u1",
            type: type,
            occurredAt: 1_609_459_200_000,
            userDisplayName: "Marcus Lee",
            bookId: book == nil ? nil : "b1",
            bookTitle: book,
            bookAuthorName: "Frank Herbert",
            bookCoverPath: nil,
            isReread: isReread,
            durationMs: durationMs,
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

    @Test func listeningSessionWeavesDurationIntoPhrase() {
        // 1h 5m = 65 minutes = 3_900_000 ms — Android reads "listened to 1 hour 5 minutes of <book>".
        #expect(
            ActivityRowItem.action(for: model(type: "listening_session", durationMs: 3_900_000))
                == "listened to 1 hour 5 minutes of"
        )
    }

    @Test func listeningSessionUnderAnHourReadsMinutesOnly() {
        // 54m = 3_240_000 ms
        #expect(
            ActivityRowItem.action(for: model(type: "listening_session", durationMs: 3_240_000))
                == "listened to 54 minutes of"
        )
    }

    @Test func listeningSessionRoundsToWholeHour() {
        // Exactly 2h = 7_200_000 ms — no trailing "0 minutes", singular unit.
        #expect(
            ActivityRowItem.action(for: model(type: "listening_session", durationMs: 7_200_000))
                == "listened to 2 hours of"
        )
    }

    @Test func zeroDurationListeningSessionReadsSeconds() {
        #expect(
            ActivityRowItem.action(for: model(type: "listening_session", durationMs: 0))
                == "listened to 0 seconds of"
        )
    }

    @Test func nonListeningActivityIgnoresDurationEvenWithMs() {
        #expect(ActivityRowItem.action(for: model(type: "started_book", durationMs: 3_900_000)) == "started")
        #expect(ActivityRowItem.action(for: model(type: "finished_book", durationMs: 3_900_000)) == "finished")
    }
}

// MARK: - Currently listening (What Others Are Listening To) mapping

@Suite("Currently listening mapping")
struct CurrentlyListeningMappingTests {

    private func session(
        user: String,
        book: String,
        startedAt: Int64,
        name: String = "Marcus Lee"
    ) -> CurrentlyListeningUiSession {
        CurrentlyListeningUiSession(
            sessionId: "\(user):\(book)",
            userId: user,
            bookId: book,
            bookTitle: "Book \(book)",
            authorName: "Frank Herbert",
            coverPath: nil,
            coverHash: nil,
            coverBlurHash: nil,
            displayName: name,
            startedAt: startedAt
        )
    }

    @Test func rowMapsSessionFields() {
        let row = CurrentlyListeningRow(from: session(user: "u1", book: "b1", startedAt: 100, name: "Priya Nair"))

        #expect(row.id == "u1:b1")
        #expect(row.userId == "u1")
        #expect(row.bookId == "b1")
        #expect(row.title == "Book b1")
        #expect(row.author == "Frank Herbert")
        #expect(row.displayName == "Priya Nair")
        #expect(row.initials == "PN")
    }

    @Test func dedupsToOneRowPerUserKeepingMostRecentBook() {
        let sessions = [
            session(user: "u1", book: "old", startedAt: 100),
            session(user: "u1", book: "new", startedAt: 200), // u1's latest
            session(user: "u2", book: "solo", startedAt: 150)
        ]
        let rows = DiscoverObserver.currentlyListeningRows(from: sessions)

        #expect(rows.count == 2)
        let u1 = rows.first { $0.userId == "u1" }
        #expect(u1?.bookId == "new")
    }

    @Test func sortsSurvivorsMostRecentFirst() {
        let sessions = [
            session(user: "u1", book: "b1", startedAt: 100),
            session(user: "u2", book: "b2", startedAt: 300),
            session(user: "u3", book: "b3", startedAt: 200)
        ]
        let rows = DiscoverObserver.currentlyListeningRows(from: sessions)

        #expect(rows.map(\.userId) == ["u2", "u3", "u1"])
    }

    @Test func emptySessionsProduceNoRows() {
        #expect(DiscoverObserver.currentlyListeningRows(from: []).isEmpty)
    }
}
