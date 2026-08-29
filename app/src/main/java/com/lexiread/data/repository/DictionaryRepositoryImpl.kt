package com.lexiread.data.repository

import com.lexiread.core.util.recoverSuspendCatching
import com.lexiread.core.util.runSuspendCatching
import com.lexiread.data.local.dao.CacheDao
import com.lexiread.data.local.entity.DictionaryCacheEntity
import com.lexiread.data.remote.api.DictionaryApi
import com.lexiread.domain.model.DefinitionMeaning
import com.lexiread.domain.model.DictionaryEntry
import com.lexiread.domain.repository.DictionaryRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

class DictionaryRepositoryImpl(
    private val dictionaryApi: DictionaryApi,
    private val cacheDao: CacheDao
) : DictionaryRepository {

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(DictionaryEntry::class.java)

    override suspend fun lookupWord(word: String): Result<DictionaryEntry> {
        val cleanWord = word.trim().lowercase().removeSurrounding("\"", "\"").removeSurrounding("'", "'")
            .filter { it.isLetter() || it == '-' }

        if (cleanWord.isBlank()) {
            return Result.failure(IllegalArgumentException("Word is empty"))
        }

        // 1. Check Room cache first, honouring the same TTL used for cache purges.
        val cached = cacheDao.getDictionaryCache(cleanWord)
        if (cached != null && cached.timestamp >= System.currentTimeMillis() - CACHE_TTL_MS) {
            try {
                val parsed = adapter.fromJson(cached.jsonResult)
                if (parsed != null) return Result.success(parsed)
            } catch (e: Exception) {
                // Ignore parse errors from stale cache
            }
        }

        // 2. Fetch from Free Dictionary API
        val result = runSuspendCatching {
            val responseList = dictionaryApi.getDefinition(cleanWord)
            val firstEntry = responseList.firstOrNull()
                ?: throw NoSuchElementException("No definition found for $cleanWord")

            val phoneticsText = firstEntry.phonetic
                ?: firstEntry.phonetics?.firstOrNull { !it.text.isNullOrBlank() }?.text
                ?: "/$cleanWord/"

            val audioUrl = firstEntry.phonetics?.firstOrNull { !it.audio.isNullOrBlank() }?.audio

            val meanings = firstEntry.meanings?.map { m ->
                DefinitionMeaning(
                    partOfSpeech = m.partOfSpeech ?: "word",
                    definitions = m.definitions?.mapNotNull { it.definition } ?: emptyList(),
                    example = m.definitions?.firstOrNull { !it.example.isNullOrBlank() }?.example,
                    synonyms = m.synonyms ?: m.definitions?.flatMap { it.synonyms ?: emptyList() } ?: emptyList()
                )
            } ?: emptyList()

            val entry = DictionaryEntry(
                word = cleanWord,
                phonetics = phoneticsText,
                audioUrl = audioUrl,
                meanings = meanings
            )

            // Cache in Room
            val jsonStr = adapter.toJson(entry)
            cacheDao.insertDictionaryCache(
                DictionaryCacheEntity(
                    word = cleanWord,
                    jsonResult = jsonStr,
                    timestamp = System.currentTimeMillis()
                )
            )

            entry
        }

        // A word that genuinely has no entry must surface as "not found";
        // inventing a definition for it hides real lookups that failed.
        if (result.exceptionOrNull() is NoSuchElementException) {
            return result
        }

        // Offline / transient failure: degrade to a clearly-labelled stub so the
        // reader screen still has something to show.
        return result.recoverSuspendCatching {
            DictionaryEntry(
                word = cleanWord,
                phonetics = "/$cleanWord/",
                audioUrl = null,
                meanings = listOf(
                    DefinitionMeaning(
                        partOfSpeech = "unknown",
                        definitions = listOf("Definition unavailable offline for '$cleanWord'."),
                        example = null,
                        synonyms = emptyList()
                    )
                )
            )
        }
    }

    private companion object {
        const val CACHE_TTL_MS = 30L * 24 * 60 * 60 * 1000
    }
}
