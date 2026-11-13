import SwiftUI

/// A part in the chapter list. Its play is a button of its own, so VoiceOver reaches it without going
/// through the row, and with `isOpen` set the rest of the row opens and closes the part.
///
/// `isOpen` is nil for a part with nothing to open: any part in the collapsed window, and one that is
/// only its announcement. Then the row is the part's name and its play, and nothing claims to expand.
///
/// `readers` is who else is here: everyone in the part while it is closed, else whoever is on the chapter folded
/// into this header. Their avatars sit before the chevron; VoiceOver hears them in the row's value.
struct BookPartRow: View {
    let part: BookPartHeader
    let isOpen: Bool?
    let canPlay: Bool
    var readers: ChapterReaderStack?
    let onToggle: () -> Void
    let onPlay: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            if let isOpen {
                Button(action: onToggle) {
                    HStack(spacing: 12) {
                        text
                        stack
                        Image(systemName: isOpen ? "chevron.down" : "chevron.right")
                            .font(.footnote.weight(.semibold))
                            .foregroundStyle(Color.listenUpOrange)
                            .accessibilityHidden(true)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(toggleName)
                .accessibilityValue(
                    [String(localized: isOpen ? "common.expanded" : "common.collapsed"), readers?.sentence]
                        .compactMap { $0 }
                        .joined(separator: ", ")
                )
                .accessibilityAddTraits(.isButton)
            } else {
                HStack(spacing: 12) {
                    text
                    stack
                }
                .accessibilityElement(children: .combine)
                .accessibilityValue(readers?.sentence ?? "")
            }
            Button(action: onPlay) {
                Image(systemName: "play.fill")
                    .font(.footnote)
                    .foregroundStyle(Color.listenUpOrange)
                    .frame(width: 30, height: 30)
                    .minimumTapTarget(visualSize: 30)
            }
            .buttonStyle(.plain)
            .disabled(!canPlay)
            .accessibilityLabel(String(format: String(localized: "book.detail_part_play_a11y"), part.title))
            // The folded chapter has no row to mark, so this is where VoiceOver hears it is current.
            .accessibilityAddTraits(part.isCurrentChapter ? [.isButton, .isSelected] : .isButton)
        }
        .frame(minHeight: 56)
        .padding(.horizontal, Spacing.m)
    }

    @ViewBuilder
    private var stack: some View {
        if let readers {
            ChapterReadersStackView(stack: readers)
        }
    }

    /// "Part II, 13 chapters · 45 hr, 30 min".
    private var toggleName: String { "\(part.title), \(part.summary)" }

    private var text: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(part.title)
                .font(.body.weight(.semibold))
                .foregroundStyle(part.isCurrentChapter ? Color.listenUpOrange : .primary)
                .lineLimit(1)
            Text(part.summary)
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
