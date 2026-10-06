import Foundation

/// Every sentence the series hierarchy speaks — the series page, its "Add sub-series" sheet, the
/// editor's "Move into…" picker, the Library grid, search and the pickers that say where a series
/// sits. Pure over native values, so each is unit-tested and none re-bridges a Kotlin object.
enum SeriesHierarchyText {
    /// Between the names of a path: "Cosmere › Mistborn".
    static let separator = " › "

    /// "1 book" / "8 books".
    static func books(_ count: Int) -> String {
        String(format: String(localized: count == 1 ? "common.book_count" : "common.books_count"), Int32(count))
    }

    /// "2 series" — the hint on a card for a series with sub-series of its own.
    static func subSeries(_ count: Int) -> String {
        String(format: String(localized: "series.subseries_count"), Int32(count))
    }

    /// "4 series · 23 books" — a parent series' hero line and its Library card.
    static func seriesAndBooks(seriesCount: Int, bookCount: Int) -> String {
        String(format: String(localized: "series.count_books"), Int32(seriesCount), Int32(bookCount))
    }

    /// Names joined into one path: "Cosmere › Mistborn".
    static func path(_ names: [String]) -> String { names.joined(separator: separator) }

    /// Where a series sits and what it holds: "in Cosmere › Mistborn · 8 books". A top-level series
    /// reads just "8 books"; a count of zero (or none known) is left out. Nil when there is nothing.
    static func placement(path names: [String], bookCount: Int?) -> String? {
        var parts: [String] = []
        if !names.isEmpty {
            parts.append(String(format: String(localized: "series.in_path"), path(names)))
        }
        if let bookCount, bookCount > 0 { parts.append(books(bookCount)) }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    // MARK: - Series page

    /// A sub-series card's line: "8 books · 2 finished", "1 book · Finished", "3 books · Not started".
    static func childMeta(bookCount: Int, finishedCount: Int) -> String {
        "\(books(bookCount)) · \(childStatus(bookCount: bookCount, finishedCount: finishedCount))"
    }

    /// A sub-series card as one VoiceOver element: "Mistborn, 2 series, 8 books, 2 finished". The
    /// progress reads as counts, never as a percentage.
    static func childAccessibilityLabel(
        name: String,
        subSeriesCount: Int,
        bookCount: Int,
        finishedCount: Int
    ) -> String {
        var parts = [name]
        if subSeriesCount > 0 { parts.append(subSeries(subSeriesCount)) }
        parts.append(books(bookCount))
        parts.append(childStatus(bookCount: bookCount, finishedCount: finishedCount))
        return parts.joined(separator: ", ")
    }

    private static func childStatus(bookCount: Int, finishedCount: Int) -> String {
        if bookCount > 0, finishedCount >= bookCount { return String(localized: "series.book_finished") }
        if finishedCount == 0 { return String(localized: "series.not_started") }
        return String(format: String(localized: "series.finished_count"), Int32(finishedCount))
    }

    /// "Continue The Hero of Ages" — a grouped page names the book, because "Continue Book 3" is
    /// ambiguous when the books come from four series.
    static func continueTitle(bookTitle: String) -> String {
        String(format: String(localized: "series.continue_title"), bookTitle)
    }

    /// "Start The Final Empire" — the grouped page's button on a series not yet begun.
    static func startTitle(bookTitle: String) -> String {
        String(format: String(localized: "series.start_title"), bookTitle)
    }

    /// "Mistborn Era 1 · Book 3" under the grouped page's Continue; just the series when unnumbered.
    static func continueWhere(seriesName: String, sequence: String?) -> String {
        guard let sequence, !sequence.isEmpty else { return seriesName }
        return String(format: String(localized: "series.continue_where"), seriesName, sequence)
    }

    /// "Also in Cosmere" — a series' own books, after its sub-series.
    static func alsoIn(_ seriesName: String) -> String {
        String(format: String(localized: "series.also_in"), seriesName)
    }

    /// "Show all 4" — expands a folded group in place.
    static func showAll(_ count: Int) -> String {
        String(format: String(localized: "series.show_all"), Int32(count))
    }

    // MARK: - Editing

    /// "Mistborn Era 1 moved to position 2 of 2" — spoken after a reorder. Positions count from 1.
    static func moved(name: String, position: Int, total: Int) -> String {
        String(format: String(localized: "series.moved_a11y"), name, Int32(position), Int32(total))
    }

    /// "Move City Watch out of Discworld into Cosmere?"
    static func moveConfirmTitle(series: String, from: String, into: String) -> String {
        String(format: String(localized: "series.move_confirm_title"), series, from, into)
    }

    /// "Add sub-series to Cosmere"
    static func addSubSeriesTitle(parent: String) -> String {
        String(format: String(localized: "series.add_subseries_to"), parent)
    }

    /// "Move “Mistborn” into…"
    static func moveIntoTitle(series: String) -> String {
        String(format: String(localized: "series.move_into_named"), series)
    }

    /// "Creates “White Sand” inside Cosmere." — nil until a name is typed.
    static func newSubSeriesBody(name: String, parent: String) -> String? {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        return String(format: String(localized: "series.new_subseries_body"), trimmed, parent)
    }

    /// "Creates “Cosmere” and moves Mistborn into it." — nil until a name is typed.
    static func newParentBody(name: String, series: String) -> String? {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        return String(format: String(localized: "series.new_parent_body"), trimmed, series)
    }

    /// "“Cosmere” already exists."
    static func nameExists(_ name: String) -> String {
        String(format: String(localized: "series.name_exists_inline"), name)
    }
}
