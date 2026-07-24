import SwiftUI

/// Centered loading state — a spinner with an optional label, filling its frame.
struct LoadingStateView: View {
    var label = String(localized: "common.loading")

    var body: some View {
        VStack(spacing: 16) {
            ProgressView()
            Text(label)
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

#Preview("LoadingStateView") {
    LoadingStateView()
        .background(Color.luSurface)
}
