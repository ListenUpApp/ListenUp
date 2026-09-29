import SwiftUI

/// A labelled stat shown on a progress / completion card.
struct ImportStat: Identifiable, Equatable {
    let systemImage: String
    let label: String
    let value: String
    var isMuted: Bool = false

    var id: String { label }
}

// MARK: - Intro

/// The wizard's first screen: a migration badge, the explainer, a numbered "how it works" list,
/// a privacy note, and the "Choose Backup File" action that opens the picker.
struct ImportIntroContent: View {
    let onChooseFile: () -> Void

    /// A grouped `List`: the explainer on the plain background, then the numbered steps as a system
    /// section whose footer carries the privacy note. HIG, Lists and tables.
    var body: some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 18) {
                    badge
                    Text(String(localized: "import.choose_backup_subtitle"))
                        .font(.subheadline)
                        .foregroundStyle(Color.luLabel2)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .listRowBackground(Color.clear)
            }
            Section {
                steps
            } header: {
                Text(String(localized: "import.how_it_works"))
            } footer: {
                privacyNote
            }
        }
        .listStyle(.insetGrouped)
        .readableListWidth()
        // The tray is a bar over the scroll view, whose edge effect the system draws (HIG, Toolbars).
        .safeAreaBar(edge: .bottom) {
            actionTray
        }
    }

    private var badge: some View {
        Label(String(localized: "import.intro_badge"), systemImage: "arrow.triangle.2.circlepath")
            .font(.footnote.weight(.semibold))
            .foregroundStyle(Color.luLabel2)
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(Color.luFill, in: Capsule())
    }

    @ViewBuilder
    private var steps: some View {
        NumberedStepRow(
            number: 1, systemImage: "doc",
            title: String(localized: "import.step_choose_title"),
            subtitle: String(localized: "import.step_choose_subtitle")
        )
        NumberedStepRow(
            number: 2, systemImage: "person",
            title: String(localized: "import.step_match_title"),
            subtitle: String(localized: "import.step_match_subtitle")
        )
        NumberedStepRow(
            number: 3, systemImage: "waveform",
            title: String(localized: "import.step_apply_title"),
            subtitle: String(localized: "import.step_apply_subtitle")
        )
    }

    private var privacyNote: some View {
        Label(String(localized: "import.data_stays_on_server"), systemImage: "lock")
            .font(.footnote)
            .foregroundStyle(Color.luLabel2)
            .frame(maxWidth: .infinity, alignment: .center)
            .padding(.top, 8)
    }

    private var actionTray: some View {
        Button(action: onChooseFile) {
            ActionLabel(title: String(localized: "import.choose_backup_file"), systemImage: "folder")
        }
        .prominentAction()
        .padding(.horizontal, 20)
        .padding(.top, 12)
        .padding(.bottom, 16)
    }
}


// MARK: - Progress (Uploading / Analyzing / Applying)

/// The shared hero-progress screen for the in-flight phases: a centered ``CircularProgressDial``
/// (determinate when totals are known, indeterminate before), a title/subtitle, an optional
/// filename or stats card, and an optional footnote. Honest progress — no fake 0%.
struct ImportProgressContent: View {
    let title: String
    let subtitle: String
    let progress: Double?
    let centerPrimary: String?
    let centerSecondary: String?
    let filename: String?
    let stats: [ImportStat]
    var footnote: String?

    /// A grouped `List`: the dial and headline on the plain background, and any running figures as a
    /// section of system rows beneath. HIG, Lists and tables.
    var body: some View {
        List {
            Section {
                VStack(spacing: 22) {
                    CircularProgressDial(progress: progress) {
                        VStack(spacing: 2) {
                            if let centerPrimary {
                                Text(centerPrimary)
                                    .font(.largeTitle.bold().monospacedDigit())
                                    .lineLimit(1)
                                    .minimumScaleFactor(0.6)
                                    .foregroundStyle(.primary)
                            } else {
                                ProgressView().controlSize(.large)
                            }
                            if let centerSecondary {
                                Text(centerSecondary)
                                    .font(.footnote.monospacedDigit())
                                    .foregroundStyle(Color.luLabel2)
                            }
                        }
                    }
                    .padding(.top, 12)

                    VStack(spacing: 6) {
                        Text(title)
                            .font(.title.weight(.bold))
                            .foregroundStyle(.primary)
                        Text(subtitle)
                            .font(.subheadline)
                            .foregroundStyle(Color.luLabel2)
                            .multilineTextAlignment(.center)
                    }

                    if let filename {
                        MonospacedTechLine(text: filename)
                            .padding(.horizontal, 4)
                    }
                }
                .frame(maxWidth: .infinity)
                .listRowBackground(Color.clear)
            }

            if !stats.isEmpty {
                Section {
                    ForEach(stats) { stat in
                        StatLineRow(systemImage: stat.systemImage, label: stat.label, value: stat.value, isMuted: stat.isMuted)
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .readableListWidth(520)
        // The tray is a bar over the scroll view, whose edge effect the system draws (HIG, Toolbars).
        .safeAreaBar(edge: .bottom) {
            if let footnote {
                Text(footnote)
                    .font(.footnote)
                    .foregroundStyle(Color.luLabel2)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
            }
        }
    }
}
