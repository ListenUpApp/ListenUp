import PDFKit
import Testing
import UIKit
@testable import ListenUp

/// End-to-end coverage for the `.notifications(named:object:)` drain rewrite: a real
/// in-memory `PDFDocument` is searched and the controller must surface the hit through the
/// async stream (no closures, no `assumeIsolated`). Guards the highlight invariant the plan
/// flagged as the behavioral risk.
@MainActor
@Suite("PdfSearchController")
struct PdfSearchControllerTests {
    /// Renders a one-page PDF whose text contains `body`, so PDFKit's text layer can find it.
    private func makeDocument(body: String) -> PDFDocument {
        let pageRect = CGRect(x: 0, y: 0, width: 612, height: 792)
        let renderer = UIGraphicsPDFRenderer(bounds: pageRect)
        let data = renderer.pdfData { context in
            context.beginPage()
            let attributes: [NSAttributedString.Key: Any] = [
                .font: UIFont.systemFont(ofSize: 18)
            ]
            body.draw(in: pageRect.insetBy(dx: 24, dy: 24), withAttributes: attributes)
        }
        return PDFDocument(data: data)!
    }

    /// Polls `condition` on the main actor until it holds or the deadline passes.
    private func waitUntil(
        timeout: Duration = .seconds(2),
        _ condition: () -> Bool
    ) async {
        let deadline = ContinuousClock.now.advanced(by: timeout)
        while ContinuousClock.now < deadline {
            if condition() { return }
            try? await Task.sleep(for: .milliseconds(20))
        }
    }

    @Test func surfacesAMatchThroughTheAsyncStream() async {
        let document = makeDocument(body: "The lazy quick brown fox jumps over the lazy dog.")
        let controller = PdfSearchController(document: document)

        controller.update(query: "lazy")
        await waitUntil { !controller.hits.isEmpty }

        #expect(!controller.hits.isEmpty)
        #expect(controller.hits.allSatisfy { $0.pageDisplay == 1 })
    }

    @Test func cancelClearsHitsAndSearchingState() async {
        let document = makeDocument(body: "Searchable searchable searchable text here.")
        let controller = PdfSearchController(document: document)

        controller.update(query: "searchable")
        await waitUntil { !controller.hits.isEmpty }
        #expect(!controller.hits.isEmpty)

        controller.cancel()
        #expect(controller.hits.isEmpty)
        #expect(controller.isSearching == false)
    }

    @Test func shortQueryDoesNotSearch() async {
        let document = makeDocument(body: "Single letter queries are ignored.")
        let controller = PdfSearchController(document: document)

        controller.update(query: "a")
        // Below the 2-char floor: no search starts, hits stay empty.
        try? await Task.sleep(for: .milliseconds(400))
        #expect(controller.hits.isEmpty)
        #expect(controller.isSearching == false)
    }
}
