import SwiftUI
import Shared

/// Admin inbox — freshly-scanned books awaiting triage before release into the library.
///
/// Layout is width-responsive (iosApp rule 12): compact width = single column list;
/// regular width (iPad, wide Split View) = adaptive multi-column grid.
/// Selection mode: tap a row to toggle; select-all / release actions appear in the header.
/// Release confirmation is a native alert. Transient errors surface as an alert.
/// Released-count confirmation surfaces as an overlay toast.
///
/// SSE updates flow through the shared VM into the observer — no extra wiring here.
struct AdminInboxView: View {
    @Environment(\.dependencies) private var deps
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    @State private var observer: AdminInboxObserver?
    @State private var showingReleaseConfirm = false

    private var isRegularWidth: Bool { horizontalSizeClass == .regular }

    var body: some View {
        Group {
            if let observer {
                content(observer: observer)
            } else {
                LoadingStateView()
            }
        }
        .background(Color.luSurface)
        .navigationTitle(String(localized: "common.inbox"))
        .navigationBarTitleDisplayMode(.large)
        .toolbar { selectToolbarItem }
        .onAppear {
            if observer == nil {
                observer = AdminInboxObserver(viewModel: deps.createAdminInboxViewModel())
            }
        }
    }

    // MARK: - Content routing

    @ViewBuilder
    private func content(observer: AdminInboxObserver) -> some View {
        switch observer.phase {
        case .loading:
            LoadingStateView()
        case .ready(let ready):
            readyBody(observer: observer, ready: ready)
                .alert(
                    String(localized: "common.something_went_wrong"),
                    isPresented: errorPresented(ready: ready),
                    actions: {
                        Button(String(localized: "common.ok"), role: .cancel) { observer.clearError() }
                    },
                    message: {
                        if let message = ready.error { Text(message) }
                    }
                )
                .alert(
                    String(localized: "admin.inbox_release_count"),
                    isPresented: $showingReleaseConfirm,
                    actions: {
                        Button(String(localized: "admin.inbox_release_count"), role: .destructive) {
                            observer.releaseSelected()
                        }
                        Button(String(localized: "common.cancel"), role: .cancel) {}
                    },
                    message: {
                        Text(releaseConfirmMessage(count: ready.selectedCount))
                    }
                )
                .overlay(alignment: .bottom) {
                    if let count = ready.lastReleasedCount {
                        ReleasedToast(count: count, onDismiss: { observer.clearReleaseResult() })
                            .padding(.bottom, 24)
                            .transition(.move(edge: .bottom).combined(with: .opacity))
                    }
                }
                .animation(.easeInOut(duration: 0.3), value: ready.lastReleasedCount)
        case .error(let message):
            errorBody(message: message, observer: observer)
        }
    }

    // MARK: - Ready body

    @ViewBuilder
    private func readyBody(observer: AdminInboxObserver, ready: AdminInboxReadyModel) -> some View {
        if !ready.hasBooks {
            emptyState
        } else {
            ScrollView {
                if isRegularWidth {
                    padLayout(observer: observer, ready: ready)
                } else {
                    phoneLayout(observer: observer, ready: ready)
                }
            }
            .refreshable { observer.reload() }
            .overlay(alignment: .bottom) {
                if ready.hasSelection {
                    releaseBar(observer: observer, ready: ready)
                }
            }
        }
    }

    // MARK: - Phone layout (compact width)

    @ViewBuilder
    private func phoneLayout(observer: AdminInboxObserver, ready: AdminInboxReadyModel) -> some View {
        VStack(spacing: 0) {
            subtitleRow(ready: ready)
                .padding(.horizontal, 20)
                .padding(.bottom, 8)
            FieldGroup(ready.books, separatorInset: ready.hasSelection ? 99 : 73) { book in
                InboxBookRow(
                    book: book,
                    isSelected: ready.selectedBookIds.contains(book.id),
                    isSelecting: ready.hasSelection,
                    onTap: { observer.toggleBookSelection(bookId: book.id) }
                )
            }
            .padding(.horizontal, 20)
            if ready.hasSelection {
                Color.clear.frame(height: 100)
            }
        }
        .padding(.vertical, 8)
    }

    // MARK: - iPad layout (regular width)

