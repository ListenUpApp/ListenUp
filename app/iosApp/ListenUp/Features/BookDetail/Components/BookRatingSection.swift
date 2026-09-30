import SwiftUI
import Shared

/// The rating block on Book Detail, directly above Readers.
///
/// The ListenUp score's headline ("★ 4.4 · 12k ratings") leads when an enabled outside source has
/// rated the book — tapping it opens `RatingBreakdownSheet`. A book only your listeners have rated
/// has no headline: their own line already says what they think, and a second number read off
/// ListenUp's curve (a lone 5★ scores about 4.4) would look like a contradiction. Before any
/// outside source has rated it, an admin
/// (`canRefresh`) sees a quiet "Refresh ratings" action where the headline would sit instead, so
/// they can fetch a first score without being stranded behind a headline that only exists once one
/// arrives. Your listeners' average comes next when anyone has rated the book ("Your listeners ★ 4
/// (3)"), then either an invitation to rate it or your own stars with an Edit button. Both "Rate"
/// and "Edit" open the `RateBookSheet`.
/// Pure/presentational: the assembly screen hands it the snapshot and owns both sheets.
struct BookRatingSection: View {
    let snapshot: BookRatingsSnapshot
    let onOpenSheet: () -> Void
    let onOpenBreakdown: () -> Void
    let onRefreshExternal: () -> Void

    /// Fires `.press` only on a genuine tap of the headline — not merely whenever `external` changes.
    @State private var breakdownTapCount = 0

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let external = Self.headline(snapshot) {
                externalRow(external)
            } else if Self.showsRefreshAction(snapshot) {
                refreshAction
            }
            if let listeners = snapshot.listeners {
                listenersRow(listeners)
            }
            if let mine = snapshot.mine {
                yourRatingRow(mine)
            } else {
                Button(action: onOpenSheet) {
                    Label(String(localized: "book.detail_rating_rate"), systemImage: "star")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .controlSize(.large)
                .frame(maxWidth: 220)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .haptic(.press, trigger: breakdownTapCount)
    }

    // MARK: - Rows

    private func externalRow(_ external: ExternalScore) -> some View {
        Button {
            breakdownTapCount += 1
            onOpenBreakdown()
        } label: {
            Text(Self.externalHeadline(external))
                .font(.subheadline)
                .foregroundStyle(.primary)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Self.externalSentence(external))
    }

    /// The quiet "Refresh ratings" action shown where the headline would sit before any enabled
    /// source has rated the book — admin only. Styled like the section's "Rate" button: a
    /// bordered, quiet pill, never prominent. Its busy/disabled state binds to
    /// `isRefreshingExternal` directly, exactly like `RatingBreakdownSheet`'s own refresh button.
    private var refreshAction: some View {
        Button(action: onRefreshExternal) {
            HStack(spacing: 7) {
                if snapshot.isRefreshingExternal {
                    ProgressView()
                        .controlSize(.small)
                } else {
                    Image(systemName: "arrow.clockwise")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(Color.luTint)
                }
                Text(String(localized: "book.detail_rating_refresh"))
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(.primary)
            }
            .frame(maxWidth: .infinity)
            .frame(height: 44)
            .overlay(
                RoundedRectangle(cornerRadius: Radius.m, style: .continuous)
                    .strokeBorder(Color.luSeparator, lineWidth: 1.5)
            )
        }
        .buttonStyle(.pressScaleChip)
        .frame(maxWidth: 220)
        .disabled(snapshot.isRefreshingExternal)
        .accessibilityLabel(String(localized: "book.detail_rating_refresh"))
    }

    /// Whether `refreshAction` shows: while there is no headline, admin only. Once a headline
    /// exists it carries refresh itself, one tap away via `RatingBreakdownSheet`. `nonisolated` so
    /// tests can call it off the main actor, same as `BookRatingsObserver.phase(from:)`.
    nonisolated static func showsRefreshAction(_ snapshot: BookRatingsSnapshot) -> Bool {
        headline(snapshot) == nil && snapshot.canRefresh
    }

    /// The score the headline shows: the snapshot's, unless your listeners are its only source.
    nonisolated static func headline(_ snapshot: BookRatingsSnapshot) -> ExternalScore? {
        guard let external = snapshot.external, !external.isListenersOnly else { return nil }
        return external
    }

    /// "★ 4.4 · 12k ratings", or "★ 4.4 · 1 rating" for exactly one.
    static func externalHeadline(_ external: ExternalScore) -> String {
        let average = RatingLabels.shared.averageLabel(average: external.average)
        if external.count == 1 {
            return String(format: String(localized: "book.detail_rating_external_one"), "\u{2605} \(average)")
        }
        let compact = RatingLabels.shared.compactCount(count: Int32(external.count))
        return String(format: String(localized: "book.detail_rating_external"), "\u{2605} \(average)", compact)
    }

    /// What VoiceOver says for the headline — "Rated 4.4 out of 5 stars from 12k ratings". The
    /// count spans every source, your listeners included, so it names ratings, not readers elsewhere.
    static func externalSentence(_ external: ExternalScore) -> String {
        let average = RatingLabels.shared.averageLabel(average: external.average)
        if external.count == 1 {
            return String(format: String(localized: "book.detail_rating_external_a11y_one"), average)
        }
        let compact = RatingLabels.shared.compactCount(count: Int32(external.count))
        return String(format: String(localized: "book.detail_rating_external_a11y"), average, compact)
    }

    private func listenersRow(_ listeners: ListenersAverage) -> some View {
        let stars = RatingStarsView.starsLabel(listeners.averageHalfStars)
        return Text(String(
            format: String(localized: "book.detail_rating_listeners"),
            "\u{2605} \(stars)",
            listeners.count
        ))
        .font(.subheadline)
        .foregroundStyle(.secondary)
        // VoiceOver hears a sentence, not "black star".
        .accessibilityLabel(Self.listenersSentence(listeners))
    }

    /// What VoiceOver says for the listeners' average — "from 1 rating", never "from 1 ratings".
    static func listenersSentence(_ listeners: ListenersAverage) -> String {
        let stars = RatingStarsView.starsLabel(listeners.averageHalfStars)
        if listeners.count == 1 {
            return String(format: String(localized: "book.detail_rating_listeners_a11y_one"), stars)
        }
        return String(
            format: String(localized: "book.detail_rating_listeners_a11y"),
            stars,
            listeners.count
        )
    }

    private func yourRatingRow(_ mine: MyRating) -> some View {
        HStack(spacing: 10) {
            Text(String(localized: "book.detail_rating_yours"))
                .font(.subheadline.weight(.semibold))
            RatingStarsView(halfStars: mine.halfStars, starSize: 15)
            Spacer(minLength: 8)
            Button(String(localized: "book.detail_rating_edit"), action: onOpenSheet)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Color.listenUpOrange)
                .frame(minHeight: 44)
                .buttonStyle(.borderless)
        }
    }
}

