import SwiftUI
import Shared

/// The presented "Match metadata on Audible" wizard, opened from Book Detail. A focused task
/// that takes the whole sheet — no app sidebar — so the work gets the width.
///
/// Navigation is path-driven inside an internal `NavigationStack`: Find → Select → Updated, with
/// the chapter review surfaced as a nested sheet from Select. On a regular width (iPad) the Find
/// and Select steps render side-by-side as a master–detail modal; on compact width they push.
/// All state comes from `MetadataMatchObserver`.
struct MetadataMatchView: View {
    let bookId: String
    let title: String
    let author: String
    let asin: String?

    @Environment(\.dependencies) private var deps
    @Environment(\.dismiss) private var dismiss
    @Environment(\.horizontalSizeClass) private var hSize

    @State private var observer: MetadataMatchObserver?
    @State private var path: [MetadataStep] = []

    var body: some View {
        Group {
            if let observer {
                content(observer)
            } else {
                LoadingStateView().background(Color.luSurface)
            }
        }
        .task(id: bookId) {
            guard observer == nil else { return }
            observer = MetadataMatchObserver(
                viewModel: deps.createMetadataViewModel(),
                bookId: bookId,
                title: title,
                author: author,
                asin: asin
            )
        }
    }

    @ViewBuilder
    private func content(_ observer: MetadataMatchObserver) -> some View {
        NavigationStack(path: $path) {
            rootStep(observer)
                .navigationDestination(for: MetadataStep.self) { step in
                    destination(step, observer)
                }
        }
        .onChange(of: observer.appliedToken) { _, token in
            if token > 0 { path = [.updated] }
        }
        .alert(
            String(localized: "common.error"),
            isPresented: Binding(
                get: { observer.lastError != nil },
                set: { if !$0 { observer.dismissError() } }
            )
        ) {
            Button(String(localized: "common.ok"), role: .cancel) { observer.dismissError() }
        } message: {
            Text(observer.lastError ?? "")
        }
    }

    // MARK: - Steps

    /// On iPad the Find + Select steps share one master–detail screen; the rest push.
    @ViewBuilder
    private func rootStep(_ observer: MetadataMatchObserver) -> some View {
        if hSize == .regular {
            MetadataMatchPadView(
                observer: observer,
                onCancel: { dismiss() },
                onReviewChapters: { path.append(.chapters) }
            )
            .navigationBarBackButtonHidden()
        } else {
            MetadataFindView(
                observer: observer,
                onCancel: { dismiss() },
                onUseMatch: { path.append(.select) }
            )
        }
    }

    @ViewBuilder
    private func destination(_ step: MetadataStep, _ observer: MetadataMatchObserver) -> some View {
        switch step {
        case .select:
            MetadataSelectView(observer: observer, onReviewChapters: { path.append(.chapters) })
        case .chapters:
            MetadataChaptersView(observer: observer, onDone: { if !path.isEmpty { path.removeLast() } })
        case .updated:
            MetadataUpdatedView(bookTitle: title, observer: observer, onDone: { dismiss() })
                .navigationBarBackButtonHidden()
        }
    }
}

/// The pushable steps after Find.
enum MetadataStep: Hashable {
    case select
    case chapters
    case updated
}
