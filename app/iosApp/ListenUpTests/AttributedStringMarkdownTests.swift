import Foundation
import Testing
@testable import ListenUp

@Suite("Book description markdown")
struct AttributedStringMarkdownTests {
    /// The plain text inside an attributed string, ignoring styling runs.
    private func plain(_ attributed: AttributedString) -> String {
        String(attributed.characters)
    }

    @Test func boldEmphasisIsParsedNotShownLiterally() {
        let result = AttributedString.fromBookMarkdown("A **bold** word.")
        let text = plain(result)
        #expect(text == "A bold word.")
        #expect(!text.contains("**"))
    }

    @Test func italicEmphasisIsParsedNotShownLiterally() {
        let result = AttributedString.fromBookMarkdown("An *italic* word.")
        let text = plain(result)
        #expect(text == "An italic word.")
        #expect(!text.contains("*"))
    }

    @Test func linkSyntaxRendersLabelNotRawMarkup() {
        let result = AttributedString.fromBookMarkdown("See [the site](https://example.com).")
        let text = plain(result)
        #expect(text.contains("the site"))
        #expect(!text.contains("https://example.com"))
        #expect(!text.contains("]("))
    }

    @Test func multiParagraphPreservesBlankLineBreak() {
        let result = AttributedString.fromBookMarkdown("First paragraph.\n\nSecond paragraph.")
        let text = plain(result)
        // .full interpreted syntax keeps the paragraph break rather than collapsing it.
        #expect(text.contains("\n"))
        #expect(text.contains("First paragraph"))
        #expect(text.contains("Second paragraph"))
    }

    @Test func emptyStringDoesNotCrashAndStaysEmpty() {
        let result = AttributedString.fromBookMarkdown("")
        #expect(plain(result).isEmpty)
    }

    @Test func plainTextWithoutMarkupSurvivesUnchanged() {
        let input = "Just a normal sentence with no markup at all."
        let result = AttributedString.fromBookMarkdown(input)
        #expect(plain(result) == input)
    }

    @Test func garbageInputFallsBackGracefully() {
        // Unbalanced / odd markup must never crash and must not vanish.
        let input = "Weird **[unclosed ((link"
        let result = AttributedString.fromBookMarkdown(input)
        #expect(!plain(result).isEmpty)
    }

    @Test func htmlEmphasisTagsBecomeStyledNotLiteral() {
        let result = AttributedString.fromBookMarkdown("An <i>italic</i> and <b>bold</b> note.")
        let text = plain(result)
        #expect(text == "An italic and bold note.")
        #expect(!text.contains("<i>"))
        #expect(!text.contains("<b>"))
    }

    @Test func htmlParagraphTagsBecomeParagraphBreaks() {
        let result = AttributedString.fromBookMarkdown("<p>First.</p><p>Second.</p>")
        let text = plain(result)
        #expect(text.contains("First."))
        #expect(text.contains("Second."))
        #expect(!text.contains("<p>"))
        #expect(!text.contains("</p>"))
    }

    @Test func htmlBreakTagBecomesNewline() {
        let result = AttributedString.fromBookMarkdown("Line one.<br>Line two.")
        let text = plain(result)
        #expect(text.contains("Line one."))
        #expect(text.contains("Line two."))
        #expect(!text.localizedCaseInsensitiveContains("<br"))
    }
}
