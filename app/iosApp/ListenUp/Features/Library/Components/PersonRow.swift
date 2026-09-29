import SwiftUI
import Shared

/// One contributor in the Authors / Narrators tab: avatar, name over a role chip + book count.
///
/// Two styles:
/// - `.listRow` (default) — a `List` row: the list draws the insets, separator, highlight and
///   disclosure indicator.
/// - `.card` — a cell in the wide-width grid: it draws its own padding, chevron and grouped surface,
///   because a grid has no rows to do it.
struct PersonRow: View {
    enum Style { case listRow, card }

    let contributor: ContributorRow
    let kind: RoleChip.Kind
    var style: Style = .listRow

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
                    content
                    Spacer(minLength: 8)
                    Image(systemName: "chevron.right")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(Color.luLabel3)
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 9)
                .background(Color.luSurface2, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .combine)
            .accessibilityLabel("\(name), \(kind.label)")
            .accessibilityValue(bookCountLabel)
            .accessibilityHint(String(localized: "contributor.view_details_hint"))
        }
    }

    private var content: some View {
        HStack(spacing: 14) {
            ContributorAvatar(
                name: name,
                imagePath: contributor.imagePath,
                id: contributor.id,
                fontSize: 16,
                streamsContributorPhoto: true
            )
            .frame(width: 50, height: 50)
            .heroSource(contributorHeroID(contributor.id))

            VStack(alignment: .leading, spacing: 5) {
                Text(name)
                    .font(.body.weight(.semibold))
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                HStack(spacing: 7) {
                    RoleChip(kind: kind)
                    Text(bookCountLabel)
                        .font(.footnote)
                        .foregroundStyle(Color.luLabel2)
                        .lineLimit(1)
                }
            }
        }
    }
}
