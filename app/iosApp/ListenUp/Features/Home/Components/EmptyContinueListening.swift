import SwiftUI

/// Interim for the NothingInProgress and NewListener kinds until Home PR 4 draws them: a native
/// `ContentUnavailableView` with Browse the Library (H17).
struct EmptyContinueListening: View {
    var onBrowseLibrary: () -> Void = {}

    var body: some View {
        ContentUnavailableView {
            Label(String(localized: "home.nothing_playing_yet"), systemImage: "headphones")
        } description: {
            Text(String(localized: "home.start_listening_to_an_audiobook"))
        } actions: {
            Button(String(localized: "home.browse_library"), action: onBrowseLibrary)
                .buttonStyle(.bordered)
                .buttonBorderShape(.capsule)
        }
    }
}

// MARK: - Preview

#Preview("Empty") {
    EmptyContinueListening()
}
