import SwiftUI
import Shared

/// Federated search across books, people, series, and tags.
///
/// Native `.searchable` provides the (Liquid Glass) search chrome and single-select
/// `.searchScopes`; results render as grouped sections (compact) or a two-column
/// responsive layout (regular width). Hosted inside the enclosing tab `NavigationStack`,
/// so tapped hits push onto the shared `path` through the standard `navigationDestination`s.
struct SearchView: View {
    @Binding var path: NavigationPath

    @Environment(\.dependencies) private var deps
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var observer: SearchObserver?
    @State private var searchText = ""
    @State private var scope: SearchScope = .all

    var body: some View {
        Group {
            if let observer {
                content(observer)
                    .searchable(
                        text: $searchText,
                        placement: .navigationBarDrawer(displayMode: .always),
                        prompt: Text(String(localized: "search.search_placeholder"))
                    )
                    .searchScopes($scope) {
                        Text(String(localized: "search.tab_all")).tag(SearchScope.all)
                        Text(String(localized: "library.books")).tag(SearchScope.books)
                        Text(String(localized: "search.people")).tag(SearchScope.people)
                        Text(String(localized: "common.series")).tag(SearchScope.series)
                        Text(String(localized: "book.detail_tags")).tag(SearchScope.tags)
                    }
                    .onChange(of: searchText) { _, newValue in observer.setQuery(newValue) }
                    .onChange(of: scope) { _, newScope in observer.selectScope(newScope) }
                    .onChange(of: observer.pendingNavigation) { _, route in
                        guard let route else { return }
                        navigate(to: route)
                        observer.pendingNavigation = nil
                    }
            } else {
                Color.luSurface
            }
        }
        .offlineTopBanner()
        .navigationTitle(String(localized: "common.search"))
        .navigationBarTitleDisplayMode(.large)
        .onAppear {
            if observer == nil {
                observer = SearchObserver(viewModel: deps.searchViewModel)
            }
        }
    }

    // MARK: - Content by phase

    @ViewBuilder
    private func content(_ observer: SearchObserver) -> some View {
        switch observer.phase {
        case .idle:
            ContentUnavailableView(
                String(localized: "search.search_your_library"),
                systemImage: "magnifyingglass",
                description: Text(String(localized: "search.find_description"))
            )
        case .searching:
            LoadingStateView(label: String(localized: "search.searching"))
        case .empty:
            ContentUnavailableView.search(text: observer.query)
        case .error(let message):
            ContentUnavailableView(
                String(localized: "search.unavailable"),
                systemImage: "exclamationmark.triangle",
                description: Text(message)
            )
        case .results:
            results(observer)
        }
    }

    @ViewBuilder
    private func results(_ observer: SearchObserver) -> some View {
        if horizontalSizeClass == .regular {
            SearchResultsPad(groups: observer.groups, onTap: { observer.selectRow($0) }, onSeeAll: showSeeAll)
        } else {
            SearchResultsList(groups: observer.groups, onTap: { observer.selectRow($0) }, onSeeAll: showSeeAll)
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

    /// Push the full single-type "See all" page for an overflowing group.
    private func showSeeAll(_ type: SearchSeeAllType) {
        path.append(SearchSeeAllDestination(query: searchText, type: type))
    }
}
