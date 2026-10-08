package com.calypsan.listenup.server.matching

import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.dto.match.ExternalRef
import com.calypsan.listenup.server.metadata.spi.FoundPerson
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId

/** One person, as one source found them. */
internal data class SourcedPerson(
    val source: MetadataProviderId,
    val person: FoundPerson,
)

/** A person merged across the sources that agree they are the same person; [sources] in route order. */
internal data class MergedPerson(
    val refs: List<ExternalRef>,
    val sources: List<MetadataProviderId>,
    val name: String,
    val roles: Set<ContributorRole>,
    val photoUrl: String?,
    val knownWorks: List<String>,
    val worksCount: Int?,
    val creditedBookIds: Set<String>,
    val viaLink: Boolean,
    val foundByName: Boolean,
)

/**
 * Conservative merging of people (decision D8): two sources' hits are one person only when their names match
 * (order- and case-insensitively) **and** both credit them on at least one of the same library books. Within a
 * source nothing merges — two same-named people stay two. Shown apart costs a glance; merged wrongly would apply
 * a stranger's photo and biography.
 */
internal object PeopleMerger {
    fun merge(people: List<SourcedPerson>): List<MergedPerson> {
        val merged = mutableListOf<MutableList<SourcedPerson>>()
        people.forEach { hit ->
            val group =
                merged.firstOrNull { group ->
                    group.none { it.source == hit.source } && group.any { it.sameAs(hit) }
                }
            if (group != null) group += hit else merged += mutableListOf(hit)
        }
        return merged.map { it.toMerged() }
    }

    private fun SourcedPerson.sameAs(other: SourcedPerson): Boolean =
        nameKey(person.name) == nameKey(other.person.name) &&
            person.creditedBookIds.any { it in other.person.creditedBookIds }

    private fun List<SourcedPerson>.toMerged(): MergedPerson {
        val first = first().person
        return MergedPerson(
            refs = map { ExternalRef(it.source.value, it.person.key) },
            sources = map { it.source },
            name = first.name,
            roles = flatMapTo(mutableSetOf()) { it.person.roles },
            photoUrl = firstNotNullOfOrNull { it.person.photoUrl },
            knownWorks = firstOrNull { it.person.knownWorks.isNotEmpty() }?.run { person.knownWorks }.orEmpty(),
            worksCount = firstNotNullOfOrNull { it.person.worksCount },
            creditedBookIds = flatMapTo(mutableSetOf()) { it.person.creditedBookIds },
            viaLink = any { it.person.viaLink },
            foundByName = any { it.person.foundByName },
        )
    }
}

/** A name's comparison key: its letters and digits, lower-cased, as a sorted set of words. */
internal fun nameKey(name: String): String =
    name
        .lowercase()
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.isNotEmpty() }
        .sorted()
        .joinToString(" ")
