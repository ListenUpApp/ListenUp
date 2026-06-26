import SwiftUI
import Shared

/// The current user's profile screen.
///
/// Identity (avatar, name, email, tagline) comes from the live `CurrentUserObserver`;
/// the real listening stats (hours listened, books finished, streaks) come from
/// `UserProfileViewModel` via `UserProfileObserver`. An Edit Profile affordance
/// presents the edit sheet; Settings and Downloads remain as action rows.
struct UserProfileView: View {
    @Environment(CurrentUserObserver.self) private var userObserver
    @Environment(\.dependencies) private var deps
    @Environment(\.horizontalSizeClass) private var sizeClass

    @State private var statsObserver: UserProfileObserver?
    @State private var isEditing = false

    private var user: User? { userObserver.user }

    var body: some View {
        ScrollView {
            content
                .padding(.horizontal, 20)
                .padding(.top, 8)
                .padding(.bottom, 24)
                .readableWidth()
        }
        .background(Color.luSurface)
        .navigationTitle(String(localized: "common.profile"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { isEditing = true } label: {
                    Image(systemName: "pencil")
                }
                .accessibilityLabel(Text("profile.edit_profile"))
            }
        }
        .sheet(isPresented: $isEditing) {
            EditProfileView()
        }
        .task {
            let observer = statsObserver ?? makeObserver()
            statsObserver = observer
            if let userId = user?.idString { observer.loadProfile(userId: userId) }
        }
        .onChange(of: user?.idString) { _, newId in
            if let newId { statsObserver?.loadProfile(userId: newId) }
        }
    }

    private func makeObserver() -> UserProfileObserver {
        UserProfileObserver(viewModel: deps.createUserProfileViewModel())
    }

    // MARK: - Layout

    /// iPad regular width places the identity column beside the actions; compact
    /// stacks them vertically, matching `ProfilePad` / `ProfilePhone`.
    @ViewBuilder private var content: some View {
        if sizeClass == .regular {
            HStack(alignment: .top, spacing: 40) {
                VStack(spacing: 22) { header; statStrip; editButton }
                    .frame(width: 320)
                actionsSection
                    .frame(maxWidth: .infinity, alignment: .top)
            }
        } else {
            VStack(spacing: 22) {
                header
                statStrip
                editButton
                actionsSection
                    .padding(.top, 8)
            }
        }
    }

    // MARK: - Header

    private var header: some View {
        VStack(spacing: 12) {
            UserAvatarView(user: user, size: 104)

            VStack(spacing: 3) {
                Text(user?.displayName ?? String(localized: "common.loading"))
                    .font(.title.bold())
                    .multilineTextAlignment(.center)

                if let detail = subtitle {
                    Text(detail)
                        .font(.subheadline)
                        .foregroundStyle(Color.luLabel2)
                        .multilineTextAlignment(.center)
                }
            }
        }
        .frame(maxWidth: .infinity)
    }

    /// "email · tagline" when both exist, otherwise whichever is present.
    private var subtitle: String? {
        let tagline = user?.tagline.flatMap { $0.isEmpty ? nil : $0 }
        switch (user?.email, tagline) {
        case let (email?, line?): return "\(email) · \(line)"
        case let (email?, nil): return email
        case let (nil, line?): return line
        default: return nil
        }
    }

    // MARK: - Stats

    @ViewBuilder private var statStrip: some View {
        if let stats = statsObserver, stats.phase == .ready {
            StatStrip(stats: [
                .init(value: ProfileStatFormat.listened(totalMs: stats.totalListenTimeMs),
                      label: String(localized: "profile.stat_listened")),
                .init(value: "\(stats.booksFinished)",
                      label: String(localized: "profile.stat_finished")),
                .init(value: "\(stats.currentStreak)",
                      label: String(localized: "profile.stat_day_streak")),
                .init(value: "\(stats.longestStreak)",
                      label: String(localized: "profile.stat_best"))
            ])
            .padding(.vertical, 14)
            .frame(maxWidth: .infinity)
            .background(Color.luSurface2, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        } else {
            ProgressView()
                .frame(maxWidth: .infinity, minHeight: 72)
                .background(Color.luSurface2, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
    }

    // MARK: - Edit button

    private var editButton: some View {
        Button { isEditing = true } label: {
            HStack(spacing: 8) {
                Image(systemName: "pencil")
                Text(String(localized: "profile.edit_profile"))
                    .fontWeight(.semibold)
            }
            .foregroundStyle(Color.luTint)
            .frame(maxWidth: .infinity, minHeight: 50)
            .background(Color.luFill, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .buttonStyle(.plain)
    }

    // MARK: - Actions

    private var actionsSection: some View {
        VStack(spacing: 0) {
            NavigationLink(value: SettingsDestination()) {
                actionRow(icon: "gearshape", title: String(localized: "common.settings"))
            }
            .buttonStyle(.plain)

            // Direct entry for admins — Administration is otherwise buried three layers deep
            // (Profile → Settings → Administration). Gated on the signed-in user being an admin.
            if userObserver.user?.isAdmin == true {
                Divider().padding(.leading, 54)
                NavigationLink(value: AdminDestination()) {
                    actionRow(
                        icon: "shield.lefthalf.filled",
                        title: String(localized: "common.administration")
                    )
                }
                .buttonStyle(.plain)
            }

            Divider().padding(.leading, 54)

            actionRow(icon: "arrow.down.circle", title: String(localized: "common.downloads"))
        }
        .background(Color.luSurface2, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    private func actionRow(icon: String, title: String) -> some View {
        HStack(spacing: 14) {
            Image(systemName: icon)
                .font(.body)
                .foregroundStyle(Color.luTint)
                .frame(width: 24)

            Text(title)
                .foregroundStyle(.primary)

            Spacer()

            Image(systemName: "chevron.right")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(Color.luLabel3)
        }
        .padding(.horizontal, 16)
        .frame(minHeight: 52)
        .contentShape(Rectangle())
    }
}

// MARK: - Preview

#Preview {
    NavigationStack {
        UserProfileView()
    }
    .environment(CurrentUserObserver())
}
