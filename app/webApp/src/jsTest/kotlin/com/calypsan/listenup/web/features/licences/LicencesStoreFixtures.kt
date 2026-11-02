package com.calypsan.listenup.web.features.licences

import kotlinx.coroutines.flow.MutableStateFlow

/** A session over a state that never changes — the shape specs use in place of the fetch. */
fun fixedLicences(state: LicencesUiState): OpenLicences =
    { LicencesSession(state = MutableStateFlow(state), close = {}) }
