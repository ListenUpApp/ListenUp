import SwiftUI
@preconcurrency import Shared

/// Home screen showing personalized content.
///
/// Based on mockup, will feature:
/// - Greeting header ("Hello, Hriday!")
/// - Search bar
/// - Horizontal section tabs (Home, Library, Series, Saved)
/// - Continue Listening carousel
/// - Recently Added section
/// - Recent Stories
/// - Discover section
/// - Newest Authors
/// - Local Books
struct HomeView: View {
    @Environment(CurrentUserObserver.self) private var userObserver

    private var user: User_? { userObserver.user }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                headerSection
                contentPlaceholder
            }
            .padding()
        }
        .background(Color(.systemBackground))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                NavigationLink(value: UserProfileDestination()) {
                    UserAvatarView(user: user, size: 32)
                }
                .buttonStyle(.plain)
            }
        }
    }

    // MARK: - Header

    private var headerSection: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(String(format: String(localized: "home.greeting"), user?.displayName ?? "Friend"))
                .font(.subheadline)
                .foregroundStyle(.secondary)

            Text(String(localized: "home.what_to_read"))
                .font(.title2.bold())
        }
    }

    // MARK: - Content Placeholder

    private var contentPlaceholder: some View {
        VStack(spacing: 16) {
            Spacer()
                .frame(height: 60)

            Image(systemName: "house")
                .font(.system(size: 64))
                .foregroundStyle(.secondary)

            Text(String(localized: "home.home_screen"))
                .font(.title2.bold())

            Text(String(localized: "home.content_coming_soon"))
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
        HomeView()
    }
    .environment(CurrentUserObserver())
}

#Preview("Dark Mode") {
    NavigationStack {
        HomeView()
    }
    .environment(CurrentUserObserver())
    .preferredColorScheme(.dark)
}
