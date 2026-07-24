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
    @State private var showGrid = false
    @State private var showSearch = false
    @State private var highlightSelection: PDFSelection?
    @State private var clearHighlight = false
    @State private var search: PdfSearchController?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.horizontalSizeClass) private var hSize

    private var pageCount: Int { pdfDocument?.pageCount ?? 0 }

    var body: some View {
        ZStack {
            Color(.systemBackground).ignoresSafeArea()

            if let pdfDocument {
                // On regular (iPad) show the persistent page rail beside the reader.
                // On compact (iPhone, or iPad in narrow Split View) show the PDF full-width.
                if hSize == .regular {
                    HStack(spacing: 0) {
                        PageRailView(
                            document: pdfDocument,
                            currentPageIndex: currentPageIndex,
                            onSelect: { goToPage = $0 }
                        )
                        pdfView(document: pdfDocument)
                    }
                } else {
                    pdfView(document: pdfDocument)
                }
            } else if didAttemptLoad {
                errorState
            } else {
                ProgressView()
            }
        }
        .task {
            pdfDocument = PDFDocument(url: document.url)
            if let pdfDocument { search = PdfSearchController(document: pdfDocument) }
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
        .fullScreenCover(isPresented: $showGrid) {
            if let pdfDocument {
                PageGridView(
                    document: pdfDocument,
                    currentPageIndex: currentPageIndex,
                    onSelect: { idx in goToPage = idx; showGrid = false },
                    onClose: { showGrid = false }
                )
            }
        }
        .fullScreenCover(isPresented: $showSearch) {
            if let search {
                DocumentSearchView(
                    controller: search,
                    onSelect: { sel in highlightSelection = sel; showSearch = false },
                    onClose: { search.cancel(); clearHighlight = true; showSearch = false }
                )
            }
        }
    }

    // MARK: - PDF view

    @ViewBuilder
    private func pdfView(document: PDFDocument) -> some View {
        PDFKitView(
            document: document,
            currentPageIndex: $currentPageIndex,
            goToPage: $goToPage,
            highlightSelection: $highlightSelection,
            clearHighlight: $clearHighlight
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
    }

    // MARK: - Top bar

    private var topBar: some View {
        HStack {
            Button(String(localized: "common.done"), action: onDone)
                .font(.body.weight(.semibold))
            Spacer()
            HStack(spacing: 8) {
                if search != nil {
                    Button { showSearch = true } label: {
                        Image(systemName: "magnifyingglass")
                    }
                    .accessibilityLabel(String(localized: "book.detail_document_search"))
                }
                // The rail is the always-on equivalent on regular (iPad); hide the grid button there.
                if hSize != .regular {
                    Button { showGrid = true } label: {
                        Image(systemName: "square.grid.2x2")
                    }
                    .accessibilityLabel(String(localized: "book.detail_document_reader_toggle_grid"))
                }
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
            Text(String(format: String(localized: "book.reader_scrubber_page_label"), display.page))
                .font(.caption.weight(.medium))
                .monospacedDigit()
                .foregroundStyle(.secondary)
                .frame(minWidth: 32, alignment: .leading)

            Slider(value: $scrubFraction, in: 0...1) { editing in
                if !editing {
                    goToPage = scrubberPage(fraction: scrubFraction, pageCount: pageCount) - 1
                }
            }

            Text(String(format: String(localized: "book.reader_scrubber_page_count"), pageCount))
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
