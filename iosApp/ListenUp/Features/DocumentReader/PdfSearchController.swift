import os
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
/// `PDFDocumentDidFindMatch` / `PDFDocumentDidEndFind` from a background thread. Instead of
/// closure observers, we drain `NotificationCenter.default.notifications(named:object:)` in
/// two `@MainActor` `Task`s the controller owns. The `for await` loop bodies run on the main
/// actor, so the (non-`Sendable`) `PDFSelection` is created and read there — no thread hop and
/// no isolation-assertion, no transfer wrapper.
///
/// **Task lifecycle:** the two drain tasks are stored in a `Sendable`, lock-guarded box so the
/// nonisolated `deinit` of this `@MainActor` class can cancel them without touching main-actor
/// state. Cancelling the task ends its `for await`, which unsubscribes from NotificationCenter.
///
/// **Match vs. end ordering:** matches arrive on one stream and are appended in post order, so
/// no highlight is missed or duplicated. `isSearching` clears off the separate end stream; the
/// only observable divergence from synchronous `queue: .main` delivery is that the spinner may
/// hide a hair before the very last buffered match lands — benign (hits still populate after).
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

    /// Lock-guarded handles to the two notification-drain tasks. `Sendable`, so the nonisolated
    /// `deinit` can cancel them; the lock is the synchronization boundary for the start/teardown
    /// race between `init` (main actor) and `deinit` (nonisolated).
    private let drainTasks = OSAllocatedUnfairLock<[Task<Void, Never>]>(initialState: [])

    /// Maximum number of search hits retained. Once reached, further matches are dropped
    /// and the find is cancelled to avoid unbounded growth on common queries.
    private let maxHits = 500

    init(document: PDFDocument) {
        self.document = document
        setup()
    }

    private func setup() {
        // `object: document` scopes each stream to this document instance only.
        let matchTask = Task { [weak self, document] in
            let matches = NotificationCenter.default.notifications(
                named: .PDFDocumentDidFindMatch, object: document
            )
            for await note in matches {
                guard let self else { return }
                guard let sel = note.userInfo?[PDFDocumentFoundSelectionKey] as? PDFSelection
                else { continue }
                self.appendHit(sel)
            }
        }
        let endTask = Task { [weak self, document] in
            let ends = NotificationCenter.default.notifications(
                named: .PDFDocumentDidEndFind, object: document
            )
            for await _ in ends {
                guard let self else { return }
                self.isSearching = false
            }
        }
        drainTasks.withLock { $0 = [matchTask, endTask] }
    }

    deinit {
        drainTasks.withLock { tasks in
            for task in tasks { task.cancel() }
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
