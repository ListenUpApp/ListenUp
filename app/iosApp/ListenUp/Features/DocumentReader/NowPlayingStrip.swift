import SwiftUI
import Shared

/// The reader's docked now-playing strip (M19): cover · the title and the mini line (chapter time left) · back N · the
/// main button. It is the accessory's own model, so the reader and the tab bar never disagree. A book that can't play
/// shows why and its way out rather than hiding: the mini line has honest content for it now. Opening or reading the
/// PDF never affects audio.
struct NowPlayingStrip: View {
    @Environment(\.dependencies) private var dependencies

    var body: some View {
        let player = dependencies.playerObserver
        if player.isVisible {
            let model = MiniPlayerAccessoryModel.make(
                snapshot: player.snapshot,
                chapterRemainingMs: max(0, player.chapterDurationMs - player.displayChapterPositionMs),
                chapterFraction: 0, isInline: false, isWide: false)
            NowPlayingStripContent(
                model: model,
                onSkipBack: { player.skipBackward() },
                onPrimary: { player.miniPrimary(model.primaryAction) })
        }
    }
}

/// The strip's layout, from a value, so the accessibility tests host it directly.
struct NowPlayingStripContent: View {
    let model: MiniPlayerAccessoryModel
    let onSkipBack: () -> Void
    let onPrimary: () -> Void

    var body: some View {
        HStack(spacing: 11) {
            BookCoverImage(bookId: model.bookId, coverPath: model.coverPath, coverHash: model.coverHash)
                .frame(width: 40, height: 40)
                .clipShape(.concentric(minimum: Radius.s))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 1) {
                Text(model.title).font(.subheadline.weight(.medium)).lineLimit(1)
                MiniPlayerLineText(model: model)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel([model.title, model.line].compactMap(\.self).joined(separator: ", "))
            Spacer(minLength: 8)
            if model.showsSkipBack {
                Button(action: onSkipBack) {
                    Image(systemName: PlayerGlyphs.skipBackward(seconds: model.skipBackSec))
                        .font(.title3)
                        .foregroundStyle(.primary)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(String(format: String(localized: "player.skip_backward"), "\(model.skipBackSec)"))
            }
            MiniPlayerPrimaryButton(model: model, action: onPrimary)
        }
        .padding(.horizontal, Spacing.s).padding(.vertical, Spacing.xs)
        // The cover sits concentric with the strip's corners.
        .containerShape(RoundedRectangle(cornerRadius: Radius.l, style: .continuous))
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: Radius.l, style: .continuous))
    }
}
