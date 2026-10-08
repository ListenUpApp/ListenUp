import SwiftUI

/// Every action person Review sends, as closures — so the content can be hosted with no ViewModel (the
/// accessibility harness does) and the observer stays the only thing that talks to Kotlin.
struct PersonReviewActions {
    var setPhotoTicked: (Bool) -> Void = { _ in }
    var choosePhoto: (MatchSourceSelection) -> Void = { _ in }
    var setBiographyTicked: (Bool) -> Void = { _ in }
    var chooseBiographySource: (MatchSourceSelection) -> Void = { _ in }

    @MainActor
    static func observing(_ observer: PersonMatchObserver) -> PersonReviewActions {
        PersonReviewActions(
            setPhotoTicked: { observer.setPhotoTicked($0) },
            choosePhoto: { observer.choosePhoto($0) },
            setBiographyTicked: { observer.setBiographyTicked($0) },
            chooseBiographySource: { observer.chooseBiographySource($0) }
        )
    }
}

/// Review for one person, in whichever phase it is: pushed on iPhone with the Apply bar at the bottom, or
/// the iPad split view's detail, whose Apply Changes sits in the toolbar.
struct PersonReviewScreen: View {
    let observer: PersonMatchObserver
    let contributorId: String
    /// The candidate this screen was opened for, or nil for the iPad detail (whatever is picked).
    let candidateId: String?
    let layout: MatchReviewLayout

    var body: some View {
        content
            .navigationTitle(String(localized: "match.title"))
            .navigationSubtitle(observer.find.subtitle)
            .navigationBarTitleDisplayMode(.inline)
            .modifier(PersonReviewAnnouncements(phase: observer.review, reloadedToken: observer.reloadedToken))
    }

    @ViewBuilder
    private var content: some View {
        switch observer.review {
        case .ready(let review) where candidateId == nil || review.candidateId == candidateId:
            PersonReviewContent(
                review: review,
                contributorId: contributorId,
                yourName: observer.find.name,
                showsReviewReloaded: observer.showsReviewReloaded,
                actions: .observing(observer)
            )
            .safeAreaBar(edge: .bottom) {
                MatchApplyBarView(bar: review.applyBar, showsButton: layout == .phone, onApply: { observer.apply() })
            }
        case .failed(let id, _, let message) where candidateId == nil || id == candidateId:
            ContentUnavailableView {
                Label(String(localized: "match.review_failed_title"), systemImage: "exclamationmark.triangle")
            } description: {
                Text(message)
            } actions: {
                Button(String(localized: "match.try_again")) { observer.retryReview(id) }
                    .buttonStyle(.borderedProminent)
            }
        case .noneChosen where candidateId == nil:
            ContentUnavailableView(
                String(localized: "match.title"),
                systemImage: "sparkle.magnifyingglass",
                description: Text(String(localized: "match.none_chosen"))
            )
        default:
            LoadingStateView(label: String(localized: "match.review_loading"))
        }
    }
}

/// Person Review, in canvas order: the person, What will change, Photo, Biography and the note that the two
/// apply separately. Each section's title is a rotor heading (HIG, VoiceOver).
struct PersonReviewContent: View {
    let review: PersonReview
    /// The contributor whose own photo Keep current shows; nil draws their initials (the harness has no server).
    let contributorId: String?
    /// The contributor's name, for the initials of their own photo.
    let yourName: String
    var showsReviewReloaded = false
    var actions = PersonReviewActions()

