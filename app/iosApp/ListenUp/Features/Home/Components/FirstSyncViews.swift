import SwiftUI

/// "Building your library" above the first-sync skeleton (spec §2.3 FirstSync, H5): the count joins the title only
/// while a scan knows both numbers, and VoiceOver hears it as it changes (§8.2).
struct FirstSyncStrip: View {
    let count: ArrivalCount?

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(FirstSyncText.title(count: count))
                .font(.subheadline.weight(.semibold))
            Text(String(localized: "home.arriving_body"))
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .padding(Spacing.s)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: Radius.l, style: .continuous))
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.updatesFrequently)
        .onChange(of: count?.percent) { _, _ in
            VoiceOverAnnouncement.post(FirstSyncText.title(count: count))
        }
    }
}

private extension ArrivalCount {
    /// Whole percent, so the strip is announced once a percent rather than once a book.
    var percent: Int { total > 0 ? done * 100 / total : 0 }
}

/// The slot's skeleton, in the Resume hero's geometry (H14). First sync draws its Resume disabled ([withResume], board
/// panel 2); page loading does not.
struct HomeSkeletonView: View {
    let withResume: Bool

    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.s) {
            HStack(spacing: 14) {
                bar(width: 116, height: 116, radius: Radius.m)
                VStack(alignment: .leading, spacing: 8) {
                    bar(width: 80, height: 12)
                    bar(width: 160, height: 18)
                    bar(width: 120, height: 14)
                }
                Spacer(minLength: 0)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(String(localized: "home.up_next_loading_a11y"))
            if withResume {
                Button {} label: {
                    Label(String(localized: "book.detail_resume_ios"), systemImage: "play.fill")
                        .frame(maxWidth: .infinity)
                }
                .prominentAction()
                .buttonBorderShape(.capsule)
                .disabled(true)
            }
        }
        .padding(Spacing.m)
        .background(
            BookDetailHeroTone.color(for: colorScheme),
            in: RoundedRectangle(cornerRadius: Radius.xl, style: .continuous)
        )
    }

    private func bar(width: CGFloat, height: CGFloat, radius: CGFloat = Radius.xs) -> some View {
        RoundedRectangle(cornerRadius: radius, style: .continuous)
            .fill(Color.gray.opacity(0.2))
            .frame(width: width, height: height)
            .shimmer()
    }
}

/// Page loading's rail: "Also in Progress" over three card-sized shimmers, read as one "Loading books".
struct HomeSkeletonRail: View {
    let margin: CGFloat

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(String(localized: "home.also_in_progress_ios"))
                .font(.title3.weight(.semibold))
                .accessibilityAddTraits(.isHeader)
                .padding(.horizontal, margin)
            HStack(alignment: .top, spacing: 12) {
                ForEach(0..<3, id: \.self) { _ in
                    RoundedRectangle(cornerRadius: Radius.s, style: .continuous)
                        .fill(Color.gray.opacity(0.2))
                        .frame(width: HomeLayout.phoneCardWidth, height: HomeLayout.phoneCardWidth)
                        .shimmer()
                }
            }
            .padding(.horizontal, margin)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(String(localized: "home.loading_books_a11y"))
        }
    }
}
