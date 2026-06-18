# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

# 项目语言规范
- 所有对话、解释、建议必须使用**简体中文**。
- 代码注释、Commit Message均要求使用中文。

## Build Commands

All commands run from repo root:

```powershell
# Debug APK
.\gradlew.bat assembleDebug --no-daemon --console plain

# Release APK
.\gradlew.bat assembleRelease --no-daemon --console plain

# Release AAB
.\gradlew.bat bundleRelease --no-daemon --console plain

# Install debug to device
.\gradlew.bat installDebug --no-daemon --console plain

# Clean
.\gradlew.bat clean --no-daemon --console plain
```

Shortcut via `pack.bat` (auto-detects JDK 17 and Android SDK):
`.\pack.bat debug` | `release` | `bundle` | `install` | `clean`

Build outputs: `app/build/outputs/apk/debug/app-debug.apk`, `app/build/outputs/apk/release/`, `app/build/outputs/bundle/release/`

## Environment

- JDK 17 required (pack.bat auto-detects from JAVA_HOME or common install paths)
- Android SDK required; `local.properties` with `sdk.dir=...` must exist (pack.bat can auto-generate it)
- Gradle 8.9, AGP 8.7.3, compileSdk/targetSdk 35, minSdk 26
- Java 17 source/target compatibility

## Architecture

Single-module pure Java Android app (`com.metahumanz.pacilread`). No Kotlin, no Jetpack Compose, no Room. Dependencies are intentionally small: AndroidX Activity/DocumentFile, Material Design, PDFBox-Android, and OkHttp.

### Activity hierarchy

- **ThemedActivity** → **BookshelfActivity** (bookshelf hub with home sections and navigation)
- **ThemedReaderActivity** → **ModernReaderActivity** (core reader — pagination, flip animations, TTS, search, TOC, style dialogs, HUD overlays, WebDAV sync on chapter change)
- **ReaderActivity** is a trivial subclass of ModernReaderActivity
- **ThemedActivity** → **SettingsActivity** (WebDAV config, theme modes, font settings, backup/restore)

### Key packages

- **storage** — `JsonDatabase` (JSON-only app data store with external gzip chapter text files, dirty write coalescing, and JSON export/import for WebDAV), `SettingsStore` (SharedPreferences wrapper for app and reader settings)
- **importer** — `BookImportService` orchestrates import; `BookFileNameParser` (Chinese filename regex), `TxtChapterParser` (encoding detection + chapter splitting), `EpubChapterParser` (OPF/spine/NCX/NAV), `PdfChapterParser` (PDFBox extraction)
- **reader** — `ReaderPaginator`/`PageSlice` (StaticLayout pagination), `JustifiedPageTextView` (full-justify rendering), `SimulationPageTurnView` (Bezier curl animation), `ReplacementEngine` (regex/text replacement rules), persistent per-book full-text search indexes, quote share-card rendering
- **sync** — `WebDavClient` (OkHttp-backed PROPFIND/MKCOL/PUT/GET/HEAD, Basic Auth) and `WebDavBackupManager` (JSON full/incremental backup/restore with selective scope)
- **tts** — `TtsPlaybackService` (foreground media playback, notification controls, sleep timer, sentence prefetch), `MimoTtsClient` (Xiaomi MiMo cloud TTS API, SSE streaming, PCM16 AudioTrack playback)
- **theme** — `ThemedActivity`/`ThemedReaderActivity`/`ThemeModeHelper` (app theme + separate reader UI theme, follow_app/system/light/dark modes)
- **ui** — `GlassUiHelper` (configurable glass-morphism opacity), `AppDrawerController` (custom drawer with gesture/fling/scrim)
- **model** — Simple data classes: BookRecord, ChapterRecord, ImportedBook, ReaderThemeRecord, ReplacementRuleRecord

### Database

The app uses JSON files under the private `files/database/` directory as the only active data source. Chapter bodies are stored outside the JSON metadata as gzip text files under `files/chapter_text/`. SQLite `reader.db` migration support has been removed; legacy SQLite databases are not imported by this version.

### WebDAV sync scope

Backup/restore can selectively include: bookshelf metadata, book files, UI settings, custom themes, background images, and chapter text archives. Incremental mode uploads JSON entity files and a manifest, then merges entities by stable identity keys.

### UI language

All user-facing strings and dialogs are in Simplified Chinese.

## Testing

JUnit tests live under `app/src/test/`. Run `testDebugUnitTest`, `assembleDebug`, and `lintDebug` before delivery.
