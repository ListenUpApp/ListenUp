import SwiftUI

/// One row in the USERS list: the user's avatar, the user's name + email, their role badge,
/// and a context-menu delete (suppressed for protected root users). A spinner replaces the
/// trailing affordance while this user's delete is in flight.
struct AdminUserRow: View {
    let user: AdminUserRowModel
    let isDeleting: Bool
    let onDelete: () -> Void

    var body: some View {
        HStack(spacing: 13) {
            UserAvatarView(userId: user.id, fallbackName: user.name, size: 40)
            VStack(alignment: .leading, spacing: 1) {
                Text(user.name)
                    .font(.body)
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                Text(user.email)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 8)
            AdminRoleBadge(label: user.roleLabel, isElevated: user.isRootBadge)
            trailing
        }
        .contentShape(Rectangle())
    }

    @ViewBuilder
    private var trailing: some View {
        if isDeleting {
            ProgressView().frame(width: TapTarget.minimum)
        } else if !user.isProtected {
            Menu {
                Button(role: .destructive, action: onDelete) {
                    Label(String(localized: "common.delete"), systemImage: "trash")
                }
            } label: {
                Image(systemName: "ellipsis")
                    .font(.body.weight(.semibold))
                    .foregroundStyle(Color.luLabel3)
                    .frame(width: TapTarget.minimum, height: TapTarget.minimum)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel(String(localized: "common.menu"))
        } else {
            // Protected user: a lock glyph stands in for the absent delete affordance.
            Image(systemName: "lock.fill")
                .font(.caption)
                .foregroundStyle(.tertiary)
                .frame(width: 28)
                .accessibilityLabel(String(localized: "admin.protected_user"))
        }
    }
}

#Preview("AdminUserRow") {
    Form {
        AdminUserRow(
            user: AdminUserRowModel(
                id: "1", name: "Simon Hull", email: "simon@example.com",
                roleLabel: "Root", isRootBadge: true, isProtected: true
            ),
            isDeleting: false, onDelete: {}
        )
        AdminUserRow(
            user: AdminUserRowModel(
                id: "2", name: "Erin Hull", email: "erin@example.com",
                roleLabel: "Member", isRootBadge: false, isProtected: false
            ),
            isDeleting: false, onDelete: {}
        )
    }
}
