import SwiftUI
@preconcurrency import Shared

/// One contributor in the Contributors list: avatar, name over a role chip + book count,
/// and a chevron. Rendered inside a `FieldGroup`, so it carries padding but no surface.
struct PersonRow: View {
    let contributor: ContributorWithBookCount_
    let kind: RoleChip.Kind

    private var contributorId: String { contributor.contributor.idString }
    private var name: String { contributor.contributor.name }
    private var bookCountLabel: String {
        let count = Int(contributor.bookCount)
        let format = count == 1
            ? String(localized: "common.book_count")
            : String(localized: "common.books_count")
        return String(format: format, count)
    }

    var body: some View {
        NavigationLink(value: ContributorDestination(id: contributorId)) {
            HStack(spacing: 14) {
                ContributorAvatar(
                    name: name,
                    imagePath: contributor.contributor.imagePath,
                    blurHash: contributor.contributor.imageBlurHash,
                    id: contributorId,
                    fontSize: 16
                )
                .frame(width: 50, height: 50)

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

                Spacer(minLength: 8)
                Image(systemName: "chevron.right")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color.luLabel3)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 9)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(name), \(kind.label)")
        .accessibilityValue(bookCountLabel)
        .accessibilityHint(String(localized: "contributor.view_details_hint"))
    }
}
