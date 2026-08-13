package com.example.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.data.local.dao.BookDao
import com.example.data.local.dao.BookmarkDao
import com.example.data.local.dao.CacheDao
import com.example.data.local.dao.ReadingProgressDao
import com.example.data.local.dao.SavedWordDao
import com.example.data.local.entity.AiExplanationEntity
import com.example.data.local.entity.BookEntity
import com.example.data.local.entity.BookmarkEntity
import com.example.data.local.entity.DictionaryCacheEntity
import com.example.data.local.entity.ReadingProgressEntity
import com.example.data.local.entity.SavedWordEntity
import com.example.data.local.entity.TranslationCacheEntity

@Database(
    entities = [
        BookEntity::class,
        ReadingProgressEntity::class,
        SavedWordEntity::class,
        BookmarkEntity::class,
        DictionaryCacheEntity::class,
        TranslationCacheEntity::class,
        AiExplanationEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun readingProgressDao(): ReadingProgressDao
    abstract fun savedWordDao(): SavedWordDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun cacheDao(): CacheDao
}
