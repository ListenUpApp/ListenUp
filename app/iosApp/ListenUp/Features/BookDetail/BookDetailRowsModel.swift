import Foundation
import Shared

/// Where a Details row leads, when it is a link.
enum BookDetailRowLink: Equatable {
    /// Cast & Credits.
    case credits
    /// The Files & Audio sheet.
    case filesAndAudio
    /// The one document, opened at once.
    case document(String)
    /// The Maps & Illustrations sheet, for more than one document.
    case documents
}

/// One Details row as the screen draws it: its key, its value, and where it leads.
struct BookDetailCell: Equatable {
    let label: String
    let value: String
    let link: BookDetailRowLink?
}

/// One Details row (spec §3.2.1 D31). Native projection of the shared `BookDetailRow`; values only, so the words and
/// units are this platform's.
enum BookDetailRowValue: Equatable {
    case publisher(String)
    case released(Int)
    case genre(name: String, abridged: Bool)
    case language(String)
    case length(durationMs: Int64, chapterCount: Int)
    case added(epochMs: Int64)
    case isbn(String)
    case asin(String)
    case allCredits(count: Int)
    case filesAndAudio(container: String?, bitrate: String?)
    case maps(count: Int, onlyDocumentId: String?, format: String?, totalBytes: Int64)

    // Deliberately no `default`: a new Kotlin row must fail to compile here.
    init(_ row: BookDetailRow) {
        switch row.sealedType() {
        case .publisher(let it): self = .publisher(it.value.name)
        case .released(let it): self = .released(Int(it.value.year))
        case .genreLine(let it): self = .genre(name: it.value.genreName, abridged: it.value.abridged)
        case .language(let it): self = .language(it.value.displayName)
        case .length(let it):
            self = .length(durationMs: it.value.durationMs, chapterCount: Int(it.value.chapterCount))
        case .added(let it): self = .added(epochMs: it.value.epochMs)
        case .isbn(let it): self = .isbn(it.value.isbn)
        case .asin(let it): self = .asin(it.value.asin)
        case .allCredits(let it): self = .allCredits(count: Int(it.value.count))
        case .filesAndAudio(let it): self = .filesAndAudio(container: it.value.container, bitrate: it.value.bitrate)
        case .mapsAndIllustrations(let it):
            let maps = it.value
            self = .maps(count: Int(maps.count), onlyDocumentId: maps.onlyDocumentId, format: maps.format,
                         totalBytes: maps.totalBytes)
        }
    }

    /// The row's words. Sizes through `StorageFormat`, lengths as the hero spells them, days in the reader's locale.
    var cell: BookDetailCell {
        switch self {
        case .publisher(let name):
            BookDetailCell(label: String(localized: "book.detail_publisher"), value: name, link: nil)
        case .released(let year):
            BookDetailCell(label: String(localized: "book.detail_released"), value: String(year), link: nil)
        case .genre(let name, let abridged):
            BookDetailCell(
                label: String(localized: "book.detail_genre"),
                value: String(
                    format: String(localized: "book.detail_genre_line"),
                    name,
                    abridged ? String(localized: "book.detail_abridged") : String(localized: "book.detail_unabridged")
                ),
                link: nil
            )
        case .language(let name):
            BookDetailCell(label: String(localized: "common.language"), value: name, link: nil)
        case .length(let durationMs, let chapterCount):
            BookDetailCell(
                label: String(localized: "book.detail_length"),
                value: Self.lengthValue(durationMs: durationMs, chapterCount: chapterCount),
                link: nil
            )
        case .added(let epochMs):
            BookDetailCell(
                label: String(localized: "book.detail_added"),
                value: Date(timeIntervalSince1970: Double(epochMs) / 1000)
                    .formatted(date: .abbreviated, time: .omitted),
                link: nil
            )
        case .isbn(let isbn):
            BookDetailCell(label: String(localized: "book.detail_isbn"), value: isbn, link: nil)
        case .asin(let asin):
            BookDetailCell(label: String(localized: "book.detail_asin"), value: asin, link: nil)
        case .allCredits(let count):
            BookDetailCell(label: String(localized: "book.detail_all_credits"), value: String(count), link: .credits)
        case .filesAndAudio(let container, let bitrate):
            BookDetailCell(
                label: String(localized: "book.detail_files_audio_ios"),
                value: [container, bitrate].compactMap { $0 }.joined(separator: " · "),
                link: .filesAndAudio
            )
        case .maps(let count, let onlyDocumentId, let format, let totalBytes):
            BookDetailCell(
                label: String(localized: "book.detail_maps_ios"),
                value: "\(format ?? String(format: String(localized: "book.detail_maps_count"), count))"
                    + " · \(StorageFormat.byteSize(totalBytes))",
                link: onlyDocumentId.map(BookDetailRowLink.document) ?? .documents
            )
        }
    }

    /// "45 hr, 30 min · 75 chapters": the hero's length, and the chapter count when there is one.
    static func lengthValue(durationMs: Int64, chapterCount: Int) -> String {
        let chapters: String? = switch chapterCount {
        case 0: nil
        case 1: String(localized: "book.detail_chapter_count_one")
        default: String(format: String(localized: "book.detail_chapter_count"), chapterCount)
        }
        return [BookDetailHero.durationText(ms: durationMs), chapters]
            .compactMap { $0 }
            .filter { !$0.isEmpty }
            .joined(separator: " · ")
    }
}
