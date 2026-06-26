import SwiftUI

/// Compact alphabet index for quick section navigation.
///
/// Features:
/// - Vertical stack of letters for quick scrolling
/// - Large letter popup when dragging
/// - Haptic feedback on letter change
/// - Fade in/out based on scroll state
struct SectionIndexBar: View {
    let letters: [String]
    let onLetterSelected: (String) -> Void
    let isVisible: Bool

    @State private var selectedLetter: String?
    @State private var isDragging = false
    @State private var endDragTask: Task<Void, Never>?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        GeometryReader { geo in
            let availableHeight = geo.size.height - 16
            let letterHeight = availableHeight / CGFloat(max(letters.count, 1))

            HStack(spacing: 8) {
                // Large letter popup when dragging
                if isDragging, let letter = selectedLetter {
                    Text(letter)
                        .font(.system(size: 44, weight: .bold, design: .rounded))
                        .foregroundStyle(.white)
                        .frame(width: 56, height: 56)
                        .background(Color.listenUpOrange, in: RoundedRectangle(cornerRadius: 10))
                        .transition(.scale.combined(with: .opacity))
                        .accessibilityLabel(String(format: String(localized: "library.index_jump"), letter))
                }

                // Letter column
                VStack(spacing: 0) {
                    ForEach(letters, id: \.self) { letter in
                        Text(letter)
                            .font(.system(size: 10, weight: .semibold))
                            .foregroundStyle(letter == selectedLetter ? Color.listenUpOrange : .primary)
                            .frame(height: letterHeight)
                            .frame(maxWidth: .infinity)
                    }
                }
                .padding(.horizontal, 4)
                .padding(.vertical, 8)
                .frame(width: 20)
                .glassControl(in: RoundedRectangle(cornerRadius: 10))
                .frame(width: 44, alignment: .trailing)
                .contentShape(Rectangle())
                .gesture(
                    DragGesture(minimumDistance: 0)
                        .onChanged { value in
                            let adjustedY = value.location.y - 8
                            handleDrag(at: adjustedY, letterHeight: letterHeight)
                        }
                        .onEnded { _ in
                            endDrag()
                        }
                )
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(String(localized: "library.section_index"))
                .accessibilityValue(selectedLetter ?? letters.first ?? "")
                .accessibilityAdjustableAction { direction in
                    guard !letters.isEmpty else { return }
                    let current = selectedLetter.flatMap { letters.firstIndex(of: $0) } ?? 0
                    let next: Int
                    switch direction {
                    case .increment: next = min(current + 1, letters.count - 1)
                    case .decrement: next = max(current - 1, 0)
                    @unknown default: return
                    }
                    let letter = letters[next]
                    selectedLetter = letter
                    onLetterSelected(letter)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .trailing)
            .padding(.trailing, 4)
        }
        .opacity(isVisible || isDragging ? 1 : 0)
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.2), value: isVisible)
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.1), value: isDragging)
        .haptic(.selectionTick, trigger: selectedLetter)
    }

    private func handleDrag(at y: CGFloat, letterHeight: CGFloat) {
        guard !letters.isEmpty, letterHeight > 0 else { return }

        if !isDragging {
            isDragging = true
        }

        let index = Int(y / letterHeight)
        let clampedIndex = max(0, min(index, letters.count - 1))
        let letter = letters[clampedIndex]

        if letter != selectedLetter {
            selectedLetter = letter
            onLetterSelected(letter)
        }
    }

    private func endDrag() {
        endDragTask?.cancel()
        endDragTask = Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(300))
            guard !Task.isCancelled else { return }
            selectedLetter = nil
            try? await Task.sleep(for: .milliseconds(200))   // 0.3s + 0.2s = the original 0.5s point
            guard !Task.isCancelled else { return }
            withAnimation(reduceMotion ? nil : .easeOut(duration: 0.2)) {
                isDragging = false
            }
        }
    }
}

// MARK: - Preview

#Preview("Section Index Bar") {
    struct PreviewWrapper: View {
        @State private var isVisible = true

        var body: some View {
            ZStack {
                Color(.systemBackground)

                SectionIndexBar(
                    letters: ["A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T"],
                    onLetterSelected: { letter in
                        print("Selected: \(letter)")
                    },
                    isVisible: isVisible
                )
                .frame(height: 400)
                .padding(.trailing, 8)

                VStack {
                    Toggle("Visible", isOn: $isVisible)
                        .padding()
                    Spacer()
                }
            }
        }
    }

    return PreviewWrapper()
}
