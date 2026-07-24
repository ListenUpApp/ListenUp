import SwiftUI
import Shared

/// "Find on Audible" sheet for a contributor: search Audible, pick a match, preview which
/// fields (name / bio / photo) to apply, then apply. Mirrors the Android two-screen flow
/// (`ContributorMetadataSearchScreen` → `ContributorMetadataPreviewScreen`) as a native
/// `NavigationStack` push. Responsive (iPhone + iPad) via `readableWidth`.
struct ContributorMetadataView: View {
    let contributorId: String

    @Environment(\.dependencies) private var deps
    @Environment(\.dismiss) private var dismiss
    @State private var observer: ContributorMetadataObserver?
    @State private var path = NavigationPath()

    var body: some View {
        NavigationStack(path: $path) {
            Group {
                if let observer {
                    ContributorMetadataSearchView(observer: observer) { selectedAsin in
                        path.append(selectedAsin)
                    }
                } else {
                    ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
            .navigationDestination(for: String.self) { asin in
                if let observer {
                    ContributorMetadataPreviewView(observer: observer, onApply: { observer.apply() })
                        // Kick the profile fetch on arrival. Without this the view model stays in
                        // its initial state (not loading, no profile, no error) and the preview
                        // renders its fallthrough spinner forever. Mirrors the Compose route,
                        // which drives the same fetch from a LaunchedEffect.
                        .task(id: asin) { observer.selectCandidate(asin) }
                }
            }
            .navigationTitle(String(localized: "contributor.find_on_audible"))
            .navigationBarTitleDisplayMode(.inline)
            .background(Color.luSurface)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(String(localized: "common.cancel")) { dismiss() }
                }
            }
        }
        .task(id: contributorId) {
            let obs = ContributorMetadataObserver(viewModel: deps.createContributorMetadataViewModel())
            observer = obs
            obs.start(contributorId: contributorId)
        }
        .onChange(of: observer?.didApply ?? false) { _, applied in if applied { dismiss() } }
        .onDisappear {
            // Release the observer; its deinit cancels the FlowBridge subscriptions.
            observer = nil
        }
    }
}

// MARK: - Search screen

private struct ContributorMetadataSearchView: View {
    let observer: ContributorMetadataObserver
    let onSelect: (String) -> Void

    @State private var queryDraft: String = ""

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                if !observer.contributorName.isEmpty {
                    Text(String(format: String(localized: "metadata.searching_for"), observer.contributorName))
                        .font(.callout)
                        .foregroundStyle(Color.luLabel2)
                }

                MetadataSearchField(text: $queryDraft) { submit() }

                VStack(alignment: .leading, spacing: 9) {
                    Text(String(localized: "contributor.audible_region"))
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(Color.luLabel2)
                    RegionPicker(
                        options: MetadataRegionOption.all,
                        selection: observer.region,
                        label: \.displayName
                    ) { observer.changeRegion($0) }
                }

                resultsSection
            }
            .padding(.horizontal, 18)
            .padding(.vertical, 16)
            .readableWidth(680)
        }
        .background(Color.luSurface)
        .onAppear { if queryDraft.isEmpty { queryDraft = observer.query } }
    }

    @ViewBuilder
    private var resultsSection: some View {
        if observer.isSearching {
            ProgressView().frame(maxWidth: .infinity).padding(.vertical, 40)
        } else if let error = observer.searchError {
            ContentUnavailableView {
                Label(String(localized: "common.error"), systemImage: "exclamationmark.triangle")
            } description: {
                Text(error)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 24)
        } else if observer.results.isEmpty {
            ContentUnavailableView {
                Label(String(localized: "contributor.find_on_audible"), systemImage: "magnifyingglass")
            } description: {
                Text(String(localized: "contributor.author_or_narrator_name"))
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 24)
        } else {
            FieldGroup(observer.results, separatorInset: 72) { hit in
                ContributorHitRowView(hit: hit) { onSelect(hit.asin) }
            }
        }
    }

    private func submit() {
        observer.updateQuery(queryDraft)
        observer.search()
    }
}

/// One Audible contributor search hit: avatar placeholder + name + chevron. The hit tier carries
/// no image (the full profile loads on selection), so a neutral person glyph stands in.
private struct ContributorHitRowView: View {
    let hit: ContributorHitRow
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 14) {
                ZStack {
                    Circle().fill(Color.luFill)
                    Image(systemName: "person.fill")
                        .font(.system(size: 22))
                        .foregroundStyle(Color.luLabel3)
                }
                .frame(width: 48, height: 48)

                Text(hit.name)
                    .font(.callout.weight(.semibold))
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)

                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(Color.luLabel3)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 11)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressScaleButtonStyle())
    }
}

// MARK: - Preview screen

private struct ContributorMetadataPreviewView: View {
    let observer: ContributorMetadataObserver
    let onApply: () -> Void

