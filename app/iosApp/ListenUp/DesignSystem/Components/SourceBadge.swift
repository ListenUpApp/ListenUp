import SwiftUI

/// A neutral capsule naming where something came from — "Hardcover" beside a read logged there.
///
/// Text only: ListenUp names another service, it never wears that service's logo or colours. A label,
/// not an action, so it stays neutral like `RoleChip` (HIG, Color: "If you apply color, reserve it for
/// elements that truly benefit from emphasis"), in a system text style so it follows Dynamic Type
/// (HIG, Typography: text styles "ensure support for Dynamic Type and larger accessibility type sizes").
struct SourceBadge: View {
    let label: String

    var body: some View {
        Text(label)
            .lineLimit(1)
            .fixedSize(horizontal: true, vertical: false)
            .font(.caption2.weight(.semibold))
            .foregroundStyle(.secondary)
            .padding(.horizontal, Spacing.xs)
            .padding(.vertical, Spacing.xxs)
            .background(Capsule().fill(Color.luFill))
    }
}

#Preview("SourceBadge") {
    SourceBadge(label: String(localized: "book.detail_readers_hardcover"))
        .padding()
        .background(Color.luSurface)
}
