import SwiftUI
@preconcurrency import Shared

/// Dedicated search screen for finding books, authors, and series.
///
/// Features:
/// - Always-visible search bar
/// - Recent searches
/// - Search results with sections (Books, Authors, Series)
struct SearchView: View {
    @Environment(\.dependencies) private var deps
    @State private var searchText = ""
    @FocusState private var isSearchFocused: Bool

    var body: some View {
        VStack(spacing: 0) {
            // Search bar - always visible at top
            searchBar
                .padding()

            if searchText.isEmpty {
                emptyState
            } else {
                // TODO: Implement search results
                searchResults
            }
        }
        .background(Color(.systemBackground))
        .navigationTitle(String(localized: "common.search"))
        .navigationBarTitleDisplayMode(.large)
    }

    // MARK: - Search Bar

    private var searchBar: some View {
        AppTextField(
            placeholder: String(localized: "search.search_placeholder"),
            text: $searchText,
            kind: .search
        )
        .focused($isSearchFocused)
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
    }

    // MARK: - Empty State

    private var emptyState: some View {
        ContentUnavailableView(
            String(localized: "search.search_your_library"),
            systemImage: "magnifyingglass",
            description: Text(String(localized: "search.find_description"))
        )
    }

    // MARK: - Search Results (Placeholder)

    private var searchResults: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 16) {
                Text(String(format: String(localized: "search.results_for"), searchText))
                    .font(.headline)
                    .foregroundStyle(.secondary)
                    .padding(.horizontal)

                // TODO: Implement actual search with SearchViewModel
                Text(String(localized: "search.coming_soon"))
                    .foregroundStyle(.secondary)
                    .padding()
            }
            .padding(.top)
        }
    }
}

// MARK: - Preview

#Preview {
    NavigationStack {
        SearchView()
    }
}
