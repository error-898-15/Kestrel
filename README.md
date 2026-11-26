# Kestrel

Kestrel is a native Android media streaming player (Kotlin, Jetpack Compose, Hilt, Room, Coil, OkHttp, Media3/ExoPlayer) with an original dark-first UI and modular extension adapters for **CloudStream `.cs3` repositories**, **Nuvio JS scrapers**, and **Stremio HTTP add-ons**.

Kestrel ships with **zero built-in content sources**. Users add repositories manually and control which plugins are installed and enabled.

---

## User Guide

1. **First Launch Notice**: On first launch, confirm the no-content disclaimer acknowledging that all catalogs and streams come solely from user-added repositories.
2. **Add a Repository (`Extensions` tab)**:
   - Paste any supported URL or identifier into the **Add Repository** field and tap **Add**:
     - **CloudStream `.cs3` repos**: direct `repo.json` URL, GitHub/Codeberg link, `cloudstreamrepo://...`, or shortcode.
     - **Nuvio JS repos**: `manifest.json` URL listing JS scrapers (scrapers start **disabled** until you toggle them on).
     - **Stremio add-ons**: `/manifest.json` URL.
   - Kestrel auto-detects the format and routes it to the matching adapter.
   - Use the **Validate / Refresh** button on any repository card to re-check availability and detect plugin updates.
   - Filter plugins by **language** chips, **`tvTypes`** chips, or search text, then install/update/uninstall and enable/disable per plugin.
3. **Browse & Search (`Discover` & `Search` tabs)**:
   - `Discover` renders catalog rows from all enabled providers, with source filter chips.
   - `Search` queries all enabled adapters concurrently and groups results by provider.
4. **Details & Playback**:
   - Tap any title to inspect poster, plot, tags, cast, seasons/episodes, and recommendations, or bookmark it to your **Library**.
   - Tap **Play** / **Resume** (or an episode) to open the **Source Picker** and launch **Media3 ExoPlayer** with HLS/DASH/MP4 support, per-stream custom headers/referer, quality/audio/subtitle selectors, playback speed (`0.5x`–`2.0x`), episode overlay, next-episode jump, and Picture-in-Picture (PiP).
5. **Library (`Library` tab)**:
   - Access **Continue Watching** (with saved resume progress), **Favorites**, and **Watch History**.

---

## Architecture & Modules

- `:app` — Application entry point, Hilt graph, `KestrelTheme`, `KestrelAppShell` navigation, and first-launch disclaimer gate.
- `:core` — Dark cinema Material 3 design tokens (`Color`, `Type`, `ShapeAndSpacing`, `KestrelTheme`, reusable UI components), OkHttp, JSON, dispatchers.
- `:domain` — Pure Kotlin models (`RepoConfig`, `PluginMetadata`, `MediaSummary`, `MediaDetails`, `StreamSource`, `LibraryEntry`), `SourceProvider`, `PlaybackSessionManager`, and repository interfaces.
- `:data` — Room database (`RepoDao`, `PluginDao`, `LibraryDao`), DataStore preferences, `RepoUrlResolver`, `RepoJsonParser`, `PluginInstaller`, and repository implementations.
- `:plugin-host` — **Only** module touching third-party plugin APIs (`com.lagradost.cloudstream3.*`, `dokar3/quickjs-kt`, Stremio DTOs). Provides `PluginSandbox`, `AdapterRegistry`, `CompositePluginRuntimeLoader`, `Cs3PluginLoader` (`DexClassLoader`), `CloudStreamAdapter`, `NuvioAdapter`, and `StremioAdapter`.
- `:feature-home`, `:feature-search`, `:feature-details`, `:feature-player`, `:feature-extensions`, `:feature-library` — Feature modules depending strictly on `:core` and `:domain`.

---

## CloudStream Compatibility Pin

- **Pinned CloudStream `library` commit**: `a28e6468f54cfe420feebd205d3d21d08ed0cde8`
- **CloudStream `library` version**: `1.0.1`
- **Supported Plugin `apiVersion`**: `1`
- **Pinned runtime dependencies**: `jackson-module-kotlin:2.13.1` (required for `minSdk 24`), `jsoup:1.18.3`, `NiceHttp:0.4.11`, `rhino:1.8.1`, `quickjs-kt:1.0.18`.

### Building Locally

1. Publish the pinned CloudStream `:library` artifact into `local-m2/`:
   ```bash
   bash scripts/fetch-cloudstream-lib.sh
   ```
2. Run unit tests, lint, and assemble the debug APK:
   ```bash
   ./gradlew testDebugUnitTest assembleDebug lintDebug
   ```

### CloudStream API Upgrade Checklist

1. Update `CLOUDSTREAM_COMMIT` in `scripts/fetch-cloudstream-lib.sh` and `.github/workflows/android-ci.yml`.
2. Delete `local-m2/` and re-run `bash scripts/fetch-cloudstream-lib.sh`.
3. Verify `CloudStreamMappers`, `CloudStreamAdapter`, `Cs3PluginLoader`, and `CloudStreamShims` compile cleanly against updated `MainAPI` / `BasePlugin` / `LoadResponse` / `ExtractorLink` signatures.
4. Update `PluginSandbox.SUPPORTED_CLOUDSTREAM_API_VERSION` if the upstream plugin `apiVersion` increments.
5. Verify `plugin-host/consumer-rules.pro` retains all required `com.lagradost.*` classes for R8 release builds.
6. Run `./gradlew testDebugUnitTest assembleDebug lintDebug` and verify a `.cs3` build from `recloudstream/TestPlugins` end-to-end.

---

## License

Licensed under the **GNU General Public License v3.0 or later** (`GPL-3.0-or-later`), compatible with `recloudstream/cloudstream`'s `library` module.