    var body: some View {
        List {
            Section {
                PersonReviewHeaderView(header: review.header)
                VStack(alignment: .leading, spacing: Spacing.xxs) {
                    Text(String(localized: "match.what_will_change"))
                        .font(.subheadline.weight(.semibold))
                        .accessibilityAddTraits(.isHeader)
                    Text(review.whatWillChange)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .contentTransition(.numericText())
                }
                .padding(.vertical, Spacing.xxs)
                if showsReviewReloaded {
                    Label(String(localized: "match.review_reloaded_person"), systemImage: "arrow.clockwise")
                        .font(.subheadline)
                }
            }

            if let photo = review.photo {
                Section {
                    PersonPhotoRowView(
                        photo: photo, contributorId: contributorId, yourName: yourName, actions: actions
                    )
                } header: {
                    sectionHeader(String(localized: "match.section_photo"), state: photo.stateTitle)
                }
            }

            if let biography = review.biography {
                Section {
                    if let alreadySame = biography.alreadySame {
                        Text(alreadySame).font(.subheadline).foregroundStyle(.secondary)
                    } else {
                        PersonBiographyRowView(biography: biography, actions: actions)
                    }
                } header: {
                    sectionHeader(String(localized: "match.section_biography"), state: biography.stateTitle)
                } footer: {
                    if review.photo != nil {
                        Text(String(localized: "match.photo_and_biography_apply_separately"))
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .readableListWidth(720)
        .animation(.default, value: review.whatWillChange)
    }

    /// "Photo  Changes" — the state beside the title is part of the heading.
    private func sectionHeader(_ title: String, state: String) -> some View {
        HStack(spacing: Spacing.xs) {
            Text(title)
            Text(state).foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
    }
}

/// The person Review is about: photo, badges and tier, name, role and source, and books in your library.
private struct PersonReviewHeaderView: View {
    let header: PersonReviewHeader
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: Spacing.s))
            : AnyLayout(HStackLayout(alignment: .top, spacing: Spacing.m))
        layout {
            PersonPhoto(url: header.photoURL, name: header.name)
                .frame(width: 72, height: 72)
            VStack(alignment: .leading, spacing: Spacing.xxs) {
                MatchBadges(isBest: header.isBest, isCurrentLink: header.isCurrentLink)
                Text(header.isStrong ? String(localized: "match.strong_match") : String(localized: "match.maybe"))
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(header.isStrong ? Color.luStrongMatch : .secondary)
                Text(header.name).font(.headline)
                Text(header.roleLine).font(.subheadline).foregroundStyle(.secondary)
                Text(header.libraryLine).font(.footnote).foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .accessibilityElement(children: .combine)
    }
}

/// The photo: a tick that is a toggle, Yours → Proposed as two figures, and the source switch with Keep
/// current. The figures stack at the accessibility sizes.
struct PersonPhotoRowView: View {
    let photo: PersonPhotoSection
    let contributorId: String?
    let yourName: String
    let actions: PersonReviewActions

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        HStack(alignment: .top, spacing: Spacing.s) {
            MatchTick(
                isOn: photo.isTicked,
                label: String(localized: "match.change_photo_a11y"),
                onChange: { actions.setPhotoTicked($0) }
            )
            VStack(alignment: .leading, spacing: Spacing.xs) {
                MatchFieldHeading(name: String(localized: "match.section_photo"), proposedFrom: photo.proposedFrom)
                if photo.setByHandNote != nil { MatchEditedFlag() }
                figures
                MatchSourceSwitch(
                    label: String(
                        format: String(localized: "match.source_switch_a11y"), String(localized: "match.section_photo")
                    ),
                    segments: photo.segments,
                    selected: photo.selectedSegment,
                    style: photo.switchStyle,
                    onChoose: { actions.choosePhoto($0) }
                )
                if let note = photo.setByHandNote {
                    Text(note).font(.footnote).foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.vertical, Spacing.xxs)
    }

    private var figures: some View {
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: Spacing.s))
            : AnyLayout(HStackLayout(alignment: .center, spacing: Spacing.m))
        return layout {
            figure(caption: photo.yoursCaption, label: photo.yoursCaption) {
                if let contributorId {
                    ContributorAvatar(
                        name: yourName, imagePath: photo.currentPath, id: contributorId, fontSize: 24,
                        streamsContributorPhoto: photo.currentPath != nil
                    )
                } else {
                    ContributorAvatar(name: yourName, imagePath: nil, id: yourName, fontSize: 24)
                }
            }
            Image(systemName: dynamicTypeSize.isAccessibilitySize ? "arrow.down" : "arrow.right")
                .foregroundStyle(.tertiary)
                .accessibilityHidden(true)
            figure(caption: String(localized: "match.proposed"), label: photo.proposedLabel) {
                PersonPhoto(url: photo.proposedURL, name: yourName)
            }
        }
    }

    private func figure<Picture: View>(
        caption: String, label: String, @ViewBuilder image: () -> Picture
    ) -> some View {
        VStack(spacing: Spacing.xxs) {
            image()
                .frame(width: 72, height: 72)
                .clipShape(Circle())
            Text(caption).font(.caption).foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label)
        .accessibilityAddTraits(.isImage)
    }
}

/// The biography: a tick that is a toggle, Yours → Proposed with Read All, the source switch with Keep yours,
/// and — for one you edited — the flag and whose edit it was.
struct PersonBiographyRowView: View {
    let biography: PersonBiographySection
    let actions: PersonReviewActions

    var body: some View {
        HStack(alignment: .top, spacing: Spacing.s) {
            MatchTick(
                isOn: biography.isTicked,
                label: String(localized: "match.apply_biography_a11y"),
                onChange: { actions.setBiographyTicked($0) }
            )
            VStack(alignment: .leading, spacing: Spacing.xs) {
                MatchFieldHeading(name: biography.values.name, proposedFrom: biography.proposedFrom)
                if biography.isEdited { MatchEditedFlag() }
                MatchValuesView(values: biography.values)
                MatchSourceSwitch(
                    label: String(format: String(localized: "match.source_switch_a11y"), biography.values.name),
                    segments: biography.segments,
                    selected: biography.selectedSegment,
                    style: biography.switchStyle,
                    onChoose: { actions.chooseBiographySource($0) }
                )
                if let note = biography.editedNote {
                    Text(note).font(.footnote).foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.vertical, Spacing.xxs)
    }
}

/// Applying, the reload and a failed Review are spoken, never silent.
private struct PersonReviewAnnouncements: ViewModifier {
    let phase: PersonReviewPhase
    let reloadedToken: Int

    private var applyBar: MatchApplyBar? {
        if case .ready(let review) = phase { return review.applyBar }
        return nil
    }

    func body(content: Content) -> some View {
        content
            .onChange(of: applyBar?.applying ?? false) { _, applying in
                if applying { VoiceOverAnnouncement.post(String(localized: "match.applying")) }
            }
            .onChange(of: applyBar?.error) { _, error in
                if let error { VoiceOverAnnouncement.post(error) }
            }
            .onChange(of: reloadedToken) { _, _ in
                VoiceOverAnnouncement.post(String(localized: "match.review_reloaded_person"))
            }
    }
}
