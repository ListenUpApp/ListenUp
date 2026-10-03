import SwiftUI
import Shared

/// Settings › Account › Hardcover › Kept Off Hardcover (#1541): the books kept off Hardcover, each with Sync Again.
///
/// One grouped `Form` section, its footer saying what keeping off means. Each row's Sync Again is a bordered
/// capsule — HIG, Buttons: a less prominent style where many rows repeat the same action — named by its own
/// book for VoiceOver. A row leaves as its book syncs again, with an announcement in place of a toast; the
/// list pops back to the Hardcover screen when the last one goes.
struct HardcoverKeptOffView: View {
    @Environment(\.dependencies) private var deps
    @Environment(\.dismiss) private var dismiss
    @State private var observer: HardcoverKeptOffObserver?
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    /// At accessibility sizes the large title truncates to "Kept off Har…"; the inline title holds it whole.
    /// HIG, Navigation bars: a large title is for when it helps, and here it costs the words.
    static func usesInlineTitle(at size: DynamicTypeSize) -> Bool { size.isAccessibilitySize }

    var body: some View {
        Group {
            if let observer {
                content(observer)
            } else {
                LoadingStateView()
            }
        }
        .background(Color.luSurface)
        .navigationTitle(String(localized: "hardcover.kept_off_title").titleStyled)
        .navigationBarTitleDisplayMode(Self.usesInlineTitle(at: dynamicTypeSize) ? .inline : .large)
        .onAppear {
            if observer == nil {
                observer = HardcoverKeptOffObserver(viewModel: deps.createKeptOffBooksViewModel())
            }
        }
        .messageAlert(Binding(get: { observer?.alert }, set: { observer?.alert = $0 }))
        .onChange(of: observer?.shouldClose ?? false) { _, close in
            if close { dismiss() }
        }
    }

    @ViewBuilder
    private func content(_ observer: HardcoverKeptOffObserver) -> some View {
        switch observer.phase {
        case .loading:
            ProgressView()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .unavailable:
            ContentUnavailableView(
                String(localized: "hardcover.kept_off_unavailable"),
                systemImage: "exclamationmark.triangle"
            )
        case .books(let books):
            Form {
                Section {
                    ForEach(books) { book in
                        KeptOffBookRowView(book: book) { observer.syncAgain(book.id) }
                    }
                } footer: {
                    Text(String(localized: "hardcover.kept_off_intro"))
                }
            }
            .readableListWidth(720)
            .animation(.default, value: books)
        }
    }
}

/// A kept-off book: its cover, title and author, and Sync Again named by the book.
///
/// Side by side at the regular sizes. At the accessibility sizes the three crushed the title to a word a line
/// and broke "Sync Again" mid-word, so the cover steps aside and Sync Again goes under the book. HIG,
/// Typography: at large sizes, stack content that sat side by side.
struct KeptOffBookRowView: View {
    let book: KeptOffBookRow
    let onSyncAgain: () -> Void
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        if dynamicTypeSize.isAccessibilitySize {
            VStack(alignment: .leading, spacing: Spacing.s) {
                bookText
                syncAgainButton
            }
        } else {
            HStack(spacing: Spacing.s) {
                BookCoverImage(bookId: book.id, coverPath: book.coverPath, coverHash: book.coverHash)
                    .frame(width: 40, height: 60)
                    .clipShape(RoundedRectangle(cornerRadius: Radius.xs))
                    .accessibilityHidden(true)
                bookText
                syncAgainButton
            }
        }
    }

    private var bookText: some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(book.title)
                .font(.body)
            if !book.authorNames.isEmpty {
                Text(book.authorNames)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }

    private var syncAgainButton: some View {
        Button(String(localized: "hardcover.sync_again").titleStyled, action: onSyncAgain)
            .buttonStyle(.bordered)
            .buttonBorderShape(.capsule)
            .accessibilityLabel(String(format: String(localized: "hardcover.sync_again_label"), book.title))
    }
}
