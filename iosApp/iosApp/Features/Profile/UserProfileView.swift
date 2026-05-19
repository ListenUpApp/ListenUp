import SwiftUI
import Shared

/// User profile screen showing the current user's information.
///
/// Shows:
/// - User avatar and name (from database)
/// - Statistics (placeholder for now)
/// - Settings link
/// - Downloads link
struct UserProfileView: View {
    @Environment(CurrentUserObserver.self) private var userObserver

    private var user: User_? { userObserver.user }

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                headerSection
                statsSection
                actionsSection
            }
            .padding()
        }
        .background(Color(.systemBackground))
        .navigationTitle(String(localized: "common.profile"))
        .navigationBarTitleDisplayMode(.inline)
    }

    // MARK: - Header

    private var headerSection: some View {
        VStack(spacing: 16) {
            UserAvatarView(user: user, size: 100)

            VStack(spacing: 4) {
                Text(user?.displayName ?? String(localized: "common.loading"))
                    .font(.title2.bold())

                if let email = user?.email {
                    Text(email)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }

                if let tagline = user?.tagline, !tagline.isEmpty {
                    Text(tagline)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.top, 4)
                }
            }
        }
    }

    // MARK: - Stats Section

    private var statsSection: some View {
        HStack(spacing: 24) {
            statItem(value: "--", label: String(localized: "profile.hours_listened"))
            statItem(value: "--", label: String(localized: "profile.books_finished"))
            statItem(value: "--", label: String(localized: "profile.in_progress"))
        }
        .padding()
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
    }

    private func statItem(value: String, label: String) -> some View {
        VStack(spacing: 4) {
            Text(value)
                .font(.title.bold())
                .foregroundStyle(Color.listenUpOrange)

            Text(label)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    // MARK: - Actions Section

    private var actionsSection: some View {
        VStack(spacing: 0) {
            NavigationLink(value: SettingsDestination()) {
                actionRow(icon: "gearshape", title: String(localized: "common.settings"))
            }
            .buttonStyle(.plain)

            Divider()

            actionRow(icon: "arrow.down.circle", title: String(localized: "common.downloads"))
        }
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
    }

    private func actionRow(icon: String, title: String) -> some View {
        HStack {
            Image(systemName: icon)
                .frame(width: 24)
                .foregroundStyle(Color.listenUpOrange)

            Text(title)
                .foregroundStyle(.primary)

            Spacer()

            Image(systemName: "chevron.right")
                .foregroundStyle(.tertiary)
        }
        .padding()
    }
}

// MARK: - Preview

#Preview {
    NavigationStack {
        UserProfileView()
    }
    .environment(CurrentUserObserver())
}
