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

/// The person Match details is open for.
struct PersonMatchTarget: Identifiable, Hashable {
    let contributorId: String
    var id: String { contributorId }
}

extension View {
    /// Presents Match details for `target`, pushed or as a cover per `MatchPresentationStyle`. Clearing
    /// `target` closes it; Match details clears it itself after Apply.
    func bookMatchDetails(_ target: Binding<BookMatchTarget?>) -> some View {
        modifier(MatchDetailsPresenter(
            target: target,
            phone: { target, close in BookMatchPhoneView(bookId: target.bookId, onApplied: close) },
            pad: { target, close in BookMatchPadView(bookId: target.bookId, onClose: close) }
        ))
    }

    /// Presents person Match details for `target`, the same way. `onMergedInto` hands on a merge made from
    /// Edit by Hand, after Match details has closed.
    func personMatchDetails(
        _ target: Binding<PersonMatchTarget?>,
        onMergedInto: @escaping (String) -> Void
    ) -> some View {
        modifier(MatchDetailsPresenter(
            target: target,
            phone: { target, close in
                PersonMatchPhoneView(contributorId: target.contributorId, onClose: close, onMergedInto: onMergedInto)
            },
            pad: { target, close in
                PersonMatchPadView(contributorId: target.contributorId, onClose: close, onMergedInto: onMergedInto)
            }
        ))
    }
}

/// Pushes `phone` in a compact width and covers the screen with `pad` in a regular one. Each gets the
/// closure that closes it.
private struct MatchDetailsPresenter<Target: Identifiable & Hashable, Phone: View, Pad: View>: ViewModifier {
    @Binding var target: Target?
    let phone: (Target, @escaping () -> Void) -> Phone
    let pad: (Target, @escaping () -> Void) -> Pad
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    private var style: MatchPresentationStyle { .style(for: horizontalSizeClass) }

    private func binding(for presented: MatchPresentationStyle) -> Binding<Target?> {
        Binding(
            get: { style == presented ? target : nil },
            set: { target = $0 }
        )
    }

    func body(content: Content) -> some View {
        content
            .navigationDestination(item: binding(for: .push)) { target in
                phone(target) { self.target = nil }
            }
            .fullScreenCover(item: binding(for: .cover)) { target in
                pad(target) { self.target = nil }
            }
    }
}
