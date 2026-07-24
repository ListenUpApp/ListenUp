import SwiftUI

/// One row in PENDING INVITES: a mail glyph, the invitee's name + expiry, the role badge, and
/// Copy-link / Revoke affordances. A spinner replaces the revoke button while it's in flight.
struct AdminInviteRow: View {
    let invite: AdminInviteRowModel
    let isRevoking: Bool
    let onCopy: () -> Void
    let onRevoke: () -> Void

    private var expiryText: String? {
        guard let expiresAt = invite.expiresAt else { return nil }
        let relative = RelativeDateTimeFormatter()
        relative.unitsStyle = .full
        let phrase = relative.localizedString(for: expiresAt, relativeTo: Date())
        return String(localized: "admin.expires_in") + " " + phrase
    }

    var body: some View {
        HStack(spacing: 13) {
            IconTile(systemImage: "envelope.fill", tint: .luTint, size: 40)
            VStack(alignment: .leading, spacing: 1) {
                Text(invite.name)
                    .font(.body)
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                Text(expiryText ?? invite.email)
                    .font(.footnote)
                    .foregroundStyle(Color.luLabel2)
                    .lineLimit(1)
            }
            Spacer(minLength: 8)
            AdminRoleBadge(label: invite.roleLabel, isElevated: invite.roleLabel.lowercased() == "admin")
            actions
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 9)
    }

    @ViewBuilder
    private var actions: some View {
        Button(action: onCopy) {
            Image(systemName: "link")
                .font(.body)
                .foregroundStyle(Color.luLabel2)
                .frame(width: 28, height: 28)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(String(localized: "admin.copy_link"))

        if isRevoking {
            ProgressView().frame(width: 28)
        } else {
            Button(role: .destructive, action: onRevoke) {
                Image(systemName: "trash")
                    .font(.body)
                    .foregroundStyle(.red)
                    .frame(width: 28, height: 28)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(String(localized: "common.revoke"))
        }
    }
}

#Preview("AdminInviteRow") {
    AdminInviteRow(
        invite: AdminInviteRowModel(
            id: "i1", name: "Sarah Chen", email: "sarah@example.com",
            roleLabel: "Member", url: "listen.example.net/join/a8f2c1", expiresAt: Date().addingTimeInterval(86_400 * 7)
        ),
        isRevoking: false, onCopy: {}, onRevoke: {}
    )
    .fieldCard()
    .padding()
    .frame(maxWidth: .infinity, maxHeight: .infinity)
    .background(Color.luSurface)
}
