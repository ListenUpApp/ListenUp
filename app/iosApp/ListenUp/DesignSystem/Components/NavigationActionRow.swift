import SwiftUI

/// A management row for a `List`/`Form`: a leading ``IconTile`` and a title / optional subtitle.
///
/// Two shapes:
/// - wrapped in a `NavigationLink` (no `action`) — the list draws the disclosure indicator and the
///   selection highlight, so the row draws neither;
/// - with an `action` — a button row that opens something in place (a sheet). It draws its own
///   chevron, because the list only draws one for a link, and keeps the system row highlight.
///
/// HIG, Lists and tables: "If you need to let people drill into a list or table row's subviews, use
/// a disclosure indicator accessory control."
struct NavigationActionRow: View {
    let systemImage: String
    /// A meaningful colour for the leading tile; `nil` (the default) keeps it neutral — see `IconTile`.
    var tint: Color?
    let title: String
    var subtitle: String?
    var action: (() -> Void)?

    var body: some View {
        if let action {
            Button(action: action) {
                HStack(spacing: 12) {
                    rowContent
                    Spacer(minLength: 12)
                    Image(systemName: "chevron.right")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(Color.luLabel3)
                        .accessibilityHidden(true)
                }
                .contentShape(Rectangle())
            }
            .foregroundStyle(.primary)
        } else {
            rowContent
        }
    }

    private var rowContent: some View {
        HStack(spacing: 13) {
            IconTile(systemImage: systemImage, tint: tint)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 1) {
                Text(title)
                    .font(.body)
                    .foregroundStyle(.primary)
                if let subtitle {
                    Text(subtitle)
                        .font(.footnote)
                        .foregroundStyle(Color.luLabel2)
                        .multilineTextAlignment(.leading)
                }
            }
        }
    }
}

#Preview("NavigationActionRow") {
    NavigationStack {
        Form {
            NavigationLink(value: 1) {
                NavigationActionRow(systemImage: "archivebox.fill", title: "Backup & Restore", subtitle: "Create backups")
            }
            NavigationActionRow(
                systemImage: "person.2.fill",
                title: "Invite Someone",
                subtitle: "Share your library with others",
                action: {}
            )
        }
    }
}
