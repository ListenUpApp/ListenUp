import SwiftUI

/// A tappable single-select option row: a leading ``IconTile``, a title / subtitle, and a
/// trailing selection indicator (a filled coral check when selected, an empty ring
/// otherwise). The clean-coral form of the mockup's `RoleRadio`.
///
/// Generic by intent — used here for the Member / Admin access-level choice, but it carries
/// no Admin vocabulary in its API. Compose several inside a `.fieldCard()` (with hairline
/// separators) to build a radio group. The whole row is the hit target.
struct SelectableOptionCard: View {
    let systemImage: String
    let title: String
    var subtitle: String?
    let isSelected: Bool
    let onSelect: () -> Void

    var body: some View {
        Button(action: onSelect) {
            HStack(spacing: 13) {
                IconTile(systemImage: systemImage, tint: .luTint)
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
                indicator
            }
            .contentShape(Rectangle())
            .padding(.horizontal, 14)
            .padding(.vertical, 11)
        }
        .buttonStyle(PressScaleButtonStyle())
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(isSelected ? [.isSelected, .isButton] : .isButton)
    }

    @ViewBuilder
    private var indicator: some View {
        if isSelected {
            Circle()
                .fill(Color.luTint)
                .frame(width: 24, height: 24)
                .overlay {
                    Image(systemName: "checkmark")
                        .font(.system(size: 13, weight: .bold))
                        .foregroundStyle(Color.luOnTint)
                }
        } else {
            Circle()
                .strokeBorder(Color.luSeparator, lineWidth: 2)
                .frame(width: 23, height: 23)
        }
    }
}

#Preview("SelectableOptionCard") {
    VStack(spacing: 0) {
        SelectableOptionCard(
            systemImage: "headphones",
            title: "Member",
            subtitle: "Can access the library",
            isSelected: true,
            onSelect: {}
        )
        Rectangle().fill(Color.luSeparator).frame(height: 0.5).padding(.leading, 61)
        SelectableOptionCard(
            systemImage: "shield.fill",
            title: "Admin",
            subtitle: "Manage server & users",
            isSelected: false,
            onSelect: {}
        )
    }
    .fieldCard()
    .padding()
    .frame(maxWidth: .infinity, maxHeight: .infinity)
    .background(Color.luSurface)
}
