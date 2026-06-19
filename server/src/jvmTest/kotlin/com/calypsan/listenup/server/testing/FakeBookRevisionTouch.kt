package com.calypsan.listenup.server.testing

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.services.BookRevisionTouch

/**
 * A recording [BookRevisionTouch] fake for seam-level tests: every [touchRevision] call
 * appends the book id to [touched] and reports success, so a test can assert exactly which
 * books a collection-membership change bumped — without a real [BookRepository] in the graph.
 */
class FakeBookRevisionTouch : BookRevisionTouch {
    val touched = mutableListOf<String>()

    override suspend fun touchRevision(id: BookId): AppResult<Unit> {
        touched += id.value
        return AppResult.Success(Unit)
    }
}
