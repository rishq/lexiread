# LexiRead

LexiRead is an Android application designed for reading e-books and improving language comprehension with built-in translation, dictionary lookup, space-repetition vocabulary learning, and AI-powered text explanations.

## Overview

LexiRead combines an e-book reader with language-learning utilities. Readers can import personal books in multiple formats or browse curated public-domain classics, tap any word to view definitions and contextual translations, and save vocabulary for review.

## Key Features

- **Personal Book Importer**: Import local EPUB, PDF, FB2, and TXT files securely. All imported books are parsed and stored locally on device.
- **Reading Engine**:
  - Offline text pagination adjusted for screen size, font scale, line spacing, and margins.
  - Page-turning options including tap zones, swipe gestures, and hardware volume keys.
  - Sepia, Light, and Dark reading themes with customizable typography (Serif, Sans-Serif, Monospace).
  - Table of contents navigation and bookmark management.
- **Instant Word Lookup & Translation**: Tap any word while reading to view definitions, phonetic transcriptions, part of speech, usage examples, and translations.
  Cloud lookups are **off by default**: the first tap shows a consent dialog
  (see `PRIVACY_POLICY.md` for the recipient list), and `Settings → Privacy &
  Cloud Lookups` toggles them anytime. API keys are stored encrypted on-device.
- **Text-to-Speech (TTS)**: Built-in audio pronunciation for words and context sentences.
- **AI Tutor & Context Analysis**: Powered by Gemini API to provide simplified explanations, grammar breakdowns, and contextual context for challenging sentences or phrases.
- **Vocabulary Trainer**:
  - Saved word repository categorized by source book and date.
  - Interactive flashcard review with flashcard flip animations and mastery status tracking.
- **Library & Progress Tracking**:
  - Filter books by Reading, Favorites, Saved, and Finished.
  - Reading progress, chapter position, and percent completed persist automatically across sessions.

## Supported File Formats

- **EPUB**: Parsed with OPF manifest, metadata, chapter hierarchy, and embedded cover extraction.
- **PDF**: Page section extraction and layout formatting.
- **FB2**: Section and paragraph structure parsing with base64 cover extraction.
- **TXT**: Automatic paragraph cleanup and chapter splitting based on standard headers.

## Architecture & Tech Stack

- **UI Framework**: Jetpack Compose with Material Design 3
- **Language**: Kotlin
- **Architecture Pattern**: MVVM (Model-View-ViewModel) with Clean Architecture layers
- **Local Database**: Room DB (Entities, DAOs, Migrations)
- **State Management**: Kotlin Coroutines & StateFlow / SharedFlow
- **Navigation**: Type-safe Navigation Compose
- **Preferences**: DataStore Preferences for persistent reader customization
- **AI Integration**: Gemini / OpenAI / Claude / DeepSeek via user-provided keys
  entered in Settings and stored locally on device. No API keys are packaged
  into the APK (see `app/build.gradle.kts` secrets `ignoreList`).

## Project Structure

- `app/src/main/java/com/example/core/`: Reader parsers, pagination engine, user preferences, and utilities (TTS, helpers).
- `app/src/main/java/com/example/data/`: Room database, DAOs, entities, and repository implementations.
- `app/src/main/java/com/example/domain/`: Domain models and repository interface declarations.
- `app/src/main/java/com/example/presentation/`: Composables and ViewModels for Library, Reader, Explore, Vocabulary, and Settings screens.

## CI/CD Pipeline (GitHub Actions)

The repository includes a complete automated CI/CD workflow (`.github/workflows/android_ci_cd.yml`):

- **Automatic Build & Test on Push / Pull Request**: Every push or PR to `main`/`master` runs unit tests, compiles the instrumented tests, and builds the **debug** APK. Release variants are deliberately not built on that path: they need the upload keystore, which never reaches a pull-request build.
- **Automated GitHub Releases on Tags**: Push a tag (e.g. `v1.0.0`) to build the signed release APK/AAB, create a GitHub Release, generate release notes, and attach both binaries. Pushing to `main` does **not** cut a release.
- **Manual Trigger (`workflow_dispatch`)**: Run the workflow directly from the GitHub Actions tab with custom release tags and pre-release options.
- **GitHub Artifacts**: The build job uploads `LexiRead-debug.apk`; the release job uploads `LexiRead-release.apk` and `LexiRead-release.aab`. Both are accessible from the workflow summary.

### Setting up Repository Secrets
Release signing secrets are **required** for any build that produces a release
artifact. `app/build.gradle.kts` refuses to assemble a release variant in CI
(`CI` env set) when they are missing, instead of silently falling back to the
debug key — a debug-signed bundle looks like a real release but is rejected by
Google Play. Configure these in **GitHub Repository Settings -> Secrets and
variables -> Actions**:
- `RELEASE_KEYSTORE_BASE64`: Base64-encoded release keystore file (`.jks`).
- `STORE_PASSWORD`: Keystore password.
- `KEY_PASSWORD`: Key alias password.

Local builds still work without them: outside CI the release variant falls back
to `debug.keystore`. Set `ALLOW_DEBUG_SIGNING=true` to allow that fallback in CI
too (e.g. for a smoke-test build).

AI provider keys are **not** injected at build time. `GEMINI_API_KEY` is excluded
from `BuildConfig` on purpose (see `secrets { ignoreList }`); every provider key
is entered by the user in Settings and stored encrypted on the device.

## License

This project is licensed under the Apache License 2.0.
