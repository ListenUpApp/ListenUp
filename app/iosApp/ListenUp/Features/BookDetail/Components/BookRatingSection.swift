import SwiftUI
import Shared

/// The rating block on Book Detail, directly above Readers.
///
/// The outside world's headline ("★ 4.4 · 12k ratings") leads when an enabled source has rated the
/// book — tapping it opens `RatingBreakdownSheet`. Your listeners' average comes next when anyone
/// has rated the book ("Your listeners ★ 4 (3)"), then either an invitation to rate it or your own
/// stars with an Edit button. Both "Rate" and "Edit" open the `RateBookSheet`.
/// Pure/presentational: the assembly screen hands it the snapshot and owns both sheets.
struct BookRatingSection: View {
    let snapshot: BookRatingsSnapshot
    let onOpenSheet: () -> Void
    let onOpenBreakdown: () -> Void

    /// Fires `.press` only on a genuine tap of the headline — not merely whenever `external` changes.
    @State private var breakdownTapCount = 0

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let external = snapshot.external {
                externalRow(external)
            }
            if let listeners = snapshot.listeners {
                listenersRow(listeners)
            }
            if let mine = snapshot.mine {
                yourRatingRow(mine)
            } else {
                IconLabelButton(
                    icon: "star",
                    title: String(localized: "book.detail_rating_rate"),
                    action: onOpenSheet
                )
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

    /// "★ 4.4 · 12k ratings", or "★ 4.4 · 1 rating" for exactly one.
    static func externalHeadline(_ external: ExternalScore) -> String {
        let average = RatingLabels.shared.averageLabel(average: external.average)
        if external.count == 1 {
            return String(format: String(localized: "book.detail_rating_external_one"), "\u{2605} \(average)")
        }
        let compact = RatingLabels.shared.compactCount(count: Int32(external.count))
        return String(format: String(localized: "book.detail_rating_external"), "\u{2605} \(average)", compact)
    }

    /// What VoiceOver says for the headline — "Rated 4.4 out of 5 stars by 12k readers elsewhere".
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
            onOpenBreakdown: {}
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
            onOpenBreakdown: {}
        )
    }
    .padding()
}
