import SwiftUI

/// Match details on iPhone (and any compact width): Find, pushed from Book Detail, with Review pushed on
/// top of it and Compare in a sheet. Back from Review returns to the intact results.
///
/// HIG, Searching: the search field lives in the navigation bar, seeded with the book's title, and the
/// results are a list right under it. HIG, Sheets: Compare is a resizable sheet with medium and large
/// detents, a quick look that leaves the results where they were.
struct BookMatchPhoneView: View {
    let bookId: String
    let onApplied: () -> Void

    @Environment(\.dependencies) private var deps
    @State private var observer: BookMatchObserver?

    var body: some View {
        Group {
            if let observer {
                MatchFindScreen(observer: observer, bookId: bookId)
            } else {
                LoadingStateView().background(Color.luSurface)
            }
        }
        .navigationTitle(String(localized: "match.title"))
        .navigationBarTitleDisplayMode(.inline)
        .task(id: bookId) {
            guard observer == nil else { return }
            let created = BookMatchObserver(viewModel: deps.createBookMatchViewModel(bookId: bookId))
            created.useTwoPane(false)
            observer = created
        }
        .onChange(of: observer?.appliedToken ?? 0) { _, token in
            if token > 0 { onApplied() }
        }
    }
}

/// Find as a list with the search field in the navigation bar; each result pushes its Review.
private struct MatchFindScreen: View {
    let observer: BookMatchObserver
    let bookId: String

    /// What the person typed and hasn't searched yet; nil shows the search the ViewModel ran.
    @State private var draft: String?
    @State private var comparing: MatchCandidateRow?
    @State private var reviewing: String?
    /// The chosen row grows into Review (a zoom, which Reduce Motion turns into a cross-fade).
    @Namespace private var transition

    private var query: Binding<String> {
        Binding(get: { draft ?? observer.find.query }, set: { draft = $0 })
    }

