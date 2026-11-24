import Foundation

/// Pure label formatting for the book-edit relational chips, factored out of the
/// view so the seam is unit-testable without constructing SwiftUI or bridged Kotlin types.
enum BookEditFormatting {
    /// "Name · 1" when a sequence is present, otherwise just the series name. A
    /// blank/whitespace sequence is treated as absent.
    static func seriesLabel(name: String, sequence: String?) -> String {
        guard let sequence, !sequence.trimmingCharacters(in: .whitespaces).isEmpty else { return name }
        return "\(name) · \(sequence)"
    }

    /// Human-readable tag label derived from its slug: "found-family" → "Found Family".
    /// Mirrors the shared `EditableTag.displayName()` so the chip reads the same on every
    /// platform without depending on the Kotlin extension crossing the Swift Export seam.
    static func tagLabel(slug: String) -> String {
        slug
            .split(separator: "-", omittingEmptySubsequences: true)
            .map { $0.prefix(1).uppercased() + $0.dropFirst() }
            .joined(separator: " ")
    }

    /// "1 book" / "%d books" subtitle for a contributor or series search result. `nil` when the
    /// count is zero so the result row renders without a meaningless "0 books" line.
    static func bookCountSubtitle(_ count: Int) -> String? {
        guard count > 0 else { return nil }
        let format = count == 1 ? String(localized: "common.book_count") : String(localized: "common.books_count")
        return String(format: format, count)
    }

    /// A genre's parent-path context for the result subtitle, mirroring the shared
    /// `EditableGenre.parentPath`: "/fiction/fantasy/epic-fantasy" → "Fiction > Fantasy". `nil`
    /// for a top-level genre with no parent.
    static func genreParentPath(_ path: String) -> String? {
        let segments = path
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            .split(separator: "/", omittingEmptySubsequences: true)
        guard segments.count > 1 else { return nil }
        return segments
            .dropLast()
            .map { $0.prefix(1).uppercased() + $0.dropFirst() }
            .joined(separator: " > ")
    }
}
