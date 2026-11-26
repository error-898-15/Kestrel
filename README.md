# Kestrel

Kestrel is a native Android media streaming player (Kotlin, Jetpack Compose, Media3/ExoPlayer) with a custom dark-first UI and modular extension adapters for CloudStream `.cs3` repositories, Nuvio JS scrapers, and Stremio add-ons.

Kestrel ships with **no built-in content sources**. Users add repositories manually and control which plugins are installed and enabled.

## Architecture & Modules

- `:app` — Application entry point, Hilt wiring, Material 3 `KestrelTheme` host, navigation shell, and first-launch disclaimer gate.
- `:core` — Design system tokens (`Color`, `Type`, `ShapeAndSpacing`, `KestrelTheme`, reusable UI components), OkHttp, JSON, and coroutine dispatchers.
- `:domain` — Pure domain models (`RepoConfig`, `PluginMetadata`, `MediaSummary`, `MediaDetails`, `StreamSource`, `LibraryEntry`) and the `SourceProvider` interface.
- `:data` — Persistence (Room, DataStore) and repository/plugin management.
- `:plugin-host` — **Only** module touching third-party plugin APIs (`com.lagradost.cloudstream3.*`, QuickJS, Stremio wire models). Implements `PluginSandbox`, `AdapterRegistry`, and `CloudStreamAdapter` / `NuvioAdapter` / `StremioAdapter`.
- `:feature-home`, `:feature-search`, `:feature-details`, `:feature-player`, `:feature-extensions`, `:feature-library` — Feature screens depending strictly on `:core` and `:domain`.

## CloudStream Compatibility Pin

- **Pinned CloudStream `library` commit**: `a28e6468f54cfe420feebd205d3d21d08ed0cde8`
- **CloudStream `library` version**: `1.0.1`
- **Supported Plugin `apiVersion`**: `1`
- **Pinned runtime libraries**: `jackson-module-kotlin:2.13.1` (required for `minSdk 24`), `jsoup:1.18.3`, `NiceHttp:0.4.11`, `rhino:1.8.1`.

### Building Locally

1. Fetch and publish the pinned CloudStream `:library` artifact into `local-m2/`:
   ```bash
   bash scripts/fetch-cloudstream-lib.sh
   ```
2. Build and test:
   ```bash
   ./gradlew testDebugUnitTest assembleDebug lintDebug
   ```

### CloudStream API Upgrade Checklist

1. Update `CLOUDSTREAM_COMMIT` in `scripts/fetch-cloudstream-lib.sh` and `.github/workflows/android-ci.yml`.
2. Delete `local-m2/` and run `bash scripts/fetch-cloudstream-lib.sh`.
3. Verify binary/source compatibility in `:plugin-host` (`MainAPI`, `BasePlugin`, `ExtractorLink`, `LoadResponse`, `SearchResponse`).
4. Check `consumer-rules.pro` for any new `com.lagradost.*` packages or reflective types.
5. Run `./gradlew testDebugUnitTest assembleDebug lintDebug` and the `TestPlugins` integration test suite.

## License

Licensed under the **GNU General Public License v3.0 or later** (`GPL-3.0-or-later`), compatible with the upstream `recloudstream/cloudstream` `library` module.
