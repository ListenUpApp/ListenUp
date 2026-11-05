import Foundation
import Shared

/// A part's header, as a native value. Mirrors the shared `ChapterOutlineRow.PartHeader`.
struct BookPartHeader: Equatable {
    let title: String
    /// The chapter carrying the marker: this part's identity, and where its play starts.
    let chapterId: String
    let chapterCount: Int
    let durationMs: Int64
    let startMs: Int64
    let containsCurrent: Bool
    /// The current chapter is the one folded into this header, so this row shows as current.
    let isCurrentChapter: Bool

    /// As the reader left it, per `overrides`, or with no override following the current chapter.
    /// The shared `ChapterOutlineRow.PartHeader.isOpen`, on the native value the view holds.
    func isOpen(overrides: [String: Bool]) -> Bool { overrides[chapterId] ?? containsCurrent }

    /// `overrides` with this part flipped, so it stays that way wherever playback goes next.
    func toggled(_ overrides: [String: Bool]) -> [String: Bool] {
        overrides.merging([chapterId: !isOpen(overrides: overrides)]) { _, flipped in flipped }
    }

    /// "13 chapters · 45 hr, 30 min", or just the length of a part that is only its announcement.
    var summary: String {
        let length = DurationFormatting.abbreviated(ms: durationMs)
        guard chapterCount > 0 else { return length }
        let count = chapterCount == 1
            ? String(localized: "book.detail_chapter_count_one")
            : String(format: String(localized: "book.detail_chapter_count"), chapterCount)
        return String(format: String(localized: "book.detail_part_summary"), count, length)
    }
}

/// One line of the shared outline, flattened once at the observer so the view never sees Kotlin.
enum BookOutlineRow: Equatable, Identifiable {
    case book(title: String, chapterId: String)
    case part(BookPartHeader)
    /// `number` is the chapter's place in the whole book; `partId` the part it sits in, if any.
    case chapter(number: Int, partId: String?)

    var id: String {
        switch self {
        case .book(_, let chapterId): "book-\(chapterId)"
        case .part(let part): "part-\(part.chapterId)"
        case .chapter(let number, _): "chapter-\(number)"
        }
    }
}

/// A marked book's chapters. The grouping is the shared outline's: this only carries it and answers
/// which of its rows a closed part hides, which each row already says for itself.
struct BookGroupedChapters: Equatable {
    let rows: [BookOutlineRow]
    let previewLabel: String?
    let previewRows: [BookOutlineRow]

    init(rows: [BookOutlineRow], previewLabel: String?, previewRows: [BookOutlineRow]) {
        self.rows = rows
        self.previewLabel = previewLabel
        self.previewRows = previewRows
    }

    init(_ grouped: GroupedChapters) {
        self.init(
            rows: grouped.rows.map(Self.row),
            previewLabel: grouped.previewLabel,
            previewRows: grouped.previewRows.map(Self.row)
        )
    }

    /// Every header, and the chapters of parts that are open.
    func visibleRows(overrides: [String: Bool]) -> [BookOutlineRow] {
        let closed = Set(rows.compactMap { row -> String? in
            guard case .part(let part) = row, !part.isOpen(overrides: overrides) else { return nil }
            return part.chapterId
        })
        return rows.filter { row in
            guard case .chapter(_, let partId?) = row else { return true }
            return !closed.contains(partId)
        }
    }

    /// Whether a hairline follows `row` in `shown`: under a chapter, as the flat list has it, and
    /// never after the last row, where the card's edge or the "All" button's own line already is.
    static func hasDivider(after row: BookOutlineRow, in shown: [BookOutlineRow]) -> Bool {
        guard case .chapter = row else { return false }
        return row != shown.last
    }

    private static func row(_ row: ChapterOutlineRow) -> BookOutlineRow {
        switch row.sealedType() {
        case .bookHeader(let type):
            .book(title: type.value.title, chapterId: type.value.chapterId)
        case .partHeader(let type):
            .part(BookPartHeader(
                title: type.value.title,
                chapterId: type.value.chapterId,
                chapterCount: Int(type.value.chapterCount),
                durationMs: type.value.durationMs,
                startMs: type.value.startMs,
                containsCurrent: type.value.containsCurrent,
                isCurrentChapter: type.value.isCurrentChapter
            ))
        case .chapterRow(let type):
            .chapter(number: Int(type.value.number), partId: type.value.partId)
        }
    }
}
