import SwiftUI
import Shared

/// Presented sheet for editing a series: cover, name, description. Bound to
/// `SeriesEditViewModel` via `SeriesEditObserver`.
struct SeriesEditView: View {
    let seriesId: String

    @Environment(\.dependencies) private var deps
    @Environment(\.dismiss) private var dismiss
    @State private var observer: SeriesEditObserver?

    var body: some View {
        Group {
            if let observer {
                EditSheetScaffold(
                    title: String(localized: "series.edit_title"),
                    canSave: observer.hasChanges,
                    isSaving: observer.isSaving,
                    onCancel: { observer.onCancel(); dismiss() },
                    onSave: { observer.onSave() }
                ) {
                    VStack(spacing: 20) {
                        ImageEditHeader(
                            shape: .rounded,
                            size: 120,
                            isUploading: observer.isUploadingCover,
                            canRemove: observer.displayCoverPath != nil,
                            onPicked: { observer.onCoverSelected($0) },
                            onRemove: { observer.onCoverRemoved() }
                        ) {
                            BookCoverImage(coverPath: observer.displayCoverPath)
                        }
                        .padding(.top, 8)

                        AppTextField(
                            placeholder: "",
                            text: Binding(get: { observer.name }, set: { observer.onNameChanged($0) }),
                            label: String(localized: "series.edit_name")
                        )
                        .fieldCard()
                        .padding(.horizontal)

                        AppTextField(
                            placeholder: String(localized: "series.edit_description_placeholder"),
                            text: Binding(
                                get: { observer.seriesDescription },
                                set: { observer.onDescriptionChanged($0) }
                            ),
                            label: String(localized: "series.edit_description"),
                            axis: .vertical
                        )
                        .fieldCard()
                        .padding(.horizontal)
                    }
                }
                .alert(
                    String(localized: "common.error"),
                    isPresented: Binding(get: { observer.error != nil }, set: { _ in observer.onDismissError() })
                ) {
                    Button(String(localized: "common.ok"), role: .cancel) { observer.onDismissError() }
                } message: {
                    Text(observer.error ?? "")
                }
                .onChange(of: observer.didFinish) { _, finished in if finished { dismiss() } }
            } else {
                LoadingStateView()
            }
        }
        .task(id: seriesId) {
            let obs = SeriesEditObserver(viewModel: deps.createSeriesEditViewModel())
            observer = obs
            obs.loadSeries(seriesId: seriesId)
        }
    }
}
