package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.dto.BookUpdate
import com.calypsan.listenup.api.dto.ContributorUpdate
import com.calypsan.listenup.api.dto.SeriesUpdate
import com.calypsan.listenup.api.dto.preferences.UpdateUserPreferencesRequest
import com.calypsan.listenup.api.dto.profile.UpdateProfileRequest

/**
 * The catalog of offline-editable domains. Each constant is the single source of truth for its
 * domain's outbox routing name and patch serializer, referenced by both the repository (write
 * half) and the DI `byDomain` map (push half).
 *
 * Adding a domain here plus one repo call and one DI line is the entire cost of making a domain
 * offline-first — see the generalization spec.
 */
internal val BookEdit = EditableDomain("books", BookUpdate.serializer())

internal val SeriesEdit = EditableDomain("series", SeriesUpdate.serializer())

internal val ContributorEdit = EditableDomain("contributors", ContributorUpdate.serializer())

internal val ProfileEdit = EditableDomain("profile", UpdateProfileRequest.serializer())

internal val PreferencesEdit = EditableDomain("preferences", UpdateUserPreferencesRequest.serializer())
