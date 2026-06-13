import SwiftUI
@preconcurrency import Shared

/// Discover screen for finding new audiobooks.
///
/// Will feature:
/// - Search/browse functionality
/// - Categories
/// - Featured content
/// - New releases
/// - Popular authors
struct DiscoverView: View {
    @Environment(CurrentUserObserver.self) private var userObserver

    private var user: User_? { userObserver.user }

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                contentPlaceholder
            }
            .padding()
            .readableWidth()
        }
        .background(Color(.systemBackground))
        .navigationTitle(String(localized: "common.discover"))
        .navigationBarTitleDisplayMode(.large)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                NavigationLink(value: UserProfileDestination()) {
                    UserAvatarView(user: user, size: 32)
                }
                .buttonStyle(.plain)
            }
        }
    }

    // MARK: - Content Placeholder

    private var contentPlaceholder: some View {
        ContentUnavailableView {
            Label {
                Text(String(localized: "discover.discover_screen"))
            } icon: {
                Image(systemName: "sparkles").foregroundStyle(Color.luTint)
            }
        } description: {
            Text(String(localized: "discover.find_next_favorite"))
        }
    }
}

// MARK: - Preview

#Preview {
    NavigationStack {
        DiscoverView()
    }
    .environment(CurrentUserObserver())
}
