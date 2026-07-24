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

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    badge
                    Text(String(localized: "import.choose_backup_subtitle"))
                        .font(.subheadline)
                        .foregroundStyle(Color.luLabel2)
                        .frame(maxWidth: .infinity, alignment: .leading)

                    AdminSectionHeader(String(localized: "import.how_it_works"))
                        .padding(.top, 4)
                    stepsCard
                    privacyNote
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 16)
                .readableWidth(640)
            }
            actionTray
        }
    }

    private var badge: some View {
        Label(String(localized: "import.intro_badge"), systemImage: "arrow.triangle.2.circlepath")
            .font(.footnote.weight(.semibold))
            .foregroundStyle(Color.luTint)
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(Color.luTint.opacity(0.15), in: Capsule())
    }

    private var stepsCard: some View {
        VStack(spacing: 0) {
            NumberedStepRow(
                number: 1, systemImage: "doc",
                title: String(localized: "import.step_choose_title"),
                subtitle: String(localized: "import.step_choose_subtitle")
            )
            separator
            NumberedStepRow(
                number: 2, systemImage: "person",
                title: String(localized: "import.step_match_title"),
                subtitle: String(localized: "import.step_match_subtitle")
            )
            separator
            NumberedStepRow(
                number: 3, systemImage: "waveform",
                title: String(localized: "import.step_apply_title"),
                subtitle: String(localized: "import.step_apply_subtitle")
            )
        }
        .fieldCard()
    }

    private var privacyNote: some View {
        Label(String(localized: "import.data_stays_on_server"), systemImage: "lock")
            .font(.footnote)
            .foregroundStyle(Color.luLabel2)
            .frame(maxWidth: .infinity, alignment: .center)
            .padding(.top, 4)
    }

    private var actionTray: some View {
        PrimaryButton(title: String(localized: "import.choose_backup_file"), icon: "folder", action: onChooseFile)
            .padding(.horizontal, 20)
            .padding(.top, 12)
            .padding(.bottom, 16)
            .background(.bar)
    }

    private var separator: some View {
        Rectangle().fill(Color.luSeparator).frame(height: 0.5).padding(.leading, 57)
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

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(spacing: 22) {
                    CircularProgressDial(progress: progress) {
                        VStack(spacing: 2) {
                            if let centerPrimary {
                                Text(centerPrimary)
                                    .font(.system(size: 34, weight: .bold).monospacedDigit())
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

                    if !stats.isEmpty {
                        statsCard
                    }
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 24)
                .frame(maxWidth: .infinity)
                .readableWidth(520)
            }
            if let footnote {
                Text(footnote)
                    .font(.footnote)
                    .foregroundStyle(Color.luLabel2)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background(.bar)
            }
        }
    }

    private var statsCard: some View {
        VStack(spacing: 0) {
            ForEach(Array(stats.enumerated()), id: \.element.id) { index, stat in
                if index > 0 {
                    Rectangle().fill(Color.luSeparator).frame(height: 0.5).padding(.leading, 57)
                }
                StatLineRow(systemImage: stat.systemImage, label: stat.label, value: stat.value, isMuted: stat.isMuted)
            }
        }
        .fieldCard()
    }
}