    var body: some View {
        List {
            MatchFindSections(
                find: observer.find,
                onChooseStore: { observer.chooseStore($0) },
                onRetrySource: { observer.retry() },
                onFailureAction: { observer.perform($0) }
            ) { row in
                HStack(spacing: Spacing.xs) {
                    Button {
                        open(row.id)
                    } label: {
                        HStack(spacing: Spacing.xs) {
                            MatchCandidateRowView(row: row)
                            Image(systemName: "chevron.forward")
                                .font(.footnote.weight(.semibold))
                                .foregroundStyle(Color.luLabel3)
                                .accessibilityHidden(true)
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .matchedTransitionSource(id: row.id, in: transition)
                    .accessibilityAddTraits(row.id == observer.find.phase.results?.pickedId ? .isSelected : [])

                    Button {
                        comparing = row
                    } label: {
                        Image(systemName: "info.circle")
                            .font(.title3)
                            .frame(minWidth: TapTarget.minimum, minHeight: TapTarget.minimum)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.borderless)
                    .accessibilityLabel(String(format: String(localized: "match.compare_a11y"), row.title))
                }
            }
        }
        .listStyle(.insetGrouped)
        .readableListWidth(720)
        .searchable(
            text: query,
            placement: .navigationBarDrawer(displayMode: .always),
            prompt: Text(String(localized: "match.search_label"))
        )
        .onSubmit(of: .search) {
            observer.search(query.wrappedValue)
            draft = nil
        }
        .navigationDestination(item: reviewBinding) { candidateId in
            MatchReviewScreen(observer: observer, bookId: bookId, candidateId: candidateId, layout: .phone)
                .navigationTransition(.zoom(sourceID: candidateId, in: transition))
        }
        .sheet(item: $comparing) { row in
            MatchCompareSheet(
                yourCopy: observer.find.yourCopy,
                candidate: row,
                onReview: {
                    comparing = nil
                    open(row.id)
                },
                onBack: { comparing = nil }
            )
        }
        .modifier(MatchFindAnnouncements(phase: observer.find.phase))
    }

    /// Popping Review goes back to the intact results; the ViewModel forgets the open Review.
    private var reviewBinding: Binding<String?> {
        Binding(
            get: { reviewing },
            set: { candidateId in
                if candidateId == nil, reviewing != nil { observer.backToResults() }
                reviewing = candidateId
            }
        )
    }

    private func open(_ candidateId: String) {
        observer.pick(candidateId)
        reviewing = candidateId
    }
}

/// Find's sections, shared by the iPhone list and the iPad sidebar: the store and Your copy, the partial
/// banner, a failure, then Strong match and Maybe. `row` draws each candidate, so each layout decides
/// what a tap does.
struct MatchFindSections<Row: View>: View {
    let find: MatchFind
    let onChooseStore: (MatchStoreChoice) -> Void
    let onRetrySource: () -> Void
    let onFailureAction: (MatchFailureAction) -> Void
    @ViewBuilder let row: (MatchCandidateRow) -> Row

    var body: some View {
        if find.store != nil || find.yourCopy != nil {
            Section {
                if let store = find.store {
                    MatchStorePicker(menu: store, onChoose: onChooseStore)
                }
                if let yourCopy = find.yourCopy {
                    MatchYourCopyView(copy: yourCopy)
                }
            }
        }

        if case .searching = find.phase {
            Section {
                HStack(spacing: Spacing.s) {
                    ProgressView()
                    Text(String(localized: "match.searching")).foregroundStyle(.secondary)
                }
            }
        }

        if let partial = find.phase.results?.partial {
            Section {
                VStack(alignment: .leading, spacing: Spacing.xs) {
                    Label(partial.message, systemImage: "exclamationmark.triangle")
                        .font(.subheadline)
                    Button(partial.retryTitle, action: onRetrySource)
                        .buttonStyle(.bordered)
                }
                .padding(.vertical, Spacing.xxs)
            }
        }

        if case .failed(let failure) = find.phase {
            Section {
                MatchFailureView(failure: failure, onAction: onFailureAction)
                    .listRowBackground(Color.clear)
            }
        }

        if let results = find.phase.results {
            if !results.strong.isEmpty {
                Section {
                    ForEach(results.strong) { row($0) }
                } header: {
                    Text(String(localized: "match.strong_match")).accessibilityAddTraits(.isHeader)
                }
            }
            if !results.maybe.isEmpty {
                Section {
                    ForEach(results.maybe) { row($0) }
                } header: {
                    Text(String(localized: "match.maybe")).accessibilityAddTraits(.isHeader)
                }
            }
        }
    }
}

/// "Audible store: United States" — a menu holding the store picker, for this search only
/// (HIG, Menus; Pickers).
struct MatchStorePicker: View {
    let menu: MatchStoreMenu
    let onChoose: (MatchStoreChoice) -> Void

    var body: some View {
        Menu {
            Picker(
                String(localized: "match.store_region"),
                selection: Binding(get: { menu.selected }, set: { onChoose($0) })
            ) {
                ForEach(menu.choices) { Text($0.displayName).tag($0) }
            }
            .pickerStyle(.inline)
        } label: {
            Label(menu.title, systemImage: "globe")
                .font(.subheadline)
                .frame(minHeight: TapTarget.minimum, alignment: .leading)
        }
        .accessibilityHint(String(localized: "match.just_this_search"))
    }
}

/// Your copy: the cover, the facts this device knows, and how Find started.
struct MatchYourCopyView: View {
    let copy: MatchYourCopy

    var body: some View {
        HStack(alignment: .top, spacing: Spacing.s) {
            BookCoverImage(coverPath: copy.coverPath, coverHash: copy.coverHash)
                .frame(width: 48, height: 48)
                .clipShape(RoundedRectangle(cornerRadius: Radius.s))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: Spacing.xxs) {
                Text(String(localized: "match.your_copy"))
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .textCase(.uppercase)
                if let detail = copy.detailLine {
                    Text(detail).font(.subheadline)
                }
                if let steps = copy.stepsLine {
                    Text(steps).font(.footnote).foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .accessibilityElement(children: .combine)
    }
}

/// One candidate: cover, badges, title, the facts that tell editions apart, why it matches, and where
/// it was found.
struct MatchCandidateRowView: View {
    let row: MatchCandidateRow
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        // The cover moves above the words at the accessibility sizes, so the title keeps the row's width.
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: Spacing.xs))
            : AnyLayout(HStackLayout(alignment: .top, spacing: Spacing.s))
        layout {
            MetadataRemoteCover(url: row.coverURL)
                .frame(width: 56, height: 56)
                .clipShape(RoundedRectangle(cornerRadius: Radius.s))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: Spacing.xxs) {
                MatchBadges(isBest: row.isBest, isCurrentLink: row.isCurrentLink)
                Text(row.title).font(.body.weight(.semibold)).foregroundStyle(.primary)
                if !row.metadataLine.isEmpty {
                    Text(row.metadataLine).font(.subheadline).foregroundStyle(.secondary)
                }
                ForEach(row.reasons, id: \.self) { reason in
                    if row.isStrong {
                        Label(reason.text, systemImage: "checkmark.circle.fill")
                            .font(.footnote)
                            .foregroundStyle(Color.luStrongMatch)
                    } else {
                        Text(reason.text).font(.footnote).foregroundStyle(.secondary)
                    }
                }
                if !row.foundInLine.isEmpty {
                    Text(row.foundInLine).font(.caption).foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.vertical, Spacing.xxs)
        .accessibilityElement(children: .combine)
    }
}

/// "Best match" and "Your current link", as words — never colour alone.
struct MatchBadges: View {
    let isBest: Bool
    let isCurrentLink: Bool

    var body: some View {
        if isBest || isCurrentLink {
            // Wraps badge by badge rather than squeezing one into three lines.
            FlowLayout(spacing: Spacing.xxs) {
                if isBest { badge(String(localized: "match.best_match"), systemImage: "star.fill") }
                if isCurrentLink { badge(String(localized: "match.your_current_link"), systemImage: "link") }
            }
        }
    }

    private func badge(_ text: String, systemImage: String) -> some View {
        Label(text, systemImage: systemImage)
            .font(.caption.weight(.semibold))
            .labelStyle(.titleAndIcon)
            .foregroundStyle(.secondary)
            .padding(.horizontal, Spacing.xs)
            .padding(.vertical, 2)
            .background(Capsule().fill(Color.luFill))
    }
}

/// A failure with its way forward (HIG, Feedback; Loading): what happened, and the actions that fix it.
struct MatchFailureView: View {
    let failure: MatchFailure
    let onAction: (MatchFailureAction) -> Void

    var body: some View {
        ContentUnavailableView {
            Label(failure.title, systemImage: failure.systemImage)
        } description: {
            Text(failure.message)
        } actions: {
            ForEach(Array(failure.actions.enumerated()), id: \.element.id) { index, action in
                if index == 0 {
                    Button(action.title) { onAction(action) }
                        .buttonStyle(.borderedProminent)
                        .disabled(!action.isEnabled)
                } else {
                    Button(action.title) { onAction(action) }
                        .buttonStyle(.bordered)
                        .disabled(!action.isEnabled)
                }
            }
            // The default size draws 28-point buttons; large keeps every way forward a 44-point target.
            .controlSize(.large)
        }
    }
}

/// A Find phase VoiceOver follows: whether a search is running, and what to say when one finishes.
protocol MatchAnnouncedFindPhase: Equatable {
    var isSearching: Bool { get }
    /// "4 matches", or a failure's title; nil while searching.
    var finishedAnnouncement: String? { get }
}

extension MatchFindPhase: MatchAnnouncedFindPhase {
    var isSearching: Bool {
        if case .searching = self { return true }
        return false
    }

    var finishedAnnouncement: String? {
        switch self {
        case .results(let results): MatchCopy.matchesAnnouncement(results.all.count)
        case .failed(let failure): failure.title
        case .searching: nil
        }
    }
}

/// Searching and its outcome are spoken, never silent — for books and people alike.
struct MatchFindAnnouncements<Phase: MatchAnnouncedFindPhase>: ViewModifier {
    let phase: Phase

    func body(content: Content) -> some View {
        content
            .onChange(of: phase.isSearching) { _, searching in
                if searching { VoiceOverAnnouncement.post(String(localized: "match.searching")) }
            }
            // Only when a search finishes: picking a row also changes the results, and isn't news.
            .onChange(of: phase) { old, new in
                guard old.isSearching, let announcement = new.finishedAnnouncement else { return }
                VoiceOverAnnouncement.post(announcement)
            }
    }
}
