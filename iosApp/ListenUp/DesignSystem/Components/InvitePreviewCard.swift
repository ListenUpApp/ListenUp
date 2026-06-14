import SwiftUI

/// The coral invite-link payoff card: a soft-coral surface carrying a recipient line and a
/// copyable URL pill. The clean-coral form of the mockup's `InvitePreview`.
///
/// Tapping Copy invokes `onCopy` and flashes an inline "Copied" confirmation on the button
/// for a moment — the screen owns whatever broader toast it wants. The URL renders
/// monospaced and truncates from the tail so the host stays readable.
struct InvitePreviewCard: View {
    /// The headline line, e.g. "Sarah is invited".
    let title: String
    /// The secondary line, e.g. "ListenUp Home · expires in 7 days".
    let subtitle: String
    let url: String
    let onCopy: () -> Void

    @State private var justCopied = false

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 13) {
                IconTile(systemImage: "person.crop.circle.badge.checkmark", tint: .luTint, size: 48, style: .solid)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.headline)
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                    Text(subtitle)
                        .font(.footnote)
                        .foregroundStyle(Color.luLabel2)
                        .lineLimit(1)
                }
                Spacer(minLength: 0)
            }
            urlPill
        }
        .padding(16)
        .background(Color.luTint.opacity(0.09))
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(Color.luTint.opacity(0.2), lineWidth: 0.5)
        )
    }

    private var urlPill: some View {
        HStack(spacing: 10) {
            Text(url)
                .font(.footnote.monospaced())
                .foregroundStyle(.primary)
                .lineLimit(1)
                .truncationMode(.tail)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button {
                onCopy()
                flashCopied()
            } label: {
                HStack(spacing: 5) {
                    Image(systemName: justCopied ? "checkmark" : "link")
                    Text(justCopied ? String(localized: "admin.link_copied") : String(localized: "common.copy"))
                }
                .font(.footnote.weight(.semibold))
                .foregroundStyle(Color.luOnTint)
                .padding(.horizontal, 14)
                .frame(height: 32)
                .background(Color.luTint, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
            }
            .buttonStyle(PressScaleButtonStyle(scale: .chip))
        }
        .padding(.leading, 14)
        .padding(.trailing, 6)
        .frame(height: 44)
        .background(Color.luFill, in: RoundedRectangle(cornerRadius: 11, style: .continuous))
    }

    private func flashCopied() {
        withAnimation { justCopied = true }
        Task {
            try? await Task.sleep(for: .seconds(1.6))
            withAnimation { justCopied = false }
        }
    }
}

#Preview("InvitePreviewCard") {
    InvitePreviewCard(
        title: "Sarah Chen is invited",
        subtitle: "ListenUp Home · expires in 7 days",
        url: "listen.warrenhome.net/join/a8f2c1",
        onCopy: {}
    )
    .padding()
    .frame(maxWidth: .infinity, maxHeight: .infinity)
    .background(Color.luSurface)
}
