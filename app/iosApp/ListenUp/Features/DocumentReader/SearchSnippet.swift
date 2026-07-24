import Foundation

/// A context window around the first case-insensitive occurrence of `query` in `pageText`,
/// whitespace-collapsed, with leading/trailing ellipses when trimmed. Empty if no match.
func searchSnippet(pageText: String, query: String, context: Int = 40) -> String {
    let collapsed = pageText
        .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
        .trimmingCharacters(in: .whitespacesAndNewlines)
    guard !query.isEmpty,
          let range = collapsed.range(of: query, options: .caseInsensitive) else { return "" }
    let lower = collapsed.index(
        range.lowerBound, offsetBy: -context, limitedBy: collapsed.startIndex
    ) ?? collapsed.startIndex
    let upper = collapsed.index(
        range.upperBound, offsetBy: context, limitedBy: collapsed.endIndex
    ) ?? collapsed.endIndex
    var snippet = String(collapsed[lower..<upper])
    if lower > collapsed.startIndex { snippet = "…" + snippet }
    if upper < collapsed.endIndex { snippet += "…" }
    return snippet
}

/// "No results" / "1 result" / "N results".
func searchResultCountText(_ count: Int) -> String {
    switch count {
    case 0: return "No results"
    case 1: return "1 result"
    default: return "\(count) results"
    }
}
