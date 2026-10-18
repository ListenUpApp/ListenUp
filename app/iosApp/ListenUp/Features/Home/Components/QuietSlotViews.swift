import SwiftUI

/// Nothing in Progress, but the reader has listened before (spec §2.3): with a series candidate, its card with **Play**
/// and **Browse the Library**; without one, Browse the Library and Discover (H17).
struct NothingInProgressView: View {
    let candidate: NextBookItem?
    let onPlay: (String) -> Void
    let onBrowse: () -> Void
    let onDiscover: () -> Void

    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(String(localized: "home.nothing_in_progress_ios"))
                .font(.title3.weight(.semibold))
                .accessibilityAddTraits(.isHeader)
            if let candidate {
                VStack(alignment: .leading, spacing: Spacing.s) {
                    NavigationLink(value: BookDestination(id: candidate.id)) { identity(candidate) }
                        .buttonStyle(.plain)
                    HStack(spacing: Spacing.xs) {
                        Button { onPlay(candidate.id) } label: {
                            Label(String(localized: "common.play"), systemImage: "play.fill")
                        }
                        .prominentAction()
                        .buttonBorderShape(.capsule)
                        QuietSecondaryButton(title: String(localized: "home.browse_library"), action: onBrowse)
                    }
                }
                .padding(Spacing.m)
                .background(
                    BookDetailHeroTone.color(for: colorScheme),
                    in: RoundedRectangle(cornerRadius: Radius.xl, style: .continuous)
                )
            } else {
                HStack(spacing: Spacing.xs) {
                    QuietSecondaryButton(title: String(localized: "home.browse_library"), action: onBrowse)
                    QuietSecondaryButton(title: String(localized: "common.discover"), action: onDiscover)
                }
            }
        }
    }

    private func identity(_ next: NextBookItem) -> some View {
        HStack(alignment: .top, spacing: 14) {
            BookCoverImage(bookId: next.id, coverPath: next.coverPath, coverHash: next.coverHash)
                .frame(width: 96, height: 96)
                .clipShape(RoundedRectangle(cornerRadius: Radius.m, style: .continuous))
                .restrictedMarker(bookId: next.id)
            VStack(alignment: .leading, spacing: 2) {
                Text(String(localized: "home.next_in_a_series"))
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.secondary)
                Text(next.title).font(.title3.bold())
                if let narrators = next.narrators, !narrators.isEmpty {
                    Text(String(format: String(localized: "library.card_read_by"), narrators))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                Text(
                    QuietSlotText.seriesLine(
                        series: next.seriesName,
                        sequence: next.sequenceLabel,
                        durationMs: next.durationMs
                    )
                )
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isLink)
    }
}

/// No listening history at all (spec §2.3 NewListener): four of the newest covers, "Pick something new", and the way
/// in — Browse the Library (prominent) and Discover. Never a count of the library (Simon, 2026-10-14).
struct WelcomeView: View {
    let covers: [DiscoverShelfCover]
    let onBrowse: () -> Void
    let onDiscover: () -> Void

    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.s) {
            HStack(spacing: Spacing.xs) {
                ForEach(covers.prefix(4), id: \.bookId) { cover in
                    BookCoverImage(bookId: cover.bookId, coverPath: cover.coverPath, coverHash: cover.coverHash)
                        .frame(width: 64, height: 64)
                        .clipShape(RoundedRectangle(cornerRadius: Radius.s, style: .continuous))
                }
                ForEach(0..<max(0, 4 - covers.count), id: \.self) { _ in
                    RoundedRectangle(cornerRadius: Radius.s, style: .continuous)
                        .fill(Color.gray.opacity(0.15))
                        .frame(width: 64, height: 64)
                }
            }
            .accessibilityHidden(true)
            Text(String(localized: "home.welcome_headline"))
                .font(.title2.weight(.semibold))
            Text(String(localized: "home.welcome_body"))
                .font(.subheadline)
                .foregroundStyle(.secondary)
            HStack(spacing: Spacing.xs) {
                Button(action: onBrowse) { Text(String(localized: "home.browse_library")) }
                    .prominentAction()
                    .buttonBorderShape(.capsule)
                QuietSecondaryButton(title: String(localized: "common.discover"), action: onDiscover)
            }
        }
        .padding(Spacing.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            BookDetailHeroTone.color(for: colorScheme),
            in: RoundedRectangle(cornerRadius: Radius.xl, style: .continuous)
        )
        .accessibilityElement(children: .contain)
        .accessibilityLabel(String(localized: "home.getting_started_a11y"))
    }
}

/// A content button beside the prominent one: bordered, not Liquid Glass — it scrolls with the content (HIG keeps
/// glass for the controls that float), as Book Detail's secondary actions are.
private struct QuietSecondaryButton: View {
    let title: String
    let action: () -> Void

    var body: some View {
        Button(action: action) { Text(title) }
            .buttonStyle(.bordered)
            .buttonBorderShape(.capsule)
            .controlSize(.large)
    }
}
