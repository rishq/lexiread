package com.lexiread.data.local

import com.lexiread.domain.model.Author
import com.lexiread.domain.model.BookFormat
import com.lexiread.domain.model.BookIdentifiers
import com.lexiread.domain.model.CatalogBook
import com.lexiread.domain.model.DefinitionMeaning
import com.lexiread.domain.model.DictionaryEntry
import com.lexiread.domain.model.FormatKind
import com.lexiread.domain.model.SourceKind
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.lang.reflect.Type

/**
 * Hand-written Moshi adapters for the domain models that get persisted as JSON
 * (the catalogue page cache and the dictionary cache).
 *
 * Two alternatives were deliberately rejected:
 *
 * 1. `@JsonClass(generateAdapter = true)` on the domain models. That would push a
 *    serialization concern into `domain/model` and force every field added there
 *    to think about the wire format. See [CatalogCacheSerializer] for the
 *    original rationale, which these adapters preserve.
 * 2. Moshi's reflective `KotlinJsonAdapterFactory`. It is convenient, but it
 *    makes `moshi-kotlin` -> `kotlin-reflect` reachable, and `kotlin-reflect` is
 *    a 3.3 MB jar of which **965 classes survived R8** in the release APK -
 *    roughly a megabyte of dex, spent on exactly two call sites.
 *
 * The wire format is byte-for-byte what the reflective adapter produced, so
 * caches written by earlier versions still deserialize:
 *
 * - property names are the Kotlin property names, in declaration order;
 * - nulls are omitted (`JsonWriter.serializeNulls` is off by default);
 * - computed properties such as `CatalogBook.authorLine` / `CatalogBook.canRead`
 *   were never constructor parameters and are not serialized;
 * - enums are written as their `name`, never their `label`;
 * - unknown properties are skipped, absent ones fall back to the default.
 *
 * [DomainJsonAdaptersTest] pins all of that down, including a fixture written in
 * the old reflective format.
 */
internal object DomainJsonAdapters : JsonAdapter.Factory {

    override fun create(type: Type, annotations: Set<Annotation>, moshi: Moshi): JsonAdapter<*>? {
        if (annotations.isNotEmpty()) return null
        return when (type) {
            CatalogBook::class.java -> CatalogBookAdapter(moshi)
            Author::class.java -> AuthorAdapter()
            BookFormat::class.java -> BookFormatAdapter()
            BookIdentifiers::class.java -> BookIdentifiersAdapter()
            DictionaryEntry::class.java -> DictionaryEntryAdapter(moshi)
            DefinitionMeaning::class.java -> DefinitionMeaningAdapter(moshi)
            else -> null
        }
    }
}

/**
 * Moshi instance for the domain models that are cached as JSON.
 *
 * Kept separate from the Retrofit instance on purpose: the network layer only
 * ever sees DTOs, which all carry `@JsonClass(generateAdapter = true)`, so it
 * needs no custom adapters.
 */
internal val domainMoshi: Moshi = Moshi.Builder()
    .add(DomainJsonAdapters)
    .build()

// ---------------------------------------------------------------------------
// Reader helpers. Moshi's `nextXOrNull()` does not exist in core, and the
// generated adapters spell these out inline; factoring them out keeps the
// adapters readable.
// ---------------------------------------------------------------------------

private fun JsonReader.nextIntOrNull(): Int? =
    if (peek() == JsonReader.Token.NULL) nextNull<Int?>() else nextInt()

private fun JsonReader.nextStringOrNull(): String? =
    if (peek() == JsonReader.Token.NULL) nextNull<String?>() else nextString()

private fun JsonReader.nextBooleanOrNull(): Boolean? =
    if (peek() == JsonReader.Token.NULL) nextNull<Boolean?>() else nextBoolean()

private fun JsonReader.nextSourceKind(): SourceKind {
    val raw = nextString()
    return SourceKind.entries.firstOrNull { it.name == raw }
        ?: throw JsonDataException("Expected one of ${SourceKind.entries.map { it.name }}, was '$raw' at $path")
}

private fun JsonReader.nextFormatKind(): FormatKind {
    val raw = nextString()
    return FormatKind.entries.firstOrNull { it.name == raw }
        ?: throw JsonDataException("Expected one of ${FormatKind.entries.map { it.name }}, was '$raw' at $path")
}

