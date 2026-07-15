import SwiftUI
import Shared

/// Contributor (Author/Narrator) detail screen — clean-coral design.
///
/// Layout (iPhone): hero (avatar + role chips + name + aka + born) → stat strip →
/// About → per-role book carousels → series grid.
/// Layout (iPad): left rail (hero + stat strip + About) | right column (carousels + series).
struct ContributorDetailView: View {
    let contributorId: String

    @Environment(\.dependencies) private var deps
    @Environment(\.dismiss) private var dismiss
    @Environment(\.horizontalSizeClass) private var hSize
    @State private var observer: ContributorDetailObserver?
    @State private var showEdit = false
    @State private var showFindOnAudible = false

    private var isRegular: Bool { hSize == .regular }

    var body: some View {
        Group {
            if let observer, !observer.isLoading {
                content(observer: observer)
            } else {
                loadingView
            }
        }
        .background(Color.luSurface)
        .navigationTitle(observer?.name ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                if observer != nil {
                    Menu {
                        Button {
                            showEdit = true
                        } label: {
                            Label(String(localized: "common.edit"), systemImage: "pencil")
                        }
                        Button {
                            showFindOnAudible = true
                        } label: {
                            Label(
                                String(localized: "contributor.find_on_audible"),
                                systemImage: "sparkle.magnifyingglass"
                            )
                        }
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
        .sheet(isPresented: $showEdit) {
            ContributorEditView(contributorId: contributorId)
        }
        .sheet(isPresented: $showFindOnAudible) {
            ContributorMetadataView(contributorId: contributorId)
        }
        .task(id: contributorId) {
            let vm = deps.createContributorDetailViewModel()
            let obs = ContributorDetailObserver(viewModel: vm)
            observer = obs
            obs.loadContributor(contributorId: contributorId)
        }
        .onDisappear {
            // Release the observer; its deinit cancels the FlowBridge subscriptions.
            observer = nil
        }
    }

    // MARK: - Content

    @ViewBuilder
    private func content(observer: ContributorDetailObserver) -> some View {
        if isRegular {
            iPadLayout(observer: observer)
        } else {
            iPhoneLayout(observer: observer)
        }
    }

    // MARK: - iPhone (single-column)

    private func iPhoneLayout(observer: ContributorDetailObserver) -> some View {
        ScrollView {
            VStack(spacing: 24) {
                heroSection(observer: observer)
                statSection(observer: observer)
                aboutSection(observer: observer)
                roleSections(observer: observer)
                seriesSection(observer: observer)
            }
            .padding(.bottom, 32)
        }
    }

    // MARK: - iPad (two-column)

    private func iPadLayout(observer: ContributorDetailObserver) -> some View {
        HStack(alignment: .top, spacing: 0) {
            ScrollView {
                VStack(spacing: 24) {
                    heroSection(observer: observer)
                    statSection(observer: observer)
                    aboutSection(observer: observer)
                }
                .padding(.bottom, 32)
                .padding(.horizontal)
            }
            .frame(width: 320)

            Divider()

            ScrollView {
                VStack(spacing: 24) {
                    roleSections(observer: observer)
                    seriesSection(observer: observer)
                }
                .padding(.bottom, 32)
            }
        }
    }

    // MARK: - Hero

    private func heroSection(observer: ContributorDetailObserver) -> some View {
        VStack(spacing: 6) {
            ContributorAvatar(
                name: observer.name,
                imagePath: observer.imagePath,
                id: contributorId,
                fontSize: 40,
                streamsContributorPhoto: true
            )
            .frame(width: 132, height: 132)
            .clipShape(Circle())
            .shadow(color: .black.opacity(0.15), radius: 12, x: 0, y: 6)

            if !observer.roles.isEmpty {
                HStack(spacing: 8) {
                    ForEach(Array(observer.roles.enumerated()), id: \.offset) { _, kind in
                        RoleChip(kind: kind)
                    }
                }
                .padding(.top, 4)
            }

            Text(observer.name)
                .font(.title.bold())
                .multilineTextAlignment(.center)

            if !observer.aliases.isEmpty {
                Text("aka \(observer.aliases.joined(separator: ", "))")
                    .font(.callout)
                    .foregroundStyle(Color.luLabel2)
                    .multilineTextAlignment(.center)
            }

            if let lifeDates = lifeDatesText(birth: observer.birthDate, death: observer.deathDate) {
                Text(lifeDates)
                    .font(.footnote)
                    .foregroundStyle(Color.luLabel3)
            }

            if let website = observer.website?.trimmingCharacters(in: .whitespaces),
               !website.isEmpty, let url = URL(string: website) {
                Link(destination: url) {
                    Label(website, systemImage: "globe")
                        .font(.footnote.weight(.medium))
                        .foregroundStyle(Color.luTint)
                        .lineLimit(1)
                        .truncationMode(.middle)
                }
                .padding(.top, 2)
            }
        }
        .padding(.horizontal)
        .padding(.top, 16)
    }

    // MARK: - Life dates

    /// "Born September 21, 1947" · "Died January 15, 2024" · "September 21, 1947 – January 15, 2024",
    /// or nil when neither date is present. Mirrors Android's `formatLifeDates`.
    private func lifeDatesText(birth: String?, death: String?) -> String? {
        let born = birth.flatMap(displayDate)
        let died = death.flatMap(displayDate)
        switch (born, died) {
        case let (born?, died?):
            return String(format: String(localized: "contributor.life_span"), born, died)
        case let (born?, nil):
            return String(format: String(localized: "contributor.born"), born)
        case let (nil, died?):
            return String(format: String(localized: "contributor.died"), died)
        default:
            return nil
        }
    }

    /// Parses the ISO `yyyy-MM-dd` birth/death strings. Pure and immutable once configured, so it's
    /// shared as a `static let` rather than rebuilt on every `displayDate(_:)` call from the hero body.
    private static let isoDateParser: DateFormatter = {
        let parser = DateFormatter()
        parser.calendar = Calendar(identifier: .gregorian)
        parser.locale = Locale(identifier: "en_US_POSIX")
        parser.dateFormat = "yyyy-MM-dd"
        return parser
    }()

    /// ISO `yyyy-MM-dd` → a long localized date ("September 21, 1947"); falls back to the raw
    /// value (e.g. a bare "1947") when it isn't a full ISO date.
    private func displayDate(_ raw: String) -> String? {
        let trimmed = raw.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return nil }
        guard let date = Self.isoDateParser.date(from: trimmed) else { return trimmed }
        return date.formatted(.dateTime.month(.wide).day().year())
    }

    // MARK: - Stat Strip

    private func statSection(observer: ContributorDetailObserver) -> some View {
        StatStrip(stats: [
            .init(value: "\(observer.bookCount)", label: String(localized: "contributor.stat_books")),
            .init(value: observer.totalDuration, label: String(localized: "contributor.stat_hours"))
        ])
        .padding(.vertical, 20)
    }

    // MARK: - About

    @ViewBuilder
    private func aboutSection(observer: ContributorDetailObserver) -> some View {
        if let bio = observer.bio, !bio.isEmpty {
            ExpandableText(
                title: String(localized: "common.about"),
                text: bio,
                lineLimit: 4,
                minimumLengthForToggle: 200
            )
            .padding(.horizontal)
        }
    }

    // MARK: - Role Carousels

    @ViewBuilder
    private func roleSections(observer: ContributorDetailObserver) -> some View {
        ForEach(observer.roleSections, id: \.role) { section in
            VStack(alignment: .leading, spacing: 12) {
                SectionRow(title: section.displayName)
                    .padding(.horizontal)

                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(spacing: 16) {
                        ForEach(section.books) { book in
                            NavigationLink(value: BookDestination(id: book.id)) {
                                WrittenCard(
                                    book: book,
                                    progress: observer.bookProgress[book.id]
                                )
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal)
                }
            }
        }
    }

    // MARK: - Series Grid

    @ViewBuilder
    private func seriesSection(observer: ContributorDetailObserver) -> some View {
        if !observer.series.isEmpty {
            VStack(alignment: .leading, spacing: 12) {
                SectionRow(title: String(localized: "contributor.series_section"))
                    .padding(.horizontal)

                LazyVGrid(
                    columns: [GridItem(.adaptive(minimum: 165), spacing: 12)],
                    spacing: 12
                ) {
                    ForEach(observer.series) { series in
                        SeriesMiniCard(series: series)
                    }
                }
                .padding(.horizontal)
            }
        }
    }

    // MARK: - Loading

    private var loadingView: some View {
        LoadingStateView()
    }
}

#Preview {
    NavigationStack {
        ContributorDetailView(contributorId: "preview")
    }
}
