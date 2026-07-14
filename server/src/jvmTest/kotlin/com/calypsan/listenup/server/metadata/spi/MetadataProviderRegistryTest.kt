package com.calypsan.listenup.server.metadata.spi

import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

// --- Tiny fakes: one object implementing several capability interfaces at once. ---

private class FakeCoverOnly(
    override val id: MetadataProviderId,
) : CoverSource {
    override suspend fun searchCovers(
        book: BookIdentity,
        locale: MetadataLocale,
    ): AppResult<List<CoverMeta>> = AppResult.Success(emptyList())
}

private class FakeCoreAndCover(
    override val id: MetadataProviderId,
) : BookCoreSource,
    CoverSource {
    override suspend fun getBookCore(
        book: BookIdentity,
        locale: MetadataLocale,
        refresh: Boolean,
    ): AppResult<BookCoreMeta?> = AppResult.Success(null)

    override suspend fun searchCovers(
        book: BookIdentity,
        locale: MetadataLocale,
    ): AppResult<List<CoverMeta>> = AppResult.Success(emptyList())
}

class MetadataProviderRegistryTest :
    FunSpec({
        test("capable() collects every provider implementing a capability, across the mixed set") {
            val audible = FakeCoreAndCover(MetadataProviderId.AUDIBLE)
            val itunes = FakeCoverOnly(MetadataProviderId.ITUNES)
            val registry = MetadataProviderRegistry(listOf(audible, itunes))

            registry.capable<CoverSource>() shouldContainExactlyInAnyOrder listOf(audible, itunes)
            registry.capable<BookCoreSource>() shouldContainExactlyInAnyOrder listOf(audible)
            registry.capable<ChapterSource>().shouldBe(emptyList())
        }

        test("get<C>(id) returns the provider only when it satisfies both the id and the capability") {
            val audible = FakeCoreAndCover(MetadataProviderId.AUDIBLE)
            val itunes = FakeCoverOnly(MetadataProviderId.ITUNES)
            val registry = MetadataProviderRegistry(listOf(audible, itunes))

            registry.get<BookCoreSource>(MetadataProviderId.AUDIBLE) shouldBe audible
            // iTunes is registered but is not a BookCoreSource → null, not a class cast.
            registry.get<BookCoreSource>(MetadataProviderId.ITUNES).shouldBeNull()
            // Unknown id → null.
            registry.get<CoverSource>(MetadataProviderId.AUDNEXUS).shouldBeNull()
        }

        test("byId keys each provider by its id") {
            val audible = FakeCoreAndCover(MetadataProviderId.AUDIBLE)
            val registry = MetadataProviderRegistry(listOf(audible))
            registry.byId[MetadataProviderId.AUDIBLE] shouldBe audible
            registry.byId[MetadataProviderId.ITUNES].shouldBeNull()
        }
    })
