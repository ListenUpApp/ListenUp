import Foundation

/// Narrows the chapter list to what the reader typed, without ever renumbering it.
///
/// ⛔ Takes rows that are ALREADY numbered against the full set. A chapter has no stored number —
/// it is wherever it sits in start-time order — so numbering the *visible* rows would relabel
/// chapter 213 as chapter 1 the moment someone typed. Numbering first and filtering second makes
/// that impossible rather than merely avoided.
///
/// Matches a title substring, case-insensitively, **or the chapter's own number typed exactly**.
/// The number is the reason this is worth having on a 311-chapter book: "213" should find chapter
/// 213, not the two hundred rows whose titles happen to contain a 2.
///
/// A blank query is not a filter — it returns everything rather than nothing.
enum ChapterListFilter {
    static func matching(_ rows: [EditableChapterRow], query: String) -> [EditableChapterRow] {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return rows }

        let asNumber = Int(trimmed)
        return rows.filter { row in
            row.title.localizedCaseInsensitiveContains(trimmed) || row.number == asNumber
        }
    }
}
