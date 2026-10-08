import NukeUI
import SwiftUI

/// Person Match details on iPhone (and any compact width): Find, pushed from the contributor page, with
/// Review pushed on top of it. Back from Review returns to the intact results.
///
/// HIG, Searching: the search field lives in the navigation bar and the results are a list right under it.
/// There is no role to choose — Find looks for the person in every role. HIG, Lists and tables: each person is
/// a row that pushes their Review.
struct PersonMatchPhoneView: View {
    let contributorId: String
    let onClose: () -> Void
    let onMergedInto: (String) -> Void

    @Environment(\.dependencies) private var deps
    @State private var observer: PersonMatchObserver?

    var body: some View {
        Group {
            if let observer {
                PersonFindScreen(
                    observer: observer, contributorId: contributorId, onClose: onClose, onMergedInto: onMergedInto
                )
            } else {
                LoadingStateView().background(Color.luSurface)
            }
        }
        .navigationTitle(String(localized: "match.title"))
        .navigationSubtitle(observer?.find.subtitle ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: contributorId) {
            guard observer == nil else { return }
            let created = PersonMatchObserver(viewModel: deps.createPersonMatchViewModel(contributorId: contributorId))
            created.useTwoPane(false)
            observer = created
        }
        .onChange(of: observer?.appliedToken ?? 0) { _, token in
            if token > 0 { onClose() }
        }
    }
}

/// Find as a list with the search field in the navigation bar; each person pushes their Review.
private struct PersonFindScreen: View {
    let observer: PersonMatchObserver
    let contributorId: String
    let onClose: () -> Void
    let onMergedInto: (String) -> Void

    /// What the person typed and hasn't searched yet; nil shows the search the ViewModel ran.
    @State private var draft: String?
    @State private var editingByHand = false

    private var query: Binding<String> {
        Binding(get: { draft ?? observer.find.query }, set: { draft = $0 })
    }

    var body: some View {
        List {
            PersonFindSections(
                find: observer.find,
                onRetrySource: { observer.retry() },
                onFailureAction: { observer.perform($0) },
                onEditByHand: { editingByHand = true }
            ) { row in
                NavigationLink {
                    PersonReviewScreen(
                        observer: observer, contributorId: contributorId, candidateId: row.id, layout: .phone
                    )
                        .onAppear { observer.pick(row.id) }
                        .onDisappear { observer.backToResults() }
                } label: {
                    PersonCandidateRowView(row: row)
                }
                .accessibilityAddTraits(row.id == observer.find.phase.results?.pickedId ? .isSelected : [])
            }
        }
        .listStyle(.insetGrouped)
        .readableListWidth(720)
        .searchable(
            text: query,
            placement: .navigationBarDrawer(displayMode: .always),
            prompt: Text(observer.find.searchPrompt)
        )
        .onSubmit(of: .search) {
            observer.search(query.wrappedValue)
            draft = nil
        }
        .modifier(MatchFindAnnouncements(phase: observer.find.phase))
        .sheet(isPresented: $editingByHand) {
            ContributorEditView(contributorId: contributorId) { survivor in
                editingByHand = false
                onClose()
                onMergedInto(survivor)
            }
        }
    }
}

/// Person Find's sections, shared by the iPhone list and the iPad sidebar: the Your-library strip — what they
/// did here, every role — how the search started, the partial banner, a failure or No profiles, then Strong
/// match and Maybe. `row` draws each person, so each layout decides what a tap does.
struct PersonFindSections<Row: View>: View {
    let find: PersonFind
    let onRetrySource: () -> Void
    let onFailureAction: (MatchFailureAction) -> Void
    let onEditByHand: () -> Void
    @ViewBuilder let row: (PersonCandidateRow) -> Row

