# LexiRead — код-ревью и полный аудит

**Дата:** 15.09.2026
**Ревьюер:** WorkBuddy AI
**Объём:** 200 файлов Kotlin, 17 494 строки (80 файлов main / 21 файл test)
**Коммит:** `be21dda` + 25 незакоммиченных изменений в рабочем дереве
**Ветка:** main

---

## 1. Как проверялось (воспроизводимая база)

| Проверка | Команда | Результат |
|---|---|---|
| Модульные тесты | `gradle --no-configuration-cache :app:testDebugUnitTest --rerun --continue` | **BUILD SUCCESSFUL — 140 тестов, 0 падений, 0 пропущено** (21 suite) |
| Android Lint | `gradle --no-configuration-cache :app:lintDebug` | **0 errors, 55 warnings** |
| Статический анализ | grep по «опасным» API (WebView, Runtime.exec, внешнее хранилище, hardcoded-секреты) | WebView/exec/`MANAGE_EXTERNAL_STORAGE` — **отсутствуют** |
| Проверка секретов | сверка `.env` / `.env.example` / сгенерированного `BuildConfig.java` / `git ls-files` | утечек в истории нет; см. находку **P0-3** |
| Анализ бинарных ресурсов | разбор заголовков RIFF/VP8X иконок | **иконки повреждены**, см. **P0-2** |
| Карта покрытия | сопоставление типов main ↔ упоминаний в тестах | 174 типа, **107 без тестов** |
| Сверка со спекой | `specs/code-review-hardening.md` | 12/12 пунктов закрыто, 4 вопроса остались «open» |

Отдельно отмечу: локальная сборка в этом окружении требует обходного пути
(`GRADLE_USER_HOME` вне read-only дерева scoop + Gradle 9.7.0 вместо wrapper 9.3.1) —
это особенность машины, а не дефект проекта.

---

## 2. Итоговая оценка

| Направление | Оценка | Комментарий |
|---|---|---|
| Архитектура и слоистость | 9/10 | Чистые слои domain/data/presentation, UseCase-слой, мапперы, DI-контейнер |
| Безопасность сети (SSRF, redirects) | 9/10 | Ручная перепроверка каждого редиректа, allow-list хостов — выше среднего по индустрии |
| Безопасность данных на устройстве | 6/10 | API-ключи в открытом DataStore; текст книги уходит 5 третьим сторонам без согласия |
| Защита парсеров от вредоносных файлов | 6/10 | Хорошие капы в EPUB/TXT, но PDF и FB2 имеют дыры (см. P1) |
| Производительность / память | 6/10 | Ленивая загрузка глав и LRU-кэш есть, но PDF/импорт держат книгу целиком в heap |
| Тесты | 6/10 | 140 зелёных тестов, но 107 типов не покрыты; UI/instrumented тестов нет |
| Соответствие требованиям сторов | **3/10** | Источник Z-Library + нераскрытая передача данных третьим лицам = блокеры публикации |
| Гигиена репозитория / сборки | 5/10 | 25 незакоммиченных файлов, мёртвые зависимости, повреждённые иконки, устаревший README |
| Качество кода и комментариев | 8/10 | Комментарии объясняют «почему», а не «что»; местами дублирование слоёв |

**Общий вывод:** это заметно выше среднего Android-проект с инженерной культурой
(тесты, миграции, комментарии-обоснования, аккуратная работа с сетью и кодировками).
Но в текущем виде **приложение нельзя публиковать**: два блокера — пиратский источник
книг и нераскрытая передача пользовательского текста третьим лицам.

---

## 3. Блокеры релиза (P0)

### P0-1. Z-Library (`zlib.bz`) как источник книг, доступный из UI

