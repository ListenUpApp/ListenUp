import Foundation

extension AttributedString {
    /// Parses a book description into a styled `AttributedString`, preserving block
    /// structure (paragraphs and line breaks) — not just inline emphasis.
    ///
    /// Audiobookshelf descriptions are Markdown-flavoured (`**bold**`, `*italic*`,
    /// `[link](url)`, blank-line paragraphs) and occasionally carry a few HTML-ish tags
    /// (`<p>`, `<br>`, `<i>`). SwiftUI's `Text("…md…")` parses *inline* Markdown but
    /// collapses newlines, so a multi-paragraph synopsis renders as one run-on blob.
    /// Using `interpretedSyntax: .full` keeps the block elements intact.
    ///
    /// A small HTML pre-pass converts the common tags to their Markdown / whitespace
    /// equivalents so HTML descriptions degrade gracefully instead of showing literal
    /// angle brackets. Anything that still fails to parse falls back to the raw string
    /// rendered as plain text — never nothing, never a crash.
    ///
    /// - Parameter markdown: The raw description text from the shared model.
    /// - Returns: A styled `AttributedString` ready to hand to `Text(_:)`.
    static func fromBookMarkdown(_ markdown: String) -> AttributedString {
        let normalized = normalizingHtmlToMarkdown(markdown)
        let options = AttributedString.MarkdownParsingOptions(
            allowsExtendedAttributes: false,
            interpretedSyntax: .full,
            failurePolicy: .returnPartiallyParsedIfPossible
        )
        if let parsed = try? AttributedString(markdown: normalized, options: options) {
            return insertingParagraphBreaks(into: parsed)
        }
        return AttributedString(markdown)
    }

    /// Materializes `.full`'s block structure into visible line breaks.
    ///
    /// With `interpretedSyntax: .full`, the parser records paragraph boundaries as
    /// `presentationIntent` *metadata* — but a single `Text(_:)` renders only the
    /// character stream, so multi-paragraph synopses collapse into one run-on blob.
    /// We walk the block runs and splice a blank line between each, turning the
    /// implicit boundaries into the explicit breaks the reader expects.
    private static func insertingParagraphBreaks(into parsed: AttributedString) -> AttributedString {
        var result = AttributedString()
        var previousBlockIdentity: Int?
        for run in parsed.runs {
            let blockIdentity = run.presentationIntent?.components.first?.identity
            if let blockIdentity, blockIdentity != previousBlockIdentity, previousBlockIdentity != nil {
                result.append(AttributedString("\n\n"))
            }
            previousBlockIdentity = blockIdentity ?? previousBlockIdentity
            result.append(parsed[run.range])
        }
        return result
    }

    /// Converts the handful of HTML tags that show up in imported descriptions into
    /// their Markdown / whitespace equivalents, then strips any remaining tags so they
    /// don't render as literal text. Markdown's `.full` parser handles everything else.
    private static func normalizingHtmlToMarkdown(_ input: String) -> String {
        guard input.contains("<") else { return input }

        var result = input
        // Block breaks → blank lines so paragraphs survive.
        let breakPatterns = ["<br>", "<br/>", "<br />", "</p>", "</div>"]
        for pattern in breakPatterns {
            result = result.replacingOccurrences(
                of: pattern, with: "\n\n", options: [.caseInsensitive]
            )
        }
        // Emphasis tags → Markdown emphasis.
        let emphasisMap: [(String, String)] = [
            ("<i>", "*"), ("</i>", "*"), ("<em>", "*"), ("</em>", "*"),
            ("<b>", "**"), ("</b>", "**"), ("<strong>", "**"), ("</strong>", "**")
        ]
        for (tag, replacement) in emphasisMap {
            result = result.replacingOccurrences(
                of: tag, with: replacement, options: [.caseInsensitive]
            )
        }
        // Strip any remaining tags (e.g. opening `<p>`, `<div>`, `<span>`).
        result = result.replacingOccurrences(
            of: "<[^>]+>", with: "", options: [.regularExpression]
        )
        // Collapse the runs of blank lines our substitutions may have produced.
        result = result.replacingOccurrences(
            of: "\n{3,}", with: "\n\n", options: [.regularExpression]
        )
        return result.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
