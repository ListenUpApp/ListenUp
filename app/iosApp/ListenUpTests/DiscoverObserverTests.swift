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
            coverHash: nil,
            seriesName: "Hail Mary",
            sequenceLabel: "1",
            durationMs: 58_000_000
        )
        let book = DiscoverBook(from: kmp)

        #expect(book.id == "book-1")
        #expect(book.title == "Project Hail Mary")
        #expect(book.author == "Andy Weir")
        #expect(book.coverPath == "/covers/phm.jpg")
        #expect(book.seriesName == "Hail Mary")
        #expect(book.sequenceLabel == "1")
        #expect(book.durationMs == 58_000_000)
    }

    @Test func theViewerMapsToANativeValue() {
        let viewer = DiscoverViewer(userId: "me", displayName: "Simon", canInvite: true)
        let expected = DiscoverViewerRow(userId: "me", displayName: "Simon", canInvite: true)
        #expect(DiscoverViewerRow(from: viewer) == expected)
    }

    @Test func summaryCarriesCountsStalenessAndObservedTime() {
        let summary = ListeningSummary(liveCount: 2, recentCount: 3, isStale: true, observedAtMs: 7_200_000)
        #expect(summary.liveCount == 2)
        #expect(summary.recentCount == 3)
        #expect(summary.isStale)
        #expect(summary.observedAt == Date(timeIntervalSince1970: 7_200))
    }

    @Test func shelfOwnerRowsMapCovers() {
        let owner = DiscoverUserShelves(
            user: DiscoverShelfOwner(id: "u1", displayName: "Ada"),
            shelves: [
                DiscoverShelfUi(
                    id: "s1", name: "Comfort reads", description: nil, bookCount: 4, totalDurationSeconds: 0,
                    covers: [ShelfCover(bookId: "b1", coverPath: "/c/b1.jpg", coverHash: "h1")]
                )
            ]
        )
        let row = DiscoverShelfOwnerRow(from: owner)
        #expect(row.id == "u1")
        #expect(row.shelves.first?.covers.first?.bookId == "b1")
        #expect(row.shelves.first?.bookCount == 4)
    }

    @Test func mosaicFillsFourTilesCoversFirst() {
        let covers = [
            DiscoverShelfCover(bookId: "b1", coverPath: nil, coverHash: nil),
            DiscoverShelfCover(bookId: "b2", coverPath: nil, coverHash: nil)
        ]
        let tiles = ShelfCoverMosaic.tiles(for: covers)
        #expect(tiles.count == 4)
        #expect(tiles.compactMap { $0 }.map(\.bookId) == ["b1", "b2"])
    }

    // MARK: - RecentlyAddedBook

    @Test func recentlyAddedBookMapsFieldsAndDate() {
        // 2021-01-01T00:00:00Z = 1_609_459_200_000 ms
        let kmp = RecentlyAddedUiBook(
            id: "book-2",
            title: "The Silkworm",
            authorName: "Robert Galbraith",
            coverPath: nil,
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

    private func entry(
        rank: Int32,
        userId: String,
        name: String,
        seconds: Int64,
        isYou: Bool = false
    ) -> LeaderboardEntry {
        LeaderboardEntry(
            rank: rank,
            userId: userId,
            displayName: name,
            totalSeconds: seconds,
            booksFinished: 0,
            currentStreakDays: 0,
            longestStreakDays: 0,
            isYou: isYou
        )
    }

    @Test func rowsTagTheEntryMarkedAsYou() {
        let snapshot = LeaderboardSnapshot(
            time: [
                entry(rank: 1, userId: "u1", name: "Marcus Lee", seconds: 51_600),
                entry(rank: 2, userId: "u2", name: "Simon Hull", seconds: 42_480, isYou: true)
            ],
            books: [],
            streak: []
        )
        let rows = LeaderboardObserver.rows(from: snapshot, metric: .time)

        #expect(rows.map(\.isCurrentUser) == [false, true])
        #expect(rows[1].displayName == "Simon Hull")
    }

    @Test func tiedLeadersShareTheNumberAndBothWearGold() {
        let snapshot = LeaderboardSnapshot(
            time: [
                entry(rank: 1, userId: "u1", name: "Ana", seconds: 7_200),
                entry(rank: 1, userId: "u2", name: "Ben", seconds: 7_200),
                entry(rank: 3, userId: "u3", name: "Cy", seconds: 3_600)
            ],
            books: [],
            streak: []
        )
        let rows = LeaderboardObserver.rows(from: snapshot, metric: .time)
        #expect(rows.map(\.rank) == [1, 1, 3])
        #expect(rows.prefix(2).allSatisfy { LeaderRankMedal(rank: $0.rank) == .gold })
    }

    @Test func periodOptionMapsEveryKotlinPeriod() {
        #expect(LeaderboardPeriodOption(kmp: LeaderboardPeriodWeek.shared) == .week)
        #expect(LeaderboardPeriodOption(kmp: LeaderboardPeriodMonth.shared) == .month)
        #expect(LeaderboardPeriodOption(kmp: LeaderboardPeriodYear.shared) == .year)
        #expect(LeaderboardPeriodOption(kmp: LeaderboardPeriodAllTime.shared) == .allTime)
    }

    @Test func topFiveThenShowAll() {
        #expect(LeaderboardSectionView.visibleCount(total: 9, expanded: false) == 5)
        #expect(LeaderboardSectionView.visibleCount(total: 9, expanded: true) == 9)
        #expect(LeaderboardSectionView.visibleCount(total: 4, expanded: false) == 4)
    }

    @Test func metricMapsEveryKotlinCategory() {
        for metric in LeaderboardMetric.allCases {
            #expect(LeaderboardMetric(kmp: metric.kmpCategory) == metric)
        }
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

// MARK: - Activity row mapping

/// iOS reads the shared activity sentence (`activityParts`), pinned phrase by phrase in Kotlin's
/// `ActivityProjectionsTest`. These pin that iOS *uses* it, says "You" on your own row, and sends
/// every row somewhere.
@Suite("Activity row mapping")
struct ActivityFeedObserverTests {

    private func model(
        type: String,
        isReread: Bool = false,
        bookId: String? = "b1",
        shelfId: String? = nil,
        durationMs: Int64 = 0,
        isYou: Bool = false
    ) -> ActivityUiModel {
        ActivityUiModel(
            id: "a1",
            userId: "u1",
            type: type,
            occurredAt: 1_609_459_200_000,
            userDisplayName: "Marcus Lee",
            bookId: bookId,
            bookTitle: bookId == nil ? nil : "Dune",
            bookAuthorName: bookId == nil ? nil : "Frank Herbert",
            bookCoverPath: nil,
            bookCoverHash: nil,
            isReread: isReread,
            durationMs: durationMs,
            milestoneValue: 30,
            milestoneUnit: "days",
            shelfId: shelfId,
            shelfName: shelfId == nil ? nil : "Sci-fi",
            isYou: isYou
        )
    }

    @Test func aRowReadsTheSharedSentence() {
        let row = ActivityRowItem(from: model(type: "listening_session", durationMs: 2_880_000))
        #expect(row.predicate == "listened to 48 minutes of")
        #expect(row.highlight == "Dune")
        #expect(row.suffix == " by Frank Herbert")
    }

    @Test func yourRowReadsYou() {
        let row = ActivityRowItem(from: model(type: "finished_book", isYou: true))
        #expect(row.who == "You")
        #expect(row.isYou)
        #expect(row.actorName == "Marcus Lee")
    }

    @Test func eachRowLeadsToItsSubject() {
        #expect(ActivityRowItem(from: model(type: "finished_book")).destination == .book("b1"))
        let shelfRow = ActivityRowItem(from: model(type: "shelf_created", bookId: nil, shelfId: "sh1"))
        #expect(shelfRow.destination == .shelf("sh1"))
        #expect(ActivityRowItem(from: model(type: "user_joined", bookId: nil)).destination == .profile("u1"))
    }

    @Test func rowItemCarriesBookAndActor() {
        let item = ActivityRowItem(from: model(type: "finished_book"))
        #expect(item.who == "Marcus Lee")
        #expect(item.highlight == "Dune")
        #expect(item.bookId == "b1")
        #expect(item.predicate == "finished")
    }

    @Test func rowItemCarriesTheBookCover() {
        let base = model(type: "finished_book")
        let withCover = ActivityUiModel(
            id: base.id, userId: base.userId, type: base.type, occurredAt: base.occurredAt,
            userDisplayName: base.userDisplayName, bookId: base.bookId, bookTitle: base.bookTitle,
            bookAuthorName: base.bookAuthorName, bookCoverPath: "/c/b1.jpg", bookCoverHash: "h1",
            isReread: base.isReread, durationMs: base.durationMs, milestoneValue: base.milestoneValue,
            milestoneUnit: base.milestoneUnit, shelfId: base.shelfId, shelfName: base.shelfName,
            isYou: false
        )
        let row = ActivityRowItem(from: withCover)
        #expect(row.coverPath == "/c/b1.jpg")
        #expect(row.coverHash == "h1")
    }

    @Test func bookLessActivityHasNoBook() {
        let item = ActivityRowItem(from: model(type: "user_joined", bookId: nil))
        #expect(item.highlight == nil)
        #expect(item.bookId == nil)
        #expect(item.predicate == "joined the server")
    }

    @Test func activityCapsAtSix() {
        #expect(ActivitySectionView.visibleCount(total: 9, expanded: false) == 6)
        #expect(ActivitySectionView.visibleCount(total: 9, expanded: true) == 9)
        #expect(ActivitySectionView.visibleCount(total: 4, expanded: false) == 4)
    }
}

// MARK: - Currently listening (What Others Are Listening To) mapping

@Suite("Currently listening mapping")
struct CurrentlyListeningMappingTests {

    private func session(
        user: String,
        book: String,
        lastActiveAt: Int64,
        isLive: Bool = true,
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
            displayName: name,
            lastActiveAt: lastActiveAt,
            isLive: isLive
        )
    }

    @Test func rowMapsSessionFields() {
        let row = CurrentlyListeningRow(from: session(user: "u1", book: "b1", lastActiveAt: 100, name: "Priya Nair"))

        #expect(row.id == "u1:b1")
        #expect(row.userId == "u1")
        #expect(row.bookId == "b1")
        #expect(row.title == "Book b1")
        #expect(row.author == "Frank Herbert")
        #expect(row.displayName == "Priya Nair")
        #expect(row.initials == "PN")
        #expect(row.isLive == true)
        #expect(row.lastActiveAt == Date(timeIntervalSince1970: 0.1))
    }

    @Test func dedupsToOneRowPerUserKeepingMostRecentBook() {
        let sessions = [
            session(user: "u1", book: "old", lastActiveAt: 100),
            session(user: "u1", book: "new", lastActiveAt: 200), // u1's latest
            session(user: "u2", book: "solo", lastActiveAt: 150)
        ]
        let rows = DiscoverObserver.currentlyListeningRows(from: sessions)

        #expect(rows.count == 2)
        let u1 = rows.first { $0.userId == "u1" }
        #expect(u1?.bookId == "new")
    }

    @Test func sortsSurvivorsMostRecentFirst() {
        let sessions = [
            session(user: "u1", book: "b1", lastActiveAt: 100),
            session(user: "u2", book: "b2", lastActiveAt: 300),
            session(user: "u3", book: "b3", lastActiveAt: 200)
        ]
        let rows = DiscoverObserver.currentlyListeningRows(from: sessions)

        #expect(rows.map(\.userId) == ["u2", "u3", "u1"])
    }

    @Test func emptySessionsProduceNoRows() {
        #expect(DiscoverObserver.currentlyListeningRows(from: []).isEmpty)
    }
}
