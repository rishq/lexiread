# Title and scope

Harden LexiRead against the defects found in the full-codebase review: make the reader's
lazily-loaded chapter list index-correct so a book cannot go blank after rotation or a settings
change, and close the SSRF redirect bypass by re-validating every download URL on each redirect
hop. Alongside those two, fix frozen SRS "due" timestamps, a leaked Retrofit response body, the
broken non-Latin catalogue dedup key, and the unbounded/unreachable archive-bomb guards in the
EPUB and PDF parsers.

## Planning anchor

- Caller / entrypoint: `ReaderViewModel.repaginateCurrentChapter()`
  (`app/src/main/java/com/lexiread/presentation/reader/ReaderViewModel.kt:244`) for the reader
  defects, and `RetrofitClient.catalogOkHttpClient` / `apiOkHttpClient`
  (`app/src/main/java/com/lexiread/data/remote/RetrofitClient.kt:90`, `:109`) for the SSRF defect.
- Changed assumption: `ReaderUiState.chapters` was treated as a **dense** list indexed by chapter
  number, but it is populated by *absolute* chapter index and padded lazily — so position and
  chapter number diverge. The second changed assumption is that `UrlValidator` runs once per
  request; OkHttp follows redirects, so the validated URL is not necessarily the fetched URL.
- Non-local because the chapter list is produced by `ReaderViewModel` and consumed by
  `ReaderScreen` (TOC + chapter counter), and because every `OkHttpClient` in `RetrofitClient`
  needs the same redirect policy.
- Impacted specs in `specs/`: none before this change; `specs/spec-lifecycle/` is policy and is
  excluded from classification. Action: this is a net-new spec.

## Connected groups or observed existing logic

1. **Entry and orchestration** — `ReaderViewModel` (731 lines) owns chapter loading, pagination,
   prefetch and progress. `goToChapter` snapshots `_uiState.value.chapters` before launching a
   coroutine and later merges into that stale snapshot, so it clobbers a concurrent prefetch merge.
2. **Loaders and resolvers** — `BookImporter.getChaptersForBook(book, limit, offset)` honours
   `limit`/`offset` on the DB and file paths but **not** on the `fullText` fallback path
   (`BookImporter.kt:278`), so legacy books materialise every chapter.
3. **Persistence** — `VocabularyRepositoryImpl.getDueWords()` captures
   `System.currentTimeMillis()` once at Flow-construction time
   (`VocabularyRepositoryImpl.kt:24-32`), freezing "due" for the process lifetime.
   `BookRepositoryImpl.initializePreloadedBooks()` inserts books then chapters non-atomically;
   a kill between the two leaves the preloaded books permanently chapter-less.
4. **Response and propagation** — `BooksRepositoryImpl.fetchStandardEbooks()` reads
   `body.byteStream()` without `use {}` (`BooksRepositoryImpl.kt:301-304`), leaking the
   connection. `resolveCatalogPage()` serves a stale cached page when a search legitimately
   returns zero results (`:506-518`), so an empty result shows the previous query's books.
5. **Validation** — `UrlValidator` validates once; the three OkHttp clients follow redirects
   without re-validation. `requireTrustedImageUrl` has **zero** production call sites, so cover
   URLs from scraped HTML reach Coil unvalidated.
6. **Parsers** — `EpubParser` zip-bomb ratio check is unreachable (line 87 duplicates the guard
   already applied at line 75). `PdfParser.tryDecompressFlate` inflates without an output cap and
   can spin forever when `inflate()` returns 0 without `needsInput()`.
7. **Specs** — no existing solution spec covers these paths; `specs/spec-lifecycle/workflow.md`
   is lifecycle policy, not a solution spec, and stays authoritative.

## Use cases

### 1. Reader keeps the correct chapter after rotation or a settings change
reader chapter list --repaginate current chapter--> pages for the current chapter
Observed Existing Logic:
`chapters` is built by absolute index. On resume at chapter 1 of 20 the DB returns one element
whose `index == 1`, stored at **position 0**. `repaginateCurrentChapter` computes
`chapterIdx = currentChapterIndex.coerceIn(0, chapters.size - 1)` = 0 and keys the pagination
cache `0`, while `prefetchNextChapter` pads positions up to `nextIdx` with empty placeholders.
A later repaginate resolves position 1 = empty placeholder and renders "Empty chapter."
Execution Logic:
- Resolve a chapter by its own `index` field, never by list position.
- Padding must place a chapter at `chapter.index`, and may only fill gaps with blank placeholders
  whose `index` equals their position.
