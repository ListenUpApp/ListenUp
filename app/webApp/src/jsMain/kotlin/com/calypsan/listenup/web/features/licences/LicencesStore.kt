package com.calypsan.listenup.web.features.licences

import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.await
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One library the web client ships, as `web/public/licences.json` describes it. */
@Serializable
data class LicencedLibrary(
    val uniqueId: String,
    val name: String,
    val artifactVersion: String? = null,
    val description: String? = null,
    val website: String? = null,
    val licenses: List<String> = emptyList(),
)

@Serializable
private data class LicenceManifest(
    val libraries: List<LicencedLibrary> = emptyList(),
)

/** What the Licences page can be showing. */
sealed interface LicencesUiState {
    /** The manifest has been asked for and has not arrived. */
    data object Loading : LicencesUiState

    /** The manifest, as shipped. */
    data class Ready(
        val libraries: List<LicencedLibrary>,
    ) : LicencesUiState

    /** The manifest could not be read. */
    data class Error(
        val message: String,
    ) : LicencesUiState
}

/** An open Licences stream, plus the teardown for it. */
class LicencesSession(
    val state: StateFlow<LicencesUiState>,
    val close: () -> Unit,
)

/**
 * How the page gets its manifest. Production fetches the static file ([graphLicences]); specs hand
 * over a fixed state instead ([fixedLicences]).
 */
typealias OpenLicences = () -> LicencesSession

/**
 * The production source: `/licences.json`, served from `web/public/`.
 *
 * ⛔ No Koin and no repository. This is a build artifact, not library data — it is generated from
 * the dependency graph by `:app:webApp:mergeWebLicences` and gated by `verifyWebLicences`, so the
 * only thing to do at runtime is read it.
 *
 * A failure is reported rather than swallowed into an empty list: "no libraries" and "the file did
 * not load" are different facts, and an attribution page claiming the first when the second is true
 * is the one lie this page cannot afford.
 */
fun graphLicences(): OpenLicences =
    {
        val state = MutableStateFlow<LicencesUiState>(LicencesUiState.Loading)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope.launch {
            state.value =
                runCatching {
                    val response = window.fetch(MANIFEST_PATH).await()
                    if (!response.ok) error("HTTP ${response.status}")
                    JSON_FORMAT.decodeFromString<LicenceManifest>(response.text().await())
                }.fold(
                    onSuccess = { LicencesUiState.Ready(it.libraries) },
                    onFailure = { LicencesUiState.Error("The licence list could not be loaded.") },
                )
        }
        LicencesSession(state = state, close = { scope.cancel() })
    }

/** A session over a state that never changes — the shape specs use in place of the fetch. */
fun fixedLicences(state: LicencesUiState): OpenLicences =
    { LicencesSession(state = MutableStateFlow(state), close = {}) }

private const val MANIFEST_PATH = "/licences.json"

/** The manifest carries fields this page does not read; ignoring them keeps it additive. */
private val JSON_FORMAT = Json { ignoreUnknownKeys = true }
