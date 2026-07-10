import SwiftUI

/// A thin "you're offline" bar pinned to the top of a browse screen, with a Retry. Shown when the
/// server is unreachable so a silently-no-op pull-to-refresh has a visible explanation (the library
/// still renders from the local store — this just says why it isn't updating).
struct OfflineTopBanner: View {
    var onRetry: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "cloud.slash.fill")
                .font(.caption)
            Text("book.detail_offline_title")
                .font(.caption.weight(.medium))
                .lineLimit(1)
            Spacer(minLength: 8)
            Button(action: onRetry) {
                Text("book.detail_retry")
                    .font(.caption.weight(.semibold))
                    .frame(minHeight: 32)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(String(localized: "book.detail_retry"))
        }
        .foregroundStyle(Color.listenUpOrange)
        .padding(.horizontal, 16)
        .padding(.vertical, 6)
        .frame(maxWidth: .infinity)
        .background(.ultraThinMaterial)
    }
}

extension View {
    /// Pins an offline banner (with Retry) to the top when the server is unreachable, reading the
    /// app-wide ``ServerReachabilityObserver`` from the environment. Apply to browse-screen roots.
    func offlineTopBanner() -> some View {
        modifier(OfflineTopBannerModifier())
    }
}

private struct OfflineTopBannerModifier: ViewModifier {
    // Optional so a host without the observer in its environment (SwiftUI previews) renders no
    // banner instead of trapping — the runtime app always injects it at `RootView`.
    @Environment(ServerReachabilityObserver.self) private var reachability: ServerReachabilityObserver?

    private var isUnreachable: Bool { reachability?.isUnreachable ?? false }

    func body(content: Content) -> some View {
        content
            .safeAreaInset(edge: .top) {
                if isUnreachable {
                    OfflineTopBanner(onRetry: { reachability?.retry() })
                        .transition(.move(edge: .top).combined(with: .opacity))
                }
            }
            .animation(.smooth, value: isUnreachable)
    }
}
