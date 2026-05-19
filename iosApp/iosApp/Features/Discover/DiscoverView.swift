import SwiftUI
import Shared

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
        VStack(spacing: 16) {
            Spacer()
                .frame(height: 60)

            Image(systemName: "sparkles")
                .font(.system(size: 64))
                .foregroundStyle(Color.listenUpOrange)

            Text(String(localized: "discover.discover_screen"))
                .font(.title2.bold())

            Text(String(localized: "discover.find_next_favorite"))
                .font(.subheadline)
                .foregroundStyle(.secondary)

            Spacer()
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 40)
    }
}

// MARK: - Preview

#Preview {
    NavigationStack {
        DiscoverView()
    }
    .environment(CurrentUserObserver())
}
