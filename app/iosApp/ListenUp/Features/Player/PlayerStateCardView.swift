import SwiftUI
import UIKit

/// The card that takes the transport's place on Now Playing (player spec P32, §3.7): a problem with its ways out, or the
/// finished book with its rating and what comes next. The actions are the shared `PlayerCards` ones.
///
/// Content, not glass (HIG, Materials: "Don't use Liquid Glass in the content layer"): a card a layer above the wash, with
/// no shadow. Its tone is in a leading glyph and the title's colour rather than a coloured slab, the decision the mini
/// player's failed line already took (red glyph and text, no tint; plan C13), so the meaning never rests on colour
/// alone (HIG, Accessibility).
struct PlayerStateCardView: View {
    let card: PlayerCardModel
    let onAction: (PlayerCardModel.Action) -> Void
    let onRate: (Int) -> Void

    /// The rating while a drag previews it; nil shows the saved one.
    @State private var dragging: Int?

    /// The card's fill: the system background on the light wash, the secondary background on the dark one, so the card
    /// always reads as a layer above the wash, with the separator as its edge (HIG, Color).
    static let fill = Color(uiColor: UIColor { traits in
        traits.userInterfaceStyle == .dark ? .secondarySystemBackground : .systemBackground
    })

    static func glyph(for tone: PlayerCardModel.Tone) -> String {
        switch tone {
        case .error: "exclamationmark.triangle.fill"
        case .held: "tray.full.fill"
        case .neutral: "info.circle.fill"
        }
    }

    static func color(for tone: PlayerCardModel.Tone) -> Color {
        switch tone {
        case .error: .luPlayerError
        case .held: .luWarning
        case .neutral: .secondary
        }
    }

    var body: some View {
        if card.kind == .sleepFading {
            // The fade is the gold card with Keep Listening alone (§5.6), the Sleep sheet's own.
            SleepFadeCard(onKeepListening: { onAction(card.primary) })
                .onAppear { announce() }
        } else {
            standard
        }
    }

    private var standard: some View {
        VStack(alignment: .leading, spacing: Spacing.s) {
            switch card.kind {
            case let .problem(title, cause, placeSaved, reference, tone):
                problem(title: title, cause: cause, placeSaved: placeSaved, reference: reference, tone: tone)
            case .finished(let model):
                finished(model)
            case .sleepFading:
                EmptyView()
            }
            actions
        }
        .padding(Spacing.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Self.fill, in: RoundedRectangle(cornerRadius: Radius.xl, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: Radius.xl, style: .continuous).stroke(Color.luSeparator))
        .accessibilityElement(children: .contain)
        .onAppear { announce() }
        .onChange(of: card) { announce() }
    }

    // MARK: - Problem

    private func problem(
        title: String, cause: String?, placeSaved: Bool, reference: String?, tone: PlayerCardModel.Tone
    ) -> some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            HStack(alignment: .firstTextBaseline, spacing: Spacing.xs) {
                Image(systemName: Self.glyph(for: tone))
                    .foregroundStyle(Self.color(for: tone))
                    .accessibilityHidden(true)
                Text(title)
                    .font(.headline)
                    .foregroundStyle(tone == .neutral ? Color.primary : Self.color(for: tone))
                    .accessibilityAddTraits(.isHeader)
            }
            if let cause {
                Text(cause).font(.subheadline).foregroundStyle(.secondary)
            }
            if placeSaved {
                Text(String(localized: "player.place_saved")).font(.subheadline).foregroundStyle(.secondary)
            }
            if let reference {
                Text(reference).font(.caption).foregroundStyle(.secondary).textSelection(.enabled)
            }
        }
        .fixedSize(horizontal: false, vertical: true)
    }

    // MARK: - Finished

    private func finished(_ model: FinishedModel) -> some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            Text(Self.finishedLine(model, now: .now))
                .font(.footnote.weight(.semibold))
                .foregroundStyle(.secondary)
            Text(String(localized: "home.how_was_it"))
                .font(.subheadline.weight(.semibold))
                .accessibilityHidden(true) // the stars say their own name and value
            RatingStarsView(
                halfStars: dragging ?? model.yourHalfStars ?? 0,
                starSize: 34,
                onChange: { dragging = $0 },
                onCommit: { picked in
                    dragging = nil
                    onRate(picked)
                },
                onCancel: { dragging = nil }
            )
            .padding(.leading, -5)
            if let next = model.next {
                NextBookRow(next: next)
                    // Here the row names the book Play Next Book starts; the button is the action, not the row.
                    .accessibilityRemoveTraits(.isLink)
            }
        }
    }

    /// "Finished Today · 45 hr, 30 min · Sep 6 – Oct 10" — Home's words for the same finished book (plan C22).
    static func finishedLine(_ model: FinishedModel, now: Date) -> String {
        guard let finishedAt = model.finishedAt else { return DurationFormatting.abbreviated(ms: model.durationMs) }
        return FinishedCardText.eyebrow(finishedAt: finishedAt, now: now) + " · "
            + FinishedCardText.meta(durationMs: model.durationMs, startedAt: model.startedAt, finishedAt: finishedAt)
    }

    // MARK: - Actions

    /// The primary as the one prominent action, the secondary bordered beside it, or under it when the two don't fit
    /// (HIG, Buttons: "keep the number of prominent buttons to one or two per view").
    private var actions: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: Spacing.s) { buttons }
            VStack(spacing: Spacing.s) { buttons }
        }
        .padding(.top, Spacing.xxs)
    }

    @ViewBuilder
    private var buttons: some View {
        Button { onAction(card.primary) } label: {
            Text(card.primary.title).frame(maxWidth: .infinity)
        }
        .prominentAction()
        .buttonBorderShape(.capsule)
        if let secondary = card.secondary {
            Button { onAction(secondary) } label: {
                Text(secondary.title).frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .controlSize(.large)
            .buttonBorderShape(.capsule)
        }
    }

    /// Says the card's title when it appears or changes, since it replaces the controls under VoiceOver's cursor.
    private func announce() {
        let title: String = switch card.kind {
        case let .problem(title, _, _, _, _): title
        case .finished: String(localized: "player.mini_finished")
        case .sleepFading: String(localized: "player.sheet_fading_title_ios")
        }
        AccessibilityNotification.Announcement(title).post()
    }
}
