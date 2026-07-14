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
            .navigationDestination(for: String.self) { _ in
                if let observer {
                    ContributorMetadataPreviewView(observer: observer, onApply: { observer.apply() })
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
            if observer.isLoadingPreview {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if let error = observer.previewError {
                ContentUnavailableView {
                    Label(
                        String(localized: "contributor.failed_to_load_profile"),
                        systemImage: "exclamationmark.triangle"
                    )
                } description: {
                    Text(error)
                }
            } else if let profile = observer.profile {
                content(profile: profile)
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
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

                ForEach(observer.fieldComparisons.filter { $0.field != .image }) { comparison in
                    ContributorComparisonRow(comparison: comparison, isMultiline: comparison.field == .biography) {
                        observer.toggleField(comparison.field)
                    }
                }

                metadataDates(profile: profile)
            }
            .padding(.horizontal, 18)
            .padding(.vertical, 16)
            .readableWidth(680)
        }
        .safeAreaInset(edge: .bottom) { applyTray }
    }

    @ViewBuilder
    private func imageComparison(profile: ContributorProfilePreview) -> some View {
        if let imageComparison = observer.fieldComparisons.first(where: { $0.field == .image }) {
            Button {
                observer.toggleField(.image)
            } label: {
                HStack(spacing: 14) {
                    Image(systemName: imageComparison.isSelected ? "checkmark.circle.fill" : "circle")
                        .font(.title3)
                        .foregroundStyle(imageComparison.isSelected ? Color.luTint : Color.luLabel3)

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
                                    blurHash: nil,
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
                .contentShape(Rectangle())
            }
            .buttonStyle(PressScaleButtonStyle())
        }
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
            .disabled(!observer.hasSelectedFields)
            .opacity(observer.hasSelectedFields ? 1 : 0.5)
        }
        .background(.bar)
    }
}

/// A name/biography comparison row: a coral check toggle, the current value, and the incoming
/// Audible value. The toggle disables when Audible returned nothing for the field.
private struct ContributorComparisonRow: View {
    let comparison: ContributorFieldComparison
    let isMultiline: Bool
    let onToggle: () -> Void

    private var isUnchanged: Bool { comparison.currentValue == comparison.newValue }

    var body: some View {
        Button(action: onToggle) {
            HStack(alignment: .top, spacing: 14) {
                Image(systemName: comparison.isSelected ? "checkmark.circle.fill" : "circle")
                    .font(.title3)
                    .foregroundStyle(comparison.isSelected ? Color.luTint : Color.luLabel3)

                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Text(comparison.label)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(.primary)
                        Spacer()
                        if isUnchanged, comparison.hasNewValue {
                            Text(String(localized: "contributor.no_change"))
                                .font(.caption2)
                                .foregroundStyle(Color.luLabel3)
                        }
                    }
                    valueLine(
                        title: String(localized: "contributor.current"),
                        value: comparison.currentValue,
                        accent: false
                    )
                    valueLine(
                        title: String(localized: "contributor.audible"),
                        value: comparison.newValue,
                        accent: true
                    )
                }
            }
            .padding(14)
            .background(Color.luSurface2)
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .contentShape(Rectangle())
        }
        .buttonStyle(PressScaleButtonStyle())
        .disabled(!comparison.hasNewValue || isUnchanged)
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
