import SwiftUI

/// Secondary outlined pill action: a hairline-bordered capsule-ish button with a leading
/// SF Symbol and label. For non-primary actions (add to shelf, mark finished, …).
struct IconLabelButton: View {
    let icon: String
    let title: String
    var tint: Color = .luTint
    var role: ButtonRole?
    let action: () -> Void

    private var foreground: Color { role == .destructive ? .red : .primary }
    private var iconColor: Color { role == .destructive ? .red : tint }

    var body: some View {
        Button(role: role, action: action) {
            HStack(spacing: 7) {
                Image(systemName: icon)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(iconColor)
                Text(title)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(foreground)
            }
            .frame(maxWidth: .infinity)
            .frame(height: 44)
            .overlay(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .strokeBorder(Color.luSeparator, lineWidth: 1.5)
            )
        }
        .buttonStyle(.pressScaleChip)
        .accessibilityLabel(Text(title))
    }
}

#Preview("IconLabelButton") {
    HStack(spacing: 12) {
        IconLabelButton(icon: "bookmark", title: "Add to Shelf", action: {})
        IconLabelButton(icon: "trash", title: "Remove", role: .destructive, action: {})
    }
    .padding()
    .frame(maxWidth: .infinity, maxHeight: .infinity)
    .background(Color.luSurface)
}
