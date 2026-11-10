package com.calypsan.listenup.client.presentation.bookedit

import com.calypsan.listenup.client.presentation.edit.FormSlot
import kotlinx.coroutines.flow.MutableStateFlow

/** A slot over a bare form, so a delegate spec can drive the form without a whole ViewModel. */
internal fun MutableStateFlow<BookEditForm>.asSlot(): FormSlot<BookEditForm, BookEditForm> =
    FormSlot(state = this, formOf = { it }, withForm = { _, form -> form })