**Где:**
- `app/src/main/java/com/lexiread/data/source/MyLibBookSource.kt:676` — `MyLibConfig.DEFAULT_HOST = "zlib.bz"`
- `app/src/main/java/com/lexiread/data/source/MyLibBookSource.kt:62` — хост добавляется в allow-list загрузки
- `app/src/main/java/com/lexiread/data/repository/BooksRepositoryImpl.kt:459-495` — `fetchMyLib()`, и **строка 486: `isPublicDomain = true`**
- `app/src/main/java/com/lexiread/presentation/catalog/CatalogScreen.kt:249` — `SourceKind.entries.forEach` рисует чип для **каждого** источника, включая `MY_LIB` («My Library»)
- `app/src/main/java/com/lexiread/domain/model/CatalogModels.kt:83` — `canRead = isPublicDomain && formats.isNotEmpty()`

**Суть:** источник выключен в `DEFAULT_SOURCES`, но включается одним тапом на чипе с
безобидной подписью «My Library». Дальше приложение скрапит поиск, качает файлы с
пиратского зеркала и **помечает записи как `isPublicDomain = true`** — то есть UI
показывает их как общественное достояние и разрешает скачивание (`canRead`).

**Риск:** нарушение авторских прав; отказ Google Play по политике IP Infringement
(немедленное удаление приложения + риск блокировки аккаунта разработчика); претензии
правообладателей. Это не «стилистика» — это юридический блокер.

**Что делать (по возрастанию объёма работ):**
1. Минимум: убрать `MY_LIB` из `SourceKind` и из UI; удалить `MyLibBookSource`,
   `MyLibConfig`, `MyLibApi`, `MyLibSelectors` и тесты к ним.
2. Если источник нужен продуктово: заменить дефолтный хост на легальный
   (Internet Archive / Standard Ebooks / Open Library) и **никогда** не выставлять
   `isPublicDomain = true` по умолчанию — флаг должен приходить от источника и быть
   `false` для всего, что не подтверждено.
3. Обязательно: убрать хардкод `isPublicDomain = true` из `fetchMyLib` независимо от решения.

> Важно: этот пункт **уже был найден** в предыдущем ревью и явно отложен
> (`specs/code-review-hardening.md`, «Open questions»: «Removing it is a product and
> compliance decision»). Решение отложено — блокер остался. Его нужно закрыть до
> любого сабмита.

---

### P0-2. Иконки лаунчера повреждены

**Где:** `app/src/main/res/mipmap-*/ic_launcher*.webp`

**Доказательства (независимо подтверждены линтом и разбором заголовков):**

| Файл | Объявлено | Факт |
|---|---|---|
| `mipmap-xxhdpi/ic_launcher.webp` | 36803 × 9421313 px | lint `IconDipSize` |
| `mipmap-xxhdpi/ic_launcher_round.webp` | 36803 × 9421313 px | lint `IconDipSize` |
| `mipmap-xxxhdpi/ic_launcher_round.webp` | 49091 × 12567041 px | lint `IconDipSize` |
| `mipmap-mdpi/ic_launcher*.webp` | RIFF-size = 233 155 / 435 395 байт | файл 1 485 / 2 634 байт; на смещении 8 лишний `0x00` (заголовок `RIFF\0WEBPVP8`) |
| `mipmap-xxxhdpi/ic_launcher.webp` | RIFF-size = 965 827 байт | файл 5 743 байта, тот же лишний NUL |

Дополнительно: `drawable/lexiread_app_icon_1786623886711.jpg` — 1024×1024, **379 КБ**,
используется как foreground адаптивной иконки (`drawable/ic_launcher_foreground.xml:6`).
Для foreground нужно 432×432; файл лежит в densityless `drawable/` (lint `IconLocation`).

**Риск:** на части лончеров иконка не отрисуется или отрисуется с искажением; лишние
379 КБ в APK. Сборка при этом проходит — AAPT не валидирует структуру WebP глубоко,
поэтому дефект не ловится CI.

**Что делать:** перегенерировать набор через Android Studio → Image Asset (адаптивная
иконка, foreground 432×432 в `drawable-nodpi`/`mipmap-*`, webp lossless), удалить
исходный JPEG из `drawable/`.

