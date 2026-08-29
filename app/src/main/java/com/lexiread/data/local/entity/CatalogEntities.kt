package com.lexiread.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A stored catalogue page, so the last successful search result survives a
 * restart and a lost connection.
 *
 * The payload is the serialized page, not a row per book: a page is always read
 * and written as a whole, and normalizing `CatalogBook` into tables would need a
 * schema migration every time the domain model gains a field.
 */
@Entity(tableName = "catalog_cache")
data class CatalogCacheEntity(
    /** Deterministic: query + page + the set of sources that were queried. */
    @PrimaryKey val cacheKey: String,
    val kind: String,
    val query: String,
    val page: Int,
    val payload: String,
    val totalResults: Int?,
    val hasMore: Boolean,
    val timestamp: Long
)
