import SwiftUI

/// The branded ambient the Liquid Glass controls float over. Abstract warm aurora
/// (coral + amber light) — never book covers, because auth happens before the library
/// is synced. `deep` enriches it (used behind Sign In); `wide` spreads it for the
/// regular-width centered-card layouts.
struct AuroraBackdrop<Content: View>: View {
    var deep: Bool = false
    var wide: Bool = false
    @ViewBuilder var content: Content

    @Environment(\.colorScheme) private var scheme
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency

    var body: some View {
        ZStack {
            Color.auroraBase(scheme).ignoresSafeArea()
            // Reduce Transparency: drop the blurred orbs — the solid base + readability
            // gradient carry the screen, no frosted bloom (rule 12, peer: CoverGlow).
            if !reduceTransparency {
                GeometryReader { proxy in
                    let w = proxy.size.width
                    let h = proxy.size.height
                    ZStack {
                        orb(.listenUpOrange, opacity: deep ? 0.55 : 0.45,
                            diameter: (wide ? 0.55 : 0.7) * w,
                            at: CGPoint(x: wide ? 0.82 * w : 0.86 * w, y: 0.06 * h))
                        orb(.auroraAmber, opacity: deep ? 0.42 : 0.38,
                            diameter: (wide ? 0.5 : 0.62) * w,
                            at: CGPoint(x: 0.08 * w, y: 0.95 * h))
                        orb(.auroraEmber, opacity: deep ? 0.30 : 0.20,
                            diameter: (wide ? 0.45 : 0.55) * w,
                            at: CGPoint(x: (wide ? 0.45 : 0.38) * w, y: 0.5 * h))
                    }
                    .blur(radius: wide ? 90 : 60)
                }
                .ignoresSafeArea()
            }

            // Readability lift: gently darken/lighten the very top & bottom edges so
            // glass controls and edge text stay legible over the aurora.
            LinearGradient(
                colors: [Color.auroraBase(scheme).opacity(0.30), .clear, .clear,
                         Color.auroraBase(scheme).opacity(0.34)],
                startPoint: .top, endPoint: .bottom
            )
            .ignoresSafeArea()

            content
        }
    }

    private func orb(_ color: Color, opacity: Double, diameter: CGFloat, at point: CGPoint) -> some View {
        Circle()
            .fill(color.opacity(opacity))
            .frame(width: diameter, height: diameter)
            .position(point)
    }
}

#Preview("Aurora – light") {
    AuroraBackdrop { Color.clear }
}

#Preview("Aurora – deep dark") {
    AuroraBackdrop(deep: true) { Color.clear }
        .preferredColorScheme(.dark)
}
