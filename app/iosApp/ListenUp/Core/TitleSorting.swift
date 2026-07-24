import Foundation

/// Section-letter grouping for title/name lists, mirroring the shared
/// `TitleSortUtils.sortLetter` (`app/sharedLogic/.../util/TitleSortUtils.kt`).
///
/// **Why iOS needs its own copy.** The shared `LibraryViewModel` already returns books/series in
/// article-aware *sorted order* (it sorts with `ignoreTitleArticles`). But iOS groups them into
/// section headers + the alphabet scrubber on the Swift side (over native `BookRow`/`SeriesRow`
/// value types, off the bridging hot path). So the section letter must be computed with the *same*
/// article rule the VM sorted by — otherwise "The Hobbit" sorts into the H run but lands under a
/// "T" header. Keep this in sync with the Kotlin source of truth.
enum TitleSorting {
    /// Leading English articles dropped before grouping when `ignoreArticles` is on. Matches
    /// `TitleSortUtils.ENGLISH_ARTICLES` ("the", "a", "an"), each only when followed by whitespace.
    private static let articles = ["the", "a", "an"]

    /// The section-header letter for a title: an uppercase A–Z letter, or `#` for numeric/symbolic
    /// titles (and, when `ignoreArticles` is on, after a leading "The/A/An " is stripped).
    static func sortLetter(_ title: String, ignoreArticles: Bool) -> Character {
        let stripped = ignoreArticles ? strippingLeadingArticle(title) : title
        guard let first = stripped.first?.uppercased().first else { return "#" }
        return first.isLetter ? first : "#"
    }

    /// Drop a leading "the/a/an" + following whitespace, matching the shared regex `^(the|a|an)\s+`.
    /// An article not followed by whitespace ("A.I.", "Theater") is left untouched.
    private static func strippingLeadingArticle(_ title: String) -> String {
        let lower = title.lowercased()
        for article in articles where lower.hasPrefix(article) {
            let afterArticle = title.index(title.startIndex, offsetBy: article.count)
            guard afterArticle < title.endIndex, title[afterArticle].isWhitespace else { continue }
            return String(title[afterArticle...].drop(while: \.isWhitespace))
        }
        return title
    }
}
