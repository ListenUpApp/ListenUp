import SwiftUI
@preconcurrency import Shared

/// Observes `SeriesDetailViewModel` — flattens the sealed `SeriesDetailUiState`
/// into flat `@Observable` properties. Thin over `FlowBridge`.
@Observable
@MainActor
final class SeriesDetailObserver {
    private(set) var isLoading: Bool = true
    private(set) var error: String?
    private(set) var seriesName: String = ""
    private(set) var seriesDescription: String?
    private(set) var coverPath: String?
    private(set) var totalDuration: String = ""
    private(set) var books: [BookListItem] = []

    var bookCount: Int { books.count }

    private let viewModel: SeriesDetailViewModel
    private let bridge = FlowBridge()

    init(viewModel: SeriesDetailViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
    }

    func stopObserving() {
        bridge.cancelAll()
    }

    // MARK: - Actions

    func loadSeries(seriesId: String) {
        viewModel.loadSeries(seriesId: seriesId)
    }

    // MARK: - State mapping

    private func apply(_ state: SeriesDetailUiState) {
        switch onEnum(of: state) {
        case .idle, .loading:
            isLoading = true
            error = nil
        case .ready(let r):
            isLoading = false
            error = nil
            seriesName = r.seriesName
            seriesDescription = r.seriesDescription
            coverPath = r.coverPath
            totalDuration = r.formatTotalDuration()
            books = Array(r.books)
        case .error(let e):
            isLoading = false
            error = e.message
        }
    }
}
