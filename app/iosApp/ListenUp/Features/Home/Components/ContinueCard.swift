import SwiftUI

/// A Home card (spec §2.5): square art with a white progress line on it (the ring retired), the title, the author,
/// "Read by …" on wide layouts, and the time left in the action colour. One accessibility element with one label
/// (§8.2). A loading item is a same-size shimmer. While selecting, a tap toggles selection. Out of reach and not
/// downloaded, the art and title dim to half and the last line reads "Needs a connection"; it stays a link (§2.5).
struct ContinueCard: View {
    let item: ContinueItem
    /// A rail card's fixed width, or nil to fill a row column.
    let width: CGFloat?
    let showsNarrator: Bool
    var selection: BookSelectionObserver?

    @Environment(\.restrictedBooks) private var restrictedBooks

    var body: some View {
        if item.isLoading {
            skeleton
        } else {
            SelectableBookCard(bookId: item.id, selection: selection) { content }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(
                    RestrictedMarker.label(
                        HomeCardText.label(
                            title: item.title,
                            author: item.author,
                            narrators: showsNarrator ? item.narrators : nil,
                            percent: item.progressPercent,
                            remainingMs: item.remainingMs,
                            needsConnection: item.needsConnection
                        ),
                        isRestricted: restrictedBooks?.isRestricted(item.id) == true
                    )
                )
                .accessibilityAddTraits(.isButton)
                .accessibilityHint(String(localized: "home.opens_book"))
        }
    }

    private var content: some View {
        VStack(alignment: .leading, spacing: 6) {
            BookCoverImage(bookId: item.id, coverPath: item.coverPath, coverHash: item.coverHash)
                .aspectRatio(1, contentMode: .fit)
                .frame(width: width)
                .clipShape(RoundedRectangle(cornerRadius: Radius.s, style: .continuous))
                .overlay(alignment: .bottom) { ArtProgressLine(progress: item.progress) }
                .restrictedMarker(bookId: item.id, isSelecting: selection?.isSelecting ?? false)
                .coverHoverEffect(cornerRadius: Radius.s)
                .shadow(color: .black.opacity(0.12), radius: 6, x: 0, y: 3)
                .bookSelectionCircle(bookId: item.id, selection: selection)
                .opacity(contentOpacity)

            VStack(alignment: .leading, spacing: 1) {
                Group {
                    Text(item.title).font(.subheadline).lineLimit(1)
                    if !item.author.isEmpty {
                        Text(item.author).font(.footnote).foregroundStyle(.secondary).lineLimit(1)
                    }
                    if showsNarrator, let narrators = item.narrators, !narrators.isEmpty {
                        Text(String(format: String(localized: "library.card_read_by"), narrators))
                            .font(.footnote).foregroundStyle(.secondary).lineLimit(1)
                    }
                }
                .opacity(contentOpacity)
                Text(HomeCardText.caption(remainingMs: item.remainingMs, needsConnection: item.needsConnection))
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(item.needsConnection ? Color.secondary : Color.luTintInk)
                    .lineLimit(1)
            }
        }
        .frame(width: width, alignment: .leading)
        .contentShape(Rectangle())
    }

    /// Half for a card that needs a connection it does not have.
    private var contentOpacity: Double { item.needsConnection ? 0.5 : 1 }

    private var skeleton: some View {
        VStack(alignment: .leading, spacing: 8) {
            RoundedRectangle(cornerRadius: Radius.s)
                .fill(Color.gray.opacity(0.2))
                .aspectRatio(1, contentMode: .fit)
                .shimmer()
            RoundedRectangle(cornerRadius: Radius.xs)
                .fill(Color.gray.opacity(0.2))
                .frame(height: 14)
                .shimmer()
        }
        .frame(width: width, alignment: .leading)
        .accessibilityHidden(true)
    }
}

/// The white progress line on a card's art (spec §2.5, iOS). Decorative: the card's label carries the percent.
private struct ArtProgressLine: View {
    let progress: Double

    var body: some View {
        GeometryReader { geometry in
            ZStack(alignment: .leading) {
                Capsule().fill(.white.opacity(0.4))
                Capsule().fill(.white).frame(width: geometry.size.width * min(max(progress, 0), 1))
            }
        }
        .frame(height: 4)
        .padding(.horizontal, Spacing.xs)
        .padding(.bottom, Spacing.xs)
        .accessibilityHidden(true)
    }
}
