import SwiftUI

/// A large coral success mark — a white checkmark on a saturated coral disc inside a soft halo —
/// for completion moments (import done, setup complete). The iOS-language counterpart of the
/// mockup's `SuccessMark`. Generic and domain-free.
struct SuccessBadge: View {
    var size: CGFloat = 116

    var body: some View {
        ZStack {
            Circle()
                .fill(Color.luTint.opacity(0.14))
                .frame(width: size, height: size)
            Circle()
                .fill(Color.luTint)
                .frame(width: size * 0.74, height: size * 0.74)
                .shadow(color: Color.luTint.opacity(0.4), radius: 14, x: 0, y: 10)
            Image(systemName: "checkmark")
                .font(.system(size: size * 0.32, weight: .bold))
                .foregroundStyle(Color.luOnTint)
        }
        .accessibilityHidden(true)
    }
}

#Preview("SuccessBadge") {
    SuccessBadge()
        .padding()
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.luSurface)
}
