import SwiftUI

/// Solid, legible content surface for the regular-width (iPad / future Mac) auth layout —
/// a floating "grouped page" the form's white field cells sit on, mirroring the iPhone
/// layout's page/cell relationship. Deliberately NOT glass — glass is reserved for floating
/// controls; the form sits on a crisp opaque card so text stays sharp (Apple HIG).
struct AuthCard<Content: View>: View {
    var maxWidth: CGFloat = AuthMetrics.cardMaxWidth
    @ViewBuilder var content: Content

    var body: some View {
        content
            .padding(26)
            .frame(maxWidth: maxWidth)
            .background(
                RoundedRectangle(cornerRadius: AuthMetrics.cardCornerRadius, style: .continuous)
                    .fill(Color(.systemGroupedBackground))
            )
            .overlay(
                RoundedRectangle(cornerRadius: AuthMetrics.cardCornerRadius, style: .continuous)
                    .strokeBorder(Color.primary.opacity(0.06), lineWidth: 0.5)
            )
            .shadow(color: .black.opacity(0.18), radius: 30, y: 18)
    }
}