    var body: some View {
        Group {
            switch observer.previewPhase {
            case .loading, nil:
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            case .missing:
                // Honest miss, not an error: the catalog has no profile in this region (Audnexus
                // localizes contributor data per region). Offer the region switch inline — the
                // same picker the search screen uses — so the user has an immediate way forward
                // instead of a dead end (Never-Stranded). Mirrors the Compose route's FilterChip row.
                ContentUnavailableView {
                    Label(String(localized: "contributor.find_on_audible"), systemImage: "magnifyingglass")
                } description: {
                    Text(String(
                        format: String(localized: "contributor.no_profile_in_region"),
                        observer.region.displayName
                    ))
                } actions: {
                    RegionPicker(
                        options: MetadataRegionOption.all,
                        selection: observer.region,
                        label: \.displayName
                    ) { observer.changeRegion($0) }
                }
            case .failed(let message):
                ContentUnavailableView {
                    Label(
                        String(localized: "contributor.failed_to_load_profile"),
                        systemImage: "exclamationmark.triangle"
                    )
                } description: {
                    Text(message)
                }
            case .ready:
                if let profile = observer.profile {
                    content(profile: profile)
                }
            }
        }
        .background(Color.luSurface)
        .navigationTitle(String(localized: "contributor.preview_changes"))
        .navigationBarTitleDisplayMode(.inline)
    }

    private func content(profile: ContributorProfilePreview) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                imageComparison(profile: profile)

                ContributorComparisonRow(
                    label: String(localized: "common.name"),
                    currentValue: observer.contributorName,
                    newValue: profile.name,
                    isMultiline: false
                )
                ContributorComparisonRow(
                    label: String(localized: "contributor.biography"),
                    currentValue: observer.currentBio,
                    newValue: profile.bio,
                    isMultiline: true
                )

                metadataDates(profile: profile)
            }
            .padding(.horizontal, 18)
            .padding(.vertical, 16)
            .readableWidth(680)
        }
        .safeAreaInset(edge: .bottom) { applyTray }
    }

    /// Side-by-side current vs. incoming photo. Informational — no toggle; the server keeps the
    /// existing photo when the incoming one is absent.
    private func imageComparison(profile: ContributorProfilePreview) -> some View {
        HStack(spacing: 14) {
            VStack(alignment: .leading, spacing: 10) {
                Text(String(localized: "common.image"))
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)

                HStack(spacing: 16) {
                    labelledImage(
                        title: String(localized: "contributor.current"),
                        content: ContributorAvatar(
                            name: observer.contributorName,
                            imagePath: observer.currentImagePath,
                            id: "",
                            fontSize: 22
                        )
                    )
                    Image(systemName: "arrow.right").foregroundStyle(Color.luLabel3)
                    labelledImage(
                        title: String(localized: "contributor.audible"),
                        content: MetadataRemoteCover(url: profile.imageURL)
                    )
                }
            }
            Spacer(minLength: 0)
        }
        .padding(14)
        .background(Color.luSurface2)
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    private func labelledImage(title: String, content: some View) -> some View {
        VStack(spacing: 4) {
            content
                .frame(width: 72, height: 72)
                .clipShape(Circle())
            Text(title).font(.caption2).foregroundStyle(Color.luLabel3)
        }
    }

    @ViewBuilder
    private func metadataDates(profile: ContributorProfilePreview) -> some View {
        let dates = [profile.birthDate, profile.deathDate].compactMap { $0 }.filter { !$0.isEmpty }
        if !dates.isEmpty {
            VStack(alignment: .leading, spacing: 6) {
                Text(String(localized: "contributor.dates"))
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color.luLabel2)
                if let birth = profile.birthDate, !birth.isEmpty {
                    Text(String(format: String(localized: "contributor.born_year"), String(birth.prefix(4))))
                        .font(.footnote)
                        .foregroundStyle(Color.luLabel3)
                }
                if let death = profile.deathDate, !death.isEmpty {
                    Text("\(String(localized: "contributor.death_date")): \(String(death.prefix(4)))")
                        .font(.footnote)
                        .foregroundStyle(Color.luLabel3)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    @ViewBuilder
    private var applyTray: some View {
        VStack(spacing: 8) {
            Divider()
            if let error = observer.applyError {
                Text(error)
                    .font(.footnote)
                    .foregroundStyle(.red)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
            }
            PrimaryButton(
                title: String(localized: "common.save_changes"),
                icon: "checkmark",
                isLoading: observer.isApplying,
                action: onApply
            )
            .padding(.horizontal, 16)
            .padding(.bottom, 16)
            .disabled(!observer.canApply)
            .opacity(observer.canApply ? 1 : 0.5)
        }
        .background(.bar)
    }
}

/// A name/biography comparison row: the current value and the incoming Audible value, side by
/// side. Informational only — there are no per-field toggles; the server applies asin +
/// biography + photo as a unit (never the name), matching Audiobookshelf's apply contract.
private struct ContributorComparisonRow: View {
    let label: String
    let currentValue: String?
    let newValue: String?
    let isMultiline: Bool

    private var isUnchanged: Bool { currentValue == newValue && !(newValue ?? "").isEmpty }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(label)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)
                Spacer()
                if isUnchanged {
                    Text(String(localized: "contributor.no_change"))
                        .font(.caption2)
                        .foregroundStyle(Color.luLabel3)
                }
            }
            valueLine(title: String(localized: "contributor.current"), value: currentValue, accent: false)
            valueLine(title: String(localized: "contributor.audible"), value: newValue, accent: true)
        }
        .padding(14)
        .background(Color.luSurface2)
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    private func valueLine(title: String, value: String?, accent: Bool) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(.caption2)
                .foregroundStyle(accent ? Color.luTint : Color.luLabel3)
            Text(value?.isEmpty == false ? value! : "—")
                .font(.footnote)
                .foregroundStyle(value?.isEmpty == false ? .primary : Color.luLabel3)
                .lineLimit(isMultiline ? 4 : 2)
        }
    }
}
