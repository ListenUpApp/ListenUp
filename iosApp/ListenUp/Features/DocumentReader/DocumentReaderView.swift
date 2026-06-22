import PDFKit
import SwiftUI

/// Full-screen PDF reader with immersive chrome: tap to toggle the top bar and bottom dock
/// (scrubber + now-playing strip). A folio hint pill anchors the top when chrome is hidden
/// so the reader always knows their position.
struct DocumentReaderView: View {
    let document: ReaderDocument
    var onDone: () -> Void

    @State private var pdfDocument: PDFDocument?
    @State private var didAttemptLoad = false
    @State private var currentPageIndex = 0
    @State private var chromeVisible = true
    @State private var goToPage: Int?
    @State private var scrubFraction: Double = 0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var pageCount: Int { pdfDocument?.pageCount ?? 0 }

    var body: some View {
        ZStack {
            Color(.systemBackground).ignoresSafeArea()

            if let pdfDocument {
                PDFKitView(
                    document: pdfDocument,
                    currentPageIndex: $currentPageIndex,
                    goToPage: $goToPage
                )
                .ignoresSafeArea(edges: .bottom)
                .contentShape(Rectangle())
                .onTapGesture {
                    withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.2)) {
                        chromeVisible.toggle()
                    }
                }
                .onChange(of: currentPageIndex) { _, newIndex in
                    scrubFraction = pageCount > 1
                        ? Double(newIndex) / Double(pageCount - 1)
                        : 0
                }
            } else if didAttemptLoad {
                errorState
            } else {
                ProgressView()
            }
        }
        .task {
            pdfDocument = PDFDocument(url: document.url)
            didAttemptLoad = true
        }
        .safeAreaInset(edge: .top) {
            if chromeVisible { topBar }
        }
        .safeAreaInset(edge: .bottom) {
            if chromeVisible, pdfDocument != nil, pageCount > 0 { bottomDock }
        }
        .overlay(alignment: .top) {
            if !chromeVisible, pageCount > 0 { folioHint }
        }
    }

    // MARK: - Top bar

    private var topBar: some View {
        HStack {
            Button(String(localized: "common.done"), action: onDone)
                .font(.body.weight(.semibold))
            Spacer()
            HStack(spacing: 8) {
                Button(action: {}) {
                    Image(systemName: "square.grid.2x2")
                }
                .disabled(true)
                Menu {
                    // placeholder — actions added in 3b
                } label: {
                    Image(systemName: "ellipsis")
                }
            }
        }
        .overlay {
            VStack(spacing: 1) {
                Text(document.title).font(.subheadline.weight(.semibold)).lineLimit(1)
                Text("PDF").font(.caption2).foregroundStyle(.secondary)
            }
            .padding(.horizontal, 80)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(.bar)
    }

    // MARK: - Bottom dock

    private var bottomDock: some View {
        VStack(spacing: 8) {
            NowPlayingStrip()
            scrubberRow
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(.bar)
    }

    private var scrubberRow: some View {
        let display = pageDisplay(currentIndex: currentPageIndex, pageCount: pageCount)
        return HStack(spacing: 8) {
            Text("p. \(display.page)")
                .font(.caption.weight(.medium))
                .monospacedDigit()
                .foregroundStyle(.secondary)
                .frame(minWidth: 32, alignment: .leading)

            Slider(value: $scrubFraction, in: 0...1) { editing in
                if !editing {
                    goToPage = scrubberPage(fraction: scrubFraction, pageCount: pageCount) - 1
                }
            }

            Text("\(pageCount)")
                .font(.caption.weight(.medium))
                .monospacedDigit()
                .foregroundStyle(.secondary)
                .frame(minWidth: 32, alignment: .trailing)
        }
    }

    // MARK: - Folio hint

    private var folioHint: some View {
        let display = pageDisplay(currentIndex: currentPageIndex, pageCount: pageCount)
        let format = String(localized: "book.detail_document_reader_controls_hint")
        return Text(String(format: format, display.page, display.total))
            .font(.caption.weight(.medium))
            .monospacedDigit()
            .foregroundStyle(.white)
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(.black.opacity(0.55), in: Capsule())
            .padding(.top, 8)
    }

    // MARK: - Error state

    private var errorState: some View {
        VStack(spacing: 8) {
            Image(systemName: "doc.questionmark").font(.largeTitle).foregroundStyle(.secondary)
            Text(String(localized: "book.detail_document_viewer_error")).foregroundStyle(.secondary)
        }
    }
}

/// Identifiable payload for `.fullScreenCover(item:)`. `id` is the local path so re-tapping
/// the same document re-presents cleanly.
struct ReaderDocument: Identifiable, Equatable {
    let id: String
    let url: URL
    let title: String

    init(localPath: String, title: String) {
        self.id = localPath
        self.url = URL(fileURLWithPath: localPath)
        self.title = title
    }
}
