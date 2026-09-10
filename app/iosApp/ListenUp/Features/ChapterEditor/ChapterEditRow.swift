import Shared
import SwiftUI

/// One boundary, and everything that can be done to it without leaving the list.
///
/// The list alone has to be sufficient — the spec calls the timeline "optional spatial sugar" — so
/// every operation the editor offers is reachable here.
///
/// The start time is rendered to the tenth of a second. A boundary is aimed at by ear, and a whole
/// second is wider than the gap the ear is judging.
struct ChapterEditRow: View {
    let row: EditableChapterRow
    let isSelected: Bool
    let isPlaying: Bool
    let playheadMs: Int64?
    let onSelect: () -> Void
    let onNudge: (Int64) -> Void
    let onSnapToPlayhead: (Int64) -> Void
    let onToggleLock: () -> Void
    let onRename: () -> Void
    let onDelete: () -> Void

    /// Coarse ± step, the same one every other client's rows use.
    private static let nudgeMs: Int64 = 1_000

    var body: some View {
        HStack(spacing: 12) {
            Text("\(row.number)")
                .font(.caption.weight(.semibold).monospacedDigit())
                .foregroundStyle(.secondary)
                .frame(minWidth: 32, alignment: .trailing)

            VStack(alignment: .leading, spacing: 2) {
                Text(row.title)
                    .font(.subheadline.weight(isSelected ? .semibold : .regular))
                    .lineLimit(1)
                HStack(spacing: 8) {
                    Text(ChapterTimeFormat.shared.precise(ms: row.startMs))
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                    // "NOW", not a progress bar: this says which boundary the listener is inside,
                    // which is the row worth aiming at, and nothing about how far through it.
                    if isPlaying {
                        Text(String(localized: "chapter_editor.now_playing"))
                            .font(.caption2.weight(.bold))
                            .foregroundStyle(Color.listenUpOrange)
                    }
                    if row.isLocked {
                        Image(systemName: "lock.fill")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                            .accessibilityLabel(String(localized: "chapter_editor.lock"))
                    }
                }
            }

            Spacer(minLength: 0)

            // The two nudges live in the row because they are the gesture the reader repeats;
            // everything rarer is one swipe or one long-press away, which is the iOS shape for a
            // list this long.
            HStack(spacing: 4) {
                nudge(by: -Self.nudgeMs, label: String(localized: "chapter_editor.nudge_back"), symbol: "minus")
                nudge(by: Self.nudgeMs, label: String(localized: "chapter_editor.nudge_forward"), symbol: "plus")
            }
        }
        .contentShape(Rectangle())
        .onTapGesture(perform: onSelect)
        .listRowBackground(isSelected ? Color.listenUpOrange.opacity(0.12) : nil)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(isSelected ? [.isSelected] : [])
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            Button(role: .destructive, action: onDelete) {
                Label(String(localized: "common.delete"), systemImage: "trash")
            }
            Button(action: onRename) {
                Label(String(localized: "chapter_editor.rename_title"), systemImage: "pencil")
            }
            .tint(.indigo)
        }
        .swipeActions(edge: .leading, allowsFullSwipe: false) {
            Button(action: onToggleLock) {
                Label(
                    row.isLocked
                        ? String(localized: "chapter_editor.unlock")
                        : String(localized: "chapter_editor.lock"),
                    systemImage: row.isLocked ? "lock.open" : "lock"
                )
            }
            .tint(.gray)
            // ⛔ Absent, not disabled, when there is no playhead for THIS book. A position borrowed
            // from a different book would write a number from somewhere else entirely.
            if let at = playheadMs {
                Button { onSnapToPlayhead(at) } label: {
                    Label(String(localized: "chapter_editor.snap_to_playhead"), systemImage: "scope")
                }
                .tint(Color.listenUpOrange)
            }
        }
    }

    private func nudge(by deltaMs: Int64, label: String, symbol: String) -> some View {
        Button { onNudge(deltaMs) } label: {
            Image(systemName: symbol)
                .font(.caption.weight(.semibold))
                .frame(width: 30, height: 30)
        }
        .buttonStyle(.bordered)
        .buttonBorderShape(.circle)
        // ⛔ A row this dense needs its controls out of the row's own tap target, or selecting a
        // boundary and nudging it become the same gesture.
        .buttonRepeatBehavior(.enabled)
        .accessibilityLabel(label)
    }
}
