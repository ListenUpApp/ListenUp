import SwiftUI

/// Role badge for a contributor: a pen-icon tinted "Author" chip, a mic-icon neutral
/// "Narrator" chip, or a generic chip for any other role.
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

        /// Author reads as the primary credit (coral); others are neutral.
        var isTinted: Bool { self == .author }
    }

    let kind: Kind

    var body: some View {
        HStack(spacing: 5) {
            Image(systemName: kind.icon).font(.caption2.weight(.semibold))
            Text(kind.label).lineLimit(1)
        }
        .fixedSize(horizontal: true, vertical: false)
        .font(.caption.weight(.semibold))
        .foregroundStyle(kind.isTinted ? Color.luTint : Color.luLabel2)
        .padding(.horizontal, 11)
        .padding(.vertical, 5)
        .background(Capsule().fill(kind.isTinted ? Color.luTint.opacity(0.12) : Color.luFill))
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
