import Foundation

extension String {
    /// This label in title-style capitalization, for a button: "Sync now" reads "Sync Now", "Find on
    /// Hardcover" stays as it is.
    ///
    /// The shared strings are written in sentence case, which Android and the web use for buttons; iOS
    /// buttons take title case. HIG, Writing: "Choose a style for each UI element type and use it
    /// consistently throughout your app — for example, title case for all alerts or sentence case for
    /// all headlines." Deriving it here keeps one English string per key rather than a second,
    /// iOS-only copy that could drift from it.
    ///
    /// The rule is English: only an English localization is changed, and every other language is
    /// returned untouched, since its own capitalization conventions are the translator's to set.
    var titleStyled: String {
        Self.titleStyled(self, languageCode: Bundle.main.preferredLocalizations.first ?? "en")
    }

    /// English title style: every word capitalized except articles, coordinating conjunctions and
    /// short prepositions, which stay lowercase unless they open or close the label. A word that
    /// already holds a capital ("ListenUp", "iPhone") is left as written.
    static func titleStyled(_ text: String, languageCode: String) -> String {
        guard languageCode.hasPrefix("en") else { return text }
        let words = text.split(separator: " ", omittingEmptySubsequences: false).map(String.init)
        return words.enumerated().map { index, word in
            let isEdge = index == 0 || index == words.count - 1
            if word.contains(where: \.isUppercase) { return word }
            if !isEdge, minorWords.contains(word) { return word }
            return capitalizingFirstLetter(word)
        }
        .joined(separator: " ")
    }

    private static let minorWords: Set<String> = [
        "a", "an", "the", "and", "but", "or", "nor", "for", "so", "yet",
        "as", "at", "by", "in", "of", "off", "on", "per", "to", "up", "via"
    ]

    /// "now" → "Now"; a word opening with punctuation ("“title”") capitalizes its first letter.
    private static func capitalizingFirstLetter(_ word: String) -> String {
        guard let index = word.firstIndex(where: \.isLetter) else { return word }
        return word.replacingCharacters(in: index...index, with: word[index].uppercased())
    }
}
