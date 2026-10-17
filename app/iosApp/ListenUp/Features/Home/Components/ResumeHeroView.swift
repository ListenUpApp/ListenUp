import SwiftUI

/// The Resume kind (spec §2.3): nothing is loaded and this is the book last played. The cover and title are one link
/// into the book (H15); Resume is a separate coral capsule that plays from the saved position (C18). The surface is
/// Book Detail's neutral hero tone until the cover wash lands (spec D3). `isOnlyBook` centres a larger cover.
struct ResumeHeroView: View {
    let resume: ResumeSlot
    let isOnlyBook: Bool
    let onResume: () -> Void

    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.s) {
            NavigationLink(value: BookDestination(id: resume.book.id)) { identity }
                .buttonStyle(.plain)
            progress
            if let next = resume.next {
                NavigationLink(value: BookDestination(id: next.id)) { nextRow(next) }
                    .buttonStyle(.plain)
            }
            Button(action: onResume) {
                Label(String(localized: "book.detail_resume_ios"), systemImage: "play.fill")
                    .frame(maxWidth: .infinity)
            }
            .prominentAction()
            .buttonBorderShape(.capsule)
            .disabled(!resume.canPlay)
            if !resume.canPlay {
                Text(String(localized: "home.resume_unavailable_offline"))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(Spacing.m)
        .background(
            BookDetailHeroTone.color(for: colorScheme),
            in: RoundedRectangle(cornerRadius: Radius.xl, style: .continuous)
        )
    }

    private var identity: some View {
        let book = resume.book
        let texts = VStack(alignment: isOnlyBook ? .center : .leading, spacing: 2) {
            Text(ResumeHeroText.eyebrow(lastPlayedAt: book.lastPlayedAt, now: .now))
                .font(.footnote.weight(.semibold))
                .foregroundStyle(.secondary)
            Text(book.title)
                .font(.title3.bold())
                .multilineTextAlignment(isOnlyBook ? .center : .leading)
            if !book.author.isEmpty { Text(book.author).font(.subheadline) }
            if let narrators = book.narrators, !narrators.isEmpty {
                Text(String(format: String(localized: "library.card_read_by"), narrators))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        let coverSide: CGFloat = isOnlyBook ? 170 : 116
        let cover = BookCoverImage(bookId: book.id, coverPath: book.coverPath, coverHash: book.coverHash)
            .frame(width: coverSide, height: coverSide)
            .clipShape(RoundedRectangle(cornerRadius: Radius.m, style: .continuous))
            .restrictedMarker(bookId: book.id)
            .shadow(color: .black.opacity(0.2), radius: 12, x: 0, y: 8)
        return Group {
            if isOnlyBook {
                VStack(spacing: Spacing.xs) {
                    cover
                    texts
                }
                .frame(maxWidth: .infinity)
            } else {
                HStack(spacing: 14) {
                    cover
                    texts
                    Spacer(minLength: 0)
                }
            }
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isLink)
    }

    private var progress: some View {
        let book = resume.book
        return VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline) {
                Text(ResumeHeroText.chapter(number: resume.chapterNumber, title: resume.chapterTitle) ?? "")
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(1)
                Spacer()
                Text(HomeCardText.timeLeft(remainingMs: book.remainingMs))
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(Color.luTintInk)
            }
            ProgressView(value: min(max(book.progress, 0), 1))
                .tint(.listenUpOrange)
                .accessibilityLabel(
                    String(format: String(localized: "library.card_progress_a11y"), book.progressPercent)
                )
            Text(ResumeHeroText.progressOfLength(percent: book.progressPercent, totalMs: book.totalMs))
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .combine)
    }

    private func nextRow(_ next: NextBookItem) -> some View {
        HStack(spacing: Spacing.s) {
            BookCoverImage(bookId: next.id, coverPath: next.coverPath, coverHash: next.coverHash)
                .frame(width: 40, height: 40)
                .clipShape(RoundedRectangle(cornerRadius: Radius.s, style: .continuous))
            VStack(alignment: .leading, spacing: 0) {
                Text(String(localized: "home.next_in_series_ios"))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                Text(next.title)
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(1)
            }
            Spacer(minLength: 0)
        }
        .padding(Spacing.xs)
        .background(.background.opacity(0.6), in: RoundedRectangle(cornerRadius: Radius.m, style: .continuous))
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isLink)
    }
}
