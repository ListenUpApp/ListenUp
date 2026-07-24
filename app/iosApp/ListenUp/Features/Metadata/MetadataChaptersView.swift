import SwiftUI
import Shared

/// Step 3: review the Audible chapter-name replacements before applying. Each row shows the
/// current label struck through → the suggested name, with a per-row circular toggle (default on).
/// Applies only the checked chapters; timings never change. Closes once `chapterAppliedToken` bumps.
struct MetadataChaptersView: View {
    let observer: MetadataMatchObserver
    let onDone: () -> Void

    @State private var lastChapterToken = 0

    var body: some View {
        Group {
            if let available = availableChapters {
                content(available)
            } else {
                LoadingStateView()
            }
        }
        .background(Color.luSurface)
        .navigationTitle(String(localized: "metadata.review_chapters"))
        .navigationBarTitleDisplayMode(.large)
        .onAppear { lastChapterToken = observer.chapterAppliedToken }
        .onChange(of: observer.chapterAppliedToken) { _, token in
            if token != lastChapterToken { onDone() }
        }
    }

    private var availableChapters: AvailableChapters? {
        if case .preview(.ready(let preview)) = observer.phase,
           case .available(let available) = preview.chapters {
            return available
        }
        return nil
    }

    @ViewBuilder
    private func content(_ available: AvailableChapters) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text(String(localized: "metadata.review_chapters_subtitle"))
                    .font(.subheadline).foregroundStyle(Color.luLabel2)
                    .fixedSize(horizontal: false, vertical: true)

                HStack {
                    MetadataGroupHeader(text: selectionText(available))
                    Spacer()
                    Button(
                        available.allSelected
                            ? String(localized: "metadata.clear_all")
                            : String(localized: "metadata.select_all")
                    ) {
                        toggleAll(available)
                    }
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color.luTint)
                }

                FieldGroup(available.rows, separatorInset: 14) { row in
                    chapterRow(row)
                }
            }
            .padding(.horizontal, 18)
            .padding(.vertical, 12)
            .readableWidth(720)
        }
        .safeAreaInset(edge: .bottom) {
            MetadataApplyTray(
                title: String(localized: "metadata.apply_chapter_names"),
                isApplying: available.isApplying,
                isEnabled: available.selectedCount > 0,
                applyError: available.applyError,
                action: { observer.applyChapterNames() }
            )
        }
    }

    private func chapterRow(_ row: ChapterRenameRow) -> some View {
        Button {
            observer.toggleChapter(row.ordinal)
        } label: {
            HStack(spacing: 13) {
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 7) {
                        Text(row.currentName)
                            .font(.caption).foregroundStyle(Color.luLabel3).strikethrough()
                            .lineLimit(1)
                        Image(systemName: "arrow.right").font(.caption2.weight(.bold)).foregroundStyle(Color.luTint)
                    }
                    Text(row.suggestedName)
                        .font(.callout.weight(.medium)).foregroundStyle(.primary).lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                CircularCheckToggle(isOn: row.isSelected) { observer.toggleChapter(row.ordinal) }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 11)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressScaleButtonStyle())
    }

    private func selectionText(_ available: AvailableChapters) -> String {
        if available.allSelected {
            let format = String(localized: "metadata.chapters_all_selected")
            return String(format: format, available.totalCount)
        }
        let format = String(localized: "metadata.chapters_some_selected")
        return String(format: format, available.selectedCount, available.totalCount)
    }

    private func toggleAll(_ available: AvailableChapters) {
        let target = !available.allSelected
        for row in available.rows where row.isSelected != target {
            observer.toggleChapter(row.ordinal)
        }
    }
}