---

### P0-3. `.env` инжектится в `BuildConfig`; комментарий в CI утверждает обратное

**Где:**
- `app/build.gradle.kts:100-104` — плагин Secrets, `propertiesFileName = ".env"`
- сгенерированный `app/build/generated/source/buildConfig/debug/com/lexiread/BuildConfig.java:12` — **`public static final String GEMINI_API_KEY = "MY_GEMINI_API_KEY";`**
- `.github/workflows/android_ci_cd.yml:75` — комментарий «GEMINI_API_KEY is intentionally **NOT** injected into the APK»
- `README.md` — «AI Integration: Gemini REST API via **server-side** AI Studio integration»

**Суть:** плагин Secrets создаёт `BuildConfig`-поле для **каждого** ключа из `.env`.
Сейчас там плейсхолдер, поэтому утечки нет. Но `.env.example` прямо инструктирует
положить туда настоящий ключ — и тогда он окажется в APK в открытом виде
(извлекается за минуту через `apktool`/`strings`). Комментарий в CI, который
якобы это запрещает, вводит в заблуждение. Поле `GEMINI_API_KEY` в коде **не
используется вообще** (`grep` — 0 обращений), то есть это чистый мёртвый риск.

**Что делать:** убрать `GEMINI_API_KEY` из `.env`/`.env.example`, либо добавить
`ignoreList.add("GEMINI_API_KEY")` в блок `secrets { }`, либо удалить строку из `.env.example`
целиком. Поправить комментарий в CI и раздел AI в README (ключи вводятся пользователем
в Settings, хранятся локально).

---

## 4. Высокий приоритет (P1)

### P1-1. OOM на больших PDF

`app/src/main/java/com/lexiread/core/reader/parsers/PdfParser.kt:47`

```kotlin
val fileBytes = file.readBytes()                 // до 80 МБ (MAX_PDF_BYTES)
...
val text = String(pdfBytes, 0, sampleSize, Charsets.ISO_8859_1)  // ещё ~2× в UTF-16
```

Пиковая память: 80 МБ (массив) + до ~160 МБ (String) + `StringBuilder` результата.
На устройстве с heap 128–256 МБ это гарантированный OOM. `BookImporter` ловит
`OutOfMemoryError`, но к этому моменту процесс уже в аварийном состоянии, а на
низкоклассных устройствах ловить OOM небезопасно.

**Что делать:** снизить `MAX_PDF_BYTES` до 25–30 МБ, читать файл через
`FileChannel`/`mmap` или окнами, работать с `ByteArray` без полной String-копии,
обрабатывать страницы инкрементально.

### P1-2. FB2: base64-обложка без ограничения размера

`app/src/main/java/com/lexiread/core/reader/parsers/Fb2Parser.kt:195-203`

```kotlin
val imageBytes = Base64.decode(base64Data, Base64.DEFAULT)   // без капа
FileOutputStream(coverFile).use { fos -> fos.write(imageBytes) }
```

В `EpubParser` аналогичное место ограничено `MAX_COVER_SIZE_BYTES = 5 МБ`
(`EpubParser.kt:168-183`), здесь — ничего. Вредоносный FB2 с гигантским `<binary>`
даёт OOM или запись произвольного объёма в `filesDir`.

**Что делать:** проверять длину base64 до декодирования (≈4/3 от лимита) и капать
декодированный буфер до 5 МБ, как в EPUB.

### P1-3. «Оглавление» не работает для лениво загружаемых книг

`app/src/main/java/com/lexiread/presentation/reader/ReaderScreen.kt:379-385`
→ `TableOfContentsSheet(chapters = uiState.chapters, ...)`

