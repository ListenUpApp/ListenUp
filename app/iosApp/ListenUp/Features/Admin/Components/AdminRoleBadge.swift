import SwiftUI

/// The user role badge for the admin users list: an elevated coral "Root" / "Admin" chip with
/// a leading shield, or a neutral capsule for a plain member role.
///
/// Distinct from the DesignSystem `RoleChip` (which is contributor-specific: Author / Narrator):
/// this renders the *account* role vocabulary (Root / Admin / Member) from the mockup's
/// `AdmRoleChip`. The elevated treatment (shield + coral tint) is driven by `isElevated`, set
/// by the observer's pure role mapping.
struct AdminRoleBadge: View {
    let label: String
    var isElevated: Bool = false

    var body: some View {
        HStack(spacing: 4) {
            if isElevated {
                Image(systemName: "shield.lefthalf.filled")
                    .font(.caption2.weight(.semibold))
            }
            Text(label)
                .lineLimit(1)
        }
        .fixedSize(horizontal: true, vertical: false)
        .font(.caption.weight(.semibold))
        .foregroundStyle(isElevated ? Color.luTint : Color.luLabel2)
        .padding(.horizontal, 11)
        .padding(.vertical, 5)
        .background(Capsule().fill(isElevated ? Color.luTint.opacity(0.13) : Color.luFill))
    }
}

#Preview("AdminRoleBadge") {
    HStack(spacing: 8) {
        AdminRoleBadge(label: "Root", isElevated: true)
        AdminRoleBadge(label: "Admin", isElevated: true)
        AdminRoleBadge(label: "Member")
    }
    .padding()
    .frame(maxWidth: .infinity, maxHeight: .infinity)
    .background(Color.luSurface)
}
