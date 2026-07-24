import Testing
@testable import ListenUp

@Suite("Search snippet")
struct SearchSnippetTests {
    @Test func snippetWindowsAroundFirstMatchWithEllipses() {
        let text = "The quick brown fox jumps over the lazy dog near the old oak tree by the river."
        let s = searchSnippet(pageText: text, query: "lazy", context: 10)
        #expect(s.contains("lazy"))
        #expect(s.hasPrefix("…"))
        #expect(s.hasSuffix("…"))
        #expect(s.count < text.count)
    }
    @Test func snippetNoEllipsisWhenMatchSpansToEdges() {
        #expect(searchSnippet(pageText: "lazy dog", query: "lazy", context: 40) == "lazy dog")
    }
    @Test func snippetIsCaseInsensitive() {
        #expect(searchSnippet(pageText: "Hello WORLD here", query: "world", context: 5).localizedCaseInsensitiveContains("world"))
    }
    @Test func snippetEmptyWhenNoMatch() {
        #expect(searchSnippet(pageText: "nothing here", query: "zzz", context: 5) == "")
    }
    @Test func snippetCollapsesWhitespace() {
        #expect(!searchSnippet(pageText: "a\n\n  b  match  c", query: "match", context: 20).contains("\n"))
    }
    @Test func resultCountTextPluralizes() {
        #expect(searchResultCountText(0) == "No results")
        #expect(searchResultCountText(1) == "1 result")
        #expect(searchResultCountText(23) == "23 results")
    }
}