/** Reads a `List<T>` with [adapter], treating an explicit `null` as an empty list. */
private fun <T> JsonReader.nextListOrEmpty(adapter: JsonAdapter<List<T>>): List<T> =
    if (peek() == JsonReader.Token.NULL) nextNull<List<T>>() ?: emptyList() else adapter.fromJson(this) ?: emptyList()

/** Writes a `List<T>` with [adapter]. `nullValue()` is a no-op for null, so the property is omitted. */
private fun <T> JsonWriter.writeListOrNull(adapter: JsonAdapter<List<T>>, value: List<T>?): JsonWriter {
    if (value == null) return nullValue()
    adapter.toJson(this, value)
    return this
}

// ---------------------------------------------------------------------------
// Adapters
// ---------------------------------------------------------------------------

private class AuthorAdapter : JsonAdapter<Author>() {

    private val options = JsonReader.Options.of("name", "birthYear", "deathYear")

    override fun fromJson(reader: JsonReader): Author {
        var name: String? = null
        var birthYear: Int? = null
        var deathYear: Int? = null

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.selectName(options)) {
                0 -> name = reader.nextString()
                1 -> birthYear = reader.nextIntOrNull()
                2 -> deathYear = reader.nextIntOrNull()
                else -> {
                    reader.skipName()
                    reader.skipValue()
                }
            }
        }
        reader.endObject()

        return Author(
            name = name ?: throw JsonDataException("Required value 'name' missing at ${reader.path}"),
            birthYear = birthYear,
            deathYear = deathYear
        )
    }

    override fun toJson(writer: JsonWriter, value: Author?) {
        if (value == null) {
            writer.nullValue()
            return
        }
        writer.beginObject()
        writer.name("name").value(value.name)
        writer.name("birthYear").value(value.birthYear)
        writer.name("deathYear").value(value.deathYear)
        writer.endObject()
    }
}

private class BookFormatAdapter : JsonAdapter<BookFormat>() {

    private val options = JsonReader.Options.of("kind", "mimeType", "url")

    override fun fromJson(reader: JsonReader): BookFormat {
        var kind: FormatKind? = null
        var mimeType: String? = null
        var url: String? = null

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.selectName(options)) {
                0 -> kind = reader.nextFormatKind()
                1 -> mimeType = reader.nextString()
                2 -> url = reader.nextString()
                else -> {
                    reader.skipName()
                    reader.skipValue()
                }
            }
        }
        reader.endObject()

        return BookFormat(
            kind = kind ?: throw JsonDataException("Required value 'kind' missing at ${reader.path}"),
            mimeType = mimeType ?: throw JsonDataException("Required value 'mimeType' missing at ${reader.path}"),
            url = url ?: throw JsonDataException("Required value 'url' missing at ${reader.path}")
        )
    }

    override fun toJson(writer: JsonWriter, value: BookFormat?) {
        if (value == null) {
            writer.nullValue()
            return
        }
        writer.beginObject()
        writer.name("kind").value(value.kind.name)
        writer.name("mimeType").value(value.mimeType)
        writer.name("url").value(value.url)
        writer.endObject()
    }
}

private class BookIdentifiersAdapter : JsonAdapter<BookIdentifiers>() {

    private val options = JsonReader.Options.of(
        "gutenbergId", "openLibraryWorkId", "googleBooksId", "isbn13", "isbn10"
    )

    override fun fromJson(reader: JsonReader): BookIdentifiers {
        var gutenbergId: Int? = null
        var openLibraryWorkId: String? = null
        var googleBooksId: String? = null
        var isbn13: String? = null
        var isbn10: String? = null

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.selectName(options)) {
                0 -> gutenbergId = reader.nextIntOrNull()
                1 -> openLibraryWorkId = reader.nextStringOrNull()
                2 -> googleBooksId = reader.nextStringOrNull()
                3 -> isbn13 = reader.nextStringOrNull()
                4 -> isbn10 = reader.nextStringOrNull()
                else -> {
                    reader.skipName()
                    reader.skipValue()
                }
            }
        }
        reader.endObject()

        return BookIdentifiers(
            gutenbergId = gutenbergId,
            openLibraryWorkId = openLibraryWorkId,
            googleBooksId = googleBooksId,
            isbn13 = isbn13,
            isbn10 = isbn10
        )
    }

    override fun toJson(writer: JsonWriter, value: BookIdentifiers?) {
        if (value == null) {
            writer.nullValue()
            return
        }
        writer.beginObject()
        writer.name("gutenbergId").value(value.gutenbergId)
        writer.name("openLibraryWorkId").value(value.openLibraryWorkId)
        writer.name("googleBooksId").value(value.googleBooksId)
        writer.name("isbn13").value(value.isbn13)
        writer.name("isbn10").value(value.isbn10)
        writer.endObject()
    }
}

