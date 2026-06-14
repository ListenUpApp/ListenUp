import SwiftUI
@preconcurrency import Shared

/// The full single-type "See all" search page — every hit of one kind for a query, reached
/// when a main-results group overflowed its display cap. A pushed screen with the system back
/// button; binds `SeeAllSearchObserver`, loads on appear, and renders with the same row views
/// as the main results. Result taps push onto the shared tab `path`, exactly like the main view.
struct SeeAllSearchView: View {
    let destination: SearchSeeAllDestination
    @Binding var path: NavigationPath

    @Environment(\.dependencies) private var deps
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var observer: SeeAllSearchObserver?

    var body: some View {
        Group {
            if let observer {
                content(observer)
                    .onChange(of: observer.pendingNavigation) { _, route in
                        guard let route else { return }
                        navigate(to: route)
                        observer.pendingNavigation = nil
                    }
            } else {
                Color.luSurface
            }
        }
        .navigationTitle(destination.type.title)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            if observer == nil {
                observer = SeeAllSearchObserver(viewModel: deps.createSeeAllSearchViewModel())
            }
            observer?.load(query: destination.query, type: destination.type)
        }
    }

    // MARK: - Content by phase

    @ViewBuilder
    private func content(_ observer: SeeAllSearchObserver) -> some View {
        switch observer.phase {
        case .idle, .loading:
            LoadingStateView(label: String(localized: "search.searching"))
        case .empty:
            ContentUnavailableView.search(text: destination.query)
        case .error(let message):
            ContentUnavailableView(
                String(localized: "search.unavailable"),
                systemImage: "exclamationmark.triangle",
                description: Text(message)
            )
        case .results(let hits):
            results(hits) { observer.selectHit($0) }
        }
    }

    @ViewBuilder
    private func results(_ hits: [SearchHit], onTap: @escaping (SearchHit) -> Void) -> some View {
        switch destination.type {
        case .book:
            SeeAllBookGrid(hits: hits, onTap: onTap)
        case .contributor:
            SeeAllRowList(hits: hits) { hit in SearchPersonRow(hit: hit) { onTap(hit) } }
        case .series:
            SeeAllRowList(hits: hits) { hit in SearchSeriesRow(hit: hit) { onTap(hit) } }
        }
    }

    // MARK: - Navigation

    private func navigate(to route: SearchRoute) {
        switch route {
        case .book(let id): path.append(BookDestination(id: id))
        case .contributor(let id): path.append(ContributorDestination(id: id))
        case .series(let id): path.append(SeriesDestination(id: id))
        case .tag(let id): path.append(TagDestination(id: id))
        }
    }
}

// MARK: - Result presentations

/// A width-driven cover grid for the full Books list. Columns flow from the available width
/// (`GridItem(.adaptive)`), so it reads well at every size — phone, iPad, Split View — instead
/// of a stretched single column.
private struct SeeAllBookGrid: View {
    let hits: [SearchHit]
    let onTap: (SearchHit) -> Void

    private let columns = [GridItem(.adaptive(minimum: 120), spacing: 16)]

    var body: some View {
        ScrollView {
            LazyVGrid(columns: columns, alignment: .leading, spacing: 20) {
                ForEach(hits, id: \.id) { hit in
                    Button { onTap(hit) } label: { SeeAllBookCard(hit: hit) }
                        .buttonStyle(.plain)
                }
            }
            .padding(16)
        }
    }
}

/// A single cover card in the See-all books grid: cover + title + author.
private struct SeeAllBookCard: View {
    let hit: SearchHit

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            BookCoverImage(bookId: hit.id, coverPath: hit.coverPath, blurHash: nil)
                .aspectRatio(1, contentMode: .fit)
                .frame(maxWidth: .infinity)
                .clipShape(RoundedRectangle(cornerRadius: 10))
            Text(hit.name)
                .font(.subheadline)
                .foregroundStyle(.primary)
                .lineLimit(2)
            if let author = hit.author, !author.isEmpty {
                Text(author)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
        }
    }
}

/// A readable-width `List` of icon-led rows (People / Series). Constrained so it never
/// stretches edge-to-edge on iPad — a comfortable reading column on regular width.
private struct SeeAllRowList<Row: View>: View {
    let hits: [SearchHit]
    @ViewBuilder let row: (SearchHit) -> Row

    var body: some View {
        List(hits, id: \.id) { hit in row(hit) }
            .listStyle(.plain)
            .frame(maxWidth: 700)
            .frame(maxWidth: .infinity)
    }
}

// MARK: - Type title

private extension SearchSeeAllType {
    /// The localized page title — the plural group name the main results already use.
    var title: String {
        switch self {
        case .book: String(localized: "library.books")
        case .contributor: String(localized: "search.people")
        case .series: String(localized: "common.series")
        }
    }
}
