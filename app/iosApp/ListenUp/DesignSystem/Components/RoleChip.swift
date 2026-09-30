import SwiftUI

/// Role badge for a contributor: a pen-icon "Author" chip, a mic-icon "Narrator" chip, or a
/// generic chip for any other role — all neutral.
struct RoleChip: View {
    enum Kind: Equatable {
        case author
        case narrator
        case other(String)

        var label: String {
            switch self {
            case .author: String(localized: "contributor.role_author")
            case .narrator: String(localized: "contributor.role_narrator")
            case let .other(label): label
            }
        }

        var icon: String {
            switch self {
            case .author: "pencil"
            case .narrator: "mic"
            case .other: "person"
            }
        }

    }

    let kind: Kind

    var body: some View {
        HStack(spacing: 5) {
            Image(systemName: kind.icon).font(.caption2.weight(.semibold))
            Text(kind.label).lineLimit(1)
        }
        .fixedSize(horizontal: true, vertical: false)
        .font(.caption.weight(.semibold))
        // A label, not an action, so every role is neutral; the icon tells them apart
        // (HIG, Color: "reserve it for elements that truly benefit from emphasis").
        .foregroundStyle(.secondary)
        .padding(.horizontal, Spacing.s)
        .padding(.vertical, Spacing.xxs)
        .background(Capsule().fill(Color.luFill))
    }
}

#Preview("RoleChip") {
    HStack(spacing: 8) {
        RoleChip(kind: .author)
        RoleChip(kind: .narrator)
        RoleChip(kind: .other("Translator"))
    }
    .padding()
    .frame(maxWidth: .infinity, maxHeight: .infinity)
    .background(Color.luSurface)
}