    var body: some View {
        if find.library != nil || find.stepsLine != nil {
            Section {
                if let library = find.library {
                    PersonLibraryStripView(strip: library)
                }
                if let stepsLine = find.stepsLine {
                    PersonAboutSearchView(stepsLine: stepsLine)
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

        switch find.phase {
        case .failed(let failure):
            Section {
                MatchFailureView(failure: failure, onAction: onFailureAction)
                    .listRowBackground(Color.clear)
            }
        case .noProfiles(let noProfiles):
            Section {
                PersonNoProfilesView(noProfiles: noProfiles, onEditByHand: onEditByHand)
                    .listRowBackground(Color.clear)
            }
        case .searching, .results:
            EmptyView()
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

extension PersonFindPhase: MatchAnnouncedFindPhase {
    var isSearching: Bool {
        if case .searching = self { return true }
        return false
    }

    var finishedAnnouncement: String? {
        switch self {
        case .results(let results) where results.all.count == 1: String(localized: "match.people_count_one")
        case .results(let results): String(format: String(localized: "match.people_count"), results.all.count)
        case .noProfiles(let noProfiles): noProfiles.title
        case .failed(let failure): failure.title
        case .searching: nil
        }
    }
}

/// The person's books here: up to three covers and "Narrated 5 of your books · Wrote 1: …".
struct PersonLibraryStripView: View {
    let strip: PersonLibraryStrip

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            if !strip.covers.isEmpty {
                HStack(spacing: Spacing.xs) {
                    ForEach(strip.covers) { cover in
                        BookCoverImage(bookId: cover.id, coverPath: cover.coverPath, coverHash: cover.coverHash)
                            .frame(width: 56, height: 56)
                            .clipShape(RoundedRectangle(cornerRadius: Radius.s))
                    }
                }
                .accessibilityHidden(true)
            }
            Text(strip.line).font(.subheadline)
        }
        .padding(.vertical, Spacing.xxs)
        .accessibilityElement(children: .combine)
    }
}

/// About this search: how it started.
struct PersonAboutSearchView: View {
    let stepsLine: String

    var body: some View {
        Text(stepsLine)
            .font(.footnote)
            .foregroundStyle(.secondary)
            .padding(.vertical, Spacing.xxs)
    }
}

/// No source has a profile for this person: say so, and offer Edit by Hand (HIG, Content unavailable).
/// The search stays above it, so another name is one tap away.
struct PersonNoProfilesView: View {
    let noProfiles: PersonNoProfiles
    let onEditByHand: () -> Void

    var body: some View {
        ContentUnavailableView {
            Label(noProfiles.title, systemImage: "person.crop.circle.badge.questionmark")
        } description: {
            Text(noProfiles.message)
        } actions: {
            Button(noProfiles.editTitle, action: onEditByHand)
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
        }
    }
}

/// One person: photo or initials, badges, name, role and works, what they did in your library, and where they
/// were found. One VoiceOver stop that says it all.
struct PersonCandidateRowView: View {
    let row: PersonCandidateRow
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        // The photo moves above the words at the accessibility sizes, so the name keeps the row's width.
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: Spacing.xs))
            : AnyLayout(HStackLayout(alignment: .top, spacing: Spacing.s))
        layout {
            PersonPhoto(url: row.photoURL, name: row.name)
                .frame(width: 56, height: 56)
            VStack(alignment: .leading, spacing: Spacing.xxs) {
                MatchBadges(isBest: row.isBest, isCurrentLink: row.isCurrentLink)
                Text(row.name).font(.body.weight(.semibold)).foregroundStyle(.primary)
                if !row.roleLine.isEmpty {
                    Text(row.roleLine).font(.subheadline).foregroundStyle(.secondary)
                }
                if !row.libraryLine.isEmpty {
                    if row.isStrong {
                        Label(row.libraryLine, systemImage: "checkmark.circle.fill")
                            .font(.footnote)
                            .foregroundStyle(Color.luStrongMatch)
                    } else {
                        Text(row.libraryLine).font(.footnote).foregroundStyle(.secondary)
                    }
                }
                if !row.sourcesLine.isEmpty {
                    Text(row.sourcesLine).font(.caption).foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.vertical, Spacing.xxs)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(row.accessibilityLabel)
    }
}

/// A person's photo from a source, or their initials while it loads or when there is none.
struct PersonPhoto: View {
    let url: String?
    let name: String

    var body: some View {
        LazyImage(url: url.flatMap(URL.init(string:))) { state in
            if let image = state.image {
                image.resizable().aspectRatio(contentMode: .fill)
            } else {
                ContributorAvatar(name: name, imagePath: nil, id: name, fontSize: 18)
            }
        }
        .clipShape(Circle())
        .accessibilityHidden(true)
    }
}
