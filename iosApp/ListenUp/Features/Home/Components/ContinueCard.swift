import SwiftUI

/// A Continue-Listening carousel card: a cover with a small progress ring overlaid in the
/// corner, the title/author beneath, and the time-left caption. Tapping navigates to the
/// book's detail. Width is width-driven by the caller (wider on iPad) so the rail reads as a
/// real layout at every size.
///
/// When `item.isLoading` is true the card renders a shimmer skeleton instead of real content —
/// a sync is in flight and the book's data hasn't landed yet.
///
/// When `selection` is supplied and selecting, the cover shows a selection circle and a tap toggles
/// selection instead of navigating; a long-press is the secondary entry. `nil` = navigation only.
/// Loading skeletons never participate in selection.
struct ContinueCard: View {
    let item: ContinueItem
    let width: CGFloat
    var selection: BookSelectionObserver?

    var body: some View {
        if item.isLoading {
            skeleton
        } else {
            SelectableBookCard(bookId: item.id, selection: selection) {
                content
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel("\(item.title), \(item.author)")
            .accessibilityValue(String(format: String(localized: "home.progress_percent"), item.progressPercent))
            .accessibilityHint(String(localized: "home.opens_book"))
        }
    }

    // MARK: - Content

    private var content: some View {
        VStack(alignment: .leading, spacing: 8) {
            BookCoverImage(bookId: item.id, coverPath: item.coverPath)
                .frame(width: width, height: width)
                .clipShape(RoundedRectangle(cornerRadius: 16))
                .shadow(color: .black.opacity(0.15), radius: 6, x: 0, y: 3)
                .overlay(alignment: .bottomTrailing) {
                    CircularProgressRing(progress: item.progress, lineWidth: 4, showPercentLabel: true)
                        .frame(width: 38, height: 38)
                        .padding(8)
                        .background(.regularMaterial, in: Circle())
                        .padding(8)
                }
                .bookSelectionCircle(bookId: item.id, selection: selection)

            VStack(alignment: .leading, spacing: 2) {
                Text(item.title)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(.primary)
                    .lineLimit(1)

                if !item.timeLeft.isEmpty {
                    Text(item.timeLeft)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                } else {
                    Text(item.author)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
        }
        .frame(width: width, alignment: .leading)
        .contentShape(Rectangle())
    }

    // MARK: - Skeleton

    private var skeleton: some View {
        VStack(alignment: .leading, spacing: 8) {
            RoundedRectangle(cornerRadius: 16)
                .fill(Color.gray.opacity(0.2))
                .frame(width: width, height: width)
                .shimmer()

            RoundedRectangle(cornerRadius: 4)
                .fill(Color.gray.opacity(0.2))
                .frame(width: width * 0.8, height: 14)
                .shimmer()
        }
        .frame(width: width, alignment: .leading)
        .accessibilityHidden(true)
    }
}
