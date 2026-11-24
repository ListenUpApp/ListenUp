import Shared
import SwiftUI

/// Another user's profile, viewed read-only — reached by tapping a user's avatar in the book
/// Readers section, the Leaderboard, or the Activity feed.
///
/// A deliberately lean, read-only counterpart to `UserProfileView`: the same header, stat strip and
/// "Recently listened" covers, but no Edit / Settings / account chrome. Everything shown (name,
/// tagline, avatar, listening stats) resolves from the synced `public_profiles` Room row, and the
/// covers from the synced activity feed, so it renders offline (Never-Stranded) — the
/// shared `UserProfileViewModel.loadProfile(userId:)` needs no network for this surface. Public shelves
/// are intentionally out of scope for now (they'd need a live RPC + shelf-detail wiring).
///
/// Own-vs-foreign is transparent: loading the signed-in user's own id simply resolves with
/// `isOwnProfile == true` and still renders read-only here.
struct ForeignProfileView: View {
    let userId: String

    var body: some View {
        WithObserver(id: userId) { deps in
            let obs = UserProfileObserver(viewModel: deps.createUserProfileViewModel())
            obs.loadProfile(userId: userId)
            return obs
        } content: { observer in
            ScrollView {
                content(observer)
                    .padding(.horizontal, Spacing.l)
                    .padding(.top, Spacing.xs)
                    .padding(.bottom, Spacing.xl)
                    .readableWidth()
            }
            .background(Color.luSurface)
            .navigationTitle(observer.displayName)
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    // MARK: - Content

    @ViewBuilder
    private func content(_ observer: UserProfileObserver) -> some View {
        switch observer.phase {
        case .ready:
            VStack(spacing: 22) {
                header(observer)
                statStrip(observer)
                RecentlyListenedStrip(books: observer.recentBooks, isOwnProfile: observer.isOwnProfile)
            }
        case .error(let message):
            LoadFailedView(message: message) { observer.refresh() }
                .frame(maxWidth: .infinity, minHeight: 320)
        case .loading:
            ProgressView()
                .frame(maxWidth: .infinity, minHeight: 320)
        }
    }

    // MARK: - Header

    private func header(_ observer: UserProfileObserver) -> some View {
        VStack(spacing: 12) {
            // Image-ness is resolved by userId inside UserAvatarView (one cheap negative-cached
            // miss falls back to initials), so no avatar-type hint is threaded from the state.
            UserAvatarView(
                userId: userId,
                fallbackName: observer.displayName,
                size: 104
            )

            VStack(spacing: 3) {
                Text(observer.displayName)
                    .font(.title.bold())
                    .multilineTextAlignment(.center)

                if let tagline = observer.tagline, !tagline.isEmpty {
                    Text(tagline)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
            }
        }
        .frame(maxWidth: .infinity)
    }

    // MARK: - Stats

    private func statStrip(_ observer: UserProfileObserver) -> some View {
        StatStrip(stats: [
            .init(value: ProfileStatFormat.listened(totalMs: observer.totalListenTimeMs),
                  label: String(localized: "profile.stat_listened")),
            .init(value: "\(observer.booksFinished)",
                  label: String(localized: "profile.stat_finished")),
            .init(value: "\(observer.currentStreak)",
                  label: String(localized: "profile.stat_day_streak")),
            .init(value: "\(observer.longestStreak)",
                  label: String(localized: "profile.stat_best"))
        ])
        .padding(.vertical, Spacing.m)
        .frame(maxWidth: .infinity)
        .background(Color.luSurface2, in: RoundedRectangle(cornerRadius: Radius.l, style: .continuous))
    }
}
