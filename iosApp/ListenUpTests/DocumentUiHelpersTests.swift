import Testing
@testable import ListenUp

@Suite("Document UI helpers")
struct DocumentUiHelpersTests {
    @Test func basenameStripsDirectory() {
        #expect(documentBasename("map.pdf") == "map.pdf")
        #expect(documentBasename("extras/charts/map.pdf") == "map.pdf")
        #expect(documentBasename("") == "")
    }

    @Test func byteSizeIsHumanReadable() {
        #expect(formatDocumentSize(0) == "0 KB")
        #expect(formatDocumentSize(512) == "1 KB")        // rounds up to the nearest KB
        #expect(formatDocumentSize(1_048_576) == "1.0 MB")
        #expect(formatDocumentSize(25_165_824) == "24.0 MB")
    }

    @Test func formatSymbolFallsBackToGenericDoc() {
        #expect(documentFormatSymbol("pdf") == "doc.richtext")
        #expect(documentFormatSymbol("epub") == "book.closed")
        #expect(documentFormatSymbol("xyz") == "doc")
        #expect(documentFormatSymbol("PDF") == "doc.richtext")   // case-insensitive
    }

    @Test func pageDisplayIsOneBasedAndClamped() {
        #expect(pageDisplay(currentIndex: 0, pageCount: 1232) == PageDisplay(page: 1, total: 1232))
        #expect(pageDisplay(currentIndex: 1179, pageCount: 1232) == PageDisplay(page: 1180, total: 1232))
        #expect(pageDisplay(currentIndex: -1, pageCount: 10) == PageDisplay(page: 1, total: 10))
        #expect(pageDisplay(currentIndex: 99, pageCount: 10) == PageDisplay(page: 10, total: 10))
        #expect(pageDisplay(currentIndex: 0, pageCount: 0) == PageDisplay(page: 0, total: 0))
    }
}