`uiState.chapters` при ленивой загрузке содержит **один** реальный элемент плюс
пустые плейсхолдеры, которые дописывает `ReaderViewModel.putChapter()`
(`ReaderViewModel.kt:264-273`, `BookChapter(title = "", content = "")`). В итоге:
- для книги из 30 глав в оглавлении 1–2 осмысленные строки, остальное — пустые карточки;
- заголовки не подгружаются, пока глава не открыта;
- переход по оглавлению фактически невозможен.

**Что делать:** добавить дешёвый запрос `chapterDao.getChapterTitles(bookId)` →
`List<Pair<Int,String>>` (только `chapterIndex, title`, без `content`) и строить TOC
из него, а не из окна загруженных глав. Это же снимет расхождение между TOC и
`totalChapterCount`.

### P1-4. Прогресс чтения пишется на каждое перелистывание без дебаунса

`app/src/main/java/com/lexiread/presentation/reader/ReaderViewModel.kt:513-534`
(`saveCurrentProgress()` вызывается из `nextPage`, `previousPage`, `goToPage`, `commitProgress`).

Каждый тап запускает новую корутину и транзакцию Room. При быстром листании — сотни
записей в SQLite, лишний износ flash и нагрузка на UI. `limitedParallelism(1)` сохраняет
порядок, но не уменьшает количество записей.

**Что делать:** conflate/debounce — `MutableStateFlow<ReadingProgress>` + `debounce(500)` +
`collectLatest`, плюс принудительная запись в `onStop()`/`DisposableEffect.onDispose`.

### P1-5. API-ключи хранятся в открытом виде

`app/src/main/java/com/lexiread/core/preferences/UserPreferencesManager.kt:26-81` —
обычный `preferencesDataStore("lexiread_settings")`, файл не шифруется.
`android:allowBackup="false"` снижает риск облачного бэкапа, но на rooted-устройстве
или через обходы `adb backup` ключи читаются в открытом виде.

Это тоже **известный отложенный пункт** предыдущего ревью. Для релиза с оплачиваемыми
ключами пользователей это нужно закрыть.

**Что делать:** шифровать значения ключей AES-GCM с ключом в Android Keystore
(`androidx.security:security-crypto` или ручная реализация); добавить зависимость в
`libs.versions.toml`.

### P1-6. Передача пользовательского текста третьим лицам без согласия

Функция «тап по слову» отправляет выделенный фрагмент сразу нескольким получателям:

| Получатель | Где |
|---|---|
| `api.dictionaryapi.dev` | `RetrofitClient.kt:239-246` |
| `api.mymemory.translated.net` | `RetrofitClient.kt:248-255` |
| Google Gemini | `RetrofitClient.kt:257-264` |
| OpenAI | `RetrofitClient.kt:266-273` |
| DeepSeek | `RetrofitClient.kt:275-282` |
| Anthropic Claude | `RetrofitClient.kt:284-291` |

Ни в UI, ни в манифесте нет раскрытия этого факта и нет явного согласия
(третий отложенный пункт предыдущего ревью).

**Что делать:** перед релизом — политика конфиденциальности, раздел Data Safety в Play
Console, экран/диалог с явным согласием при первом использовании, плюс переключатель
«не отправлять текст в облако» (офлайн-режим).

---

## 5. Средний приоритет (P2)

