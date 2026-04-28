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
- Gradle 8.9, AGP 8.7.3, compileSdk/targetSdk 33, minSdk 26
- Java 17 source/target compatibility

## Architecture

Single-module pure Java Android app (`com.metahumanz.pacilread`). No Kotlin, no Jetpack Compose, no Room, no OkHttp — everything is hand-implemented with minimal dependencies (Material Design 1.9.0, PDFBox-Android 2.0.27.0).

### Activity hierarchy

- **ThemedActivity** → **MainActivity** (bookshelf hub with custom edge-swipe drawer)
- **ThemedReaderActivity** → **ModernReaderActivity** (core reader — pagination, flip animations, TTS, search, TOC, style dialogs, HUD overlays, WebDAV sync on chapter change)
- **ReaderActivity** is a trivial subclass of ModernReaderActivity
- **ThemedActivity** → **SettingsActivity** (WebDAV config, theme modes, font settings, backup/restore)

### Key packages

- **storage** — `ReaderDatabaseHelper` (singleton SQLiteOpenHelper, `reader.db` v3, additive schema migration via `ensureColumn()`) and `SettingsStore` (SharedPreferences wrapper for ~50 settings keys, JSON export/import for WebDAV)
- **importer** — `BookImportService` orchestrates import; `BookFileNameParser` (Chinese filename regex), `TxtChapterParser` (encoding detection + chapter splitting), `EpubChapterParser` (OPF/spine/NCX/NAV), `PdfChapterParser` (PDFBox extraction)
- **reader** — `ReaderPaginator`/`PageSlice` (StaticLayout pagination), `JustifiedPageTextView` (full-justify rendering), `SimulationPageTurnView` (Bezier curl animation), `ReplacementEngine` (regex/text replacement rules), `ReaderThemeConfig`
- **sync** — `WebDavClient` (raw HttpURLConnection, PROPFIND/MKCOL/PUT/GET/HEAD, Basic Auth) and `WebDavBackupManager` (full/incremental backup/restore with selective scope)
- **tts** — `MimoTtsClient` (Xiaomi MiMo cloud TTS API, SSE streaming, PCM16 AudioTrack playback)
- **theme** — `ThemedActivity`/`ThemedReaderActivity`/`ThemeModeHelper` (app theme + separate reader UI theme, follow_app/system/light/dark modes)
- **ui** — `GlassUiHelper` (configurable glass-morphism opacity), `AppDrawerController` (custom drawer with gesture/fling/scrim)
- **model** — Simple data classes: BookRecord, ChapterRecord, ImportedBook, ReaderThemeRecord, ReplacementRuleRecord

### Database

`reader.db` (version 3) with tables: `books`, `chapters`, `replacement_rules`, `custom_themes`. Migration is additive-only — new columns are added via ALTER TABLE in `ensureColumn()` without dropping existing data.

### WebDAV sync scope

Backup/restore can selectively include: bookshelf metadata, book files, UI settings, custom themes, background images. Incremental mode uses a "lite" database (no chapter content) and merges via upsert on title+author key.

### UI language

All user-facing strings and dialogs are in Simplified Chinese.

## Testing

No test infrastructure exists — no test directories, no test dependencies, no lint configuration.