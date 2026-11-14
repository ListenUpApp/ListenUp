import Foundation
import Shared

/// A part's divider, as a native value. Mirrors the shared `ChapterOutlineRow.PartHeader`. It is a heading, never
/// current and never a control: only a chapter row is either.
struct BookPartHeader: Equatable {
    let title: String
    /// The chapter carrying the marker: this part's identity.
    let chapterId: String
    let chapterCount: Int
    let durationMs: Int64

    /// "13 chapters · 5 hr, 16 min", the quiet line under the title.
    var summary: String {
        let length = DurationFormatting.abbreviated(ms: durationMs)
        return String(format: String(localized: "book.detail_part_summary"), count, length)
    }

    /// "Part IV: Reaper, 13 chapters, 5 hours, 16 minutes": what VoiceOver reads for the heading, the length spelled
    /// out because "hr" and "min" are abbreviations for the eye, not words for the ear.
    var spokenName: String {
        let length = Duration.milliseconds(max(0, durationMs))
            .formatted(.units(allowed: [.hours, .minutes], width: .wide))
        return [title, count, length].joined(separator: ", ")
    }

    private var count: String {
        chapterCount == 1
            ? String(localized: "book.detail_chapter_count_one")
            : String(format: String(localized: "book.detail_chapter_count"), chapterCount)
    }
}

/// One line of the shared outline, flattened once at the observer so the view never sees Kotlin.
enum BookOutlineRow: Equatable, Identifiable {
    case book(title: String, chapterId: String)
    case part(BookPartHeader)
    /// `index` is the chapter's place in the whole chapter list, which indexes it; `number` is the one a reader sees,
    /// which starts again at each book of an omnibus and so never indexes anything.
    case chapter(index: Int, number: Int)

    var id: String {
        switch self {
        case .book(_, let chapterId): "book-\(chapterId)"
        case .part(let part): "part-\(part.chapterId)"
        case .chapter(let index, _): "chapter-\(index)"
        }
    }
}

/// A chapter in a section: where it sits in the whole list, and the number it shows.
struct BookOutlineChapter: Equatable {
    let index: Int
    let number: Int
}

/// One card of the chapter list: the heading of the book that opens here, if one does, the part's divider if it has
/// one, and its chapters. A run of chapters before a book's first part is a card with no divider.
struct BookOutlineSection: Equatable, Identifiable {
    /// The first row the section was built from, so it keeps one identity as the window moves.
    let id: String
    var book: String?
    var part: BookPartHeader?
    var chapters: [BookOutlineChapter] = []
}

/// A marked book's chapters. The grouping is the shared outline's: this carries it, and splits it into the sections
/// the list draws, one inset card per part.
struct BookGroupedChapters: Equatable {
    let rows: [BookOutlineRow]
    let previewRows: [BookOutlineRow]

    init(rows: [BookOutlineRow], previewRows: [BookOutlineRow]) {
        self.rows = rows
        self.previewRows = previewRows
    }

    init(_ grouped: GroupedChapters) {
        self.init(rows: grouped.rows.map(Self.row), previewRows: grouped.previewRows.map(Self.row))
    }

    /// `rows` as cards: a new one at every book and part, a book's heading riding on the card of the part that opens
    /// it. Every chapter lands in exactly one card, in order.
    static func sections(_ rows: [BookOutlineRow]) -> [BookOutlineSection] {
        rows.reduce(into: []) { sections, row in
            switch row {
            case .book(let title, _):
                sections.append(BookOutlineSection(id: row.id, book: title))
            case .part(let part):
                if let last = sections.last, last.part == nil, last.chapters.isEmpty {
                    sections[sections.count - 1].part = part
                } else {
                    sections.append(BookOutlineSection(id: row.id, part: part))
                }
            case .chapter(let index, let number):
                if sections.isEmpty { sections.append(BookOutlineSection(id: row.id)) }
                sections[sections.count - 1].chapters.append(BookOutlineChapter(index: index, number: number))
            }
        }
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
                durationMs: type.value.durationMs
            ))
        case .chapterRow(let type):
            .chapter(index: Int(type.value.index), number: Int(type.value.number))
        }
    }
}
