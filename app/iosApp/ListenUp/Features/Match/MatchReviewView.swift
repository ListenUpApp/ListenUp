import SwiftUI
import Shared

/// Every action Review sends, as closures — so the content can be hosted with no ViewModel (the
/// accessibility harness does) and the observer stays the only thing that talks to Kotlin. Closures,
/// not bare method references: an isolated method reference over a Swift Export type crashed CI's
/// Swift 6.3.1 IRGen (#1521).
struct MatchReviewActions {
    var setTicked: (BookField, Bool) -> Void = { _, _ in }
    var chooseSource: (BookField, MatchSourceSelection) -> Void = { _, _ in }
    var chooseCover: (String) -> Void = { _ in }
    var removeLabel: (LabelKind, String) -> Void = { _, _ in }
    var restoreLabel: (LabelKind, String) -> Void = { _, _ in }
    var toggleSuggestion: (LabelKind, String) -> Void = { _, _ in }
    var setChapterNamesIncluded: (Bool) -> Void = { _ in }
    var toggleChapter: (Int32) -> Void = { _ in }
    var apply: () -> Void = {}

    @MainActor
    static func observing(_ observer: BookMatchObserver) -> MatchReviewActions {
        MatchReviewActions(
            setTicked: { observer.setTicked($0, $1) },
            chooseSource: { observer.chooseSource($0, $1) },
            chooseCover: { observer.chooseCover($0) },
            removeLabel: { observer.removeLabel($0, $1) },
            restoreLabel: { observer.restoreLabel($0, $1) },
            toggleSuggestion: { observer.toggleSuggestion($0, $1) },
            setChapterNamesIncluded: { observer.setChapterNamesIncluded($0) },
            toggleChapter: { observer.toggleChapter($0) },
            apply: { observer.apply() }
        )
    }
}

/// Where Review is shown: pushed on iPhone, with the Apply bar at the bottom; or the iPad split view's
/// detail, whose Apply Changes sits in the toolbar.
enum MatchReviewLayout {
    case phone, pad
}

/// Review for one candidate, in whichever phase it is.
struct MatchReviewScreen: View {
    let observer: BookMatchObserver
    let bookId: String
    /// The candidate this screen was opened for, or nil for the iPad detail (whatever is picked).
    let candidateId: String?
    let layout: MatchReviewLayout

    var body: some View {
        content
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .modifier(MatchReviewAnnouncements(phase: observer.review, reloadedToken: observer.reloadedToken))
    }

    private var title: String {
        if case .ready(let review) = observer.review { return review.candidate.title }
        return String(localized: "match.review")
    }

    @ViewBuilder
    private var content: some View {
        switch observer.review {
        case .ready(let review) where candidateId == nil || review.candidate.id == candidateId:
            MatchReviewContent(
                review: review,
                bookId: bookId,
                showsReviewReloaded: observer.showsReviewReloaded,
                actions: .observing(observer)
            )
            .safeAreaBar(edge: .bottom) {
                MatchApplyBarView(bar: review.applyBar, showsButton: layout == .phone, onApply: { observer.apply() })
            }
        case .failed(let id, _, let message) where candidateId == nil || id == candidateId:
            ContentUnavailableView {
                Label(String(localized: "match.review_failed_title"), systemImage: "exclamationmark.triangle")
            } description: {
                Text(message)
            } actions: {
                Button(String(localized: "match.try_again")) { observer.retryReview(id) }
                    .buttonStyle(.borderedProminent)
            }
        case .noneChosen where candidateId == nil:
            ContentUnavailableView(
                String(localized: "match.title"),
                systemImage: "sparkle.magnifyingglass",
                description: Text(String(localized: "match.none_chosen"))
            )
        default:
            LoadingStateView(label: String(localized: "match.review_loading"))
        }
    }
}

/// Review's sections, in canvas order: the candidate, What will change, Cover, Changes, Fills a gap, You
/// edited this, Genres & moods, Chapter names, Already the same. A section with nothing in it isn't drawn.
struct MatchReviewContent: View {
    let review: MatchReview
    let bookId: String?
    var showsReviewReloaded = false
    var actions = MatchReviewActions()

    var body: some View {
        ScrollViewReader { proxy in
            List {
                Section {
                    MatchReviewHeader(review: review)
                    if !review.summary.isEmpty {
                        MatchSummaryStrip(items: review.summary) { section in
                            withAnimation { proxy.scrollTo(section, anchor: .top) }
                        }
                    }
                    if showsReviewReloaded {
                        Label(String(localized: "match.review_reloaded"), systemImage: "arrow.clockwise")
                            .font(.subheadline)
                    }
                }

                if let cover = review.cover {
                    section(.cover, String(localized: "match.section_cover")) {
                        MatchCoverPicker(cover: cover, bookId: bookId, onChoose: { actions.chooseCover($0) })
                    }
                }
                fieldSection(.changes, String(localized: "match.section_changes"), review.changes)
                fieldSection(.fillsGap, String(localized: "match.section_fills_gap"), review.fillsGap)
                fieldSection(.youEdited, String(localized: "match.section_you_edited"), review.youEdited)

                if !review.labels.isEmpty {
                    section(.labels, String(localized: "match.section_genres_moods"),
                            footer: String(localized: "match.tags_note")) {
                        ForEach(review.labels) { group in
                            MatchLabelGroupView(group: group, actions: actions)
                        }
                    }
                }

                if let chapters = review.chapters {
                    section(.chapterNames, String(localized: "match.section_chapter_names")) {
                        MatchChapterSectionView(section: chapters, actions: actions)
                    }
                }

                if let alreadySame = review.alreadySame {
                    section(.alreadySame, String(localized: "match.section_already_same")) {
                        Text(alreadySame).font(.subheadline).foregroundStyle(.secondary)
                    }
                }
            }
            .listStyle(.insetGrouped)
            .readableListWidth(720)
        }
    }

