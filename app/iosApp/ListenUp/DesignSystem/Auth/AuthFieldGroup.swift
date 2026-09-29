import SwiftUI

/// iOS inset-grouped container — an opaque rounded surface holding one or more field
/// rows with hairline separators. The iOS-forms hallmark; concentric inside `AuthCard`.
struct AuthFieldGroup<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        VStack(spacing: 0) { content }
            // The fields draw their own insets and separators here; in a List the list does.
            .environment(\.appTextFieldChrome, .drawn)
            .background(
                RoundedRectangle(cornerRadius: AuthMetrics.fieldGroupCornerRadius, style: .continuous)
                    .fill(Color(.secondarySystemGroupedBackground))
            )
            .clipShape(RoundedRectangle(cornerRadius: AuthMetrics.fieldGroupCornerRadius, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: AuthMetrics.fieldGroupCornerRadius, style: .continuous)
                    .strokeBorder(Color.luSeparator, lineWidth: 0.5)
            )
    }
}
