import SwiftUI

/// The rating block on Book Detail, directly above Readers.
///
/// Your listeners' average leads when anyone has rated the book ("Your listeners ★ 4 (3)"), then
/// either an invitation to rate it or your own stars with an Edit button. Both "Rate" and "Edit"
/// open the `RateBookSheet`. Pure/presentational: the assembly screen hands it the snapshot and
/// owns the sheet.
struct BookRatingSection: View {
    let snapshot: BookRatingsSnapshot
    let onOpenSheet: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
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
    }

    // MARK: - Rows

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
        .accessibilityLabel(String(
            format: String(localized: "book.detail_rating_listeners_a11y"),
            stars,
            listeners.count
        ))
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
            snapshot: BookRatingsSnapshot(listeners: nil, mine: nil),
            onOpenSheet: {}
        )
        BookRatingSection(
            snapshot: BookRatingsSnapshot(
                listeners: ListenersAverage(averageHalfStars: 7.6, count: 3),
                mine: MyRating(halfStars: 7, note: "Loved it")
            ),
            onOpenSheet: {}
        )
    }
    .padding()
}
