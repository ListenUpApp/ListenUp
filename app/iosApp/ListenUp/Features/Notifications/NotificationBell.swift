import SwiftUI
import Shared

/// Toolbar notification bell with an unread badge. Self-contained like `SyncStatusIndicator`:
/// owns its observer, tappable to the inbox, badge hidden at zero, count capped at 99+.
struct NotificationBell: View {
    @Environment(\.dependencies) private var deps
    @State private var observer: NotificationBellObserver?

    var body: some View {
        NavigationLink(value: NotificationsDestination()) {
            Image(systemName: (observer?.unreadCount ?? 0) > 0 ? "bell.badge.fill" : "bell")
                .overlay(alignment: .topTrailing) {
                    if let count = observer?.unreadCount, count > 0 {
                        Text(count > 99 ? "99+" : "\(count)")
                            .font(.system(size: 10, weight: .bold)) // decorative fixed size
                            .foregroundStyle(.white)
                            .padding(.horizontal, 4)
                            .padding(.vertical, 1)
                            .background(Color.luTint, in: Capsule())
                            .offset(x: 8, y: -8)
                    }
                }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(String(localized: "notifications.title"))
        // The badge Text is swallowed by the link's own label, so the count has to be announced
        // explicitly — otherwise VoiceOver says "Notifications" identically at 0 and at 99. Announced
        // as a value (not folded into the label) so it re-reads when the count changes, and omitted
        // entirely at zero: "0 unread" is noise, and the unbadged bell already means "nothing new".
        .accessibilityValue(unreadAnnouncement)
        .onAppear {
            if observer == nil {
                observer = NotificationBellObserver(viewModel: deps.createNotificationBellViewModel())
            }
        }
    }

    /// "N unread" while anything is unread, empty at zero (nothing extra is announced). Uses the
    /// true count, not the badge's "99+" cap — the cap exists so the pill can't stretch, which is
    /// a visual concern VoiceOver does not share.
    private var unreadAnnouncement: String {
        let count = observer?.unreadCount ?? 0
        guard count > 0 else { return "" }
        return String(format: String(localized: "notifications.unread_count_a11y"), count)
    }
}
