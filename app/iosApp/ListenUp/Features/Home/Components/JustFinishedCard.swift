import SwiftUI

/// The just-finished kind (spec §2.3): when it was finished, the book as one link, the next in its series with
/// **Play Next Book** (the leading action, C16), the shared half-star control (Simon's decision, Q2) and who else
/// finished it. ✕ dismisses it on every device. With no next book there is no primary button; the rating is the action.
struct JustFinishedCard: View {
    let slot: FinishedSlot
    let onPlay: (String) -> Void
    let onDismiss: () -> Void
    let onRate: (Int) -> Void

    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// The cover's colours (Home spec D3): neutral until known, at once on a revisit.
    @State private var coverTheme = CoverThemeObserver()
    /// The rating while a drag previews it; nil shows the saved one.
    @State private var dragging: Int?

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.s) {
            HStack(alignment: .firstTextBaseline) {
                Text(FinishedCardText.eyebrow(finishedAt: slot.finishedAt, now: .now))
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.secondary)
                Spacer()
                Button(action: onDismiss) {
                    Image(systemName: "xmark")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
                .minimumTapTarget(visualSize: 20)
                .accessibilityLabel(String(localized: "home.dismiss_everywhere_a11y"))
            }
            NavigationLink(value: BookDestination(id: slot.bookId)) { identity }
                .buttonStyle(.plain)
            if let next = slot.next {
                NavigationLink(value: BookDestination(id: next.id)) { NextBookRow(next: next) }
                    .buttonStyle(.plain)
                Button { onPlay(next.id) } label: {
                    Label(String(localized: "home.play_next_book_ios"), systemImage: "play.fill")
                        .frame(maxWidth: .infinity)
                }
                .prominentAction()
                .tint(coverTheme.colors?.action)
                .buttonBorderShape(.capsule)
                .accessibilityLabel(String(format: String(localized: "home.play_next_book_a11y_ios"), next.title))
            }
            rating
            if let line = FinishedCardText.alsoFinished(names: slot.alsoFinishedNames, more: slot.alsoFinishedOthers) {
                // Book Detail, landed on its Readers (spec §6.4).
                NavigationLink(value: BookDestination(id: slot.bookId, focus: .readers)) {
                    Text(line)
                        .font(.subheadline)
                        .foregroundStyle(Color.luTintInk)
                        .frame(minHeight: 44, alignment: .leading)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(Spacing.m)
        .background(
            coverTheme.colors?.wash ?? BookDetailHeroTone.color(for: colorScheme),
            in: RoundedRectangle(cornerRadius: Radius.xl, style: .continuous)
        )
        .task(id: "\(slot.bookId)#\(slot.coverHash ?? "")") {
            coverTheme.load(bookId: slot.bookId, coverHash: slot.coverHash)
        }
        .animation(CoverThemeMotion.animation(fadesIn: coverTheme.fadesIn, reduceMotion: reduceMotion),
                   value: coverTheme.colors)
        .accessibilityElement(children: .contain)
        .accessibilityLabel(String(localized: "home.you_finished_a_book_a11y"))
    }

    private var identity: some View {
        HStack(alignment: .top, spacing: 14) {
            BookCoverImage(bookId: slot.bookId, coverPath: slot.coverPath, coverHash: slot.coverHash)
                .frame(width: 116, height: 116)
                .clipShape(RoundedRectangle(cornerRadius: Radius.m, style: .continuous))
                .restrictedMarker(bookId: slot.bookId)
            VStack(alignment: .leading, spacing: 2) {
                Text(slot.title).font(.title3.bold())
                if !slot.author.isEmpty { Text(slot.author).font(.subheadline) }
                Text(
                    FinishedCardText.meta(
                        durationMs: slot.durationMs,
                        startedAt: slot.startedAt,
                        finishedAt: slot.finishedAt
                    )
                )
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isLink)
    }

    private var rating: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(String(localized: "home.how_was_it"))
                .font(.subheadline.weight(.semibold))
                .accessibilityHidden(true) // the stars say their own name and value
            RatingStarsView(
                halfStars: dragging ?? slot.yourHalfStars ?? 0,
                starSize: 34,
                onChange: { dragging = $0 },
                onCommit: { picked in
                    dragging = nil
                    onRate(picked)
                },
                onCancel: { dragging = nil }
            )
            .padding(.leading, -5)
        }
    }
}

/// "Next in the Series": the book after this one, as a quiet row that opens it.
struct NextBookRow: View {
    let next: NextBookItem

    var body: some View {
        HStack(spacing: Spacing.s) {
            BookCoverImage(bookId: next.id, coverPath: next.coverPath, coverHash: next.coverHash)
                .frame(width: 40, height: 40)
                .clipShape(RoundedRectangle(cornerRadius: Radius.s, style: .continuous))
            VStack(alignment: .leading, spacing: 0) {
                Text(String(localized: "home.next_in_series_ios"))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                Text(next.title)
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(1)
            }
            Spacer(minLength: 0)
        }
        .padding(Spacing.xs)
        .background(.background.opacity(0.6), in: RoundedRectangle(cornerRadius: Radius.m, style: .continuous))
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isLink)
    }
}
