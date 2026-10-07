import SwiftUI

/// How Match details is presented, decided by the presenting context's width.
///
/// - **Push** in a compact width — every iPhone, and an iPad in a narrow Split View — as a navigation
///   destination of Book Detail's own stack, so Back returns to the book (HIG, Navigation).
/// - **Cover** in a regular width: a full-screen cover hosting a split view, results beside Review. A sheet
///   reports a compact size class on iPad, which is why the old split layout never rendered (HIG, Split views).
enum MatchPresentationStyle: Equatable {
    case push
    case cover

    static func style(for horizontalSizeClass: UserInterfaceSizeClass?) -> MatchPresentationStyle {
        horizontalSizeClass == .regular ? .cover : .push
    }
}

/// The book Match details is open for.
struct BookMatchTarget: Identifiable, Hashable {
    let bookId: String
    var id: String { bookId }
}

extension View {
    /// Presents Match details for `target`, pushed or as a cover per `MatchPresentationStyle`. Clearing
    /// `target` closes it; Match details clears it itself after Apply.
    func bookMatchDetails(_ target: Binding<BookMatchTarget?>) -> some View {
        modifier(BookMatchPresenter(target: target))
    }
}

private struct BookMatchPresenter: ViewModifier {
    @Binding var target: BookMatchTarget?
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    private var style: MatchPresentationStyle { .style(for: horizontalSizeClass) }

    private func binding(for presented: MatchPresentationStyle) -> Binding<BookMatchTarget?> {
        Binding(
            get: { style == presented ? target : nil },
            set: { target = $0 }
        )
    }

    func body(content: Content) -> some View {
        content
            .navigationDestination(item: binding(for: .push)) { target in
                BookMatchPhoneView(bookId: target.bookId, onApplied: { self.target = nil })
            }
            .fullScreenCover(item: binding(for: .cover)) { target in
                BookMatchPadView(bookId: target.bookId, onClose: { self.target = nil })
            }
    }
}