// MARK: - Preview

#Preview("Rating section") {
    VStack(alignment: .leading, spacing: 32) {
        BookRatingSection(
            snapshot: BookRatingsSnapshot(
                listeners: nil,
                mine: nil,
                external: nil,
                breakdown: [],
                canRefresh: false,
                isRefreshingExternal: false
            ),
            onOpenSheet: {},
            onOpenBreakdown: {},
            onRefreshExternal: {}
        )
        BookRatingSection(
            snapshot: BookRatingsSnapshot(
                listeners: ListenersAverage(averageHalfStars: 7.6, count: 3),
                mine: MyRating(halfStars: 7, note: "Loved it"),
                external: ExternalScore(average: 4.4, count: 12_000),
                breakdown: [ExternalRatingRow(source: .audible, average: 4.4, count: 12_000)],
                canRefresh: true,
                isRefreshingExternal: false
            ),
            onOpenSheet: {},
            onOpenBreakdown: {},
            onRefreshExternal: {}
        )
        BookRatingSection(
            snapshot: BookRatingsSnapshot(
                listeners: nil,
                mine: nil,
                external: nil,
                breakdown: [],
                canRefresh: true,
                isRefreshingExternal: false
            ),
            onOpenSheet: {},
            onOpenBreakdown: {},
            onRefreshExternal: {}
        )
    }
    .padding()
}