    @ViewBuilder
    private func padLayout(observer: AdminInboxObserver, ready: AdminInboxReadyModel) -> some View {
        VStack(spacing: 0) {
            padHeader(observer: observer, ready: ready)
                .padding(.horizontal, 36)
                .padding(.bottom, 16)
            LazyVGrid(
                columns: [GridItem(.adaptive(minimum: 320), spacing: 16)],
                spacing: 16
            ) {
                ForEach(ready.books) { book in
                    FieldGroup([book], separatorInset: 0) { b in
                        InboxBookRow(
                            book: b,
                            isSelected: ready.selectedBookIds.contains(b.id),
                            isSelecting: ready.hasSelection,
                            onTap: { observer.toggleBookSelection(bookId: b.id) }
                        )
                    }
                }
            }
            .padding(.horizontal, 36)
            .padding(.bottom, 32)
        }
        .padding(.top, 8)
    }

    // MARK: - Subviews

    @ViewBuilder
    private func subtitleRow(ready: AdminInboxReadyModel) -> some View {
        let text: String = {
            if ready.hasSelection {
                let count = ready.selectedCount
                return count == 1
                    ? String(localized: "admin.inbox_release_count")
                    : String(format: String(localized: "admin.inbox_released_count_plural"), count)
            } else {
                return "\(ready.bookCount) \(String(localized: "admin.inbox_workflow"))"
            }
        }()
        Text(text)
            .font(.subheadline)
            .foregroundStyle(Color.luLabel2)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private func padHeader(observer: AdminInboxObserver, ready: AdminInboxReadyModel) -> some View {
        HStack(alignment: .bottom) {
            VStack(alignment: .leading, spacing: 4) {
                Text(String(localized: "common.administration").uppercased())
                    .font(.caption.weight(.semibold))
                    .kerning(0.5)
                    .foregroundStyle(Color.luTint)
                Text(String(localized: "common.inbox"))
                    .font(.system(size: 40, weight: .bold))
                subtitleRow(ready: ready)
                    .font(.subheadline)
            }
            Spacer()
            HStack(spacing: 10) {
                Button {
                    if ready.allSelected { observer.clearSelection() } else { observer.selectAll() }
                } label: {
                    Text(ready.allSelected
                         ? String(localized: "admin.inbox_deselect_all")
                         : String(localized: "admin.inbox_select_all"))
                        .font(.subheadline.weight(.semibold))
                        .padding(.horizontal, 18)
                        .padding(.vertical, 11)
                        .background(Color.luFill, in: Capsule())
                        .overlay(Capsule().stroke(Color.luSeparator, lineWidth: 0.5))
                }
                .buttonStyle(.plain)
                if ready.hasSelection {
                    Button {
                        showingReleaseConfirm = true
                    } label: {
                        HStack(spacing: 8) {
                            Image(systemName: "checkmark")
                                .font(.subheadline.weight(.bold))
                            Text(String(format: String(localized: "admin.inbox_release_count"), ready.selectedCount))
                                .font(.subheadline.weight(.semibold))
                        }
                        .foregroundStyle(Color.luOnTint)
                        .padding(.horizontal, 20)
                        .padding(.vertical, 11)
                        .background(Color.luTint, in: Capsule())
                        .shadow(color: Color.luTint.opacity(0.4), radius: 6, y: 3)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    // MARK: - Release action bar (compact / phone)

    @ViewBuilder
    private func releaseBar(observer: AdminInboxObserver, ready: AdminInboxReadyModel) -> some View {
        VStack(spacing: 0) {
            LinearGradient(
                colors: [Color.luSurface.opacity(0), Color.luSurface],
                startPoint: .top,
                endPoint: .bottom
            )
            .frame(height: 24)
            HStack(spacing: 12) {
                Button {
                    showingReleaseConfirm = true
                } label: {
                    HStack(spacing: 8) {
                        Image(systemName: "checkmark")
                            .font(.body.weight(.semibold))
                        Text(String(format: String(localized: "admin.inbox_release_count"), ready.selectedCount))
                            .font(.body.weight(.semibold))
                    }
                    .foregroundStyle(Color.luOnTint)
                    .frame(maxWidth: .infinity)
                    .frame(height: 50)
                    .background(Color.luTint, in: RoundedRectangle(cornerRadius: 13))
                    .shadow(color: Color.luTint.opacity(0.4), radius: 8, y: 4)
                }
                .buttonStyle(.plain)
                .disabled(ready.isReleasing)
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 8)
            .background(Color.luSurface)
        }
    }

    // MARK: - Empty state

    private var emptyState: some View {
        VStack(spacing: 20) {
            Spacer()
            ZStack {
                RoundedRectangle(cornerRadius: 24, style: .continuous)
                    .fill(Color.luFill)
                    .frame(width: 96, height: 96)
                Image(systemName: "tray")
                    .font(.system(size: 46, weight: .light))
                    .foregroundStyle(Color.luLabel3)
            }
            VStack(spacing: 6) {
                Text(String(localized: "admin.inbox_empty"))
                    .font(.title2.weight(.bold))
                Text(String(localized: "admin.inbox_setting_subtitle"))
                    .font(.subheadline)
                    .foregroundStyle(Color.luLabel2)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 280)
                    .lineSpacing(2)
            }
            Spacer()
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 30)
    }

    // MARK: - Error body

    @ViewBuilder
    private func errorBody(message: String, observer: AdminInboxObserver) -> some View {
        VStack(spacing: 16) {
            Spacer()
            Image(systemName: "exclamationmark.triangle")
                .font(.system(size: 48, weight: .light))
                .foregroundStyle(Color.luLabel3)
            Text(message)
                .font(.subheadline)
                .foregroundStyle(Color.luLabel2)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 40)
            Button(String(localized: "common.retry")) {
                observer.reload()
            }
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(Color.luTint)
            Spacer()
        }
        .frame(maxWidth: .infinity)
    }

    // MARK: - Toolbar

    @ToolbarContentBuilder
    private var selectToolbarItem: some ToolbarContent {
        ToolbarItem(placement: .topBarTrailing) {
            if case .ready(let ready) = observer?.phase, ready.hasBooks {
                Button {
                    if ready.hasSelection { observer?.clearSelection() } else { observer?.selectAll() }
                } label: {
                    Text(ready.hasSelection
                         ? String(localized: "admin.inbox_deselect_all")
                         : String(localized: "admin.inbox_select_all"))
                        .font(.body)
                        .fontWeight(ready.hasSelection ? .semibold : .regular)
                        .foregroundStyle(Color.luTint)
                }
            }
        }
    }

    // MARK: - Helpers

    private func errorPresented(ready: AdminInboxReadyModel) -> Binding<Bool> {
        Binding(
            get: { ready.error != nil },
            set: { presenting in if !presenting { observer?.clearError() } }
        )
    }

    private func releaseConfirmMessage(count: Int) -> String {
        count == 1
            ? String(localized: "admin.inbox_released_count")
            : String(format: String(localized: "admin.inbox_released_count_plural"), count)
    }
}

// MARK: - Book row

private struct InboxBookRow: View {
    let book: InboxBookRowModel
    let isSelected: Bool
    let isSelecting: Bool
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 13) {
                if isSelecting {
                    selectionIndicator
                }
                BookCoverImage(
                    bookId: book.id,
                    coverPath: book.coverPath,
                    coverHash: book.coverHash,
                    accessibilityLabel: nil
                )
                .frame(width: 52, height: 52)
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                VStack(alignment: .leading, spacing: 2) {
                    Text(book.title)
                        .font(.system(size: 15.5, weight: .semibold))
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                    if let author = book.author {
                        Text(author)
                            .font(.footnote)
                            .foregroundStyle(Color.luLabel2)
                    }
                    Text(book.formattedDuration)
                        .font(.caption)
                        .foregroundStyle(Color.luLabel3)
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 11)
            .contentShape(Rectangle())
            .background(isSelected ? Color.luTint.opacity(0.08) : Color.clear)
            .animation(.easeInOut(duration: 0.15), value: isSelected)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(book.title)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }

    private var selectionIndicator: some View {
        ZStack {
            if isSelected {
                Circle()
                    .fill(Color.luTint)
                    .frame(width: 26, height: 26)
                Image(systemName: "checkmark")
                    .font(.system(size: 12, weight: .bold))
                    .foregroundStyle(Color.luOnTint)
            } else {
                Circle()
                    .stroke(Color.luSeparator, lineWidth: 2)
                    .frame(width: 26, height: 26)
            }
        }
        .animation(.easeInOut(duration: 0.15), value: isSelected)
    }
}

// MARK: - Released toast

private struct ReleasedToast: View {
    let count: Int
    let onDismiss: () -> Void

    private var label: String {
        count == 1
            ? String(localized: "admin.inbox_released_count")
            : String(format: String(localized: "admin.inbox_released_count_plural"), count)
    }

    var body: some View {
        HStack(spacing: 11) {
            Image(systemName: "checkmark.circle.fill")
                .foregroundStyle(.green)
            Text(label)
                .font(.subheadline.weight(.medium))
        }
        .foregroundStyle(.white)
        .padding(.horizontal, 18)
        .padding(.vertical, 15)
        .background(.black.opacity(0.88), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .shadow(color: .black.opacity(0.3), radius: 12, y: 4)
        .padding(.horizontal, 16)
        .onTapGesture { onDismiss() }
        .task {
            try? await Task.sleep(for: .seconds(2.5))
            onDismiss()
        }
    }
}

// MARK: - Preview

#Preview {
    NavigationStack {
        AdminInboxView()
            .environment(CurrentUserObserver())
    }
}