| # | Находка | Где | Комментарий |
|---|---|---|---|
| P2-1 | `RedirectGuard` висит и на API-клиенте, где allow-list — это список **хостов книг** | `RetrofitClient.kt:169` | Кросс-хост редирект `api.openai.com → cdn...` бросит `SecurityException`. Сейчас не стреляет, но хрупко: для API-клиента нужен отдельный guard (same-host) |
| P2-2 | `UrlValidator.extraImageHosts` — глобальное мутируемое множество | `UrlValidator.kt:48-60` | Источник расширяет allow-list для всего процесса; в тестах возможны протечки между кейсами. Передавать хосты параметром (как `extraHosts`) |
| P2-3 | Импорт глав целиком в память | `BookImporter.kt:117-149` | `parseChapters()` возвращает **все** главы, и только потом применяются `MAX_CHAPTERS`/`MAX_CHAPTER_BYTES`. Для 100 МБ TXT пик ≈ 2× размер в heap |
| P2-4 | `getFileSize()` возвращает 0 при отсутствии колонки | `BookImporter.kt:202-210` | Предварительная проверка молча пропускается (компенсируется проверкой в цикле копирования, но неявно) |
| P2-5 | `BookRepositoryImpl.searchBooksOnline` не использует `CatalogDeduper` | `BookRepositoryImpl.kt:115` | Дедуп только `distinctBy { it.id }` — кросс-источниковые дубликаты (разные id) остаются, хотя в `BooksRepositoryImpl` дедуп есть. Несогласованность поведения двух репозиториев |
| P2-6 | SE-загрузка повторно ищет книгу по названию и матчит id | `BookSources.kt:315-323` | Хрупко: заголовок мог измениться. Лучше кэшировать acquisition-ссылку из результата поиска |
| P2-7 | `AppContainer.initialize()` делает downcast `bookRepository as BookRepositoryImpl` | `AppContainer.kt:209` | DI-контейнер знает о конкретной реализации; `initializePreloadedBooks()` отсутствует в интерфейсе |
| P2-8 | `ReaderViewModel` отдаёт в UI сырой `exception.message` | `ReaderViewModel.kt:131` | В остальных местах используется `UserErrorMessages.messageFor(...)` — здесь обойдено |
| P2-9 | `versionCode` никогда не увеличивается | `app/build.gradle.kts:34` | `versionCode = 1`, `versionName = "1.0"`, а CI генерирует тег `v1.0.<run_number>`. Google Play отклонит второе обновление. Брать `versionCode` из `github.run_number` |
| P2-10 | CI проглатывает падение release-сборки | `.github/workflows/android_ci_cd.yml:107-116, 124-130` | `if ./gradlew assembleRelease bundleRelease; then ... else echo ...` — при отсутствии подписи публикуется **неподписанный** `app-release-unsigned.apk` в GitHub Release. Нужно падать явно |
| P2-11 | Мёртвые зависимости в version catalog | `gradle/libs.versions.toml:26-46, 96-106` | camera ×4, play-services-location, firebase ×5, credentials ×2, googleid, accompanist-permissions, roborazzi — объявлены, но не подключены. Плюс плагин `google-services` при отсутствии Firebase (`missingGoogleServicesStrategy = WARN` маскирует это) |
| P2-12 | Огромный `MyLibBookSource` | 733 строки | Смешаны URL-билдинг, эвристики парсинга и классификация ссылок. `autoDetectItems` — O(anchors × climb × children) при `MAX_ANCHORS = 600` |
| P2-13 | Нет UI/instrumented тестов | `app/src/androidTest/.../ExampleInstrumentedTest.kt` | Единственный тест — шаблонный. Roborazzi в каталоге есть, но не подключён — заявленная визуальная регрессия не работает |
| P2-14 | ~150 UI-строк захардкожены | все `*Screen.kt` | Нет `strings.xml`; локализация невозможна. Известный отложенный пункт |

---

## 6. Низкий приоритет и гигиена (P3)

**Из отчёта Lint (0 errors, 55 warnings):**
- `ObsoleteSdkInt` — `PaginationEngine.kt:178`: проверка `SDK_INT >= M` мертва при `minSdk 24`.
- `DefaultLocale` — `SettingsScreen.kt:165`: `String.format` без `Locale`.
- `RedundantLabel` — `AndroidManifest.xml:22`: `android:label` дублирует application.
- `UnusedResources` — 7 цветов в `res/values/colors.xml`.
- `OldTargetApi` — `targetSdk = 36` (доступны более новые).
- 40+ предупреждений об устаревших версиях: Compose BOM `2024.09.00` (двухлетней давности), Room 2.7.0, OkHttp/Retrofit 4.12/2.12 (есть 5.5/3.0), AGP 9.1.1 (есть 9.4.0), Kotlin 2.2.10 (есть 2.4.20).

