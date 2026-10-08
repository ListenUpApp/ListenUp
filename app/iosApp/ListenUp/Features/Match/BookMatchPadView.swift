import SwiftUI

/// Match details on iPad: a full-screen cover hosting a split view, the results in the sidebar and Review
/// in the detail, so comparing editions never loses the list (HIG, Split views). The best Strong match
/// opens in the detail straight away. Cancel and a prominent Apply Changes sit in the system placements
/// (HIG, Toolbars). A narrow window collapses the split view into one column and the ViewModel goes back to
/// waiting for a tap.
struct BookMatchPadView: View {
    let bookId: String
    let onClose: () -> Void

    @Environment(\.dependencies) private var deps
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var observer: BookMatchObserver?
    @State private var draft: String?

    var body: some View {
        Group {
            if let observer {
                split(observer)
            } else {
                LoadingStateView().background(Color.luSurface)
            }
        }
        .task(id: bookId) {
            guard observer == nil else { return }
            let created = BookMatchObserver(viewModel: deps.createBookMatchViewModel(bookId: bookId))
            created.useTwoPane(horizontalSizeClass == .regular)
            observer = created
        }
        .onChange(of: horizontalSizeClass) { _, sizeClass in
            observer?.useTwoPane(sizeClass == .regular)
        }
        .onChange(of: observer?.appliedToken ?? 0) { _, token in
            if token > 0 { onClose() }
        }
    }

    private func split(_ observer: BookMatchObserver) -> some View {
        NavigationSplitView {
            List(selection: selection(observer)) {
                MatchFindSections(
                    find: observer.find,
                    onChooseStore: { observer.chooseStore($0) },
                    onRetrySource: { observer.retry() },
                    onFailureAction: { observer.perform($0) }
                ) { row in
                    MatchCandidateRowView(row: row).tag(row.id)
                }
            }
            .listStyle(.sidebar)
            .searchable(
                text: Binding(get: { draft ?? observer.find.query }, set: { draft = $0 }),
                placement: .sidebar,
                prompt: Text(String(localized: "match.search_label"))
            )
            .onSubmit(of: .search) {
                observer.search(draft ?? observer.find.query)
                draft = nil
            }
            .navigationTitle(String(localized: "match.title"))
            .navigationSplitViewColumnWidth(min: 300, ideal: 360, max: 440)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(String(localized: "common.cancel"), action: onClose)
                }
            }
            .modifier(MatchFindAnnouncements(phase: observer.find.phase))
        } detail: {
            NavigationStack {
                MatchReviewScreen(observer: observer, bookId: bookId, candidateId: nil, layout: .pad)
                    .navigationSubtitle(applySummary(observer) ?? "")
                    .toolbar {
                        ToolbarItem(placement: .confirmationAction) {
                            if applyBar(observer)?.applying == true {
                                ProgressView()
                                    .accessibilityLabel(String(localized: "match.applying"))
                            } else {
                                Button(String(localized: "match.apply_changes_title")) { observer.apply() }
                                    .buttonStyle(.borderedProminent)
                                    .disabled(!(applyBar(observer)?.canApply ?? false))
                                    .accessibilityHint(applySummary(observer) ?? "")
                            }
                        }
                    }
            }
        }
        .navigationSplitViewStyle(.balanced)
    }

    /// The sidebar's selection is the open Review; choosing a row opens it.
    private func selection(_ observer: BookMatchObserver) -> Binding<String?> {
        Binding(
            get: { observer.review.candidateId ?? observer.find.phase.results?.pickedId },
            set: { candidateId in
                if let candidateId { observer.pick(candidateId) } else { observer.backToResults() }
            }
        )
    }

    private func applyBar(_ observer: BookMatchObserver) -> MatchApplyBar? {
        if case .ready(let review) = observer.review { return review.applyBar }
        return nil
    }

    private func applySummary(_ observer: BookMatchObserver) -> String? { applyBar(observer)?.summary }
}
