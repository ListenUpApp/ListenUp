import Foundation
import ListenupContract
import Testing
import Shared
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
            narratorNames: "Michael Kramer, Kate Reading",
            coverPath: "/covers/wok.jpg",
            coverHash: nil,
            progress: 0.42,
            currentPositionMs: 1_000,
            totalDurationMs: 10_000,
            lastPlayedAtMs: 1_760_000_000_000,
            isDownloaded: true
        )
        let item = ContinueItem(from: ContinueListeningItemReady(bookId: "book-1", book: book))

        #expect(item.id == "book-1")
        #expect(item.title == "The Way of Kings")
        #expect(item.author == "Brandon Sanderson")
        #expect(item.coverPath == "/covers/wok.jpg")
        // progress crosses as Float → Double; allow for float widening imprecision.
        #expect(abs(item.progress - 0.42) < 0.0001)
        #expect(item.progressPercent == 42)
        // timeLeft is the KMP computed `timeRemainingFormatted` passed straight through.
        #expect(item.timeLeft == book.timeRemainingFormatted)
        #expect(item.isLoading == false)
        #expect(item.narrators == "Michael Kramer, Kate Reading")
        #expect(item.isDownloaded == true)
        #expect(item.lastPlayedAt == Date(timeIntervalSince1970: 1_760_000_000))
        #expect(item.remainingMs == 9_000)
        #expect(item.totalMs == 10_000)
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
        let shelf = Shelf(
            id: ShelfId(value: "shelf-1"),
            name: "To Read",
            description: nil,
            isPrivate: false,
            ownerId: "user-1",
            ownerDisplayName: "Simon",
            bookCount: 7,
            totalDurationSeconds: 9_000,
            createdAtMs: 0,
            updatedAtMs: 0,
            covers: [ShelfCover(bookId: "b1", coverPath: "/a.jpg", coverHash: "h1")]
        )
        let item = ShelfItem(from: shelf)

        #expect(item.id == "shelf-1")
        #expect(item.name == "To Read")
        #expect(item.bookCount == 7)
        // durationLabel is the KMP computed `formattedDuration` (9000s = 2h 30m).
        #expect(item.durationLabel == shelf.formattedDuration)
        #expect(item.durationLabel == "2h 30m")
        #expect(item.covers.map(\.bookId) == ["b1"])
        #expect(item.isPrivate == false)
        #expect(item.totalSeconds == 9_000)
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
        #expect(mapped.streakChipDays == 3)
        #expect(mapped.bestStreakDays == 5)

        #expect(mapped.days.count == 2)
        #expect(mapped.days[0].dayOffset == 0)
        #expect(mapped.days[0].seconds == 1_800)
        #expect(mapped.days[1].seconds == 3_600)
        // maxDaySeconds is the KMP computed scaling helper (max across buckets).
        #expect(mapped.maxDaySeconds == 3_600)

        #expect(mapped.genres.count == 2)
        #expect(mapped.genres[0].name == "Fantasy")
        #expect(mapped.genres[0].seconds == 6_000)
        #expect(mapped.genres[0].percent == 66)
        #expect(mapped.genres[1].percent == 33)
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
        #expect(mapped.streakChipDays == nil)
        #expect(mapped.bestStreakDays == nil)
        #expect(mapped.maxDaySeconds == 0)
    }

    /// A lapsed streak (current 0, longest > 0) shows no chip, and the past best survives the map for the footer's
    /// "Best streak 9 days" — never a misleading "0-day streak".
    @Test func homeStatsDataCarriesPastStreakWhenCurrentIsZero() {
        let data = HomeStatsUiStateData(
            totalSecondsThisWeek: 0,
            currentStreakDays: 0,
            longestStreakDays: 9,
            dailyBuckets: [],
            topGenres: []
        )
        let mapped = HomeStatsData(from: data)

        #expect(mapped.streakChipDays == nil)
        #expect(mapped.bestStreakDays == 9)
    }

    // MARK: - HomeReady

    /// The greeting is worded on iOS from the part of the day and the first name; `HomeReady` carries those two,
    /// never a pre-worded string (H2).
    @Test func homeReadyMapsTimeOfDayAndFirstName() {
        let ready = HomeUiStateReady(
            myShelves: [],
            isSyncing: false,
            isBuildingInitialLibrary: false,
            isLibraryArriving: false,
            scanProgress: nil,
            timeOfDay: TimeOfDay.Evening,
            firstName: "Simon",
            slot: HomeSlotAbsent.shared,
            everythingInProgress: [],
            connectivity: HomeConnectivity.Online,
            continueListeningError: nil
        )
        let mapped = HomeReady(from: ready)

        #expect(mapped.timeOfDay == .evening)
        #expect(mapped.firstName == "Simon")
        #expect(mapped.slot == .absent)
        #expect(mapped.connectivity == .online)
        #expect(mapped.continueListeningError == nil)
    }

    // MARK: - Slot

    private func clBook(_ id: String) -> ContinueListeningBook {
        ContinueListeningBook(
            bookId: id, title: "Book \(id)", authorNames: "A. Writer", narratorNames: nil, coverPath: nil, coverHash: nil,
            progress: 0.5, currentPositionMs: 1_000, totalDurationMs: 2_000, lastPlayedAtMs: 0, isDownloaded: false
        )
    }

    private func next(_ id: String) -> NextInSeries {
        NextInSeries(
            bookId: id, title: "Words of Radiance", coverPath: nil, coverHash: nil, narratorNames: "Kate Reading",
            seriesName: "The Stormlight Archive", sequenceLabel: "2", durationMs: 173_700_000
        )
    }

    @Test func theLoadedBookIsFirstInEverythingInProgress() {
        // The shared rule puts the loaded book first (`everythingInProgressOf`); the wrapper keeps that order.
        let ready = HomeUiStateReady(
            myShelves: [], isSyncing: false,
            isBuildingInitialLibrary: false, isLibraryArriving: false,
            scanProgress: nil, timeOfDay: TimeOfDay.Morning, firstName: nil,
            slot: HomeSlotAbsent.shared,
            everythingInProgress: [
                ContinueListeningItemReady(bookId: "loaded", book: clBook("loaded")),
                ContinueListeningItemReady(bookId: "other", book: clBook("other"))
            ],
            connectivity: HomeConnectivity.Online,
            continueListeningError: nil
        )
        #expect(HomeReady(from: ready).everythingInProgress.map(\.id) == ["loaded", "other"])
    }

    @Test func slotMapsJustFinished() {
        let card = FinishedCard(
            book: FinishedBook(
                bookId: "done", title: "The Way of Kings", authorNames: "Brandon Sanderson",
                coverPath: nil, coverHash: nil, durationMs: 163_800_000
            ),
            startedAtMs: 1_757_000_000_000,
            finishedAtMs: 1_760_000_000_000,
            yourHalfStars: 8,
            next: next("words"),
            alsoFinished: AlsoFinished(names: ["Maya", "Tom"], othersCount: 1)
        )
        guard case .justFinished(let finished) = HomeSlotItem(from: HomeSlotJustFinished(card: card)) else {
            Issue.record("expected justFinished"); return
        }
        #expect(finished.bookId == "done")
        #expect(finished.finishedAt == Date(timeIntervalSince1970: 1_760_000_000))
        #expect(finished.startedAt == Date(timeIntervalSince1970: 1_757_000_000))
        #expect(finished.yourHalfStars == 8)
        #expect(finished.next?.id == "words")
        #expect(finished.alsoFinishedNames == ["Maya", "Tom"])
        #expect(finished.alsoFinishedOthers == 1)
    }

    @Test func slotMapsTheQuietKinds() {
        #expect(HomeSlotItem(from: HomeSlotAbsent.shared) == .absent)
        #expect(HomeSlotItem(from: HomeSlotFirstSync.shared) == .firstSync)
        #expect(HomeSlotItem(from: HomeSlotNothingInProgress(candidate: nil)) == .nothingInProgress(nil))
        let newListener = HomeSlotItem(from: HomeSlotNewListener(
            covers: [ShelfCover(bookId: "n1", coverPath: "/c/n1.jpg", coverHash: "h1")]
        ))
        guard case .newListener(let covers) = newListener else { Issue.record("expected newListener"); return }
        #expect(covers.map(\.bookId) == ["n1"])
    }

    @Test func readyCarriesTheContinueListeningErrorMessage() {
        let ready = HomeUiStateReady(
            myShelves: [], isSyncing: false,
            isBuildingInitialLibrary: false, isLibraryArriving: false,
            scanProgress: nil, timeOfDay: TimeOfDay.Morning, firstName: nil,
            slot: HomeSlotAbsent.shared,
            everythingInProgress: [ContinueListeningItemReady(bookId: "b", book: clBook("b"))],
            connectivity: HomeConnectivity.ServerUnreachable,
            continueListeningError: UnexpectedClientError(correlationId: nil, debugInfo: nil)
        )
        let mapped = HomeReady(from: ready)
        #expect(mapped.continueListeningError == "Something went wrong in the app.")
        #expect(mapped.everythingInProgress.map(\.id) == ["b"])
        #expect(mapped.connectivity == .serverUnreachable)
    }

    @Test func statsCarryTheOneStreakRule() {
        let data = HomeStatsUiStateData(
            totalSecondsThisWeek: 0, currentStreakDays: 0, longestStreakDays: 9, dailyBuckets: [], topGenres: []
        )
        let mapped = HomeStatsData(from: data)
        #expect(mapped.streakChipDays == nil)
        #expect(mapped.bestStreakDays == 9)
    }

    // MARK: - Every state

    private func scan(books: Int32, total: Int32) -> ScanProgressState {
        ScanProgressState(
            phase: "analyzing", current: books, total: total, added: 0, updated: 0, removed: 0,
            filesTotal: 0, books: books, booksTotal: total, authors: 0, durationMs: 0, currentFile: nil,
            recentBooks: [], startedAtMs: 0
        )
    }

    private func ready(
        isLibraryArriving: Bool = false,
        scanProgress: ScanProgressState? = nil,
        everythingInProgress: [ContinueListeningItem] = [],
        connectivity: HomeConnectivity = HomeConnectivity.Online
    ) -> HomeUiStateReady {
        HomeUiStateReady(
            myShelves: [], isSyncing: false,
            isBuildingInitialLibrary: false, isLibraryArriving: isLibraryArriving,
            scanProgress: scanProgress, timeOfDay: TimeOfDay.Morning, firstName: nil,
            slot: HomeSlotFirstSync.shared, everythingInProgress: everythingInProgress,
            connectivity: connectivity,
            continueListeningError: nil
        )
    }

    @Test func readyCarriesTheLibraryArrivingAndTheScanCount() {
        let mapped = HomeReady(from: ready(isLibraryArriving: true, scanProgress: scan(books: 412, total: 1280)))
        #expect(mapped.isLibraryArriving)
        #expect(mapped.arrivalCount == ArrivalCount(done: 412, total: 1280))
        #expect(HomeReady(from: ready(scanProgress: scan(books: 3, total: 0))).arrivalCount == nil)
    }

    @Test func anErrorKeepsThePartOfTheDay() {
        let error = UnexpectedClientError(correlationId: nil, debugInfo: nil)
        let phase = HomePhase(error: HomeUiStateError(error: error, timeOfDay: TimeOfDay.Evening))
        guard case .error(_, let timeOfDay) = phase else { Issue.record("expected error"); return }
        #expect(timeOfDay == .evening)
    }

    @Test func aContinueItemKnowsWhenItNeedsAConnection() {
        let item = ContinueListeningItemReady(bookId: "babel", book: clBook("babel"))
        #expect(ContinueItem(from: item, connectivity: HomeConnectivity.Offline).needsConnection)
        #expect(ContinueItem(from: item, connectivity: HomeConnectivity.ServerUnreachable).needsConnection)
        #expect(!ContinueItem(from: item, connectivity: HomeConnectivity.Online).needsConnection)
        let mapped = HomeReady(from: ready(everythingInProgress: [item], connectivity: HomeConnectivity.Offline))
        #expect(mapped.everythingInProgress.first?.needsConnection == true)
    }

    @Test func readyCarriesTheSharedOfflineAndStripSignals() {
        let quiet = HomeReady(from: ready())
        #expect(!quiet.isOffline)
        #expect(!quiet.showsArrivalStrip)
        let scanning = HomeReady(
            from: ready(scanProgress: scan(books: 1, total: 2), connectivity: HomeConnectivity.Offline)
        )
        #expect(scanning.isOffline)
        #expect(scanning.showsArrivalStrip)
    }

    @Test func shelvesLoadWhileTheLibraryArrives() {
        #expect(HomeReady(from: ready(isLibraryArriving: true)).showsShelvesLoading)
        #expect(!HomeReady(from: ready()).showsShelvesLoading)
    }
}
