import SwiftUI

/// Inline "server unreachable" banner for content screens — a Liquid-Glass card with a
/// cloud-slash icon, a short explanation, and a Retry action. Shown when the server can't be
/// reached and the content isn't available offline, so the user is never left guessing why an
/// action is disabled (never stranded). The retry is delegated to the caller.
struct ServerUnreachableBanner: View {
    var onRetry: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "cloud.slash.fill")
                .font(.title3)
                .foregroundStyle(Color.listenUpOrange)

            VStack(alignment: .leading, spacing: 2) {
                Text("book.detail_offline_title")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)
                Text("book.detail_offline_body")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }

            Spacer(minLength: 8)

            Button(action: onRetry) {
                Text("book.detail_retry")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color.listenUpOrange)
                    .frame(minHeight: 44)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(String(localized: "book.detail_retry"))
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassControl(in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}