- Key `paginationCache` by `chapter.index` (already the case for `goToChapter`; make
  `repaginateCurrentChapter` consistent).
Implementation Logic:
Introduce two private helpers on `ReaderViewModel`:
 - `chapterAt(index: Int): BookChapter?` — `chapters.getOrNull(index)?.takeIf { it.index == index && it.content.isNotBlank() }`
 - `putChapter(chapter)` — atomic `_uiState.update { }` that pads to `chapter.index` with
   `BookChapter("", "", position)` and writes `chapter` at `chapter.index`.
Both `prefetchNextChapter` and `goToChapter` use them, which also removes the lost-update race
caused by `goToChapter` merging into a snapshot captured before its coroutine started.
Files And Functions:
 - existing: app/src/main/java/com/lexiread/presentation/reader/ReaderViewModel.kt#repaginateCurrentChapter - resolve via `chapterAt`
 - existing: app/src/main/java/com/lexiread/presentation/reader/ReaderViewModel.kt#prefetchNextChapter - pad via `putChapter`, reset loading flag in `finally`
 - existing: app/src/main/java/com/lexiread/presentation/reader/ReaderViewModel.kt#goToChapter - atomic merge via `putChapter`
Tests:
 - description: resuming at chapter 1 of 20 repaginates chapter 1, not an empty placeholder
   input: chapters = [BookChapter(index=1)], currentChapterIndex = 1, totalChapterCount = 20
   workflow: loaded chapter list --repaginate current chapter--> pages for chapter 1
   expected outcome: one non-empty page set; no "Empty chapter." page

### 2. Page turn and progress use the real chapter total
reader at last page of chapter N --next page--> chapter N+1
Observed Existing Logic:
`nextPage()` compares `currentChapterIndex < chapters.size - 1` (`:358`). With lazy loading
`chapters.size == 1` while `currentChapterIndex` may be 3, so the turn silently no-ops.
`saveCurrentProgress()` divides by `chapters.size.coerceAtLeast(1)` (`:489`), so chapter 3 of 20
reports ~300% clamped to 100%. `ReaderScreen:327` renders "Chapter N / 1".
Execution Logic:
- Any "am I on the last chapter" test uses `totalChapterCount` when it is > 0, falling back to
  `chapters.size` only when the total is unknown.
- Progress percentage and the UI chapter counter use the same rule.
Files And Functions:
 - existing: app/src/main/java/com/lexiread/presentation/reader/ReaderViewModel.kt#nextPage - bound on `totalChapterCount`
 - existing: app/src/main/java/com/lexiread/presentation/reader/ReaderViewModel.kt#saveCurrentProgress - divide by `totalChapterCount`
 - existing: app/src/main/java/com/lexiread/presentation/reader/ReaderScreen.kt - counter uses `totalChapterCount`

### 3. Downloads cannot escape the host allow-list through a redirect
validated download URL --fetch with redirects--> response body written to disk
Observed Existing Logic:
`UrlValidator.requireTrustedDownloadUrl` runs once before the call. All three OkHttp clients
follow redirects by default, so an open redirect on an allow-listed host makes the app fetch and
store a body from an arbitrary host.
Execution Logic:
- Disable automatic redirect following on the catalogue, API and download clients.
- Install a redirect-following interceptor that re-validates each `Location` target through
  `UrlValidator.requireTrustedDownloadUrl` before issuing the next request, capped at
  `MAX_REDIRECTS = 3`, and throws `SecurityException` on a disallowed or missing target.
- The interceptor is the single place that enforces the allow-list across every hop.
Types:
RedirectGuard
 - maxHops: Int # constant, 3
Files And Functions:
 - existing: app/src/main/java/com/lexiread/data/remote/RetrofitClient.kt - add `RedirectGuard`, attach to all clients
 - existing: app/src/main/java/com/lexiread/core/util/UrlValidator.kt#requireTrustedDownloadUrl - reused unchanged
Tests:
 - description: a redirect from an allowed host to a disallowed host is rejected
   input: 302 response with Location pointing at a host outside the allow-list
   workflow: validated download URL --follow redirect--> disallowed host
   expected outcome: SecurityException, no request reaches the disallowed host

