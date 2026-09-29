import SwiftUI

/// Shown only when the inbox is empty on BOTH counts — no held books and no scan issues.
///
/// The distinction matters: an inbox holding only failed imports is populated, and telling the
/// admin it is empty while sitting on a list of problems would be the screen contradicting itself.
/// `AdminInboxReadyModel.isEmpty` is the test; `hasBooks` is not.
struct AdminInboxEmptyState: View {
    var body: some View {
        VStack(spacing: 20) {
            Spacer()
            ZStack {
                RoundedRectangle(cornerRadius: Radius.xxl, style: .continuous)
                    .fill(Color.luFill)
                    .frame(width: 96, height: 96)
                Image(systemName: "tray")
                    .font(.system(size: 46, weight: .light)) // decorative fixed size
                    .foregroundStyle(.tertiary)
            }
            VStack(spacing: 6) {
                Text(String(localized: "admin.inbox_empty"))
                    .font(.title2.weight(.bold))
                Text(String(localized: "admin.inbox_setting_subtitle"))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 280)
                    .lineSpacing(2)
            }
            Spacer()
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, Spacing.xxl)
    }
}
