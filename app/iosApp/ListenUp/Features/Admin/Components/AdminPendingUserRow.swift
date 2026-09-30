import SwiftUI

/// One row in PENDING REGISTRATIONS: the user's avatar, name + email, and Deny / Approve
/// actions. While either action is in flight a spinner replaces both buttons.
struct AdminPendingUserRow: View {
    let user: AdminUserRowModel
    let isBusy: Bool
    let onApprove: () -> Void
    let onDeny: () -> Void

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
            actions
        }
    }

    @ViewBuilder
    private var actions: some View {
        if isBusy {
            ProgressView().frame(width: 64)
        } else {
            // 10pt apart, so the two widened 44pt hit areas meet without overlapping.
            HStack(spacing: 10) {
                Button(action: onDeny) {
                    Image(systemName: "xmark")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(Color.secondary)
                        .frame(width: 34, height: 34)
                        .background(Circle().fill(Color.luFill))
                        .minimumTapTarget(visualSize: 34)
                }
                .buttonStyle(PressScaleButtonStyle(scale: .chip))
                .accessibilityLabel(String(localized: "common.deny"))

                Button(action: onApprove) {
                    Image(systemName: "checkmark")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(Color.luOnTint)
                        .frame(width: 34, height: 34)
                        .background(Circle().fill(Color.luTint))
                        .minimumTapTarget(visualSize: 34)
                }
                .buttonStyle(PressScaleButtonStyle(scale: .chip))
                .accessibilityLabel(String(localized: "common.approve"))
            }
        }
    }
}

#Preview("AdminPendingUserRow") {
    Form {
        AdminPendingUserRow(
            user: AdminUserRowModel(
                id: "9", name: "Marcus Lee", email: "marcus@example.com",
                roleLabel: "Member", isRootBadge: false, isProtected: false
            ),
            isBusy: false, onApprove: {}, onDeny: {}
        )
    }
}
