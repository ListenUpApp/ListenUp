import Foundation
import Shared

/// Where the reader is with the book. Native projection of the shared `ReadingStage`.
enum BookReadingStage: Equatable {
    case notStarted, inProgress, finished

    init(_ stage: ReadingStage) {
        switch stage.sealedType() {
        case .notStarted: self = .notStarted
        case .inProgress: self = .inProgress
        case .finished: self = .finished
        }
    }
}

/// Book Detail's primary button. Native projection of the shared `PrimaryAction`.
enum BookPrimaryActionKind: Equatable {
    case play, resume, listenAgain, unavailableOffline

    init(_ action: PrimaryAction) {
        switch action.sealedType() {
        case .play: self = .play
        case .resume: self = .resume
        case .listenAgain: self = .listenAgain
        case .unavailableOffline: self = .unavailableOffline
        }
    }
}

/// A section below the action unit. Native projection of the shared `BookDetailSection`.
enum BookDetailSectionKind: Equatable, Hashable {
    case about, ratings, hardcover, readers, series, chapters, details

    init(_ section: BookDetailSection) {
        switch section.sealedType() {
        case .about: self = .about
        case .ratings: self = .ratings
        case .hardcover: self = .hardcover
        case .readers: self = .readers
        case .series: self = .series
        case .chapters: self = .chapters
        case .details: self = .details
        }
    }
}

/// The chapter the saved position is in.
struct BookCurrentChapter: Equatable {
    let index: Int
    let number: Int
    let title: String
    let startMs: Int64
    let elapsedInChapterMs: Int64

    init(index: Int, number: Int, title: String, startMs: Int64, elapsedInChapterMs: Int64) {
        self.index = index
        self.number = number
        self.title = title
        self.startMs = startMs
        self.elapsedInChapterMs = elapsedInChapterMs
    }

    init(_ chapter: CurrentChapter) {
        self.init(index: Int(chapter.index), number: Int(chapter.number), title: chapter.title,
                  startMs: chapter.startMs, elapsedInChapterMs: chapter.elapsedInChapterMs)
    }
}

/// When a finished book was read.
struct BookFinishedSpan: Equatable {
    let startedAtMs: Int64?
    let finishedAtMs: Int64

    init(_ span: FinishedSpan) {
        startedAtMs = span.startedAtMs
        finishedAtMs = span.finishedAtMs
    }
}

/// One cover on the series shelf.
struct BookSeriesShelfItem: Identifiable, Equatable {
    let id: String
    let coverPath: String?
    let coverHash: String?
    let sequenceLabel: String?
    let isThis: Bool
}

/// The book's series as a shelf of covers in sequence.
struct BookSeriesShelf: Equatable {
    let seriesId: String
    let name: String
    let books: [BookSeriesShelfItem]
    let thisIndex: Int
    let nextBookId: String?

    init(_ shelf: SeriesShelf) {
        seriesId = shelf.seriesId
        name = shelf.name
        books = shelf.books.map {
            BookSeriesShelfItem(id: $0.id, coverPath: $0.coverPath, coverHash: $0.coverHash,
                                sequenceLabel: $0.sequenceLabel, isThis: $0.isThis)
        }
        thisIndex = Int(shelf.thisIndex)
        nextBookId = shelf.nextBookId
    }
}

/// Book Detail's reading state — stage, the visit's section order, the primary action, the current chapter and its
/// window, time left, the finished span, the download size, the series shelf and the admin-only scan flag —
/// projected to native values once, at the observer, from the shared `Ready`.
struct BookReadingState: Equatable {
    var stage: BookReadingStage = .notStarted
    var sectionOrder: [BookDetailSectionKind] = []
    var primaryAction: BookPrimaryActionKind = .play
    var currentChapter: BookCurrentChapter?
    var chapterPreview: [BookChapterRow] = []
    var timeRemainingMs: Int64?
    var finishedSpan: BookFinishedSpan?
    var downloadSizeBytes: Int64 = 0
    var seriesShelf: BookSeriesShelf?
    var showScanWarning = false

    init() {}

    init(_ ready: BookDetailUiStateReady) {
        stage = BookReadingStage(ready.readingStage)
        sectionOrder = ready.sectionOrder.map(BookDetailSectionKind.init)
        primaryAction = BookPrimaryActionKind(ready.primaryAction)
        currentChapter = ready.currentChapter.map(BookCurrentChapter.init)
        chapterPreview = ready.chapterPreview.map { BookChapterRow($0) }
        timeRemainingMs = ready.timeRemainingMs
        finishedSpan = ready.finishedSpan.map(BookFinishedSpan.init)
        downloadSizeBytes = ready.downloadSizeBytes
        seriesShelf = ready.seriesShelf.map(BookSeriesShelf.init)
        showScanWarning = ready.showScanWarning
    }
}

/// Who else is reading, for the hero's social line. `nil` when nobody but you is.
struct ReadersSocialLine: Equatable {
    let listening: [String]
    let finished: [String]
    let avatarUserIds: [String]
    let othersCount: Int

    init?(_ summary: ReadersSummary) {
        guard !summary.avatars.isEmpty else { return nil }
        listening = summary.listening.map(\.displayName)
        finished = summary.finished.map(\.displayName)
        avatarUserIds = summary.avatars.map(\.userId)
        othersCount = Int(summary.othersCount)
    }
}
