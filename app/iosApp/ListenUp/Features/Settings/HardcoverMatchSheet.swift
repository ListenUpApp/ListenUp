import SwiftUI
import Shared

/// Which book Find on Hardcover is open for, as a sheet item.
struct HardcoverMatchTarget: Identifiable, Equatable {
    let bookId: String
    var id: String { bookId }
}

/// Find on Hardcover for one book: a sheet with the system search field, the results grouped by
/// whether they share the book's author, and the book's current match (with Remove Match) on top.
///
/// HIG, Sheets: a sheet for "a targeted experience" scoped to one task; it has nothing to save, so it
/// carries Cancel alone, in the cancellation slot. HIG, Search fields: `.searchable` in the sheet's
/// navigation bar. The HIG would "start search immediately when a person types", but every search
/// here spends the user's Hardcover request budget, so it runs on open and on the keyboard's Search
/// key. It follows the same page's "Consider showing suggested search terms" (No Results, and a
/// weak result set) and "consider categorizing them" ("By {author}" before "Other results").
///
/// Picking a result links it in one tap and closes the sheet. HIG, Alerts: "Avoid displaying alerts
/// for common, undoable actions" — a wrong pick is one Change Match away. Removing a match is not a
/// pick, so it confirms (HIG, Action sheets).
struct HardcoverMatchSheet: View {
    let bookId: String
    @Environment(\.dependencies) private var deps
    @Environment(\.dismiss) private var dismiss
    @State private var observer: HardcoverMatchObserver?
    /// The field's text, held here so typing never waits on the ViewModel's round trip.
    @State private var searchText = ""
    @State private var confirmingRemoval = false

    var body: some View {
        NavigationStack {
            Group {
                if let observer {
                    content(observer)
                } else {
                    LoadingStateView()
                }
            }
            .background(Color.luSurface)
            .navigationTitle(String(localized: "hardcover.match_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(String(localized: "common.cancel"), role: .cancel) { dismiss() }
                }
            }
        }
        .onAppear {
            if observer == nil {
                observer = HardcoverMatchObserver(viewModel: deps.createHardcoverMatchViewModel(bookId: bookId))
            }
        }
        .onChange(of: observer?.shouldDismiss ?? false) { _, should in
            if should { dismiss() }
        }
        .messageAlert(Binding(get: { observer?.alert }, set: { observer?.alert = $0 }))
        .confirmationDialog(
            String(localized: "hardcover.match_remove").titleStyled,
            isPresented: $confirmingRemoval,
            titleVisibility: .hidden
        ) {
            Button(String(localized: "hardcover.match_remove").titleStyled, role: .destructive) {
                observer?.removeMatch()
            }
            Button(String(localized: "common.cancel"), role: .cancel) {}
        } message: {
            Text(String(localized: "hardcover.match_remove_detail"))
        }
    }

    @ViewBuilder
    private func content(_ observer: HardcoverMatchObserver) -> some View {
        switch observer.phase {
        case .loading:
            ProgressView()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .bookMissing:
            ContentUnavailableView(String(localized: "hardcover.match_book_missing"), systemImage: "books.vertical")
        case .ready(let model):
            HardcoverMatchList(
                model: model,
                onSearchFor: { observer.search(for: $0) },
                onRetry: { observer.search() },
                onPick: { observer.pick($0) },
                onRemove: { confirmingRemoval = true }
            )
            .searchable(
                text: $searchText,
                placement: .navigationBarDrawer(displayMode: .always),
                prompt: String(localized: "hardcover.match_search_placeholder")
            )
            // A query, like `TextEntry.search`: nothing capitalized, nothing corrected.
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .onSubmit(of: .search) { observer.search() }
            .onChange(of: searchText) { _, text in
                if text != model.query { observer.queryChanged(text) }
            }
            .onChange(of: model.query, initial: true) { _, query in
                // The ViewModel fills the field on open and when a suggestion is tapped.
                if query != searchText { searchText = query }
            }
        }
    }
}