private class CatalogBookAdapter(private val moshi: Moshi) : JsonAdapter<CatalogBook>() {

    // Resolved lazily: looking these up inside `create()` would recurse back into
    // Moshi while it is still assembling this adapter.
    private val authorListAdapter: JsonAdapter<List<Author>> by lazy {
        moshi.adapter(Types.newParameterizedType(List::class.java, Author::class.java))
    }
    private val bookFormatListAdapter: JsonAdapter<List<BookFormat>> by lazy {
        moshi.adapter(Types.newParameterizedType(List::class.java, BookFormat::class.java))
    }
    private val stringListAdapter: JsonAdapter<List<String>> by lazy {
        moshi.adapter(Types.newParameterizedType(List::class.java, String::class.java))
    }
    private val bookIdentifiersAdapter: JsonAdapter<BookIdentifiers> by lazy {
        moshi.adapter(BookIdentifiers::class.java)
    }

    private val options = JsonReader.Options.of(
        "id", "title", "authors", "coverUrl", "description", "language",
        "subjects", "source", "formats", "identifiers", "isPublicDomain",
        "downloadCount", "publishedYear"
    )

    override fun fromJson(reader: JsonReader): CatalogBook {
        var id: String? = null
        var title: String? = null
        var authors: List<Author> = emptyList()
        var coverUrl: String? = null
        var description: String? = null
        var language: String? = null
        var subjects: List<String> = emptyList()
        var source: SourceKind? = null
        var formats: List<BookFormat> = emptyList()
        var identifiers: BookIdentifiers = BookIdentifiers()
        var isPublicDomain = false
        var downloadCount: Int? = null
        var publishedYear: Int? = null

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.selectName(options)) {
                0 -> id = reader.nextString()
                1 -> title = reader.nextString()
                2 -> authors = reader.nextListOrEmpty(authorListAdapter)
                3 -> coverUrl = reader.nextStringOrNull()
                4 -> description = reader.nextStringOrNull()
                5 -> language = reader.nextStringOrNull()
                6 -> subjects = reader.nextListOrEmpty(stringListAdapter)
                7 -> source = reader.nextSourceKind()
                8 -> formats = reader.nextListOrEmpty(bookFormatListAdapter)
                9 -> identifiers = if (reader.peek() == JsonReader.Token.NULL) {
                    reader.nextNull<BookIdentifiers?>()
                    BookIdentifiers()
                } else {
                    bookIdentifiersAdapter.fromJson(reader) ?: BookIdentifiers()
                }
                10 -> isPublicDomain = reader.nextBooleanOrNull() ?: false
                11 -> downloadCount = reader.nextIntOrNull()
                12 -> publishedYear = reader.nextIntOrNull()
                else -> {
                    reader.skipName()
                    reader.skipValue()
                }
            }
        }
        reader.endObject()

        return CatalogBook(
            id = id ?: throw JsonDataException("Required value 'id' missing at ${reader.path}"),
            title = title ?: throw JsonDataException("Required value 'title' missing at ${reader.path}"),
            authors = authors,
            coverUrl = coverUrl,
            description = description,
            language = language,
            subjects = subjects,
            source = source ?: throw JsonDataException("Required value 'source' missing at ${reader.path}"),
            formats = formats,
            identifiers = identifiers,
            isPublicDomain = isPublicDomain,
            downloadCount = downloadCount,
            publishedYear = publishedYear
        )
    }

    override fun toJson(writer: JsonWriter, value: CatalogBook?) {
        if (value == null) {
            writer.nullValue()
            return
        }
        writer.beginObject()
        writer.name("id").value(value.id)
        writer.name("title").value(value.title)
        writer.name("authors").writeListOrNull(authorListAdapter, value.authors)
        writer.name("coverUrl").value(value.coverUrl)
        writer.name("description").value(value.description)
        writer.name("language").value(value.language)
        writer.name("subjects").writeListOrNull(stringListAdapter, value.subjects)
        writer.name("source").value(value.source.name)
        writer.name("formats").writeListOrNull(bookFormatListAdapter, value.formats)
        writer.name("identifiers").let { bookIdentifiersAdapter.toJson(writer, value.identifiers) }
        writer.name("isPublicDomain").value(value.isPublicDomain)
        writer.name("downloadCount").value(value.downloadCount)
        writer.name("publishedYear").value(value.publishedYear)
        writer.endObject()
    }
}

