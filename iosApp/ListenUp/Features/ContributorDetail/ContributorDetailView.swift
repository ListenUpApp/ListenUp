import SwiftUI
@preconcurrency import Shared
import UIKit

/// Contributor (Author/Narrator) detail screen — Liquid Glass design.
///
/// Layout:
/// - Circular avatar with shadow
/// - Name, aliases, stats on glass panel
/// - Expandable biography
/// - Role sections with horizontal book carousels
struct ContributorDetailView: View {
    let contributorId: String

    @Environment(\.dependencies) private var deps
    @Environment(\.dismiss) private var dismiss
    @State private var observer: ContributorDetailObserver?

    var body: some View {
        Group {
            if let observer, !observer.isLoading {
                content(observer: observer)
            } else {
                loadingView
            }
        }
        .background(Color(.systemBackground))
        .navigationTitle(observer?.name ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                if observer != nil {
                    Menu {
                        Button(role: .destructive, action: {
                            observer?.onDeleteContributor()
                        }) {
                            Label(String(localized: "common.delete"), systemImage: "trash")
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                }
            }
        }
        .confirmationDialog(
            String(format: String(localized: "common.delete_name"), observer?.name ?? ""),
            isPresented: Binding(
                get: { observer?.showDeleteConfirmation ?? false },
                set: { _ in observer?.onDismissDelete() }
            ),
            titleVisibility: .visible
        ) {
            Button(String(localized: "common.delete"), role: .destructive) {
                observer?.onConfirmDelete {
                    dismiss()
                }
            }
            Button(String(localized: "common.cancel"), role: .cancel) {}
        } message: {
            Text(String(localized: "contributor.remove_from_library"))
        }
        .task(id: contributorId) {
            let vm = deps.createContributorDetailViewModel()
            let obs = ContributorDetailObserver(viewModel: vm)
            observer = obs
            obs.loadContributor(contributorId: contributorId)
        }
        .onDisappear {
            observer?.stopObserving()
            observer = nil
        }
    }

    // MARK: - Content

    private func content(observer: ContributorDetailObserver) -> some View {
        ScrollView {
            VStack(spacing: 24) {
                headerSection(observer: observer)

                if let bio = observer.bio, !bio.isEmpty {
                    ExpandableText(
                        title: String(localized: "common.about"),
                        text: bio,
                        lineLimit: 4,
                        minimumLengthForToggle: 200
                    )
                    .padding(.horizontal)
                }

                ForEach(observer.roleSections, id: \.role) { section in
                    roleSectionView(section: section, observer: observer)
                }
            }
            .padding(.bottom, 32)
        }
    }

    // MARK: - Header

    private func headerSection(observer: ContributorDetailObserver) -> some View {
        VStack(spacing: 16) {
            // Avatar — circular with shadow
            ContributorAvatar(
                name: observer.name,
                imagePath: observer.imagePath,
                blurHash: observer.imageBlurHash,
                id: contributorId,
                fontSize: 40
            )
            .frame(width: 120, height: 120)
            .clipShape(Circle())
            .shadow(color: .black.opacity(0.15), radius: 12, x: 0, y: 6)

            VStack(spacing: 6) {
                Text(observer.name)
                    .font(.title2.bold())

                if !observer.aliases.isEmpty {
                    Text(String(
                        format: String(localized: "contributor.aka"),
                        observer.aliases.joined(separator: ", ")
                    ))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .italic()
                }

                Text("\(observer.totalBookCount) \(observer.totalBookCount == 1 ? String(localized: "contributor.audiobook_count") : String(localized: "contributor.audiobooks_count"))")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)

                if let dateString = formatDateRange(
                    birth: observer.birthDate,
                    death: observer.deathDate
                ) {
                    Text(dateString)
                        .font(.caption)
                        .foregroundStyle(.tertiary)
                }

                if let website = observer.website, let url = URL(string: website) {
                    Link(destination: url) {
                        Label(String(localized: "common.website"), systemImage: "globe")
                            .font(.caption)
                    }
                    .foregroundStyle(Color.listenUpOrange)
                }
            }
        }
        .padding(.top, 16)
    }

    private func formatDateRange(birth: String?, death: String?) -> String? {
        guard let birth else { return nil }
        let birthYear = String(birth.prefix(4))
        if let death {
            return "\(birthYear) – \(String(death.prefix(4)))"
        }
        return String(format: String(localized: "contributor.born_year"), birthYear)
    }

    // MARK: - Role Sections

    private func roleSectionView(section: RoleSection, observer: ContributorDetailObserver) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text(section.displayName)
                    .font(.headline)

                Text("\(section.bookCount)")
                    .font(.caption.weight(.medium))
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(Color.listenUpOrange.opacity(0.15), in: Capsule())
                    .foregroundStyle(Color.listenUpOrange)

                Spacer()
            }
            .padding(.horizontal)

            // Horizontal book carousel
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 16) {
                    ForEach(Array(section.previewBooks), id: \.idString) { book in
                        bookCard(
                            book: book,
                            progress: observer.bookProgress[book.idString]
                        )
                    }
                }
                .padding(.horizontal)
            }
        }
    }

    private func bookCard(book: BookListItem, progress: Float?) -> some View {
        NavigationLink(value: BookDestination(id: book.idString)) {
            VStack(alignment: .leading, spacing: 8) {
                // Cover with progress overlay — floating, no card
                ZStack(alignment: .bottom) {
                    BookCoverImage(book: book)
                        .frame(width: 110, height: 110)
                        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))

                    if let progress, progress > 0 {
                        ProgressBar(progress: progress, style: .overlay)
                            .frame(height: 4)
                    }
                }
                .shadow(color: .black.opacity(0.12), radius: 8, x: 0, y: 4)

                Text(book.title)
                    .font(.caption.weight(.medium))
                    .foregroundStyle(.primary)
                    .lineLimit(2)
                    .frame(width: 110, alignment: .leading)
            }
        }
        .buttonStyle(.plain)
    }

    // MARK: - Loading

    private var loadingView: some View {
        VStack(spacing: 16) {
            ProgressView()
            Text(String(localized: "common.loading"))
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

#Preview {
    NavigationStack {
        ContributorDetailView(contributorId: "preview")
    }
}