/// The sheet's list for a ready search: what is being matched, the current match, then the search's
/// state — searching, the grouped results, no results with suggestions, or a failure with Try Again.
/// Pure: the sheet wires its actions to the observer.
struct HardcoverMatchList: View {
    let model: HardcoverMatchModel
    let onSearchFor: (String) -> Void
    let onRetry: () -> Void
    let onPick: (Int64) -> Void
    let onRemove: () -> Void
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        List {
            Section {
                // No rows: the header names the book the search is for, under the field.
            } header: {
                matchingCaption
            }
            if let current = model.current {
                currentMatchSection(current)
            }
            searchSections
        }
        .listStyle(.insetGrouped)
        .readableListWidth(720)
    }

    // MARK: - Sections

    /// "Matching **Project Hail Mary** · Andy Weir": what is being matched, under the field.
    private var matchingCaption: some View {
        var caption = AttributedString(String(format: String(localized: "hardcover.match_matching"), model.bookTitle))
        if let range = caption.range(of: model.bookTitle) {
            caption[range].inlinePresentationIntent = .stronglyEmphasized
            caption[range].foregroundColor = .primary
        }
        if !model.bookAuthors.isEmpty {
            caption += AttributedString(" · \(model.bookAuthors)")
        }
        return VStack(alignment: .leading, spacing: Spacing.xxs) {
            Text(caption)
            if let resultsFor {
                Text(resultsFor)
            }
        }
        .font(.footnote)
        .textCase(nil)
    }

    /// At the accessibility sizes the one-line search field shows only the start of a long query, so the
    /// results say in full what they are for. At the regular sizes the field holds it, and this would repeat it.
    private var resultsFor: String? {
        guard dynamicTypeSize.isAccessibilitySize, case .results = model.search,
              !model.query.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
        return String(format: String(localized: "hardcover.match_results_for"), model.query)
    }

    private func currentMatchSection(_ current: HardcoverCurrentMatch) -> some View {
        Section {
            VStack(alignment: .leading, spacing: 2) {
                Text(current.title)
                    .font(.body.weight(.semibold))
                if let byline = current.byline {
                    Text(byline)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }
            .accessibilityElement(children: .combine)
            Button(role: .destructive) {
                onRemove()
            } label: {
                HStack {
                    Text(String(localized: "hardcover.match_remove").titleStyled)
                    if model.isRemoving {
                        Spacer()
                        ProgressView()
                    }
                }
            }
            .disabled(model.isBusy)
        } header: {
            Text(String(localized: "hardcover.match_current"))
        } footer: {
            Text(String(localized: "hardcover.match_remove_detail"))
        }
    }

    @ViewBuilder
    private var searchSections: some View {
        switch model.search {
        case .searching:
            Section {
                ProgressView(String(localized: "hardcover.match_searching"))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, Spacing.xxl)
                    .listRowBackground(Color.clear)
            }
        case .noResults:
            Section {
                ContentUnavailableView {
                    Label(String(localized: "hardcover.match_no_results_title"), systemImage: "magnifyingglass")
                } description: {
                    Text(String(format: String(localized: "hardcover.match_no_results_detail"), model.query))
                } actions: {
                    ForEach(model.suggestions, id: \.self) { suggestion in
                        Button(String(format: String(localized: "hardcover.match_search_for"), suggestion)) {
                            onSearchFor(suggestion)
                        }
                        .buttonStyle(.bordered)
                        .buttonBorderShape(.capsule)
                    }
                }
                .listRowBackground(Color.clear)
            }
        case .failed(let message):
            Section {
                ContentUnavailableView {
                    Label(String(localized: "hardcover.match_failed"), systemImage: "wifi.exclamationmark")
                } description: {
                    Text(message)
                } actions: {
                    Button(String(localized: "hardcover.try_again").titleStyled) { onRetry() }
                        .buttonStyle(.bordered)
                        .buttonBorderShape(.capsule)
                }
                .listRowBackground(Color.clear)
            }
        case .results(let byAuthor, let others):
            if model.isWeak, let author = model.leadAuthor {
                weakResultsSection(author: author)
            }
            if !byAuthor.isEmpty, let author = model.leadAuthor {
                Section(String(format: String(localized: "hardcover.match_by_author"), author)) {
                    candidateRows(byAuthor)
                }
            }
            if !others.isEmpty {
                Section(
                    byAuthor.isEmpty
                        ? String(localized: "hardcover.match_results")
                        : String(localized: "hardcover.match_other_results")
                ) {
                    candidateRows(others)
                }
            }
        }
    }

    /// Lots of results, none by the book's author: say so, and offer the search that adds the author.
    private func weakResultsSection(author: String) -> some View {
        Section {
            Label {
                VStack(alignment: .leading, spacing: 2) {
                    Text(String(format: String(localized: "hardcover.match_weak_title"), author))
                        .font(.body)
                    Text(String(localized: "hardcover.match_weak_detail"))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            } icon: {
                Image(systemName: "exclamationmark.magnifyingglass")
                    .foregroundStyle(.secondary)
            }
            .accessibilityElement(children: .combine)
            ForEach(model.suggestions, id: \.self) { suggestion in
                Button(String(format: String(localized: "hardcover.match_search_for"), suggestion)) {
                    onSearchFor(suggestion)
                }
            }
        }
    }

    private func candidateRows(_ rows: [HardcoverCandidate]) -> some View {
        ForEach(rows) { row in
            Button { onPick(row.id) } label: {
                HardcoverCandidateRowView(row: row, isLinking: model.linkingId == row.id)
            }
            .disabled(model.isBusy)
            .accessibilityLabel(String(format: String(localized: "hardcover.match_pick_a11y"), row.title))
            .accessibilityValue([row.authors, row.detail, row.ratings].compactMap { $0 }.joined(separator: ", "))
        }
    }
}

/// One result: the title, who wrote it, then whether there is an audiobook, the year and the rating
/// count. A result by the book's own author is set stronger — semibold title, ratings in the primary
/// colour — so the real book stands out from a summary at a glance.
private struct HardcoverCandidateRowView: View {
    let row: HardcoverCandidate
    let isLinking: Bool

    var body: some View {
        HStack(spacing: Spacing.s) {
            VStack(alignment: .leading, spacing: 2) {
                Text(row.title)
                    .font(row.sharesAuthor ? .body.weight(.semibold) : .body)
                    // `Color`, not the hierarchical style: inside a Button label that inherits the tint.
                    .foregroundStyle(Color.primary)
                Text(row.authors)
                    .font(.subheadline)
                    .foregroundStyle(Color.secondary)
                meta
                    .font(.footnote)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if isLinking {
                ProgressView()
            }
        }
        .padding(.vertical, 2)
        .contentShape(Rectangle())
    }

    /// "Audiobook · 2021 · **47,312 ratings**".
    private var meta: Text {
        let ratings = Text(row.ratings)
            .fontWeight(row.sharesAuthor ? .semibold : .regular)
            .foregroundStyle(row.sharesAuthor ? Color.primary : Color.secondary)
        guard let detail = row.detail else { return ratings }
        return Text("\(Text(verbatim: "\(detail) · ").foregroundStyle(Color.secondary))\(ratings)")
    }
}
