import SwiftUI

/// The quiet offline pill under the greeting (spec §3.1): the device or the server is out of reach, and downloaded
/// books still play. It explains the dimmed cards below, so it is point-of-need, not an ambient offline state.
struct OfflinePill: View {
    var body: some View {
        Label(String(localized: "home.offline_pill"), systemImage: "icloud.slash")
            .font(.footnote)
            .foregroundStyle(.secondary)
            .padding(.horizontal, Spacing.s)
            .padding(.vertical, 6)
            .background(Color.secondary.opacity(0.12), in: Capsule())
            .accessibilityAddTraits(.updatesFrequently)
            .onAppear { VoiceOverAnnouncement.post(String(localized: "home.offline_pill")) }
    }
}

/// Continue Listening could not load (spec §3.3): one line with **Try again**, in the slot's position. Replaces the
/// banner; the wrapper's inline error still posts the VoiceOver announcement.
struct ContinueListeningFailedLine: View {
    let onRetry: () -> Void

    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: Spacing.xs) { contents }
            VStack(alignment: .leading, spacing: 4) { contents }
        }
    }

    @ViewBuilder
    private var contents: some View {
        Text(String(localized: "home.couldnt_load_listening"))
            .font(.subheadline)
            .foregroundStyle(.secondary)
        Button(String(localized: "common.try_again"), action: onRetry)
            .frame(minHeight: 44)
    }
}

/// Home could not load at all (spec §3.3): the greeting stays in the subtitle, and the slot's place says so with
/// **Try again**.
struct HomeErrorCard: View {
    let onRetry: () -> Void

    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.s) {
            Text(String(localized: "home.couldnt_load"))
                .font(.body)
            Button(action: onRetry) {
                Label(String(localized: "common.try_again"), systemImage: "arrow.clockwise")
            }
            .buttonStyle(.bordered)
            .buttonBorderShape(.capsule)
        }
        .padding(Spacing.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            BookDetailHeroTone.color(for: colorScheme),
            in: RoundedRectangle(cornerRadius: Radius.xl, style: .continuous)
        )
    }
}
