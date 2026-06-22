import PDFKit
import SwiftUI

/// A single in-document search hit. `selection` drives navigation/highlight; `pageDisplay`
/// (1-based) + `snippet` are precomputed for the list.
struct PdfSearchHit: Identifiable {
    let id = UUID()
    let selection: PDFSelection
    let pageDisplay: Int
    let snippet: String
}

/// Owns the PDFKit async find lifecycle for one document. Debounced, cancel-on-new-query.
///
/// PDFKit fires match and end-find notifications on whatever thread it uses internally.
/// We register via `#selector`/`@objc` on the main queue so the Objective-C runtime
/// delivers each callback on the main thread. Because `@objc` notification callbacks
/// predate Swift concurrency, the compiler cannot prove isolation statically; the
/// `@MainActor` annotation on each `@objc` method asserts it explicitly, which is sound
/// given the `queue: .main` registration — the main queue IS the main actor's executor.
@MainActor
@Observable
final class PdfSearchController {
    private(set) var query: String = ""
    private(set) var hits: [PdfSearchHit] = []
    private(set) var isSearching: Bool = false

    private let document: PDFDocument
    private var debounceTask: Task<Void, Never>?

    init(document: PDFDocument) {
        self.document = document
        // Observer registration happens after full init; see setup() called at end of init.
        setup()
    }

    private func setup() {
        // `object: document` scopes both notifications to this document instance only.
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(didFindMatch(_:)),
            name: NSNotification.Name.PDFDocumentDidFindMatch,
            object: document
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(didEndFind),
            name: NSNotification.Name.PDFDocumentDidEndFind,
            object: document
        )
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    // MARK: - Notification callbacks

    @objc @MainActor private func didFindMatch(_ note: Notification) {
        guard let sel = note.userInfo?[PDFDocumentFoundSelectionKey] as? PDFSelection else { return }
        appendHit(sel)
    }

    @objc @MainActor private func didEndFind() {
        isSearching = false
    }

    // MARK: - Public API

    func update(query: String) {
        self.query = query
        debounceTask?.cancel()
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.count >= 2 else { cancel(); return }
        debounceTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(300))
            guard !Task.isCancelled, let self else { return }
            self.start(trimmed)
        }
    }

    func cancel() {
        debounceTask?.cancel()
        document.cancelFindString()
        hits = []
        isSearching = false
    }

    // MARK: - Private helpers

    private func start(_ queryString: String) {
        document.cancelFindString()
        hits = []
        isSearching = true
        document.beginFindString(queryString, withOptions: [.caseInsensitive])
    }

    private func appendHit(_ selection: PDFSelection) {
        guard let page = selection.pages.first else { return }
        let pageIndex = document.index(for: page)
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        let snippet = searchSnippet(pageText: page.string ?? "", query: trimmed)
        hits.append(PdfSearchHit(
            selection: selection,
            pageDisplay: pageIndex + 1,
            snippet: snippet.isEmpty ? (selection.string ?? "") : snippet
        ))
    }
}