**Прочее:**
- `AndroidManifest.xml:10-13`: `allowBackup="false"` делает `dataExtractionRules` и `fullBackupContent` мёртвыми — либо убрать правила, либо пересмотреть политику бэкапа.
- `ReaderScreen.kt:98-99`: `with(density) { constraints.maxWidth }` — no-op обёртка (значения уже в px), вводит в заблуждение.
- `ReaderScreen.kt`: хардкод `Color(0xFF…)` и `top = 28.dp`; при `enableEdgeToEdge()` не учтены `WindowInsets` — текст может заезжать под системные бары.
- `CrashLogger.kt`: стектрейс пишется в `filesDir/crash_log.txt` и первые 4000 символов уходят в logcat — в трейс может попасть текст книги.
- `ReaderScreen.kt:216-241`: `runCatching` вокруг обработки тапа — приемлемо, но глотает все исключения; лучше логировать тип.
- `BookImporter.kt:103,119`: перехват `OutOfMemoryError` — спорная практика, оправдана только здесь; стоит зафиксировать это комментарием (частично уже есть).
- `BookFormatSelector.classify` / `BookImporter.getExtension` / `ChapterParser` используют `lowercase()`/`uppercase()` без `Locale` — потенциальная турецкая «i»-проблема (в `MyLibBookSource` locale указан корректно).
- `ChapterParser.cleanParagraphs` — `split("\n")` по всей книге, вызывается многократно на одном тексте.
- `PaginationEngine` считает `StaticLayout` на `Dispatchers.Default`; документировать это решение либо переносить на main с чанкованием.

**Гигиена репозитория:**
- **25 изменённых файлов не закоммичены** (+446/−156) — включая `ReaderViewModel`, `RetrofitClient`, `UrlValidator`. Работа из предыдущего ревью существует только в рабочем дереве; потеря при `git checkout` = потеря исправлений безопасности.
- Дублирующиеся пакеты инструкций: `.agents/skills`, `.claude/skills`, `.cursor/skills` — по 7 файлов и 101 КБ каждый, содержимое различается. Плюс `.github/skills`, `.github/planning`, `specs/`.
- `.kilo/` — 60 МБ (в `.gitignore`, но занимает место).
- `README.md` устарел: описывает `com.example`-пакеты, экран «Explore», «Gemini via server-side AI Studio integration», не упоминает SM-2, каталог, PDF/FB2-парсеры, 7 источников.

---

## 7. Что сделано хорошо (не переделывать)

1. **SSRF-защита уровня выше среднего.** `RedirectGuard` (`RetrofitClient.kt:95-121`) отключает
   автоматические редиректы и перепроверяет каждый `Location` через allow-list,
   ограничивая цепочку 3 хопами. Плюс `forceHttps()`, `requireTrustedDownloadUrl`,
   `requireTrustedRedirect`. Большинство приложений валидируют только первый URL.
2. **Корректная работа с кодировками.** `TextEncoding` определяет BOM → XML-пролог →
   `<meta charset>` → строгую валидность UTF-8, и только затем падает в windows-1251.
   Плюс единая точка снятия BOM. Это редкая и правильная деталь для читалки.
3. **Защита от вредоносных архивов.** `EpubParser`: кап на запись (15 МБ), кап на сумму
   (40 МБ), достижимая проверка коэффициента сжатия с собственным порогом (1 МБ / ratio 100),
   кап на обложку (5 МБ). `SafeDownloader` — потоковая запись с капом и удалением
   частичного файла. `TextEncoding.readCappedBytes` — кап даже при неизвестном размере.
4. **Устойчивость каталога.** Источники опрашиваются параллельно в `supervisorScope`,
   отказ одного не роняет поиск, имена упавших источников уходят в `failedSources` и
   показываются пользователю. Офлайн-фолбэк в Room по ключу `(kind, query, page, sources)`.
