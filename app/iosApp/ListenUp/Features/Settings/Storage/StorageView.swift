import SwiftUI
import Shared

/// Storage management screen — downloaded books, their footprint, per-book delete, clear-all, and
/// free-space usage. Reached from Settings › Downloads. The Cupertino counterpart to the Android
/// `StorageScreen`, backed by the same shared `StorageViewModel` via `StorageObserver`.
///
/// Deleting the currently-playing book is refused by the VM (B9); the screen surfaces a
/// "stop playback first" alert rather than silently no-op.
struct StorageView: View {
    @Environment(\.dependencies) private var deps
    @State private var observer: StorageObserver?

    var body: some View {
        Group {
            if let observer {
                content(observer)
            } else {
                LoadingStateView()
            }
        }
        .navigationTitle(String(localized: "common.storage"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if let observer, !observer.books.isEmpty {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(role: .destructive) {
                        observer.confirmClearAll()
                    } label: {
                        Text(String(localized: "settings.clear_all"))
                    }
                    .disabled(observer.isDeleting)
                }
            }
        }
        .onAppear {
            if observer == nil {
                observer = StorageObserver(viewModel: deps.createStorageViewModel())
            }
        }
    }

    // MARK: - Content

    @ViewBuilder
    private func content(_ observer: StorageObserver) -> some View {
        if observer.isLoading {
            LoadingStateView()
        } else {
            booksList(observer)
        }
    }

    private func booksList(_ observer: StorageObserver) -> some View {
        List {
                Section {
                    StorageSummaryCard(
                        totalUsed: observer.totalStorageUsed,
                        available: observer.availableStorage,
                        bookCount: observer.books.count
                    )
                    .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
                }

                if observer.books.isEmpty {
                    Section {
                        ContentUnavailableView {
                            Label(String(localized: "settings.no_downloads"), systemImage: "arrow.down.circle")
                        } description: {
                            Text(String(localized: "settings.downloaded_books_will_appear_here"))
                        }
                    }
                } else {
                    Section(String(localized: "settings.downloaded_books")) {
                        ForEach(observer.books) { book in
                            DownloadedBookRowView(book: book)
                                .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                                    Button(role: .destructive) {
                                        observer.confirmDeleteBook(id: book.id)
                                    } label: {
                                        Label(String(localized: "common.delete"), systemImage: "trash")
                                    }
                                    .disabled(observer.isDeleting)
                                }
                        }
                    }
                }
            }
            .readableWidth(720)
            .alert(
                deletionTitle(observer.pendingDeletion),
                isPresented: pendingBinding(observer),
                presenting: observer.pendingDeletion
            ) { _ in
                Button(String(localized: "common.delete"), role: .destructive) {
                    observer.executeDelete()
                }
                Button(String(localized: "common.cancel"), role: .cancel) {
                    observer.cancelDelete()
                }
            } message: { deletion in
                Text(deletionMessage(deletion))
            }
            .alert(
                String(localized: "settings.delete_blocked_title"),
                isPresented: blockedBinding(observer),
                presenting: observer.blockedDeletionTitle
            ) { _ in
                Button(String(localized: "common.ok"), role: .cancel) {
                    observer.dismissDeleteBlocked()
                }
            } message: { title in
                Text(String(format: String(localized: "settings.delete_blocked_message"), title))
            }
    }

    // MARK: - Alert copy

    private func deletionTitle(_ deletion: StoragePendingDeletion?) -> String {
        switch deletion {
        case .single, .none: String(localized: "book.delete_download")
        case .all: String(localized: "settings.clear_all_downloads")
        }
    }

    private func deletionMessage(_ deletion: StoragePendingDeletion) -> String {
        switch deletion {
        case .single(let title, _):
            String(format: String(localized: "book.detail_remove_the_downloaded_files_for"), title)
                + String(localized: "book.detail_you_can_redownload_anytime_by")
        case .all:
            String(localized: "settings.you_can_redownload_books_anytime")
        }
    }

    // MARK: - Bindings

    private func pendingBinding(_ observer: StorageObserver) -> Binding<Bool> {
        Binding(
            get: { observer.pendingDeletion != nil },
            set: { if !$0 { observer.cancelDelete() } }
        )
    }

    private func blockedBinding(_ observer: StorageObserver) -> Binding<Bool> {
        Binding(
            get: { observer.blockedDeletionTitle != nil },
            set: { if !$0 { observer.dismissDeleteBlocked() } }
        )
    }
}

// MARK: - Summary card

/// The storage-usage summary: bytes used (headline), download count, free space, and a usage bar.
private struct StorageSummaryCard: View {
    let totalUsed: Int64
    let available: Int64
    let bookCount: Int

    private var usageFraction: Double {
        let total = totalUsed + available
        return total > 0 ? Double(totalUsed) / Double(total) : 0
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(StorageFormat.byteSize(totalUsed))
                        .font(.title2.weight(.bold))
                        .foregroundStyle(.primary)
                    Text(String(
                        format: String(localized: bookCount == 1
                            ? "settings.book_downloaded_count"
                            : "settings.books_downloaded_count"),
                        bookCount
                    ))
                    .font(.footnote)
                    .foregroundStyle(Color.luLabel2)
                }
                Spacer()
                Text(String(format: String(localized: "settings.storage_available"), StorageFormat.byteSize(available)))
                    .font(.footnote)
                    .foregroundStyle(Color.luLabel2)
            }

            ProgressView(value: usageFraction)
                .tint(Color.listenUpOrange)
        }
        .padding(.vertical, 4)
    }
}

// MARK: - Downloaded book row

private struct DownloadedBookRowView: View {
    let book: DownloadedBookRow

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(book.title)
                    .font(.body)
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                if !book.authorNames.isEmpty {
                    Text(book.authorNames)
                        .font(.caption)
                        .foregroundStyle(Color.luLabel2)
                        .lineLimit(1)
                }
                Text(String(
                    format: String(localized: "settings.storage_size_files"),
                    StorageFormat.byteSize(book.sizeBytes),
                    book.fileCount
                ))
                .font(.caption)
                .foregroundStyle(Color.luLabel3)
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 2)
    }
}

// MARK: - Byte formatting

/// Human-readable byte sizes for the Storage screen. Wraps `ByteCountFormatter` in one place so the
/// screen and its row share a single, consistent format ("1.2 GB", "340 MB").
enum StorageFormat {
    static func byteSize(_ bytes: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: max(0, bytes), countStyle: .file)
    }
}

#Preview {
    NavigationStack {
        StorageView()
    }
}
