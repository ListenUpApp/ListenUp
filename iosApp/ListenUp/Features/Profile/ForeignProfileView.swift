import Shared
import SwiftUI

/// Another user's profile, viewed read-only — reached by tapping a user's avatar in the book
/// Readers section, the Leaderboard, or the Activity feed.
///
/// A deliberately lean, read-only counterpart to `UserProfileView`: the same header + stat strip,
/// but no Edit / Settings / account chrome. Everything shown (name, tagline, avatar, listening stats)
/// resolves from the synced `public_profiles` Room row, so it renders offline (Never-Stranded) — the
/// shared `UserProfileViewModel.loadProfile(userId:)` needs no network for this surface. Public shelves
/// are intentionally out of scope for now (they'd need a live RPC + shelf-detail wiring).
///
/// Own-vs-foreign is transparent: loading the signed-in user's own id simply resolves with
/// `isOwnProfile == true` and still renders read-only here.
struct ForeignProfileView: View {
    let userId: String

    @Environment(\.dependencies) private var deps

    @State private var observer: UserProfileObserver?

    var body: some View {
        ScrollView {
            content
                .padding(.horizontal, 20)
                .padding(.top, 8)
                .padding(.bottom, 24)
                .readableWidth()
        }
        .background(Color.luSurface)
        .navigationTitle(observer?.displayName ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            let observer = observer ?? UserProfileObserver(viewModel: deps.createUserProfileViewModel())
            self.observer = observer
            observer.loadProfile(userId: userId)
        }
    }

    // MARK: - Content

    @ViewBuilder private var content: some View {
        switch observer?.phase ?? .loading {
        case .ready:
            VStack(spacing: 22) {
                header
                statStrip
            }
        case .error(let message):
            ContentUnavailableView(
                String(localized: "common.error"),
                systemImage: "person.slash",
                description: Text(message)
            )
            .frame(maxWidth: .infinity, minHeight: 320)
        case .loading:
            ProgressView()
                .frame(maxWidth: .infinity, minHeight: 320)
        }
    }

    // MARK: - Header

    private var header: some View {
        VStack(spacing: 12) {
            // Image-ness is resolved by userId inside UserAvatarView (one cheap negative-cached
            // miss falls back to initials), so no avatar-type hint is threaded from the state.
            UserAvatarView(
                userId: userId,
                fallbackName: observer?.displayName ?? "",
                avatarColor: observer?.avatarColorHex,
                size: 104
            )

            VStack(spacing: 3) {
                Text(observer?.displayName ?? "")
                    .font(.title.bold())
                    .multilineTextAlignment(.center)

                if let tagline = observer?.tagline, !tagline.isEmpty {
                    Text(tagline)
                        .font(.subheadline)
                        .foregroundStyle(Color.luLabel2)
                        .multilineTextAlignment(.center)
                }
            }
        }
        .frame(maxWidth: .infinity)
    }

    // MARK: - Stats

    @ViewBuilder private var statStrip: some View {
        if let stats = observer {
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
        }
    }
}