private class DefinitionMeaningAdapter(private val moshi: Moshi) : JsonAdapter<DefinitionMeaning>() {

    private val stringListAdapter: JsonAdapter<List<String>> by lazy {
        moshi.adapter(Types.newParameterizedType(List::class.java, String::class.java))
    }

    private val options = JsonReader.Options.of("partOfSpeech", "definitions", "example", "synonyms")

    override fun fromJson(reader: JsonReader): DefinitionMeaning {
        var partOfSpeech: String? = null
        var definitions: List<String> = emptyList()
        var example: String? = null
        var synonyms: List<String> = emptyList()

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.selectName(options)) {
                0 -> partOfSpeech = reader.nextString()
                1 -> definitions = reader.nextListOrEmpty(stringListAdapter)
                2 -> example = reader.nextStringOrNull()
                3 -> synonyms = reader.nextListOrEmpty(stringListAdapter)
                else -> {
                    reader.skipName()
                    reader.skipValue()
                }
            }
        }
        reader.endObject()

        return DefinitionMeaning(
            partOfSpeech = partOfSpeech
                ?: throw JsonDataException("Required value 'partOfSpeech' missing at ${reader.path}"),
            definitions = definitions,
            example = example,
            synonyms = synonyms
        )
    }

    override fun toJson(writer: JsonWriter, value: DefinitionMeaning?) {
        if (value == null) {
            writer.nullValue()
            return
        }
        writer.beginObject()
        writer.name("partOfSpeech").value(value.partOfSpeech)
        writer.name("definitions").writeListOrNull(stringListAdapter, value.definitions)
        writer.name("example").value(value.example)
        writer.name("synonyms").writeListOrNull(stringListAdapter, value.synonyms)
        writer.endObject()
    }
}

private class DictionaryEntryAdapter(private val moshi: Moshi) : JsonAdapter<DictionaryEntry>() {

    private val meaningListAdapter: JsonAdapter<List<DefinitionMeaning>> by lazy {
        moshi.adapter(Types.newParameterizedType(List::class.java, DefinitionMeaning::class.java))
    }

    private val options = JsonReader.Options.of("word", "phonetics", "audioUrl", "meanings")

    override fun fromJson(reader: JsonReader): DictionaryEntry {
        var word: String? = null
        var phonetics = ""
        var audioUrl: String? = null
        var meanings: List<DefinitionMeaning> = emptyList()

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.selectName(options)) {
                0 -> word = reader.nextString()
                1 -> phonetics = reader.nextStringOrNull() ?: ""
                2 -> audioUrl = reader.nextStringOrNull()
                3 -> meanings = reader.nextListOrEmpty(meaningListAdapter)
                else -> {
                    reader.skipName()
                    reader.skipValue()
                }
            }
        }
        reader.endObject()

        return DictionaryEntry(
            word = word ?: throw JsonDataException("Required value 'word' missing at ${reader.path}"),
            phonetics = phonetics,
            audioUrl = audioUrl,
            meanings = meanings
        )
    }

    override fun toJson(writer: JsonWriter, value: DictionaryEntry?) {
        if (value == null) {
            writer.nullValue()
            return
        }
        writer.beginObject()
        writer.name("word").value(value.word)
        writer.name("phonetics").value(value.phonetics)
        writer.name("audioUrl").value(value.audioUrl)
        writer.name("meanings").writeListOrNull(meaningListAdapter, value.meanings)
        writer.endObject()
    }
}
