package com.lexiread.data.local

import com.lexiread.domain.model.CatalogBook
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

/**
 * Serializes a catalogue page into the Room cache.
 *
 * Uses the hand-written [domainMoshi] adapters rather than Moshi's reflection
 * adapter or codegen on purpose:
 * - annotating `CatalogBook` with `@JsonClass` would push a serialization
 *   concern into the domain model and force data-layer concerns onto every
 *   field added there;
 * - `KotlinJsonAdapterFactory` resolves constructor fields reflectively at
 *   runtime, which R8 breaks in minified releases
 *   (AssertionError "Missing field in ...SourceKind" on opening search —
 *   the serializer is built when BooksRepositoryImpl is created).
 * The wire format matches what the reflective adapter produced, so caches
 * written by earlier versions still deserialize.
 */
class CatalogCacheSerializer(
    moshi: Moshi = domainMoshi
) {

    private val adapter = moshi.adapter<List<CatalogBook>>(
        Types.newParameterizedType(List::class.java, CatalogBook::class.java)
    )

    fun serialize(books: List<CatalogBook>): String = adapter.toJson(books)

    fun deserialize(json: String): List<CatalogBook>? =
        runCatching { adapter.fromJson(json) }.getOrNull()
}
