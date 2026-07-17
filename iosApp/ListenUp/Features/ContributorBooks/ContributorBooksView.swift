import SwiftUI
import Shared

/// The full "See all" list of a contributor's books for one role, reached from a truncated role
/// carousel on `ContributorDetailView`. Series-grouped first (each series its own section), then
/// standalone books — the same order the shared `ContributorBooksViewModel` produces. A
/// width-responsive cover grid (`GridItem(.adaptive`) keeps it right from a narrow Split View up
/// to full-screen iPad.
struct ContributorBooksView: View {
    let contributorId: String
    let role: String
    let contributorName: String
    let roleDisplayName: String

    @Environment(\.dependencies) private var deps
    @State private var observer: ContributorBooksObserver?

    var body: some View {
        Group {
            if let observer {
                content(observer)
            } else {
                LoadingStateView()
            }
        }
        .background(Color.luSurface)
        .navigationTitle(observer?.contributorName ?? contributorName)
        .navigationBarTitleDisplayMode(.inline)
        .task(id: "\(contributorId)-\(role)") {
            let obs = ContributorBooksObserver(
                viewModel: deps.createContributorBooksViewModel(),
                fallbackName: contributorName,
                fallbackRole: roleDisplayName
            )
            observer = obs
            obs.loadBooks(contributorId: contributorId, role: role)
        }
        .onDisappear {
            // Release the observer; its deinit cancels the FlowBridge subscriptions.
            observer = nil
        }
    }

    // MARK: - Content by phase

    @ViewBuilder
    private func content(_ observer: ContributorBooksObserver) -> some View {
        switch observer.phase {
        case .loading:
            LoadingStateView()
        case .error:
            ContentUnavailableView(
                observer.errorMessage ?? String(localized: "common.something_went_wrong"),
                systemImage: "exclamationmark.triangle"
            )
        case .ready:
            if observer.totalBooks == 0 {
                ContentUnavailableView(
                    String(localized: "contributor.other_books"),
                    systemImage: "books.vertical"
                )
            } else {
                list(observer)
            }
        }
    }

    // MARK: - List

    private func list(_ observer: ContributorBooksObserver) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                header(observer)

                ForEach(observer.seriesGroups) { group in
                    section(title: group.seriesName, books: group.books, progress: observer.bookProgress)
                }

                if !observer.standaloneBooks.isEmpty {
                    section(
                        // Only badge the standalone group as "Other Books" when series sections
                        // sit above it; when it's the whole list, the screen header already frames it.
                        title: observer.seriesGroups.isEmpty ? nil : String(localized: "contributor.other_books"),
                        books: observer.standaloneBooks,
                        progress: observer.bookProgress
                    )
                }
            }
            .readableWidth()
            .padding(.horizontal)
            .padding(.top, 4)
            .padding(.bottom, 100)
        }
    }

    // MARK: - Header

    private func header(_ observer: ContributorBooksObserver) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(observer.roleDisplayName)
                .font(.caption.weight(.bold))
                .tracking(0.6)
                .textCase(.uppercase)
                .foregroundStyle(Color.luLabel2)
            Text(observer.contributorName)
                .font(.title.weight(.bold))
                .foregroundStyle(.primary)
                .lineLimit(2)
            StatStrip(
                stats: [
                    .init(value: "\(observer.totalBooks)", label: String(localized: "contributor.stat_books"))
                ],
                centered: false
            )
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .contain)
    }

    // MARK: - Section

    @ViewBuilder
    private func section(title: String?, books: [BookRow], progress: [String: Float]) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            if let title {
                SectionRow(title: title)
            }
            LazyVGrid(
                columns: [GridItem(.adaptive(minimum: 150), spacing: 16)],
                spacing: 20
            ) {
                ForEach(books) { book in
                    NavigationLink(value: BookDestination(id: book.id)) {
                        BookCoverCard(book: book, progress: progress[book.id])
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }
}

#Preview {
    NavigationStack {
        ContributorBooksView(
            contributorId: "preview",
            role: "author",
            contributorName: "Brandon Sanderson",
            roleDisplayName: "Written By"
        )
    }
}
