import SwiftUI

/// The *Held* marker: a book held for review in the admin inbox, hidden from every member.
///
/// A capsule in the `RoleChip` / `SourceBadge` family by shape, set apart by colour (`luWarning`,
/// the WarningAmber set), the `tray.full` glyph and the word — never colour alone. VoiceOver hears
/// the sentence, "Held for review, hidden from all members", not "Held". Distinct from the
/// collection-visibility marker, which uses neither amber nor the tray.
struct HeldBadge: View {
    /// Over cover artwork, the tint alone has no contrast, so the capsule sits on the system material.
    var onCover = false

    var body: some View {
        HStack(spacing: Spacing.xxs) {
            Image(systemName: "tray.full")
                .font(.caption2.weight(.semibold))
            Text(String(localized: "admin.held"))
                .lineLimit(1)
        }
        .fixedSize(horizontal: true, vertical: false)
        .font(.caption.weight(.semibold))
        .foregroundStyle(Color.luWarning)
        .padding(.horizontal, Spacing.xs)
        .padding(.vertical, Spacing.xxs)
        .background {
            if onCover {
                Capsule().fill(.thickMaterial)
            } else {
                Capsule().fill(Color.luWarning.opacity(0.13))
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(String(localized: "admin.held_a11y"))
    }
}

#Preview("HeldBadge") {
    VStack(spacing: 12) {
        HeldBadge()
        HeldBadge(onCover: true)
    }
    .padding()
    .background(Color.luSurface)
}