5. **Сеть без утечек.** Все `ResponseBody` закрываются через `use {}`; дисковый кэш
   OkHttp с интерцептором, добавляющим `Cache-Control` только JSON-ответам
   (`CatalogCacheInterceptor`) — аккуратное решение.
6. **Безопасность сборки.** `debug.keystore`, `.env`, `local.properties` не в git
   (проверено `git ls-files`). Release-ключ берётся из переменных окружения.
   Логи HTTP только в debug + редакция `key=...`.
7. **Room-миграции до v5** с переносом `fullText` в отдельную таблицу `book_chapters`
   до `DROP TABLE`, и это покрыто `MigrationTest` (схемы подключены через assets
   debug-варианта — нетривиальное и верное решение).
8. **Комментарии объясняют причину**, а не пересказывают код: почему `@Volatile` + `Mutex`,
   почему проверка ratio вынесена из-под капа, почему `take()` вместо чанкового декодирования.
   Это редкость и сильно снижает стоимость поддержки.

---

## 8. Тесты: состояние и пробелы

**Есть:** 140 тестов, 0 падений. Покрыты: `EpubParser`, `HtmlParser`, `ChapterParser`,
`PaginationEngine`, `TextEncoding`, `SrsScheduler`, `CatalogDeduper`, `UrlValidator`,
`BookFormatSelector`, `UserErrorMessages`, `MigrationTest`, `BooksRepositoryImpl`
(с фейками IA/SE), `MyLibBookSource`, `PgaBookSource`, `BookSources`,
`GutendexBookSourceDownload`, `CatalogApiParse`, `CatalogLivePayload`, `ReaderViewModel`.

**Нет тестов у 107 типов**, из них значимые:

| Приоритет | Тип | Почему важно |
|---|---|---|
| Высокий | `PdfParser` | Содержит P1-1; парсинг недоверенного ввода без тестов |
| Высокий | `Fb2Parser` | Содержит P1-2 |
| Высокий | `RetryPolicy` | Логика backoff с переполнением — уже была багом |
| Высокий | `VocabularyRepositoryImpl` | SM-2 и «due»-логика; сломанный `nextReviewEpoch` = сломанный тренажёр |
| Средний | `BookRepositoryImpl` | Транзакция preload, удаление зависимых строк |
| Средний | `DictionaryRepositoryImpl`, `TranslationRepositoryImpl` | Кэш, TTL, фолбэки, обработка `CancellationException` |
| Средний | `CoverUrls` | Реальный enforcement image allow-list |
| Средний | `HomeViewModel`, `LibraryViewModel`, `CatalogViewModel`, `BookDetailsViewModel`, `VocabularyViewModel` | Ни один VM кроме `ReaderViewModel` не покрыт |
| Низкий | DTO/Entity/Dao | Ценность низкая, тестировать косвенно |

**Отсутствует полностью:** instrumented/Compose UI-тесты. Заявленная в каталоге
Roborazzi не подключена — визуальной регрессии нет.

---

## 9. План действий

### Перед публикацией (обязательно)
- [ ] **P0-1** Убрать Z-Library: удалить `MY_LIB` из `SourceKind`/UI или заменить хост на легальный; убрать `isPublicDomain = true` из `fetchMyLib`
- [ ] **P0-2** Перегенерировать иконки; убрать 379 КБ JPEG из `drawable/`
- [ ] **P0-3** Убрать `GEMINI_API_KEY` из `.env`/`.env.example`; поправить комментарий в CI и README
- [ ] **P1-6** Политика конфиденциальности + Data Safety + согласие на отправку текста
- [ ] **P2-9** `versionCode` из номера сборки
- [ ] **P2-10** Падать явно при неудачной подписи release, не публиковать unsigned APK
- [ ] **P3** Зафиксировать 25 незакоммиченных файлов (в них исправления безопасности)

