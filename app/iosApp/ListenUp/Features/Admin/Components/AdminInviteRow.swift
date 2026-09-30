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
            IconTile(systemImage: "envelope.fill", size: 40)
            VStack(alignment: .leading, spacing: 1) {
                Text(invite.name)
                    .font(.body)
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                Text(expiryText ?? invite.email)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 8)
            AdminRoleBadge(label: invite.roleLabel, isElevated: invite.roleLabel.lowercased() == "admin")
            actions
        }
    }

    @ViewBuilder
    private var actions: some View {
        Button(action: onCopy) {
            Image(systemName: "link")
                .font(.body)
                .foregroundStyle(Color.secondary)
                .frame(width: TapTarget.minimum, height: TapTarget.minimum)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(String(localized: "admin.copy_link"))

        if isRevoking {
            ProgressView().frame(width: TapTarget.minimum)
        } else {
            Button(role: .destructive, action: onRevoke) {
                Image(systemName: "trash")
                    .font(.body)
                    .foregroundStyle(.red)
                    .frame(width: TapTarget.minimum, height: TapTarget.minimum)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(String(localized: "common.revoke"))
        }
    }
}

#Preview("AdminInviteRow") {
    Form {
        AdminInviteRow(
            invite: AdminInviteRowModel(
                id: "i1", name: "Sarah Chen", email: "sarah@example.com",
                roleLabel: "Member", url: "listen.example.net/join/a8f2c1", expiresAt: Date().addingTimeInterval(86_400 * 7)
            ),
            isRevoking: false, onCopy: {}, onRevoke: {}
        )
    }
}