    @ViewBuilder
    private func fieldSection(_ id: MatchReviewSection, _ title: String, _ rows: [MatchFieldRow]) -> some View {
        if !rows.isEmpty {
            section(id, title) {
                ForEach(rows) { row in
                    MatchFieldRowView(
                        row: row,
                        onTick: { actions.setTicked(row.field, $0) },
                        onChooseSource: { actions.chooseSource(row.field, $0) }
                    )
                }
            }
        }
    }

    /// A section whose title is a rotor heading (HIG, VoiceOver) and a scroll target for What will change.
    private func section<Content: View>(
        _ id: MatchReviewSection,
        _ title: String,
        footer: String? = nil,
        @ViewBuilder content: () -> Content
    ) -> some View {
        Section {
            content()
        } header: {
            Text(title).accessibilityAddTraits(.isHeader)
        } footer: {
            if let footer { Text(footer) }
        }
        .id(id)
    }
}

/// The candidate Review is about: its cover, badges, title, where it was found and the facts.
private struct MatchReviewHeader: View {
    let review: MatchReview

    var body: some View {
        HStack(alignment: .top, spacing: Spacing.m) {
            MetadataRemoteCover(url: review.candidate.coverURL)
                .frame(width: 72, height: 72)
                .clipShape(RoundedRectangle(cornerRadius: Radius.m))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: Spacing.xxs) {
                MatchBadges(isBest: review.candidate.isBest, isCurrentLink: review.candidate.isCurrentLink)
                Text(review.candidate.title).font(.headline)
                Text(review.foundInSentence).font(.subheadline).foregroundStyle(.secondary)
                if !review.candidate.metadataLine.isEmpty {
                    Text(review.candidate.metadataLine).font(.footnote).foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .accessibilityElement(children: .combine)
    }
}

/// What will change: each count is a button that jumps to its section.
private struct MatchSummaryStrip: View {
    let items: [MatchSummaryItem]
    let onJump: (MatchReviewSection) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            Text(String(localized: "match.what_will_change"))
                .font(.subheadline.weight(.semibold))
                .accessibilityAddTraits(.isHeader)
            FlowLayout(spacing: Spacing.xs) {
                ForEach(items) { item in
                    Button {
                        onJump(item.section)
                    } label: {
                        HStack(alignment: .firstTextBaseline, spacing: Spacing.xxs) {
                            Text(item.value).font(.headline).contentTransition(.numericText())
                            Text(item.caption).font(.subheadline).foregroundStyle(.secondary)
                        }
                        .padding(.horizontal, Spacing.s)
                        .frame(minHeight: TapTarget.minimum)
                        .background(RoundedRectangle(cornerRadius: Radius.m).fill(Color.luFill))
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityElement(children: .combine)
                }
            }
        }
        .padding(.vertical, Spacing.xxs)
    }
}

/// The sticky Apply bar (HIG, Toolbars; Buttons): an inline error, the summary, and Apply Changes.
///
/// At the accessibility sizes the summary leaves the bar — it is the first thing Review says anyway — so the
/// bar keeps to its button and stays under a quarter of the screen (lesson M8).
struct MatchApplyBarView: View {
    let bar: MatchApplyBar
    var showsButton = true
    let onApply: () -> Void

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        if showsButton || bar.error != nil {
            VStack(spacing: Spacing.xs) {
                if let error = bar.error {
                    Label(error, systemImage: "exclamationmark.circle")
                        .font(.footnote)
                        .foregroundStyle(.red)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .dynamicTypeSize(...DynamicTypeSize.accessibility2)
                }
                if showsButton {
                    if !dynamicTypeSize.isAccessibilitySize {
                        Text(bar.summary)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .contentTransition(.numericText())
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    Button(action: onApply) {
                        ActionLabel(title: String(localized: "match.apply_changes_title"), isBusy: bar.applying)
                            .dynamicTypeSize(...DynamicTypeSize.accessibility1)
                    }
                    .prominentAction()
                    .accessibilityShowsLargeContentViewer()
                    .accessibilityHint(bar.summary)
                    .disabled(!bar.canApply || bar.applying)
                }
            }
            .padding(Spacing.m)
            .readableWidth(720)
            .animation(.default, value: bar.summary)
        }
    }
}

/// Applying, the reload and a failed Review are spoken, never silent.
private struct MatchReviewAnnouncements: ViewModifier {
    let phase: MatchReviewPhase
    let reloadedToken: Int

    private var applying: Bool {
        if case .ready(let review) = phase { return review.applyBar.applying }
        return false
    }

    private var applyError: String? {
        if case .ready(let review) = phase { return review.applyBar.error }
        return nil
    }

    func body(content: Content) -> some View {
        content
            .onChange(of: applying) { _, applying in
                if applying { VoiceOverAnnouncement.post(String(localized: "match.applying")) }
            }
            .onChange(of: applyError) { _, error in
                if let error { VoiceOverAnnouncement.post(error) }
            }
            .onChange(of: reloadedToken) { _, _ in
                VoiceOverAnnouncement.post(String(localized: "match.review_reloaded"))
            }
    }
}
