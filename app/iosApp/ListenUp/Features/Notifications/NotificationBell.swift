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
        .onAppear {
            if observer == nil {
                observer = NotificationBellObserver(viewModel: deps.createNotificationBellViewModel())
            }
        }
    }
}
