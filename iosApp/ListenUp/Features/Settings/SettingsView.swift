import SwiftUI

/// Settings screen.
///
/// Based on mockup showing:
/// - Interface settings (bookshelf view, orientation, theme, haptics, language)
/// - Playback settings (auto rewind, seeking, jump intervals)
/// - Other settings
struct SettingsView: View {
    @State private var useBookshelfView = false
    @State private var lockOrientation = false
    @State private var darkMode = false
    @State private var autoRewind = false

    var body: some View {
        List {
            // Interface section
            Section(String(localized: "settings.interface")) {
                Toggle(String(localized: "settings.use_bookshelf_view"), isOn: $useBookshelfView)
                Toggle(String(localized: "settings.lock_orientation"), isOn: $lockOrientation)

                HStack {
                    Text(String(localized: "settings.theme"))
                    Spacer()
                    Picker(String(localized: "settings.theme"), selection: $darkMode) {
                        Image(systemName: "sun.max.fill").tag(false)
                        Image(systemName: "moon.fill").tag(true)
                    }
                    .pickerStyle(.segmented)
                    .frame(width: 100)
                }

                HStack {
                    Text(String(localized: "settings.haptic_feedback"))
                    Spacer()
                    Text(String(localized: "settings.haptic_light"))
                        .foregroundStyle(.secondary)
                }

                HStack {
                    Text(String(localized: "settings.language"))
                    Spacer()
                    Text(String(localized: "settings.language_english"))
                        .foregroundStyle(.secondary)
                }
            }

            // Playback section
            Section(String(localized: "settings.playback")) {
                Toggle(String(localized: "settings.auto_rewind_label"), isOn: $autoRewind)

                HStack {
                    Text(String(localized: "settings.jump_forward"))
                    Spacer()
                    Image(systemName: "goforward.10")
                        .foregroundStyle(.secondary)
                }

                HStack {
                    Text(String(localized: "settings.jump_backward"))
                    Spacer()
                    Image(systemName: "gobackward.10")
                        .foregroundStyle(.secondary)
                }
            }

            // Server section
            Section(String(localized: "settings.server")) {
                HStack {
                    Text(String(localized: "settings.connected_to"))
                    Spacer()
                    Text("myserver.local")
                        .foregroundStyle(.secondary)
                }

                Button(String(localized: "common.disconnect"), role: .destructive) {
                    // TODO: Handle disconnect
                }
            }

            // Account section
            Section(String(localized: "settings.account")) {
                Button(String(localized: "common.sign_out"), role: .destructive) {
                    // TODO: Handle sign out
                }
            }

            // About section
            Section(String(localized: "common.about")) {
                HStack {
                    Text(String(localized: "common.version"))
                    Spacer()
                    Text("1.0.0")
                        .foregroundStyle(.secondary)
                }
            }
        }
        .navigationTitle(String(localized: "common.settings"))
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - Preview

#Preview {
    NavigationStack {
        SettingsView()
    }
}
