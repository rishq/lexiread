# LexiRead — Code Review & Fix Report

Date: 2026-08-29
Scope: full codebase (51 Kotlin sources) — bug fix for unreadable book text + full review
Verification: `testDebugUnitTest` → **37 tests, 0 failures**

---

## Part 1 — The "unreadable book text" bug (FIXED)

### Symptom
The entire text of an installed/downloaded book rendered as garbage — either mojibake
(`ÐÐ»Ð°Ð²Ð°` style) or littered with replacement glyphs (`�`).

### Root cause — two independent decoding defects in `EpubParser`

**1. Chunked UTF-8 decoding split multi-byte characters** (`EpubParser.parseChapters`)

```kotlin
val buffer = ByteArray(8192)
while (inputStream.read(buffer).also { read = it } != -1) {
    out.append(String(buffer, 0, read, Charsets.UTF_8))   // ← decodes per chunk
}
```

Each 8192-byte chunk was decoded *independently*. Any character whose bytes straddle
the boundary is destroyed: Cyrillic is 2 bytes in UTF-8, CJK is 3, emoji is 4. Each
half decodes to U+FFFD. A 500 KB book had ~60 corruption points.

**2. Encoding was hardcoded to UTF-8**

EPUB XHTML declares its own encoding in the XML prolog or a `<meta charset>` tag.
A large part of the Russian e-book corpus is still distributed as **windows-1251**.
Decoding those as UTF-8 turns the *whole* book into mojibake — which matches the
reported symptom exactly.

Notably `TxtParser` already had windows-1251 detection; `EpubParser` and `Fb2Parser`
did not. That asymmetry is why `.txt` books looked fine while `.epub` did not.

### The fix

New `core/util/TextEncoding.kt` — detection order:

1. **BOM** (UTF-8 / UTF-16LE / UTF-16BE)
2. **Declared encoding** — XML prolog, `<meta charset>`, `content-type` charset
3. **Strict UTF-8 validation** — malformed input is reported, not replaced
4. **windows-1251 fallback** — only when the bytes genuinely aren't UTF-8

It always decodes a *complete byte array in a single call* (or streams through an
`InputStreamReader`), so multi-byte sequences can never be split.

Applied to:
- `EpubParser` — whole entries decoded at once; OPF/container XML parsed from a
  decoded `String` via `StringReader` instead of `setInput(stream, "UTF-8")`
- `Fb2Parser` — replaced three `readText(Charsets.UTF_8)` calls
- `TxtParser` — deduplicated onto the shared detector

### Proof the fix is real

Two regression tests were added and **verified to fail against the old code**:

| Test | Old code | New code |
|---|---|---|
| `keeps multi-byte characters intact across read buffer boundaries` | ❌ FAIL (U+FFFD present) | ✅ PASS |
| `declares windows-1251 chapters using their declared encoding` | ❌ FAIL | ✅ PASS |

Plus 8 `TextEncodingTest` unit tests. Total: **37 tests, 0 failures**.

---

## Part 2 — Issues fixed during review

| # | Severity | File | Issue |
|---|---|---|---|
| A | HIGH | `Daos.kt` | `getLatestProgress()` had no `JOIN books` → a **deleted** book's progress was returned forever |
| B | HIGH | `Daos.kt` / `BookRepositoryImpl` | Deleting a book orphaned its `reading_progress` + `bookmarks` rows (no FK cascade) |
| C | HIGH | `VocabularyViewModel` | Marking a card KNOWN re-filters the list, shifting the index → **cards silently skipped** |
| D | HIGH | `VocabularyViewModel` | `startReviewMode()` had **zero call sites** — the entire flashcard trainer was dead code |
| E | MED | `HomeViewModel` | Hero card showed another book's progress when the last-read book was deleted |
| F | MED | `DictionaryRepositoryImpl` | Dictionary cache had **no TTL** → stale results served forever |
| G | MED | `DictionaryRepositoryImpl` | Fabricated a fake definition (`"A word from English literature: 'x'"`) for *every* failure, masking real "not found" |
| H | MED | `TTSHelper` | `shutdown()` never called → TTS engine binding leaked for the whole process; engine also built eagerly even if never used |
| I | MED | `BookDetailsViewModel` | No error handling → a DB throw left the screen spinning forever |
| J | LOW | `LexiReadApp` | Logged up to 512 KB of stack trace (may contain book text) in one logcat call |
| K | LOW | `LibraryScreen` | File picker accepted `*/*`; snackbar effect could leave the message set |
| L | LOW | `ReaderViewModel` | `if (book != null)` was dead code (compiler warned "Condition is always true") |

---

## Part 3 — Remaining findings (reported, not changed)

### MEDIUM

**M1. `AppDatabase` version 3 but only `MIGRATION_2_3` registered**
`app/schemas/.../` contains only `3.json`. If any device ever shipped v1 or v2, Room
throws `IllegalStateException` on open. → Add `MIGRATION_1_2`, or reset `version = 1`
if v1/v2 never shipped.

**M2. `LibraryViewModel` keeps 4 concurrent Room observers alive**
`combine(getAllBooks, getFavoriteBooks, getSavedBooks, getFinishedBooks)` maintains
four invalidation trackers and rebuilds all four lists on every DB write, though only
one tab is visible. → `flatMapLatest(selectedTab) { daoFor(it) }`.

