import PDFKit
import SwiftUI

/// Bridges a `PDFView` into SwiftUI. Continuous vertical scroll with pinch-zoom
/// (`autoScales`). The `PDFDocument` is loaded once by the caller and passed in; the
/// current page index is published back via `currentPageIndex` for the "Page X of Y" label.
/// Set `goToPage` (0-based) to programmatically navigate; it is cleared after the jump.
/// Set `highlightSelection` to scroll to and highlight a match; it is cleared after the jump.
/// Set `clearHighlight` to `true` to remove any existing highlight; it is reset to `false`
/// after clearing so the binding acts as a one-shot trigger.
struct PDFKitView: UIViewRepresentable {
    let document: PDFDocument
    @Binding var currentPageIndex: Int
    @Binding var goToPage: Int?
    @Binding var highlightSelection: PDFSelection?
    @Binding var clearHighlight: Bool

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> PDFView {
        let view = PDFView()
        view.displayMode = .singlePageContinuous
        view.displayDirection = .vertical
        view.autoScales = true
        view.backgroundColor = .clear
        view.document = document
        NotificationCenter.default.addObserver(
            context.coordinator,
            selector: #selector(Coordinator.pageChanged(_:)),
            name: .PDFViewPageChanged,
            object: view
        )
        return view
    }

    func updateUIView(_ uiView: PDFView, context: Context) {
        if let target = goToPage,
           target >= 0, target < document.pageCount,
           let page = document.page(at: target),
           uiView.currentPage !== page {
            uiView.go(to: page)
            Task { @MainActor in goToPage = nil }
        }
        if clearHighlight {
            uiView.highlightedSelections = []
            Task { @MainActor in clearHighlight = false }
        } else if let sel = highlightSelection {
            uiView.go(to: sel)
            uiView.setCurrentSelection(sel, animate: false)
            uiView.highlightedSelections = [sel]
            Task { @MainActor in highlightSelection = nil }
        }
    }

    static func dismantleUIView(_ uiView: PDFView, coordinator: Coordinator) {
        NotificationCenter.default.removeObserver(coordinator)
    }

    @MainActor
    final class Coordinator: NSObject {
        private let parent: PDFKitView
        init(_ parent: PDFKitView) { self.parent = parent }

        @objc func pageChanged(_ note: Notification) {
            guard let view = note.object as? PDFView,
                  let current = view.currentPage,
                  let index = view.document?.index(for: current) else { return }
            parent.currentPageIndex = index
        }
    }
}