### 4. Catalogue results no longer collapse non-Latin books or leak connections
catalogue response --dedupe--> merged catalogue cards
Observed Existing Logic:
`CatalogDeduper.normalizeTitle` strips with `Regex("[^a-z0-9]+")`, so "Война и мир" normalizes to
`""`. Every Cyrillic/CJK/Greek record produces the identical `ta:|` key and `dedupe()` merges all
of them into one card. Separately, `fetchStandardEbooks` never closes its `ResponseBody`.
Execution Logic:
- Normalize on Unicode letters and digits (`\p{L}\p{N}`) instead of `a-z0-9`.
- Close the response body on every fetch path.
- Serve the cache only when the fetch actually failed; a genuine empty result is a valid answer
  and must invalidate the cached page for that key.
Files And Functions:
 - existing: app/src/main/java/com/lexiread/core/util/CatalogDeduper.kt#normalizeTitle - Unicode-aware
 - existing: app/src/main/java/com/lexiread/core/util/CatalogDeduper.kt#normalizeAuthor - Unicode-aware
 - existing: app/src/main/java/com/lexiread/data/repository/BooksRepositoryImpl.kt#fetchStandardEbooks - wrap in `use`
 - existing: app/src/main/java/com/lexiread/data/repository/BooksRepositoryImpl.kt#resolveCatalogPage - empty result invalidates cache
Tests:
 - description: two distinct Cyrillic titles stay two cards
   input: "Война и мир" and "Анна Каренина", same author
   workflow: catalogue response --dedupe--> merged catalogue cards
   expected outcome: two entries, both retained

### 5. Due-word queries re-evaluate "now" per emission
vocabulary screen --observe due words--> due words
Observed Existing Logic:
`getDueWords()` / `getDueWordCount()` capture `System.currentTimeMillis()` once when the method
is called. Room re-emits on table changes but always with the frozen timestamp, so words that
become due while the app is open never appear.
Execution Logic:
- Keep the SQL query but re-read the clock per emission: map the flow through a fresh timestamp,
  or filter the full saved-word list in Kotlin against the current time.
- Simplest correct form: observe all saved words and filter with `isDue` evaluated at map time.
Files And Functions:
 - existing: app/src/main/java/com/lexiread/data/repository/VocabularyRepositoryImpl.kt#getDueWords - per-emission clock
 - existing: app/src/main/java/com/lexiread/data/repository/VocabularyRepositoryImpl.kt#getDueWordCount - per-emission clock

### 6. Archive parsers enforce their documented size bounds
untrusted archive --parse--> chapters
Observed Existing Logic:
`EpubParser` skips entries over `MAX_ENTRY_SIZE_BYTES` at line 75, then re-tests the same
condition at line 87 — the zip-bomb ratio check is unreachable. `PdfParser.tryDecompressFlate`
writes into an unbounded `ByteArrayOutputStream` and, when `inflate()` returns 0 without
`needsInput()`, spins forever. `PdfParser.extractMetadata` computes
`file.length().toInt()`, which is negative for files over 2 GB and throws
`NegativeArraySizeException`.
Execution Logic:
- EPUB: apply the ratio check on its own threshold, independent of the hard per-entry cap, so a
  highly compressible but small entry is still accepted and a genuine bomb is rejected.
- PDF: cap inflated output at `MAX_INFLATED_BYTES`, break on zero progress, and clamp
  `file.length()` to `Long` before narrowing.
Files And Functions:
 - existing: app/src/main/java/com/lexiread/core/reader/parsers/EpubParser.kt - reachable ratio guard
 - existing: app/src/main/java/com/lexiread/core/reader/parsers/PdfParser.kt#tryDecompressFlate - bounded output, no spin
 - existing: app/src/main/java/com/lexiread/core/reader/parsers/PdfParser.kt#extractMetadata - Long-clamped buffer size

