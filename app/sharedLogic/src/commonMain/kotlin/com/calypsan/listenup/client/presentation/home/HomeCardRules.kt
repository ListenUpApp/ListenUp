package com.calypsan.listenup.client.presentation.home

import com.calypsan.listenup.client.domain.model.ContinueListeningBook

/**
 * Whether this Also-in-progress card needs a connection it does not have (spec §2.5 "Offline (A, i)"): the device or
 * the server is out of reach and none of the book is on this device. The card stays tappable; Book Detail explains.
 */
fun ContinueListeningBook.needsConnection(connectivity: HomeConnectivity): Boolean =
    connectivity != HomeConnectivity.Online && !isDownloaded
