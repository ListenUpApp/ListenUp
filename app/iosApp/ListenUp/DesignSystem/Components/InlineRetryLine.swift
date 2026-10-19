import SwiftUI

/// One line saying what failed, then **Try Again**, which restarts only that section (spec §3.8).
///
/// The line is announced when it appears (§8.2's status row), so VoiceOver hears the failure without
/// moving; Try Again is at least 44pt tall. Home's sections use the same piece.
struct InlineRetryLine: View {
    let line: String
    let action: () -> Void

    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: Spacing.s) { content }
            VStack(alignment: .leading, spacing: Spacing.xs) { content }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .onAppear { AccessibilityNotification.Announcement(line).post() }
    }

    @ViewBuilder
    private var content: some View {
        Text(line)
            .font(.subheadline)
            .foregroundStyle(.secondary)
            .accessibilityAddTraits(.updatesFrequently)
        Button(String(localized: "discover.try_again_ios"), action: action)
            .buttonStyle(.bordered)
            .frame(minHeight: 44)
    }
}
