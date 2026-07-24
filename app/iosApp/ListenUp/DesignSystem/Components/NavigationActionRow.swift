import SwiftUI

/// A tappable management row: a leading ``IconTile``, a title / optional subtitle, and a
/// trailing chevron. The clean-coral form of the mockup's chevron `SRow`.
///
/// Generic over its action — pass an `action` closure for a button, or wrap the row in a
/// `NavigationLink` and omit it. The whole row is the hit target; it carries its own
/// padding so it sits flush inside a `.fieldCard()` or a `FieldGroup` row slot.
struct NavigationActionRow: View {
    let systemImage: String
    var tint: Color = .luTint
    let title: String
    var subtitle: String?
    var action: (() -> Void)?

    var body: some View {
        if let action {
            Button(action: action) { rowContent }
                .buttonStyle(PressScaleButtonStyle())
        } else {
            rowContent
        }
    }

    private var rowContent: some View {
        HStack(spacing: 13) {
            IconTile(systemImage: systemImage, tint: tint)
            VStack(alignment: .leading, spacing: 1) {
                Text(title)
                    .font(.body)
                    .foregroundStyle(.primary)
                if let subtitle {
                    Text(subtitle)
                        .font(.footnote)
                        .foregroundStyle(Color.luLabel2)
                        .multilineTextAlignment(.leading)
                }
            }
            Spacer(minLength: 12)
            Image(systemName: "chevron.right")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(Color.luLabel3)
                .accessibilityHidden(true)
        }
        .contentShape(Rectangle())
        .padding(.horizontal, 14)
        .padding(.vertical, 11)
    }
}

#Preview("NavigationActionRow") {
    VStack(spacing: 0) {
        NavigationActionRow(
            systemImage: "person.2.fill",
            tint: .luTint,
            title: "Invite Someone",
            subtitle: "Share your library with others",
            action: {}
        )
    }
    .fieldCard()
    .padding()
    .frame(maxWidth: .infinity, maxHeight: .infinity)
    .background(Color.luSurface)
}