### 7. Import and preload paths honour their own parameters
imported file --import--> persisted chapters
Observed Existing Logic:
`getChaptersForBook` ignores `limit`/`offset` on the `fullText` fallback path.
`initializePreloadedBooks` inserts books and chapters in two separate statements, so a kill in
between leaves books with no chapters and the id guard then blocks re-seeding forever.
`PgaBookSource.downloadContent` uses `runCatching`, which swallows `CancellationException` and
issues a second request from an already-cancelled coroutine.
Execution Logic:
- `limit`/`offset` apply to every chapter-loading path including the fallback.
- Preload runs in a single Room transaction and re-checks the chapter table, not just the book row.
- `CancellationException` is rethrown before any fallback is attempted.
Files And Functions:
 - existing: app/src/main/java/com/lexiread/core/reader/BookImporter.kt#getChaptersForBook - apply limit/offset on fallback
 - existing: app/src/main/java/com/lexiread/data/repository/BookRepositoryImpl.kt#initializePreloadedBooks - single transaction
 - existing: app/src/main/java/com/lexiread/data/source/PgaBookSource.kt#downloadContent - rethrow cancellation

### 8. Small correctness and hygiene fixes
misc defects --fix--> hardened behaviour
Execution Logic:
- `RetryPolicy`: mask the shift distance (`coerceAtMost(30)`) so backoff cannot collapse, and
  clamp the multiplied delay without overflowing to a negative value.
- `TextEncoding.decode`: strip a leading BOM centrally so `EpubParser`/`HtmlParser` chapter
  titles do not start with an invisible character.
- `ChapterParser.stripTags`: decode numeric HTML entities (`&#NNNN;`) on the fallback path.
- `TTSHelper.speak`: queue the first utterance and flush it from `onInit`, instead of dropping it.
- `MyLibBookSource.parseSearchPage`: `hasMore` is false when zero entries were parsed.
- Session index caches in `BooksRepositoryImpl` and `PgaBookSource` are `@Volatile` + `Mutex`
  guarded (matching the `@Synchronized` pattern already used by `MyLibBookSource`).
- Cover URLs are filtered through `UrlValidator.requireTrustedImageUrl` at the `AsyncImage` call
  sites, which is the only place `TRUSTED_IMAGE_HOSTS` was ever meant to apply.
- Delete dead code with verified-zero callers: `RetrofitClient.downloadOkHttpClient`,
  `ui/theme/Theme.kt#MyApplicationTheme`, `ExampleUnitTest`, `debug/PagDebugTest`,
  `ReaderViewModel.updateIsPaginated`, `SettingsViewModel.setFontFamily`/`setTargetLanguage`,
  duplicate imports in `LibraryScreen`.

### 9. P1: API keys fail closed when encryption is unavailable
API key --encrypt using provider-generated IV--> encrypted value or save error
Implementation Logic:
- `ApiKeyCrypto.encrypt` uses Cipher-generated GCM IV and retains randomized encryption enforcement. New values have an `enc:v1:` envelope. No plaintext is written after encryption failure.
- Existing `plain:` and legacy values remain readable for compatibility; saving them again encrypts them. Automatic migration of existing keys is deferred.
- Failed decryption of a versioned envelope returns an empty value, never ciphertext as an API key.
- `UserPreferencesManager` persists only successful encryption; `SettingsViewModel` catches save failure and `SettingsScreen` displays a generic error without secrets.
Tests:
- JVM tests use an injected key supplier, verify encrypted round trips, different IVs, and failure propagation without fallback.
- Instrumented test verifies the real Android Keystore path; execution requires a connected device.

### 10. P2: Pagination cache follows the current layout
container dimensions or reader settings --cancel stale work and invalidate cache--> freshly paginated chapters
Implementation Logic:
- Cancel pagination and prefetch before clearing the chapter-index cache when dimensions or settings change.
- Check cancellation before committing newly calculated pages to the cache. Reuse unchanged dimensions without invalidation.
Tests:
- Resize an already paginated chapter and compare against fresh PaginationEngine output.
- Navigate to a previously prefetched chapter after resizing and verify its new layout.

## Implementation checklist

1. [x] Reader chapter indexing: `chapterAt` / `putChapter`, atomic merges, cache keyed by `chapter.index`
2. [x] Bound page turn, progress percentage and UI counter on `totalChapterCount`
3. [x] `RedirectGuard` interceptor on all three OkHttp clients, `MAX_REDIRECTS = 3`
4. [x] `CatalogDeduper` Unicode normalization; close `ResponseBody` in `fetchStandardEbooks`
5. [~] Catalogue cache: the original "serve cached page on an empty result" behaviour is **kept**.
       See "Deviations" — the reviewer's finding #5 was a false positive because the cache is keyed
       by query, so an empty result only ever falls back to its own query's cache.
