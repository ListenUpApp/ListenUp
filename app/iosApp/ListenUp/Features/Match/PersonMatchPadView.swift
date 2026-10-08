import SwiftUI

/// Person Match details on iPad: a full-screen cover hosting a split view, the people in the sidebar and
/// Review in the detail, so comparing two people never loses the list (HIG, Split views). The best Strong
/// match opens in the detail straight away. Cancel and a prominent Apply Changes sit in the system placements
/// (HIG, Toolbars). A narrow window collapses the split view into one column and the ViewModel goes back to
/// waiting for a tap.
struct PersonMatchPadView: View {
    let contributorId: String
    let onClose: () -> Void
    let onMergedInto: (String) -> Void

    @Environment(\.dependencies) private var deps
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var observer: PersonMatchObserver?
    @State private var draft: String?
    @State private var editingByHand = false

    var body: some View {
        Group {
            if let observer {
                split(observer)
            } else {
                LoadingStateView().background(Color.luSurface)
            }
        }
        .task(id: contributorId) {
            guard observer == nil else { return }
            let created = PersonMatchObserver(viewModel: deps.createPersonMatchViewModel(contributorId: contributorId))
            created.useTwoPane(horizontalSizeClass == .regular)
            observer = created
        }
        .onChange(of: horizontalSizeClass) { _, sizeClass in
            observer?.useTwoPane(sizeClass == .regular)
        }
        .onChange(of: observer?.appliedToken ?? 0) { _, token in
            if token > 0 { onClose() }
        }
        .sheet(isPresented: $editingByHand) {
            ContributorEditView(contributorId: contributorId) { survivor in
                editingByHand = false
                onClose()
                onMergedInto(survivor)
            }
        }
    }

    private func split(_ observer: PersonMatchObserver) -> some View {
        NavigationSplitView {
            List(selection: selection(observer)) {
                PersonFindSections(
                    find: observer.find,
                    onRetrySource: { observer.retry() },
                    onFailureAction: { observer.perform($0) },
                    onEditByHand: { editingByHand = true }
                ) { row in
                    PersonCandidateRowView(row: row).tag(row.id)
                }
            }
            .listStyle(.sidebar)
            .searchable(
                text: Binding(get: { draft ?? observer.find.query }, set: { draft = $0 }),
                placement: .sidebar,
                prompt: Text(observer.find.searchPrompt)
            )
            .onSubmit(of: .search) {
                observer.search(draft ?? observer.find.query)
                draft = nil
            }
            .navigationTitle(String(localized: "match.title"))
            .navigationSubtitle(observer.find.subtitle)
            .navigationSplitViewColumnWidth(min: 300, ideal: 360, max: 440)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(String(localized: "common.cancel"), action: onClose)
                }
            }
            .modifier(MatchFindAnnouncements(phase: observer.find.phase))
        } detail: {
            NavigationStack {
                PersonReviewScreen(observer: observer, contributorId: contributorId, candidateId: nil, layout: .pad)
                    .toolbar {
                        ToolbarItem(placement: .confirmationAction) {
                            if applyBar(observer)?.applying == true {
                                ProgressView()
                                    .accessibilityLabel(String(localized: "match.applying"))
                            } else {
                                Button(String(localized: "match.apply_changes_title")) { observer.apply() }
                                    .buttonStyle(.borderedProminent)
                                    .disabled(!(applyBar(observer)?.canApply ?? false))
                                    .accessibilityHint(applyBar(observer)?.summary ?? "")
                            }
                        }
                    }
            }
        }
        .navigationSplitViewStyle(.balanced)
    }

    /// The sidebar's selection is the open Review; choosing a row opens it.
    private func selection(_ observer: PersonMatchObserver) -> Binding<String?> {
        Binding(
            get: { observer.review.candidateId ?? observer.find.phase.results?.pickedId },
            set: { candidateId in
                if let candidateId { observer.pick(candidateId) } else { observer.backToResults() }
            }
        )
    }

    private func applyBar(_ observer: PersonMatchObserver) -> MatchApplyBar? {
        if case .ready(let review) = observer.review { return review.applyBar }
        return nil
    }
}