### Спринт 1 (надёжность)
- [ ] **P1-1** PDF: снизить кап, потоковое чтение, убрать полную String-копию
- [ ] **P1-2** FB2: кап на base64-обложку
- [ ] **P1-5** Шифрование API-ключей (Keystore)
- [ ] Тесты на `PdfParser`, `Fb2Parser`, `RetryPolicy`, `VocabularyRepositoryImpl`
- [ ] **P3** Убрать мёртвые зависимости, `ObsoleteSdkInt`, `DefaultLocale`, `RedundantLabel`, неиспользуемые цвета

### Спринт 2 (UX и качество)
- [ ] **P1-3** Оглавление из метаданных глав, а не из окна загрузки
- [ ] **P1-4** Debounce записи прогресса
- [ ] **P2-1/P2-2** Разделить redirect-политику для API и загрузок; убрать глобальный мутабельный allow-list
- [ ] **P2-3** Потоковая обработка глав при импорте
- [ ] **P2-5** Единый дедуп через `CatalogDeduper` в обоих репозиториях
- [ ] **P2-13** Подключить Compose UI-тесты (и Roborazzi либо удалить из каталога)
- [ ] **P2-14** Вынести строки в `strings.xml`

### Спринт 3 (архитектура)
- [ ] Разделить `ReaderViewModel` (755 строк) и `MyLibBookSource` (733 строки)
- [ ] Убрать дублирование `BookSource.search` ↔ `BooksRepositoryImpl`
- [ ] Обновить Compose BOM и зависимости (40+ предупреждений об устаревании)
- [ ] Актуализировать `README.md`
- [ ] Разобраться с дублирующимися `.agents/`/`.claude/`/`.cursor/` паками инструкций

---

## 10. Приложение: команды для воспроизведения

```bash
# Тестовый гейт (в этом окружении требуется обход read-only GRADLE_USER_HOME)
export GRADLE_USER_HOME=C:/Users/Admin/AppData/Local/Temp/lexiread-gradle-home
/c/Users/Admin/scoop/apps/gradle/current/bin/gradle --no-configuration-cache \
  :app:testDebugUnitTest --continue          # 140 тестов, 0 падений

# Lint
/c/Users/Admin/scoop/apps/gradle/current/bin/gradle --no-configuration-cache :app:lintDebug
# отчёт: app/build/reports/lint-results-debug.html|txt|xml

# Проверка инжекта секретов в артефакт
grep -n "API_KEY" app/build/generated/source/buildConfig/debug/com/lexiread/BuildConfig.java

# Проверка, что секреты не в git
git ls-files | grep -iE "\.env$|keystore|local\.properties"
```

---

## 11. Сводка находок

| ID | Severity | Область | Файл |
|---|---|---|---|
| P0-1 | Блокер | Комплаенс/авторские права | `MyLibBookSource.kt:676`, `BooksRepositoryImpl.kt:486`, `CatalogScreen.kt:249` |
| P0-2 | Блокер | Ресурсы/сборка | `res/mipmap-*/ic_launcher*.webp` |
| P0-3 | Блокер | Секреты | `build.gradle.kts:100`, `.github/workflows/android_ci_cd.yml:75` |
| P1-1 | Высокий | Память/OOM | `PdfParser.kt:47` |
| P1-2 | Высокий | Память/OOM | `Fb2Parser.kt:195-203` |
| P1-3 | Высокий | Функциональность | `ReaderScreen.kt:379`, `ReaderViewModel.kt:264` |
| P1-4 | Высокий | Производительность | `ReaderViewModel.kt:513-534` |
| P1-5 | Высокий | Безопасность данных | `UserPreferencesManager.kt:26-81` |
| P1-6 | Высокий | Приватность/комплаенс | `RetrofitClient.kt:239-291` |
| P2-1…P2-14 | Средний | Разное | см. §5 |
| P3 | Низкий | Гигиена/Lint | см. §6 |

**Всего:** 3 блокера, 6 высоких, 14 средних, ~25 низких (включая 55 предупреждений Lint).
