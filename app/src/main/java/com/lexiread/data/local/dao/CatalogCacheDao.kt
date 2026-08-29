package com.lexiread.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lexiread.data.local.entity.CatalogCacheEntity

@Dao
interface CatalogCacheDao {

    @Query("SELECT * FROM catalog_cache WHERE cacheKey = :key")
    suspend fun get(key: String): CatalogCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: CatalogCacheEntity)

    @Query("DELETE FROM catalog_cache WHERE timestamp < :expireBefore")
    suspend fun deleteExpired(expireBefore: Long)

    @Query("DELETE FROM catalog_cache")
    suspend fun clear()
}
