import PDFKit
import SwiftUI

/// Bridges a `PDFView` into SwiftUI. Continuous vertical scroll with pinch-zoom
/// (`autoScales`). Publishes the current page index back via `currentPageIndex` so the
/// reader can show "Page X of Y". `document` is built once from the local file URL.
struct PDFKitView: UIViewRepresentable {
    let url: URL
    @Binding var currentPageIndex: Int
    @Binding var pageCount: Int

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> PDFView {
        let view = PDFView()
        view.displayMode = .singlePageContinuous
        view.displayDirection = .vertical
        view.autoScales = true
        view.backgroundColor = .clear
        view.document = PDFDocument(url: url)
        pageCount = view.document?.pageCount ?? 0
        NotificationCenter.default.addObserver(
            context.coordinator,
            selector: #selector(Coordinator.pageChanged(_:)),
            name: .PDFViewPageChanged,
            object: view
        )
        return view
    }

    func updateUIView(_ uiView: PDFView, context: Context) {}

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
