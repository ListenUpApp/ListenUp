package com.calypsan.listenup.api.dto

/**
 * The role a contributor plays in creating an audiobook.
 *
 * The shared vocabulary behind the `role` string carried by
 * [com.calypsan.listenup.api.sync.BookContributorPayload] and the book-edit
 * contributor inputs. [apiValue] is the canonical lowercase token; the wire and
 * DB continue to carry that string, and both the server scanner and the client
 * resolve it to this enum via [fromApiValue]. Promoted to `:contract` so server
 * and client share one definition — closing the role-string drift seam that let
 * the scanner emit un-typed values.
 */
enum class ContributorRole(
    val apiValue: String,
) {
    AUTHOR("author"),
    NARRATOR("narrator"),
    EDITOR("editor"),
    TRANSLATOR("translator"),
    FOREWORD("foreword"),
    INTRODUCTION("introduction"),
    AFTERWORD("afterword"),
    PRODUCER("producer"),
    ADAPTER("adapter"),
    ILLUSTRATOR("illustrator"),
    ;

    companion object {
        /** Resolves a stored/wire [value] (case-insensitive) to a role, or null if unrecognized. */
        fun fromApiValue(value: String): ContributorRole? =
            entries.find { it.apiValue.equals(value, ignoreCase = true) }
    }
}
