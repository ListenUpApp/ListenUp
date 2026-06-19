package com.calypsan.listenup.server.sync

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll

private object SampleUserScopedTable : UserScopedSyncableTable("sample_user_scoped")

class UserScopedSyncableTableTest :
    FunSpec({
        test("a UserScopedSyncableTable has the SyncableTable columns plus user_id") {
            val columns = SampleUserScopedTable.columns.map { it.name }
            columns shouldContainAll listOf("revision", "created_at", "updated_at", "deleted_at", "client_op_id", "user_id")
        }
    })