**M3. Whole-table materialisation instead of SQL**
- `HomeViewModel` loads the entire `saved_words` table just to compute two counts.
- `VocabularyViewModel` loads every word and filters in Kotlin while
  `SavedWordDao.getWordsByStatus` (already written) sits unused.
- Missing indices: `saved_words.learningStatus`, `reading_progress.lastReadTimestamp`,
  `books.addedTimestamp`.
→ Add `SELECT COUNT(*)` queries and use `getWordsByStatus`.

**M4. `TranslationRepositoryImpl`**
- `cacheKey` is the full sentence used as a TEXT primary key → unbounded row growth.
- Text is sent to MyMemory (500-byte API cap) **without truncation**, so any long
  selection is guaranteed to fail.

**M5. Hardcoded UI strings everywhere**
`HomeScreen`, `LibraryScreen`, `BookDetailsScreen`, `VocabularyScreen`,
`SettingsScreen` — none use `res/values/strings.xml`. No i18n, no TalkBack-friendly
loading of dynamic text.

### LOW

**L1. `NavRoutes.createRoute()` does not encode the argument**
`"book_details/$bookId"`. IDs originate from remote JSON (`ia_<identifier>`,
`se_<author~title>`). Currently safe only because `InternetArchiveBookSource`
validates identifiers with a regex and Standard Ebooks IDs have `/` replaced by `~`.
→ Use `android.net.Uri.encode(bookId)`. *Not changed: I could not run instrumented
tests to confirm Navigation's decode behaviour on receipt.*

**L2. `AiRepositoryImpl.escapeForPrompt()`** escapes `<`/`>` without escaping `&`
first, so a literal `&lt;` in book text is indistinguishable from escaped input.

**L3. `SettingsScreen:268`** uses `remember` for a typed-but-unsaved API key → lost
on rotation. Use `rememberSaveable` or hoist to the ViewModel.

**L4. `LibraryScreen`** doesn't call `takePersistableUriPermission()` on the picked URI.

**L5. Unused code**: `AiRepositoryImpl` (`flow.first`) and `SettingsScreen` (`Divider`)
imports; `dictionaryApi`/`home` unused symbols.

**L6. `Dtos.kt:67,155,183`** — `@Json` annotation applies to the value parameter only;
will change meaning in a future Kotlin release. Add `@param:` to pin current behaviour.

---

## Part 4 — Security posture (verified sound)

Checked and **found clean**:

- ✅ `cleartextTrafficPermitted="false"` (network security config)
- ✅ `android:allowBackup="false"`
- ✅ No hardcoded API keys / secrets anywhere (verified by scan)
- ✅ SSRF host allow-lists in every download path (`Gutendex`, `InternetArchive`,
  `StandardEbooks` — all `requireValidUrl` / trusted-host checks)
- ✅ Internet Archive identifiers validated against a strict regex (blocks traversal)
- ✅ Download size caps enforced while streaming (10–40 MB), not just via `Content-Length`
- ✅ EPUB per-entry (15 MB) and total (40 MB) uncompressed caps — zip-bomb resistant
- ✅ EPUB covers capped at 5 MB
- ✅ Prompt input sanitised + length-limited before being sent to AI providers
- ✅ API keys masked in HTTP logging; body logging disabled on the download client
- ✅ Only `MainActivity` exported, no intent-filters beyond `MAIN`/`LAUNCHER`

**Residual risk (accepted, worth knowing):** API keys are stored in plain
DataStore preferences. That is app-private storage and fine for a normal device,
but readable on a rooted one. If that matters, move to `EncryptedSharedPreferences`
or Android Keystore.

---

## Files changed

**New**
- `app/src/main/java/com/lexiread/core/util/TextEncoding.kt`
- `app/src/test/java/com/lexiread/core/util/TextEncodingTest.kt`

**Fixed**
- `core/reader/parsers/EpubParser.kt` — encoding-aware, non-chunked decoding
- `core/reader/parsers/Fb2Parser.kt` — encoding detection
- `core/reader/parsers/TxtParser.kt` — shared detector
- `core/util/TTSHelper.kt` — lazy engine binding, thread-safe shutdown
- `data/local/dao/Daos.kt` — `getLatestProgress` JOIN + cascade delete queries
- `data/repository/BookRepositoryImpl.kt` — delete dependent rows
- `data/repository/DictionaryRepositoryImpl.kt` — cache TTL, honest failure
- `presentation/home/HomeViewModel.kt` — progress/book match
- `presentation/vocabulary/VocabularyViewModel.kt` — ID-based review queue
- `presentation/vocabulary/VocabularyScreen.kt` — Review button, frozen queue
- `presentation/details/BookDetailsViewModel.kt` — error handling
- `presentation/library/LibraryScreen.kt` — MIME filter, snackbar finally
- `presentation/reader/ReaderViewModel.kt` — removed dead null branch
- `MainActivity.kt` — TTS shutdown on destroy
- `LexiReadApp.kt` — truncate crash log

## Build note

Gradle in this workspace fails with
`'void Settings_gradle.<init>(KotlinScriptHost, ...)'` due to stale Kotlin DSL
script caches. Fix before building:

```bash
rm -rf .gradle/configuration-cache .kotlin/sessions
gradle --offline --no-configuration-cache testDebugUnitTest
```
