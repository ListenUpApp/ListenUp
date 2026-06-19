package com.calypsan.listenup.server.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

class BookChildrenTablesTest :
    FunSpec({

        test("BookChapterTable columns") {
            BookChapterTable.columns.map { it.name } shouldContainExactlyInAnyOrder
                listOf("book_id", "ordinal", "id", "title", "duration", "start_time")
        }

        test("BookChapterTable tableName is 'book_chapters'") {
            BookChapterTable.tableName shouldBe "book_chapters"
        }

        test("BookChapterTable composite PK is (book_id, ordinal)") {
            BookChapterTable.primaryKey?.columns?.map { it.name } shouldBe
                listOf("book_id", "ordinal")
        }

        test("BookAudioFileTable columns") {
            BookAudioFileTable.columns.map { it.name } shouldContainExactlyInAnyOrder
                listOf("book_id", "ordinal", "id", "filename", "format", "codec", "duration", "size")
        }

        test("BookAudioFileTable tableName is 'book_audio_files'") {
            BookAudioFileTable.tableName shouldBe "book_audio_files"
        }

        test("BookAudioFileTable composite PK is (book_id, ordinal)") {
            BookAudioFileTable.primaryKey?.columns?.map { it.name } shouldBe
                listOf("book_id", "ordinal")
        }
    })
