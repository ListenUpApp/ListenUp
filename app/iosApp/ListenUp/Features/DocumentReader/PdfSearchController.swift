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
/// **Threading:** PDFKit's `beginFindString` searches asynchronously and posts
/// `PDFDocumentDidFindMatch` / `PDFDocumentDidEndFind` from a background thread. We register
/// observers using the **closure form** (`addObserver(forName:object:queue:using:)`) with
/// `queue: .main`, which causes NotificationCenter to dispatch every callback on the main
/// queue regardless of the posting thread. This makes delivery main-thread-safe without any
/// hops or assertions.
///
/// **Swift 6 / non-Sendable `PDFSelection`:** `PDFSelection` is not `Sendable` because
/// PDFKit predates Swift concurrency annotations. The `queue: .main` closure runs on the
/// main thread, so the selection is both created and read there. `SelectionTransfer` is a
/// confined one-shot wrapper that satisfies the `@Sendable` closure requirement without
/// crossing any thread boundary — the wrapped value is extracted immediately on the same
/// main-queue invocation it arrived on.
///
/// **Observer token storage:** The tokens are held in a `nonisolated(unsafe)` array because
/// `deinit` on a `@MainActor` class is nonisolated in Swift 6 and cannot read main-actor
/// properties. The access pattern is safe: `setup()` writes the array once on the main actor
/// before any notification can fire; `deinit` reads it once after all callers have released
/// their last reference. No concurrent access is possible.
///
/// **Hit cap:** results are capped at `maxHits` to bound memory and snippet-computation
/// cost for common queries in large documents.
@MainActor
@Observable
final class PdfSearchController {
    private(set) var query: String = ""
    private(set) var hits: [PdfSearchHit] = []
    private(set) var isSearching: Bool = false

    private let document: PDFDocument
    private var debounceTask: Task<Void, Never>?
    // nonisolated(unsafe) is intentional: written once in setup() on the main actor before
    // any notification fires; read once in deinit after all strong references are gone.
    // No concurrent access is possible, so this is safe despite the annotation.
    // (Plain `nonisolated` can't apply to this @Observable mutable stored property; the
    //  "has no effect" warning is a known false-positive here.)
    nonisolated(unsafe) private var observerTokens: [NSObjectProtocol] = []

    /// Maximum number of search hits retained. Once reached, further matches are dropped
    /// and the find is cancelled to avoid unbounded growth on common queries.
    private let maxHits = 500

    /// One-shot transfer of a `PDFSelection` from a `queue: .main` notification closure
    /// into the `@MainActor`-isolated append method. The selection is always created and
    /// read on the main thread; this wrapper satisfies `@Sendable` for the notification
    /// block while keeping the value main-confined.
    private struct SelectionTransfer: @unchecked Sendable {
        let selection: PDFSelection
    }

    init(document: PDFDocument) {
        self.document = document
        setup()
    }

    private func setup() {
        // `object: document` scopes both notifications to this document instance only.
        // `queue: .main` guarantees main-thread delivery regardless of PDFKit's posting thread.
        let matchObserver = NotificationCenter.default.addObserver(
            forName: .PDFDocumentDidFindMatch,
            object: document,
            queue: .main
        ) { [weak self] note in
            guard let self,
                  let sel = note.userInfo?[PDFDocumentFoundSelectionKey] as? PDFSelection
            else { return }
            let transfer = SelectionTransfer(selection: sel)
            // The queue: .main registration guarantees this block runs on the main queue,
            // which is the main actor's executor, so assumeIsolated is sound here.
            MainActor.assumeIsolated {
                self.appendHit(transfer.selection)
            }
        }
        let endObserver = NotificationCenter.default.addObserver(
            forName: .PDFDocumentDidEndFind,
            object: document,
            queue: .main
        ) { [weak self] _ in
            guard let self else { return }
            MainActor.assumeIsolated {
                self.isSearching = false
            }
        }
        observerTokens = [matchObserver, endObserver]
    }

    deinit {
        for token in observerTokens {
            NotificationCenter.default.removeObserver(token)
        }
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
        guard hits.count < maxHits else {
            document.cancelFindString()
            return
        }
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
