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
    @State private var observer: ContributorDetailObserver?
    @State private var showEdit = false
    /// Set to open person Match details for the contributor on screen.
    @State private var matchTarget: PersonMatchTarget?
    /// Set when an edit-sheet merge re-targets this screen; from then on it shows the survivor
    /// instead. (A rename-collision merge soft-deletes the contributor we were opened with; an
    /// alias merge survives in place, so the re-target is a same-id refresh.)
    @State private var mergedIntoContributorId: String?
    /// Set to open Move and Scale on the contributor's photo, from the menu.
    @State private var adjustTarget: AdjustPhotoTarget?
    /// Choose Another was tapped in Move and Scale: open Edit once the editor has gone (R6).
    @State private var editsAfterAdjust = false
    /// Bumped on each Save from Move and Scale, to show "Photo adjusted." for a moment.
    @State private var photoAdjustedCount = 0
    @State private var showsPhotoAdjusted = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// The contributor actually on screen — the survivor after a merge, otherwise the one we were
    /// opened with. Re-targeting in place is deliberate: pushing the survivor would leave the
    /// deleted contributor in the back stack for the reader to return to, and popping to the
    /// library would lose their place. `.task(id:)` reloads when this changes.
    private var activeContributorId: String { mergedIntoContributorId ?? contributorId }

    var body: some View {
        Group {
            if let observer, observer.notFound {
                notFoundView
            } else if let observer, !observer.isLoading {
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
                // Edit/merge/delete make no sense on a row that no longer exists, and each action shows
                // only to those allowed it: edit and match need Edit metadata, delete needs Curate library.
                // (`== false` keeps `observer` optional so the body's chaining stays valid.)
                if observer?.notFound == false,
                   observer?.canEditMetadata == true || observer?.canCurateLibrary == true {
                    Menu {
                        if observer?.canEditMetadata == true {
                            Button {
                                showEdit = true
                            } label: {
                                Label(String(localized: "common.edit"), systemImage: "pencil")
                            }
                            Button {
                                matchTarget = PersonMatchTarget(contributorId: activeContributorId)
                            } label: {
                                Label(String(localized: "match.menu_item"), systemImage: "sparkles")
                            }
                        }
                        if observer?.canAdjustPhoto == true {
                            Button {
                                adjustTarget = AdjustPhotoTarget(
                                    contributorId: activeContributorId,
                                    imagePath: observer?.imagePath,
                                    frame: observer?.imageFrame
                                )
                            } label: {
                                Label(String(localized: "edit.adjust_photo_ios"), systemImage: "crop")
                            }
                        }
                        if observer?.canCurateLibrary == true {
                            Button(role: .destructive, action: {
                                observer?.onDeleteContributor()
                            }) {
                                Label(String(localized: "common.delete"), systemImage: "trash")
                            }
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
            ContributorEditView(
                contributorId: activeContributorId,
                onMergedInto: { survivor in mergedIntoContributorId = survivor }
            )
        }
        // Match details: pushed on a compact width, a full-screen split view on a regular one. A merge made
        // from its Edit by Hand re-targets this screen, as one made from Edit does.
        .personMatchDetails($matchTarget, onMergedInto: { survivor in mergedIntoContributorId = survivor })
        // The receipt Match details leaves behind, with Undo.
        .matchReceipt(contributorId: activeContributorId, name: observer?.name ?? "")
        .frameEditor(item: $adjustTarget, onDismiss: editIfAsked) { target in
            FetchedPhotoFrameEditor(
                fetch: {
                    await FramePhoto.contributor(contributorId: target.contributorId, imagePath: target.imagePath)
                },
                initial: target.frame,
                confirm: .save,
                onConfirm: { frame in
                    observer?.adjustPhoto(frame)
                    adjustTarget = nil
                    photoAdjustedCount += 1
                },
                onCancel: { adjustTarget = nil },
                onChooseAnother: {
                    // A saved photo that won't open is replaced from Edit, where a new one is picked (R6).
                    editsAfterAdjust = true
                    adjustTarget = nil
                }
            )
        }
        .safeAreaInset(edge: .bottom) {
            if showsPhotoAdjusted {
                PhotoAdjustedCapsule()
                    .padding(.bottom, Spacing.xs)
                    .transition(reduceMotion ? .opacity : .move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(reduceMotion ? .easeInOut(duration: 0.2) : .spring(duration: 0.35), value: showsPhotoAdjusted)
        .haptic(.commit, trigger: photoAdjustedCount)
        .task(id: photoAdjustedCount) {
            guard photoAdjustedCount > 0 else { return }
            VoiceOverAnnouncement.post(String(localized: "edit.frame_saved"))
            showsPhotoAdjusted = true
            try? await Task.sleep(for: .seconds(2))
            guard !Task.isCancelled else { return }
            showsPhotoAdjusted = false
        }
        .task(id: activeContributorId) {
            let vm = deps.createContributorDetailViewModel()
            let obs = ContributorDetailObserver(viewModel: vm)
            observer = obs
            obs.loadContributor(contributorId: activeContributorId)
        }
        .onDisappear {
            // Release the observer; its deinit cancels the FlowBridge subscriptions.
            observer = nil
        }
    }

    /// Opens Edit after Move and Scale has gone, when Choose Another asked for it.
    private func editIfAsked() {
        guard editsAfterAdjust else { return }
        editsAfterAdjust = false
        showEdit = true
    }

    // MARK: - Content

    @ViewBuilder
    private func content(observer: ContributorDetailObserver) -> some View {
        DetailColumnsReader { columns in
            switch columns {
            case .split(let railWidth): iPadLayout(observer: observer, railWidth: railWidth)
            case .stacked: iPhoneLayout(observer: observer)
            }
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
            .padding(.bottom, Spacing.xxl)
        }
    }

    // MARK: - Wide (two-column)

    /// The rail scrolls on its own beside the role sections, and is sized from the width.
    private func iPadLayout(observer: ContributorDetailObserver, railWidth: CGFloat) -> some View {
        HStack(alignment: .top, spacing: 0) {
            ScrollView {
                VStack(spacing: 24) {
                    heroSection(observer: observer)
                    statSection(observer: observer)
                    aboutSection(observer: observer)
                }
                .padding(.bottom, Spacing.xxl)
                .padding(.horizontal)
            }
            .frame(width: railWidth)

            Divider()

            ScrollView {
                VStack(spacing: 24) {
                    roleSections(observer: observer)
                    seriesSection(observer: observer)
                }
                .padding(.bottom, Spacing.xxl)
            }
        }
    }

    // MARK: - Hero

    private func heroSection(observer: ContributorDetailObserver) -> some View {
        VStack(spacing: 6) {
            ContributorAvatar(
                name: observer.name,
                imagePath: observer.imagePath,
                id: activeContributorId,
                fontSize: 40,
                streamsContributorPhoto: true,
                frame: observer.imageFrame
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
                .padding(.top, Spacing.xxs)
            }

            Text(observer.name)
                .font(.title.bold())
                .multilineTextAlignment(.center)

            if !observer.aliases.isEmpty {
                Text("aka \(observer.aliases.joined(separator: ", "))")
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }

            if let lifeDates = lifeDatesText(birth: observer.birthDate, death: observer.deathDate) {
                Text(lifeDates)
                    .font(.footnote)
                    .foregroundStyle(.tertiary)
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
        .padding(.top, Spacing.m)
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

    /// ISO `yyyy-MM-dd` → a long localized date ("September 21, 1947"); falls back to the raw
    /// value (e.g. a bare "1947") when it isn't a full ISO date.
    private func displayDate(_ raw: String) -> String? {
        let trimmed = raw.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return nil }
        guard let date = ISODate.parse(trimmed) else { return trimmed }
        return date.formatted(.dateTime.month(.wide).day().year())
    }

    // MARK: - Stat Strip

    private func statSection(observer: ContributorDetailObserver) -> some View {
        StatStrip(stats: [
            .init(value: "\(observer.bookCount)", label: String(localized: "contributor.stat_books")),
            .init(value: observer.totalDuration, label: String(localized: "contributor.stat_hours"))
        ])
        .padding(.vertical, Spacing.l)
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
                roleHeader(section, contributorName: observer.name)
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
                            .bookContextMenu(bookId: book.id, selection: nil) {
                                WrittenCard(book: book, progress: observer.bookProgress[book.id])
                            }
                        }
                    }
                    .padding(.horizontal)
                }
            }
        }
    }

    /// Role carousel header: the role title and, when the carousel is truncated, a "See all" link
    /// to the full `ContributorBooksView` for this contributor+role. Mirrors `SectionRow`'s look but
    /// uses a `NavigationLink` (value-typed push) rather than `SectionRow`'s imperative action slot.
    @ViewBuilder
    private func roleHeader(_ section: RoleSectionRow, contributorName: String) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text(section.displayName)
                .font(.title2.bold())
                .accessibilityAddTraits(.isHeader)
                .foregroundStyle(.primary)
            Spacer()
            if section.showViewAll {
                NavigationLink(value: ContributorBooksDestination(
                    contributorId: activeContributorId,
                    role: section.role,
                    contributorName: contributorName,
                    roleDisplayName: section.displayName
                )) {
                    HStack(spacing: 2) {
                        Text(String(localized: "common.see_all"))
                        Image(systemName: "chevron.right").font(.caption.weight(.semibold))
                    }
                    .font(.subheadline)
                    .foregroundStyle(Color.luTint)
                }
                .buttonStyle(.plain)
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
                    // SeriesMiniCard is a full-width row card (cover stack + title + chevron), so a
                    // 165pt minimum packed 2 columns onto a phone and crushed the title to 2-3 chars.
                    // A 320pt minimum yields one column on every phone width and readable multi-column
                    // on iPad — width-driven, no size-class fork.
                    columns: [GridItem(.adaptive(minimum: 320), spacing: 12)],
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

    /// Terminal state for a contributor with no live local row — merged into another
    /// contributor or genuinely missing. The navigation bar's back button is the way out.
    private var notFoundView: some View {
        ContentUnavailableView {
            Label(String(localized: "contributor.no_longer_here"), systemImage: "person.slash")
        } description: {
            Text(String(localized: "contributor.no_longer_here_detail"))
        }
    }
}

/// Move and Scale opened from the contributor menu, on the photo shown when it was opened.
private struct AdjustPhotoTarget: Identifiable {
    let contributorId: String
    let imagePath: String?
    let frame: ImageFrameValue?
    var id: String { contributorId }
}

/// The moment's confirmation that a frame was saved from the menu: "Photo adjusted." on floating glass.
private struct PhotoAdjustedCapsule: View {
    var body: some View {
        Label(String(localized: "edit.frame_saved"), systemImage: "checkmark.circle.fill")
            .font(.subheadline.weight(.semibold))
            .padding(.horizontal, Spacing.m)
            .padding(.vertical, Spacing.s)
            .glassControl(in: Capsule())
            .accessibilityHidden(true)
    }
}

#Preview {
    NavigationStack {
        ContributorDetailView(contributorId: "preview")
    }
}
