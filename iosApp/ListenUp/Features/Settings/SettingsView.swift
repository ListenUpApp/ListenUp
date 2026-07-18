import SwiftUI
import Shared

/// Settings screen — the Cupertino counterpart to the Android M3 Expressive settings.
///
/// A grouped `Form` of tinted SF-icon rows backed by the shared `SettingsViewModel`
/// (via `SettingsObserver`): an account header, then Appearance / Playback / Sleep
/// Timer / Library / Downloads / Account / About sections, each row bound to a VM
/// field so changes reflect and persist through the shared store. Sign Out is a
/// destructive action behind a confirmation.
///
/// Only settings the VM actually exposes are shown. The Downloads section links to the
/// `StorageView` (usage + per-book delete + clear-all, backed by `StorageViewModel`); the
/// mockup's Now Playing wallpaper row is intentionally omitted — it has no VM backing yet.
/// The Administration row is shown only
/// to admin / root users (`User.isAdmin`) and pushes `AdminView`. The Devices row
/// is now present and pushes `DevicesView`.
struct SettingsView: View {
    @Environment(\.dependencies) private var deps
    @Environment(CurrentUserObserver.self) private var currentUser

    @State private var observer: SettingsObserver?
    @State private var showingSignOutConfirmation = false

    var body: some View {
        Form {
            if let observer {
                accountSection(observer)
                if currentUser.user?.isAdmin == true {
                    administrationSection()
                }
                appearanceSection(observer)
                playbackSection(observer)
                sleepTimerSection(observer)
                librarySection(observer)
                downloadsSection(observer)
                accountInfoSection(observer)
                aboutSection(observer)
                signOutSection(observer)
            }
        }
        .navigationTitle(String(localized: "common.settings"))
        .navigationBarTitleDisplayMode(.large)
        .readableWidth(720)
        .miniPlayerBottomInset()
        .onAppear {
            if observer == nil {
                observer = SettingsObserver(
                    viewModel: deps.createSettingsViewModel(),
                    stopPlayback: { await deps.playerCoordinator.stop() }
                )
            }
        }
        .confirmationDialog(
            String(localized: "settings.sign_out_confirm_title"),
            isPresented: $showingSignOutConfirmation,
            titleVisibility: .visible
        ) {
            Button(String(localized: "common.sign_out"), role: .destructive) {
                Task { await observer?.signOut() }
            }
            Button(String(localized: "common.cancel"), role: .cancel) {}
        } message: {
            Text(String(localized: "settings.are_you_sure_you_want"))
        }
    }

    // MARK: - Account header

