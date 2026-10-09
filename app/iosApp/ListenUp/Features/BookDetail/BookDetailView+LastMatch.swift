import SwiftUI
import UIKit

/// Book Detail's "Undo last match", split out of `BookDetailView.swift`, which sits at SwiftLint's body cap.
@MainActor
extension BookDetailView {
    /// The last-match row under the actions, while the book's last match can still be undone; nothing otherwise.
    @ViewBuilder
    func lastMatchSection(_ observer: BookDetailObserver) -> some View {
        if let lastMatch = observer.lastMatch {
            LastMatchRow(
                model: lastMatch,
                onSeeWhatChanged: { observer.seeWhatChanged() },
                onUndo: { observer.undoLastMatch() }
            )
        }
    }
}

extension View {
    /// See What Changed for the last match — the receipt's own sheet — and Undo's outcome in the receipt's capsule.
    func lastMatchFeedback(_ observer: BookDetailObserver?) -> some View {
        modifier(LastMatchFeedback(observer: observer))
    }
}

/// The last-match row's sheet and confirmation. The outcome is announced, takes VoiceOver focus, and stays while
/// VoiceOver runs; otherwise it dismisses itself after the receipt's own time (HIG, Feedback).
private struct LastMatchFeedback: ViewModifier {
    let observer: BookDetailObserver?

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @AccessibilityFocusState private var isFocused: Bool

    private var outcome: MatchReceiptPhase { observer?.lastMatchOutcome ?? .none }

    func body(content: Content) -> some View {
        content
            .sheet(isPresented: Binding(
                get: { observer?.lastMatch?.showingChanges ?? false },
                set: { if !$0 { observer?.closeWhatChanged() } }
            )) {
                if let receipt = observer?.lastMatch?.receipt {
                    MatchWhatChangedSheet(receipt: receipt, onClose: { observer?.closeWhatChanged() })
                }
            }
            .safeAreaInset(edge: .bottom) {
                if outcome != .none {
                    MatchReceiptCapsule(
                        phase: outcome,
                        onUndo: {},
                        onSeeWhatChanged: {},
                        onDismiss: { observer?.dismissLastMatchOutcome() }
                    )
                    .accessibilityFocused($isFocused)
                    .padding(.horizontal, Spacing.m)
                    .padding(.bottom, Spacing.xs)
                    .transition(reduceMotion ? .opacity : .move(edge: .bottom).combined(with: .opacity))
                }
            }
            .animation(reduceMotion ? .easeInOut(duration: 0.2) : .spring(duration: 0.35), value: outcome)
            .onChange(of: outcome) { _, new in
                guard let message = MatchReceiptAnnouncement.text(for: new) else { return }
                VoiceOverAnnouncement.post(message)
                isFocused = true
            }
            .task(id: outcome) {
                guard outcome != .none,
                      let delay = MatchReceiptTiming.autoDismissDelay(
                          voiceOverRunning: UIAccessibility.isVoiceOverRunning
                      )
                else { return }
                try? await Task.sleep(for: delay)
                guard !Task.isCancelled else { return }
                observer?.dismissLastMatchOutcome()
            }
    }
}
