import SwiftUI
import Shared

/// One audio file, as the Files & Audio sheet lists it.
struct FilesAndAudioFile: Identifiable, Equatable {
    let id: String
    let name: String
    let durationMs: Int64
    let sizeBytes: Int64
}

/// Files & Audio (spec §3.2.1 D32): the sheet behind the Details row and the scan warning's Review. Each file by name,
/// length and size, then Format, Bitrate, Sample Rate and Channels (HIG, Sheets: a medium detent, Done to leave).
struct FilesAndAudioSheet: View {
    let files: [FilesAndAudioFile]
    let format: AudioFormatDisplay
    let onDone: () -> Void

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(files) { file in
                        HStack(spacing: Spacing.s) {
                            Text(file.name).lineLimit(1).frame(maxWidth: .infinity, alignment: .leading)
                            Text(BookDetailHero.durationText(ms: file.durationMs)).foregroundStyle(.secondary)
                            Text(BookSize.label(file.sizeBytes)).foregroundStyle(.secondary)
                        }
                        .font(.subheadline)
                        .accessibilityElement(children: .combine)
                    }
                }
                Section {
                    formatRow(String(localized: "book.detail_format"), format.format)
                    formatRow(String(localized: "book.detail_bitrate"), format.bitrate)
                    formatRow(String(localized: "book.detail_sample_rate"), format.sampleRate)
                    formatRow(String(localized: "book.detail_channels"), format.channels)
                }
            }
            .navigationTitle(String(localized: "book.detail_files_sheet_title_ios"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(String(localized: "common.done"), action: onDone)
                }
            }
        }
    }

    @ViewBuilder
    private func formatRow(_ label: String, _ value: String?) -> some View {
        if let value {
            LabeledContent(label, value: value)
        }
    }
}
