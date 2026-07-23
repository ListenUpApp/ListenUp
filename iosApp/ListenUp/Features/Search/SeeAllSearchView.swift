import SwiftUI
import Shared

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
        case .results(let rows):
            results(rows) { observer.selectRow($0) }
        }
    }

    @ViewBuilder
    private func results(_ rows: [SearchRow], onTap: @escaping (SearchRow) -> Void) -> some View {
        switch destination.type {
        case .book:
            SeeAllBookGrid(rows: rows, onTap: onTap)
        case .contributor:
            SeeAllRowList(rows: rows) { row in SearchPersonRow(row: row) { onTap(row) } }
        case .series:
            SeeAllRowList(rows: rows) { row in SearchSeriesRow(row: row) { onTap(row) } }
        }
    }

    // MARK: - Navigation

    private func navigate(to route: SearchRoute) {
        switch route {
        case .book(let id): path.append(BookDestination(id: id))
        case .contributor(let id): path.append(ContributorDestination(id: id))
        case .series(let id): path.append(SeriesDestination(id: id))
        case .tag(let id, let name): path.append(FacetDestination(kind: .tag, id: id, name: name))
        }
    }
}

// MARK: - Result presentations

/// A width-driven cover grid for the full Books list. Columns flow from the available width
/// (`GridItem(.adaptive)`), so it reads well at every size — phone, iPad, Split View — instead
/// of a stretched single column.
private struct SeeAllBookGrid: View {
    let rows: [SearchRow]
    let onTap: (SearchRow) -> Void

    private let columns = [GridItem(.adaptive(minimum: 120), spacing: 16)]

    var body: some View {
        ScrollView {
            LazyVGrid(columns: columns, alignment: .leading, spacing: 20) {
                ForEach(rows) { row in
                    Button { onTap(row) } label: { SeeAllBookCard(row: row) }
                        .buttonStyle(.plain)
                }
            }
            .padding(16)
        }
    }
}

/// A single cover card in the See-all books grid: cover + title + author.
private struct SeeAllBookCard: View {
    let row: SearchRow

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            BookCoverImage(bookId: row.id, coverPath: row.coverPath, coverHash: row.coverHash)
                .aspectRatio(1, contentMode: .fit)
                .frame(maxWidth: .infinity)
                .clipShape(RoundedRectangle(cornerRadius: 10))
            Text(row.name)
                .font(.subheadline)
                .foregroundStyle(.primary)
                .lineLimit(2)
            if let author = row.author, !author.isEmpty {
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
    let rows: [SearchRow]
    @ViewBuilder let row: (SearchRow) -> Row

    var body: some View {
        List(rows) { row($0) }
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
