import SwiftUI
import Shared

/// The rating block on Book Detail, yours first: a "Ratings" heading over one inset group. Your stars come
/// first — the control itself, saved as your finger lifts — with your note, and a Menu holding Edit Note and
/// a destructive Remove Rating. Everyone else follows in labelled rows: the ListenUp score (which opens the
/// sources sheet) and your listeners, in one notation. While Book Detail's Hardcover check runs and no score
/// shows yet, "Checking Hardcover…" holds the score's row. At 560 pt and wider the two halves sit side by side.
///
/// Remove Rating is immediate, unless a note would be lost; then a confirmation dialog asks first (HIG,
/// Action sheets). iOS has no undo toast, so the observer announces the removal to VoiceOver instead.
/// Pure/presentational: the assembly screen hands it the snapshot and owns the sheets.
struct BookRatingSection: View {
    let snapshot: BookRatingsSnapshot
    let onSetStars: (Int) -> Void
    let onEditNote: () -> Void
    let onRemove: () -> Void
    let onOpenSources: () -> Void
    let onRefreshExternal: () -> Void

    /// The stars a drag is previewing, until the finger lifts.
    @State private var dragging: Int?
    @State private var width: CGFloat = 0
    @State private var isConfirmingRemove = false
    /// Fires `.press` only on a genuine tap of the score's row.
    @State private var sourcesTapCount = 0

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(String(localized: "book.detail_rating_heading"))
                .font(.headline)
                .accessibilityAddTraits(.isHeader)
                .padding(.horizontal, 4)
            group
                .background(
                    Color(.secondarySystemBackground),
                    in: RoundedRectangle(cornerRadius: Radius.xl, style: .continuous)
                )
                .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .haptic(.press, trigger: sourcesTapCount)
        .confirmationDialog(
            String(localized: "book.detail_rating_remove_confirm"),
            isPresented: $isConfirmingRemove,
            titleVisibility: .visible
        ) {
            Button(String(localized: "book.detail_rating_clear").titleStyled, role: .destructive, action: onRemove)
            Button(String(localized: "common.cancel"), role: .cancel) {}
        }
    }

    @ViewBuilder
    private var group: some View {
        if Self.isSplit(width: width) {
            HStack(alignment: .top, spacing: 0) {
                yourRating.frame(maxWidth: .infinity, alignment: .leading)
                Divider()
                everyone.frame(maxWidth: .infinity, alignment: .leading)
            }
        } else {
            VStack(alignment: .leading, spacing: 0) {
                yourRating
                Divider().padding(.leading, 16)
                everyone
            }
        }
    }

    // MARK: - You

    private var shownStars: Int { dragging ?? snapshot.mine?.halfStars ?? 0 }

    private var yourRating: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 8) {
                Text(String(localized: "book.detail_rating_yours"))
                    .font(.body.weight(.semibold))
                Spacer(minLength: 8)
                Text(Self.valueLabel(shownStars))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .accessibilityHidden(true) // the stars say their own value
                if let mine = snapshot.mine {
                    optionsMenu(mine)
                }
            }
            .frame(minHeight: 44)
            RatingStarsView(
                halfStars: shownStars,
                starSize: 30,
                onChange: { dragging = $0 },
                onCommit: { picked in
                    dragging = nil
                    onSetStars(picked)
                },
                onCancel: { dragging = nil }
            )
            .padding(.leading, -7) // the first glyph lines up with the text; its slot reaches the edge
            if dragging != nil {
                hint(String(localized: "book.detail_rating_saved_on_lift"))
            } else if let note = snapshot.mine?.note, !note.isEmpty {
                Text(String(format: String(localized: "book.detail_readers_note"), note))
                    .font(.subheadline)
                    .italic()
                    .foregroundStyle(.secondary)
            } else if snapshot.mine == nil {
                hint(String(localized: "book.detail_rating_tap_hint"))
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 11)
    }

    private func optionsMenu(_ mine: MyRating) -> some View {
        Menu {
            Button(action: onEditNote) {
                Label(Self.noteActionTitle(mine), systemImage: "square.and.pencil")
            }
            Button(role: .destructive) {
                if Self.removeNeedsConfirmation(mine) { isConfirmingRemove = true } else { onRemove() }
            } label: {
                Label(String(localized: "book.detail_rating_clear").titleStyled, systemImage: "trash")
            }
        } label: {
            Image(systemName: "ellipsis.circle")
                .imageScale(.large)
                .foregroundStyle(Color.luTint)
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .accessibilityLabel(String(localized: "book.detail_rating_options"))
    }

    private func hint(_ text: String) -> some View {
        Text(text)
            .font(.subheadline)
            .foregroundStyle(.secondary)
    }

    // MARK: - Everyone

    private var everyone: some View {
        VStack(alignment: .leading, spacing: 0) {
            switch snapshot.scoreRow {
            case .shown(let score): scoreRow(score)
            case .checking: checkingRow
            case .noRatings: noRatingsRow
            case .absent: EmptyView()
            }
            if let listeners = snapshot.listeners {
                if snapshot.scoreRow != .absent { Divider().padding(.leading, 16) }
                listenersRow(listeners)
            }
            if snapshot.scoreRow == .absent, snapshot.showsInlineRefresh {
                Divider().padding(.leading, 16)
                row { refreshButton }
            }
        }
    }

    private func scoreRow(_ score: ExternalScore) -> some View {
        Button {
            sourcesTapCount += 1
            onOpenSources()
        } label: {
            row {
                VStack(alignment: .leading, spacing: 1) {
                    Text(String(localized: "book.detail_rating_score"))
                    Text(Self.scoreDetail(score, snapshot))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: 8)
                Text("\u{2605} \(RatingLabels.shared.averageLabel(average: score.average))")
                    .fontWeight(.semibold)
                Image(systemName: "chevron.forward")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Self.scoreSentence(score, snapshot))
        // a11y audit M4 (Android, 2026-10-03), held on every platform: the score row is a real Button, so
        // VoiceOver can activate it, `row` floors it at 44 pt, and the hint names what activating it does.
        .accessibilityHint(String(localized: "book.detail_rating_show_sources"))
    }

    private var checkingRow: some View {
        row {
            VStack(alignment: .leading, spacing: 1) {
                Text(String(localized: "book.detail_rating_score"))
                Text(String(localized: "book.detail_rating_checking"))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 8)
            ProgressView()
        }
        .accessibilityElement(children: .combine)
    }

    private var noRatingsRow: some View {
        row {
            Text(String(localized: "book.detail_rating_no_ratings"))
                .foregroundStyle(.secondary)
            Spacer(minLength: 8)
            if snapshot.showsInlineRefresh { refreshButton }
        }
    }

    private func listenersRow(_ listeners: ListenersAverage) -> some View {
        row {
            VStack(alignment: .leading, spacing: 1) {
                Text(String(localized: "rating.source_listeners"))
                Text(Self.countLabel(listeners.count))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 8)
            Text("\u{2605} \(listeners.label)")
                .fontWeight(.semibold)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Self.listenersSentence(listeners))
    }

    private var refreshButton: some View {
        Button(action: onRefreshExternal) {
            if snapshot.isRefreshingExternal {
                ProgressView()
            } else {
                Text(String(localized: "book.detail_rating_refresh"))
            }
        }
        .foregroundStyle(Color.luTint)
        .frame(minHeight: 44)
        .disabled(snapshot.isRefreshingExternal)
        .accessibilityLabel(String(localized: "book.detail_rating_refresh"))
    }

    private func row(@ViewBuilder _ content: () -> some View) -> some View {
        HStack(spacing: 12) { content() }
            .padding(.horizontal, 16)
            .padding(.vertical, 11)
            .frame(minHeight: 44)
    }

    // MARK: - Pure helpers (unit-tested; `nonisolated` so tests call them off the main actor)

    /// At this width of its own, the group splits into your half and everyone's.
    nonisolated static func isSplit(width: CGFloat) -> Bool { width >= 560 }

    /// Remove Rating asks first only when a note would be lost.
    nonisolated static func removeNeedsConfirmation(_ mine: MyRating) -> Bool {
        !(mine.note ?? "").isEmpty
    }

    /// "Add a Note" or "Edit Note", title-styled for a menu (HIG, Menus).
    nonisolated static func noteActionTitle(_ mine: MyRating) -> String {
        // Literal keys, so `verifySwiftStringKeys` sees both.
        let title = (mine.note ?? "").isEmpty
            ? String(localized: "book.detail_rating_add_note")
            : String(localized: "book.detail_rating_edit_note")
        return title.titleStyled
    }

    /// "4.5", or "Not rated" before a star is chosen.
    nonisolated static func valueLabel(_ halfStars: Int) -> String {
        halfStars < RatingStarsView.minHalfStars
            ? String(localized: "rating.stars_unrated")
            : RatingStarsView.starsLabel(Double(halfStars))
    }

    /// "12k ratings", or "1 rating".
    nonisolated static func countLabel(_ count: Int) -> String {
        if count == 1 { return String(localized: "book.detail_rating_count_one") }
        return String(
            format: String(localized: "book.detail_rating_count"),
            RatingLabels.shared.compactCount(count: Int32(count))
        )
    }

    /// "Audible, Hardcover, your listeners".
    nonisolated static func sourcesLabel(_ snapshot: BookRatingsSnapshot) -> String {
        let outside = snapshot.outsideSourcesInScore.map(\.displayName)
        let listeners = snapshot.listenersInScore ? [String(localized: "rating.source_listeners_inline")] : []
        return (outside + listeners).joined(separator: ", ")
    }

    /// "12k ratings · Audible, Hardcover, your listeners".
    nonisolated static func scoreDetail(_ score: ExternalScore, _ snapshot: BookRatingsSnapshot) -> String {
        String(
            format: String(localized: "book.detail_rating_score_detail"),
            countLabel(score.count),
            sourcesLabel(snapshot)
        )
    }

    /// What VoiceOver says for the score's row.
    nonisolated static func scoreSentence(_ score: ExternalScore, _ snapshot: BookRatingsSnapshot) -> String {
        let average = RatingLabels.shared.averageLabel(average: score.average)
        let sources = sourcesLabel(snapshot)
        if score.count == 1 {
            return String(format: String(localized: "book.detail_rating_score_a11y_one"), average, sources)
        }
        let compact = RatingLabels.shared.compactCount(count: Int32(score.count))
        return String(format: String(localized: "book.detail_rating_score_a11y"), average, compact, sources)
    }

    /// What VoiceOver says for your listeners' row — one decimal, and "1 rating", never "1 ratings".
    nonisolated static func listenersSentence(_ listeners: ListenersAverage) -> String {
        if listeners.count == 1 {
            return String(format: String(localized: "book.detail_rating_listeners_a11y_one"), listeners.label)
        }
        return String(
            format: String(localized: "book.detail_rating_listeners_a11y"),
            listeners.label,
            listeners.count
        )
    }
}

// MARK: - Preview

#Preview("Ratings, yours first") {
    let score = ExternalScore(
        average: 4.6,
        count: 12_203,
        outsideShares: [.audible: 0.62, .hardcover: 0.3],
        listenersShare: 0.08
    )
    ScrollView {
        VStack(spacing: 32) {
            BookRatingSection(
                snapshot: BookRatingsSnapshot(
                    listeners: ListenersAverage(averageHalfStars: 8, count: 3),
                    mine: MyRating(halfStars: 9, note: "Loved it"),
                    external: score,
                    breakdown: [],
                    canRefresh: false,
                    isRefreshingExternal: false,
                    scoreRow: .shown(score),
                    outsideSourcesInScore: [.audible, .hardcover],
                    listenersInScore: true
                ),
                onSetStars: { _ in }, onEditNote: {}, onRemove: {}, onOpenSources: {}, onRefreshExternal: {}
            )
            BookRatingSection(
                snapshot: BookRatingsSnapshot(
                    listeners: ListenersAverage(averageHalfStars: 9, count: 1),
                    mine: MyRating(halfStars: 9, note: nil),
                    external: nil,
                    breakdown: [],
                    canRefresh: false,
                    isRefreshingExternal: false,
                    isCheckingExternal: true,
                    scoreRow: .checking
                ),
                onSetStars: { _ in }, onEditNote: {}, onRemove: {}, onOpenSources: {}, onRefreshExternal: {}
            )
            BookRatingSection(
                snapshot: BookRatingsSnapshot(
                    listeners: nil, mine: nil, external: nil, breakdown: [],
                    canRefresh: true, isRefreshingExternal: false,
                    scoreRow: .noRatings, showsInlineRefresh: true
                ),
                onSetStars: { _ in }, onEditNote: {}, onRemove: {}, onOpenSources: {}, onRefreshExternal: {}
            )
        }
        .padding()
    }
}