    @ViewBuilder
    private func accountSection(_ observer: SettingsObserver) -> some View {
        Section {
            HStack(spacing: 14) {
                UserAvatarView(user: currentUser.user, size: 56)
                VStack(alignment: .leading, spacing: 2) {
                    Text(currentUser.user?.displayName ?? String(localized: "common.account"))
                        .font(.title3.weight(.semibold))
                        .foregroundStyle(.primary)
                    if let email = currentUser.user?.email {
                        Text(email)
                            .font(.footnote)
                            .foregroundStyle(Color.luLabel2)
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(.vertical, 4)
        }
    }

    // MARK: - Administration (admin / root only)

    @ViewBuilder
    private func administrationSection() -> some View {
        Section {
            NavigationLink(value: AdminDestination()) {
                SettingsLabel(
                    title: String(localized: "common.administration"),
                    subtitle: String(localized: "admin.server_settings"),
                    systemImage: "shield.lefthalf.filled",
                    tint: .luTint
                )
            }
        }
    }

    // MARK: - Appearance

    @ViewBuilder
    private func appearanceSection(_ observer: SettingsObserver) -> some View {
        Section(String(localized: "settings.appearance")) {
            Picker(selection: themeBinding(observer)) {
                Text(String(localized: "settings.theme_automatic")).tag(ThemeMode.system)
                Text(String(localized: "settings.theme_light")).tag(ThemeMode.light)
                Text(String(localized: "settings.theme_dark")).tag(ThemeMode.dark)
            } label: {
                SettingsLabel(title: String(localized: "settings.appearance"), systemImage: "moon.fill", tint: .indigo)
            }
            .haptic(.selectionTick, trigger: observer.themeMode)
            // No "Dynamic Colors" toggle on iOS — Material You dynamic color is Android-only and has
            // no effect here. The shared preference stays for Android (see SettingsScreen.showDynamicColors).
        }
    }

    // MARK: - Playback

    @ViewBuilder
    private func playbackSection(_ observer: SettingsObserver) -> some View {
        Section(String(localized: "settings.playback")) {
            Picker(selection: speedBinding(observer)) {
                ForEach(Self.speedOptions, id: \.self) { speed in
                    Text(SettingsFormat.speedLabel(speed)).tag(speed)
                }
            } label: {
                SettingsLabel(
                    title: String(localized: "settings.default_speed"),
                    systemImage: "slider.horizontal.3",
                    tint: .luTint
                )
            }
            .haptic(.selectionTick, trigger: observer.defaultPlaybackSpeed)

            Picker(selection: skipForwardBinding(observer)) {
                ForEach(Self.skipOptions, id: \.self) { sec in
                    Text(SettingsFormat.skipLabel(seconds: sec)).tag(sec)
                }
            } label: {
                SettingsLabel(
                    title: String(localized: "settings.skip_forward"),
                    systemImage: "goforward.30",
                    tint: .luTint
                )
            }
            .haptic(.selectionTick, trigger: observer.defaultSkipForwardSec)

            Picker(selection: skipBackwardBinding(observer)) {
                ForEach(Self.skipOptions, id: \.self) { sec in
                    Text(SettingsFormat.skipLabel(seconds: sec)).tag(sec)
                }
            } label: {
                SettingsLabel(
                    title: String(localized: "settings.skip_backward"),
                    systemImage: "gobackward.10",
                    tint: .luTint
                )
            }
            .haptic(.selectionTick, trigger: observer.defaultSkipBackwardSec)

            Toggle(isOn: boolBinding(observer.autoRewindEnabled, observer.setAutoRewindEnabled)) {
                SettingsLabel(
                    title: String(localized: "settings.autorewind_on_resume"),
                    systemImage: "clock.arrow.circlepath",
                    tint: .luTint
                )
            }
            .haptic(.toggleOn, trigger: observer.autoRewindEnabled)

        }
    }

    // MARK: - Sleep Timer

    @ViewBuilder
    private func sleepTimerSection(_ observer: SettingsObserver) -> some View {
        Section(String(localized: "settings.sleep_timer")) {
            Picker(selection: sleepTimerBinding(observer)) {
                Text(String(localized: "settings.timer_off")).tag(Int?.none)
                ForEach(Self.sleepTimerOptions, id: \.self) { minutes in
                    Text(SettingsFormat.sleepTimerLabel(minutes: minutes, offLabel: ""))
                        .tag(Int?.some(minutes))
                }
            } label: {
                SettingsLabel(
                    title: String(localized: "settings.default_timer"),
                    systemImage: "moon.zzz.fill",
                    tint: .purple
                )
            }
            .haptic(.selectionTick, trigger: observer.defaultSleepTimerMin)
        }
    }

    // MARK: - Library

    @ViewBuilder
    private func librarySection(_ observer: SettingsObserver) -> some View {
        Section(String(localized: "settings.library")) {
            Toggle(isOn: boolBinding(observer.ignoreTitleArticles, observer.setIgnoreTitleArticles)) {
                SettingsLabel(
                    title: String(localized: "settings.ignore_articles_when_sorting"),
                    subtitle: String(localized: "settings.sort_ignoring_leading_articles_a"),
                    systemImage: "textformat.abc",
                    tint: .blue
                )
            }
            .haptic(.toggleOn, trigger: observer.ignoreTitleArticles)

            Toggle(isOn: boolBinding(observer.hideSingleBookSeries, observer.setHideSingleBookSeries)) {
                SettingsLabel(
                    title: String(localized: "settings.hide_singlebook_series"),
                    subtitle: String(localized: "settings.hide_series_with_only_one"),
                    systemImage: "square.stack.3d.up",
                    tint: .blue
                )
            }
            .haptic(.toggleOn, trigger: observer.hideSingleBookSeries)
        }
    }

    // MARK: - Downloads

    @ViewBuilder
    private func downloadsSection(_ observer: SettingsObserver) -> some View {
        Section(String(localized: "settings.downloads")) {
            NavigationLink(value: StorageDestination()) {
                SettingsLabel(
                    title: String(localized: "settings.manage_storage"),
                    subtitle: String(localized: "settings.view_and_manage_downloaded_audiobooks"),
                    systemImage: "internaldrive",
                    tint: .green
                )
            }

            Toggle(isOn: boolBinding(observer.wifiOnlyDownloads, observer.setWifiOnlyDownloads)) {
                SettingsLabel(
                    title: String(localized: "settings.wifi_only_downloads"),
                    systemImage: "wifi",
                    tint: .green
                )
            }
            .haptic(.toggleOn, trigger: observer.wifiOnlyDownloads)
        }
    }

    // MARK: - Account (server info)

    @ViewBuilder
    private func accountInfoSection(_ observer: SettingsObserver) -> some View {
        Section(String(localized: "common.account")) {
            NavigationLink(value: DevicesDestination()) {
                SettingsLabel(
                    title: String(localized: "settings.devices"),
                    subtitle: String(localized: "settings.manage_active_sessions"),
                    systemImage: "iphone.gen3",
                    tint: .blue
                )
            }

            LabeledContent {
                Text(observer.serverUrl ?? "—")
                    .font(.callout.monospaced())
                    .foregroundStyle(Color.luLabel2)
            } label: {
                SettingsLabel(title: String(localized: "common.server"), systemImage: "globe", tint: .teal)
            }

            Toggle(isOn: boolBinding(observer.hapticFeedbackEnabled, observer.setHapticFeedbackEnabled)) {
                SettingsLabel(
                    title: String(localized: "settings.haptic_feedback"),
                    systemImage: "hand.tap",
                    tint: .teal
                )
            }
            .haptic(.toggleOn, trigger: observer.hapticFeedbackEnabled)
        }
    }

    // MARK: - About

    @ViewBuilder
    private func aboutSection(_ observer: SettingsObserver) -> some View {
        Section(String(localized: "common.about")) {
            LabeledContent {
                Text(observer.serverVersion ?? "—")
                    .foregroundStyle(Color.luLabel2)
            } label: {
                SettingsLabel(title: String(localized: "common.version"), systemImage: "star.fill", tint: .gray)
            }

            NavigationLink(value: LicensesDestination()) {
                SettingsLabel(
                    title: String(localized: "settings.open_source_licenses"),
                    systemImage: "doc.text",
                    tint: .green
                )
            }
        }
    }

    // MARK: - Sign Out

    @ViewBuilder
    private func signOutSection(_ observer: SettingsObserver) -> some View {
        Section {
            Button(role: .destructive) {
                showingSignOutConfirmation = true
            } label: {
                Text(String(localized: "common.sign_out"))
                    .frame(maxWidth: .infinity)
            }
        }
    }

    // MARK: - Picker / toggle option sets

    private static let speedOptions: [Float] = [0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0]
    private static let skipOptions: [Int] = [5, 10, 15, 30, 45, 60]
    private static let sleepTimerOptions: [Int] = [5, 10, 15, 30, 45, 60]

    // MARK: - Bindings (read flat state, write through the observer's forwarders)

    private func themeBinding(_ observer: SettingsObserver) -> Binding<ThemeMode> {
        Binding(get: { observer.themeMode }, set: { observer.setThemeMode($0) })
    }

    private func speedBinding(_ observer: SettingsObserver) -> Binding<Float> {
        Binding(get: { observer.defaultPlaybackSpeed }, set: { observer.setDefaultPlaybackSpeed($0) })
    }

    private func skipForwardBinding(_ observer: SettingsObserver) -> Binding<Int> {
        Binding(get: { observer.defaultSkipForwardSec }, set: { observer.setDefaultSkipForwardSec($0) })
    }

    private func skipBackwardBinding(_ observer: SettingsObserver) -> Binding<Int> {
        Binding(get: { observer.defaultSkipBackwardSec }, set: { observer.setDefaultSkipBackwardSec($0) })
    }

    private func sleepTimerBinding(_ observer: SettingsObserver) -> Binding<Int?> {
        Binding(get: { observer.defaultSleepTimerMin }, set: { observer.setDefaultSleepTimerMin($0) })
    }

    private func boolBinding(_ value: Bool, _ set: @escaping (Bool) -> Void) -> Binding<Bool> {
        Binding(get: { value }, set: { set($0) })
    }
}

// MARK: - Tinted icon row label

/// A settings row label: a tinted rounded SF-icon tile leading a title (and optional
/// subtitle), matching the mockup's `IconTile` + `SRow` vocabulary in native form.
private struct SettingsLabel: View {
    let title: String
    var subtitle: String?
    let systemImage: String
    let tint: Color

    var body: some View {
        Label {
            VStack(alignment: .leading, spacing: 1) {
                Text(title).foregroundStyle(.primary)
                if let subtitle {
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(Color.luLabel2)
                }
            }
        } icon: {
            RoundedRectangle(cornerRadius: 7, style: .continuous)
                .fill(tint)
                .frame(width: 29, height: 29)
                .overlay {
                    Image(systemName: systemImage)
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(.white)
                }
                .accessibilityHidden(true)
        }
    }
}

// MARK: - Preview

#Preview {
    NavigationStack {
        SettingsView()
            .environment(CurrentUserObserver())
    }
}
