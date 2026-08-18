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
- **AI Integration**: Gemini REST API via server-side AI Studio integration

## Project Structure

- `app/src/main/java/com/example/core/`: Reader parsers, pagination engine, user preferences, and utilities (TTS, helpers).
- `app/src/main/java/com/example/data/`: Room database, DAOs, entities, and repository implementations.
- `app/src/main/java/com/example/domain/`: Domain models and repository interface declarations.
- `app/src/main/java/com/example/presentation/`: Composables and ViewModels for Library, Reader, Explore, Vocabulary, and Settings screens.

## CI/CD Pipeline (GitHub Actions)

The repository includes a complete automated CI/CD workflow (`.github/workflows/android_ci_cd.yml`):

- **Automatic Build & Test on Push / Pull Request**: Every push or PR to `main`/`master` runs unit tests and builds the debug and release APKs / AAB bundles.
- **Automated GitHub Releases on Tags**: Push a tag (e.g. `v1.0.0`) to automatically trigger a build, create a GitHub Release, generate release notes, and attach the APK and AAB binaries.
- **Manual Trigger (`workflow_dispatch`)**: Run the workflow directly from the GitHub Actions tab with custom release tags and pre-release options.
- **GitHub Artifacts**: All successful builds upload artifacts (`LexiRead-debug.apk`, `LexiRead-release.apk`, `LexiRead-release.aab`) accessible directly in the workflow summary.

### Setting up Repository Secrets (Optional)
To sign release APKs and enable Gemini API in production builds, configure these in **GitHub Repository Settings -> Secrets and variables -> Actions**:
- `GEMINI_API_KEY`: Your Gemini API Key (injected into `.env`).
- `RELEASE_KEYSTORE_BASE64`: Base64-encoded release keystore file (`.jks`).
- `STORE_PASSWORD`: Keystore password.
- `KEY_PASSWORD`: Key alias password.

## License

This project is licensed under the Apache License 2.0.
