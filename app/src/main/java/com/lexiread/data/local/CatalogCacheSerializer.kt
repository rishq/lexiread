package com.lexiread.data.local

import com.lexiread.domain.model.CatalogBook
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/**
 * Serializes a catalogue page into the Room cache.
 *
 * Uses Moshi's reflection adapter rather than codegen on purpose: annotating
 * `CatalogBook` with `@JsonClass` would push a serialization concern into the
 * domain model and force data-layer concerns onto every field added there.
 */
class CatalogCacheSerializer(
    moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
) {

    private val adapter = moshi.adapter<List<CatalogBook>>(
        Types.newParameterizedType(List::class.java, CatalogBook::class.java)
    )

    fun serialize(books: List<CatalogBook>): String = adapter.toJson(books)

    fun deserialize(json: String): List<CatalogBook>? =
        runCatching { adapter.fromJson(json) }.getOrNull()
}