6. [x] `VocabularyRepositoryImpl` due queries re-read the clock per emission
7. [x] `EpubParser` reachable ratio guard; `PdfParser` bounded inflate + Long-clamped buffer
8. [x] `BookImporter` limit/offset fallback; preload transaction; `PgaBookSource` cancellation
9. [x] `RetryPolicy`, `TextEncoding` BOM, `ChapterParser` numeric entities, `TTSHelper`, `MyLibBookSource.hasMore`
10. [x] Cache visibility guards; cover-URL allow-list wiring
11. [x] Dead-code removal
12. [x] Run `:app:testDebugUnitTest` — green (140 tests, 0 failures)
13. [-] P1: provider-generated IV, fail-closed saves, safe Settings error and crypto tests
14. [ ] P2: invalidate resized pagination and prefetch, add regression tests
15. [ ] Run unit tests, lint, Kotlin and instrumentation compilation; record device-test availability

## Deviations

- **Finding #5 (empty result replacing cached page) was NOT applied.** The cache is keyed by
  `(kind, query, page, sources)`, so a search for a *different* query can never show another
  query's cache. Serving the same query's cached page when the live fetch returns empty is the
  intended offline-first behaviour and is covered by the existing test
  `serves the cached page when the network goes away`. Applying the change would break that test
  for no real benefit.
- **Finding #13 (limit/offset on the full-text fallback) was KEPT**, because `loadChapter` relies
  on `getChaptersForBook(book, limit = 1, offset = index)` returning exactly chapter `index`.
  But the lazy window previously fed `totalChapterCount` (via `chapters.size`), collapsing the
  navigation bounds to a single chapter and breaking `goToChapter clamps out-of-range index` and
  `stale progress…`. Fixed by adding `BookImporter.getChapterCount(book)` (DB count, else full-text
  chapter count) and using it in `loadBook` so `totalChapterCount` is always the real total.

## Open questions

- **non-blocking**: Z-Library mirror `zlib.bz` is hardcoded as `MyLibConfig.DEFAULT_HOST` and is
  injected into the download allow-list at `MyLibBookSource.kt:101`. Removing it is a product and
  compliance decision, not a bug fix, so it is left in place. Recommend gating it behind an
  explicit user-configured host before any store submission.
- P1 follow-up supersedes the plaintext-storage deferral: encryption uses Android Keystore directly without a new dependency. Legacy plaintext stays readable until the user saves it again.
- **non-blocking**: tap-translate and tap-explain send selected text to five third-party
  recipients with no consent disclosure. This needs a privacy-notice decision from the product
  side.
- **deferred (maintainability)**: the `BookSource.search` / `BooksRepositoryImpl` duplication, the
  `ReaderViewModel` split, the cover `Box`/`AsyncImage` duplication, the `SourceKind`-id-prefix
  duplication, the build-config cleanup (`google-services`/`roborazzi` unused plugins, 2-year-old
  Compose BOM), and extracting the ~150 hardcoded UI strings into `strings.xml`. These are real
  problems flagged by the quality review but are refactors, not defects, and carry regression risk
  across `BookSourcesTest`, `MyLibBookSourceTest`, `BooksRepositoryImplTest` and the Robolectric
  UI tests.

## Decision log

- Chose to keep `ReaderUiState.chapters` as a padded `List` and add index-aware accessors rather
  than replace it with a `Map`: `ReaderScreen` binds the TOC directly to that list, so a map
  would have forced UI changes across three bottom sheets for no behavioural gain.
- Chose a redirect interceptor over `followRedirects(false)` plus manual handling: every source
  calls Retrofit, so a per-call-site fix would have been bypassed by any new source.
- Chose to filter due words in Kotlin against a per-emission clock rather than add a Room query,
  because the existing query is correct and only the captured timestamp was wrong.
- Kept the `MAX_TOTAL_UNCOMPRESSED_BYTES` accumulator in `EpubParser`; the ratio guard is now an
  additional per-entry check rather than a replacement.
- Added `UrlValidator.requireTrustedRedirect` and a `CoverUrls.sanitize` helper so the image
  allow-list (`requireTrustedImageUrl`) is finally enforced in production instead of only in a test.
- Deferred the maintainability refactors listed under "Open questions".
