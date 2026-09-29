import SwiftUI

/// One option in a single-choice `List`/`Form` section: a leading ``IconTile``, a title / subtitle,
/// and a trailing checkmark on the chosen row. Several in one `Section` make the choice — the
/// iOS list idiom for picking one of a few options, with the system row highlight on tap.
/// HIG, Lists and tables: "a table that lists options often highlights a row only briefly before
/// adding an image — such as a checkmark — indicating that the item is selected."
///
/// Generic by intent — used for the Member / Admin access-level choice, but it carries no Admin
/// vocabulary in its API.
struct SelectableOptionRow: View {
    let systemImage: String
    let title: String
    var subtitle: String?
    let isSelected: Bool
    let onSelect: () -> Void

    var body: some View {
        Button(action: onSelect) {
            HStack(spacing: 13) {
                IconTile(systemImage: systemImage)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 1) {
                    Text(title)
                        .font(.body)
                        .foregroundStyle(.primary)
                    if let subtitle {
                        Text(subtitle)
                            .font(.footnote)
                            .foregroundStyle(Color.luLabel2)
                    }
                }
                Spacer(minLength: 12)
                Image(systemName: "checkmark")
                    .font(.body.weight(.semibold))
                    .foregroundStyle(Color.luTint)
                    .opacity(isSelected ? 1 : 0)
                    .accessibilityHidden(true)
            }
            .contentShape(Rectangle())
        }
        // A list button tints its label with the accent; the option's words stay label-coloured.
        .foregroundStyle(Color.primary)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(isSelected ? [.isSelected, .isButton] : .isButton)
    }
}

#Preview("SelectableOptionRow") {
    Form {
        SelectableOptionRow(
            systemImage: "headphones",
            title: "Member",
            subtitle: "Can access the library",
            isSelected: true,
            onSelect: {}
        )
        SelectableOptionRow(
            systemImage: "shield.fill",
            title: "Admin",
            subtitle: "Manage server & users",
            isSelected: false,
            onSelect: {}
        )
    }
}
