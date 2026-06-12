import SwiftUI

/// Empty state for the Continue-Listening section — shown when nothing is in progress.
/// A native `ContentUnavailableView` nudging the user to start a book.
struct EmptyContinueListening: View {
    var body: some View {
        ContentUnavailableView {
            Label(String(localized: "home.nothing_playing_yet"), systemImage: "headphones")
        } description: {
            Text(String(localized: "home.start_listening_to_an_audiobook"))
        }
    }
}

// MARK: - Preview

#Preview("Empty") {
    EmptyContinueListening()
}
