import Testing
@preconcurrency import Shared
@testable import ListenUp

/// Pure mapping tests for the Home wrappers. These construct the KMP domain types
/// directly (they're exported to `Shared`) and exercise the testable mapping
/// initializers — no live ViewModel or flow needed.
@Suite("HomeWrapper mapping")
struct HomeWrapperTests {

    // MARK: - ContinueItem (Ready)

    @Test func continueItemMapsReadyBook() {
        let book = ContinueListeningBook(
            bookId: "book-1",
            title: "The Way of Kings",
            authorNames: "Brandon Sanderson",
            coverPath: "/covers/wok.jpg",
            coverBlurHash: "L6Pj0^jE.AyE",
            progress: 0.42,
            currentPositionMs: 1_000,
            totalDurationMs: 10_000,
            lastPlayedAt: "2026-06-11T00:00:00Z"
        )
        let item = ContinueItem(from: ContinueListeningItemReady(bookId: "book-1", book: book))

        #expect(item.id == "book-1")
        #expect(item.title == "The Way of Kings")
        #expect(item.author == "Brandon Sanderson")
        #expect(item.coverPath == "/covers/wok.jpg")
        #expect(item.blurHash == "L6Pj0^jE.AyE")
        // progress crosses as Float → Double; allow for float widening imprecision.
        #expect(abs(item.progress - 0.42) < 0.0001)
        #expect(item.progressPercent == 42)
        // timeLeft is the KMP computed `timeRemainingFormatted` passed straight through.
        #expect(item.timeLeft == book.timeRemainingFormatted)
        #expect(item.isLoading == false)
    }

    // MARK: - ContinueItem (Loading skeleton)

    @Test func continueItemMapsLoadingSkeleton() {
        let item = ContinueItem(from: ContinueListeningItemLoading(bookId: "book-pending"))

        #expect(item.id == "book-pending")
        #expect(item.isLoading == true)
        #expect(item.title.isEmpty)
        #expect(item.author.isEmpty)
        #expect(item.coverPath == nil)
        #expect(item.progress == 0)
        #expect(item.progressPercent == 0)
        #expect(item.timeLeft.isEmpty)
    }

    // MARK: - ShelfItem

    @Test func shelfItemMapsFields() {
        let shelf = Shelf_(
            id: "shelf-1",
            name: "To Read",
            description: nil,
            isPrivate: false,
            ownerId: "user-1",
            ownerDisplayName: "Simon",
            bookCount: 7,
            totalDurationSeconds: 9_000,
            createdAtMs: 0,
            updatedAtMs: 0,
            coverPaths: ["/a.jpg", "/b.jpg"]
        )
        let item = ShelfItem(from: shelf)

        #expect(item.id == "shelf-1")
        #expect(item.name == "To Read")
        #expect(item.bookCount == 7)
        // durationLabel is the KMP computed `formattedDuration` (9000s = 2h 30m).
        #expect(item.durationLabel == shelf.formattedDuration)
        #expect(item.durationLabel == "2h 30m")
        #expect(item.coverPaths == ["/a.jpg", "/b.jpg"])
    }

    // MARK: - HomeStatsData

    @Test func homeStatsDataMapsFields() {
        let data = HomeStatsUiStateData(
            totalSecondsThisWeek: 9_000,
            currentStreakDays: 3,
            longestStreakDays: 5,
            dailyBuckets: [
                DayBucket(dayOffsetFromToday: 0, totalSeconds: 1_800),
                DayBucket(dayOffsetFromToday: 1, totalSeconds: 3_600)
            ],
            topGenres: [
                GenreShare(genreName: "Fantasy", totalSeconds: 6_000),
                GenreShare(genreName: "Sci-Fi", totalSeconds: 3_000)
            ]
        )
        let mapped = HomeStatsData(from: data)

        // listenTimeLabel is the KMP computed `formattedListenTime` (9000s = 2h 30m).
        #expect(mapped.listenTimeLabel == data.formattedListenTime)
        #expect(mapped.listenTimeLabel == "2h 30m")
        #expect(mapped.currentStreak == 3)
        #expect(mapped.hasStreak == true)

        #expect(mapped.days.count == 2)
        #expect(mapped.days[0].dayOffset == 0)
        #expect(mapped.days[0].seconds == 1_800)
        #expect(mapped.days[1].seconds == 3_600)
        // maxDaySeconds is the KMP computed scaling helper (max across buckets).
        #expect(mapped.maxDaySeconds == 3_600)

        #expect(mapped.genres.count == 2)
        #expect(mapped.genres[0].name == "Fantasy")
        #expect(mapped.genres[0].seconds == 6_000)
        #expect(mapped.hasGenreData == true)
    }

    @Test func homeStatsDataHasNoGenresWhenEmpty() {
        let data = HomeStatsUiStateData(
            totalSecondsThisWeek: 0,
            currentStreakDays: 0,
            longestStreakDays: 0,
            dailyBuckets: [],
            topGenres: []
        )
        let mapped = HomeStatsData(from: data)

        #expect(mapped.hasGenreData == false)
        #expect(mapped.hasStreak == false)
        #expect(mapped.maxDaySeconds == 0)
    }
}
