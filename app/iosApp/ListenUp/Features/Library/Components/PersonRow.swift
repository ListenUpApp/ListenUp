import SwiftUI
import Shared

/// One contributor in the Authors / Narrators tab: avatar, name over a role chip + book count.
///
/// Two styles:
/// - `.listRow` (default) — a `List` row: the list draws the insets, separator, highlight and
///   disclosure indicator.
/// - `.card` — a cell in the wide-width grid: it draws its own padding, chevron and grouped surface,
///   because a grid has no rows to do it, and the contributor's two newest covers.
struct PersonRow: View {
    enum Style { case listRow, card }

    let contributor: ContributorRow
    let kind: RoleChip.Kind
    var style: Style = .listRow
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    private var name: String { contributor.name }
    private var bookCountLabel: String {
        let count = contributor.bookCount
        let format = count == 1
            ? String(localized: "common.book_count")
            : String(localized: "common.books_count")
        return String(format: format, count)
    }

    var body: some View {
        switch style {
        case .listRow:
            NavigationLink(value: ContributorDestination(id: contributor.id)) {
                content
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel("\(name), \(kind.label)")
            .accessibilityValue(bookCountLabel)
            .accessibilityHint(String(localized: "contributor.view_details_hint"))
        case .card:
            NavigationLink(value: ContributorDestination(id: contributor.id)) {
                HStack(spacing: 8) {
                    // The words win the width: the covers take only what is left, and step aside rather than crush them.
                    content.layoutPriority(1)
                    Spacer(minLength: 8)
                    if Self.showsPreviewCovers(style: style, dynamicTypeSize: dynamicTypeSize, coverCount: contributor.previewCoverIds.count) {
                        ViewThatFits(in: .horizontal) {
                            previewCovers
                            EmptyView()
                        }
                    }
                    Image(systemName: "chevron.right")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.tertiary)
                }
                .padding(.horizontal, Spacing.m)
                .padding(.vertical, Spacing.xs)
                .background(Color.luSurface2, in: RoundedRectangle(cornerRadius: Radius.m, style: .continuous))
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .combine)
            .accessibilityLabel("\(name), \(kind.label)")
            .accessibilityValue(bookCountLabel)
            .accessibilityHint(String(localized: "contributor.view_details_hint"))
        }
    }

    /// Whether a card draws its covers: the wide card only, and never at an accessibility text size, where the name and
    /// count need the whole width.
    static func showsPreviewCovers(style: Style, dynamicTypeSize: DynamicTypeSize, coverCount: Int) -> Bool {
        style == .card && coverCount > 0 && !dynamicTypeSize.isAccessibilitySize
    }

    /// The card's two newest covers (spec c7, board `Library-iPad-Authors`): decoration, so the card is read exactly as
    /// before. The list row (iPhone) has no room for them.
    private var previewCovers: some View {
        HStack(spacing: 4) {
            ForEach(contributor.previewCoverIds, id: \.self) { bookId in
                BookCoverImage(bookId: bookId, coverPath: nil, coverHash: nil)
                    .frame(width: 40, height: 40)
                    .clipShape(RoundedRectangle(cornerRadius: Radius.xs, style: .continuous))
            }
        }
        .accessibilityHidden(true)
    }

    /// One line at the default sizes, two from XXL, three at an accessibility size.
    static func nameLines(_ size: DynamicTypeSize) -> Int {
        if size.isAccessibilitySize { return 3 }
        return size >= .xxLarge ? 2 : 1
    }

    private var bookCountText: some View {
        Text(bookCountLabel)
            .font(.footnote)
            .foregroundStyle(.secondary)
            .fixedSize(horizontal: false, vertical: true)
    }

    private var content: some View {
        HStack(spacing: 14) {
            ContributorAvatar(
                name: name,
                imagePath: contributor.imagePath,
                id: contributor.id,
                fontSize: 16,
                streamsContributorPhoto: true,
                frame: contributor.imageFrame
            )
            .frame(width: 50, height: 50)
            .heroSource(contributorHeroID(contributor.id))

            VStack(alignment: .leading, spacing: 5) {
                // Never truncated to nothing: at the larger text sizes the name wraps rather than ellipsizing.
                Text(name)
                    .font(.body.weight(.semibold))
                    .foregroundStyle(.primary)
                    .lineLimit(Self.nameLines(dynamicTypeSize))
                // The chip and the count share a line while they fit, and stack when they do not, so the count is
                // never the part that disappears.
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 7) {
                        RoleChip(kind: kind)
                        bookCountText
                    }
                    VStack(alignment: .leading, spacing: 4) {
                        RoleChip(kind: kind)
                        bookCountText
                    }
                }
            }
        }
    }
}
