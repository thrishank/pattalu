# Pattalu

Native, offline-first Android music player. Search uses yt-dlp; completed M4A files and artwork live in app-specific external storage and metadata is stored in Room.

## Build

Build and test with `./gradlew testDebugUnitTest assembleDebug`. The VM SDK is configured by the ignored `local.properties` file.

The application bundles `youtubedl-android` and FFmpeg 0.18.1. Because that dependency is GPL-3.0, redistributed builds must provide corresponding source and attribution. Download only media you are authorized to save.
