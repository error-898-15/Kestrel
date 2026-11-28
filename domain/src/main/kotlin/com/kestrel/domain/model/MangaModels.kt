// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class MangaExtensionInstallState {
    NOT_INSTALLED,
    INSTALLING,
    INSTALLED,
    ERROR
}

@Serializable
data class MangaSourceDescriptor(
    val id: String,
    val name: String,
    val lang: String,
    val baseUrl: String = "",
    val isVideo: Boolean = false
)

@Serializable
data class MangaRepoConfig(
    val id: String,
    val url: String,
    val resolvedUrl: String,
    val name: String,
    val description: String = "",
    val badgeLabel: String = "",
    val addedAtEpochMs: Long = 0L,
    val lastCheckedEpochMs: Long? = null,
    val isHealthy: Boolean = true,
    val lastError: String? = null
)

@Serializable
data class MangaExtensionMetadata(
    val id: String,
    val repoId: String,
    val name: String,
    val packageName: String,
    val apkUrl: String,
    val jarUrl: String? = null,
    val iconUrl: String? = null,
    val versionName: String = "1.0",
    val versionCode: Int = 1,
    val lang: String = "en",
    val isNsfw: Boolean = false,
    val sources: List<MangaSourceDescriptor> = emptyList(),
    val localApkPath: String? = null,
    val isEnabled: Boolean = false,
    val installState: MangaExtensionInstallState = MangaExtensionInstallState.NOT_INSTALLED,
    val lastError: String? = null
)

@Serializable
data class MangaExtensionTestResult(
    val extensionId: String,
    val isReachable: Boolean,
    val statusCode: Int,
    val latencyMs: Long,
    val checkedUrl: String,
    val error: String? = null
)

@Serializable
data class MangaSummary(
    val id: String,
    val extensionId: String,
    val sourceId: String,
    val sourceName: String,
    val title: String,
    val url: String,
    val coverUrl: String? = null,
    val coverHeaders: Map<String, String> = emptyMap(),
    val author: String? = null,
    val status: String? = null,
    val description: String? = null,
    val rating: Double? = null
)

@Serializable
enum class MangaBrowseMode {
    POPULAR,
    LATEST
}

@Serializable
data class MangaCatalogPage(
    val items: List<MangaSummary> = emptyList(),
    val hasNextPage: Boolean = false
)

@Serializable
data class MangaSectionRow(
    val id: String,
    val title: String,
    val sourceId: String?,
    val sourceName: String,
    val browseMode: MangaBrowseMode,
    val items: List<MangaSummary>,
    val hasNextPage: Boolean = false
)

@Serializable
data class MangaChapter(
    val id: String,
    val name: String,
    val url: String,
    val chapterNumber: Float = 0f,
    val scanlator: String? = null,
    val dateUploadEpochMs: Long = 0L,
    val isRead: Boolean = false,
    val lastPageRead: Int = 0
)

@Serializable
data class MangaDetails(
    val summary: MangaSummary,
    val artist: String? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val chapters: List<MangaChapter> = emptyList(),
    val isFavorite: Boolean = false
)

@Serializable
data class MangaProgressInfo(
    val mangaId: String,
    val extensionId: String,
    val sourceId: String,
    val sourceName: String,
    val title: String,
    val url: String,
    val coverUrl: String? = null,
    val isFavorite: Boolean = false,
    val lastChapterId: String? = null,
    val lastChapterName: String? = null,
    val lastPageRead: Int = 0,
    val totalPages: Int = 0,
    val readChapterIds: Set<String> = emptySet(),
    val updatedAtEpochMs: Long = 0L
)

@Serializable
data class MangaPage(
    val index: Int,
    val imageUrl: String,
    val headers: Map<String, String> = emptyMap()
)

@Serializable
data class DownloadedChapterManifest(
    val chapterId: String,
    val mangaId: String,
    val pagePaths: List<String>
)

@Serializable
enum class MangaReadingLayout { CONTINUOUS, PAGED }

@Serializable
enum class MangaReadingDirection { UP, DOWN, LEFT, RIGHT }

@Serializable
enum class MangaDualPageMode { SINGLE, DUAL, SPLIT }

@Serializable
enum class MangaReaderBackground { BLACK, DARK_GRAY, WHITE, SEPIA }

@Serializable
enum class MangaControlTheme { DEFAULT, LAVENDER, CRIMSON, OLED_MINIMAL }

@Serializable
enum class MangaImageQuality { LOW, MEDIUM, HIGH, ORIGINAL }

@Serializable
enum class MangaTapZonePreset { DEFAULT, L_SHAPED, KINDLE, EDGE }

@Serializable
data class MangaReaderSettings(
    val layout: MangaReadingLayout = MangaReadingLayout.CONTINUOUS,
    val direction: MangaReadingDirection = MangaReadingDirection.DOWN,
    val dualPageMode: MangaDualPageMode = MangaDualPageMode.SINGLE,
    val tapZonePreset: MangaTapZonePreset = MangaTapZonePreset.DEFAULT,
    val spacedPages: Boolean = false,
    val overscroll: Boolean = true,
    val autoWebtoonMode: Boolean = false,
    val navigateByNumber: Boolean = false,
    val fitToScreenWidth: Boolean = true,

    val controlTheme: MangaControlTheme = MangaControlTheme.DEFAULT,
    val background: MangaReaderBackground = MangaReaderBackground.BLACK,
    val imageFilterQuality: MangaImageQuality = MangaImageQuality.MEDIUM,
    val persistentPageIndicator: Boolean = false,
    val cropBorders: Boolean = false,
    val keepScreenOn: Boolean = true,
    val alwaysShowChapterTransition: Boolean = false,
    val longPressForPageActions: Boolean = true,
    val eInkDisplayRefresh: Boolean = false,
    val volumeKeysNavigation: Boolean = false,
    val invertVolumeKeys: Boolean = false,
    val autoScroll: Boolean = false,
    val preloadPages: Int = 3,

    val customBrightness: Boolean = false,
    val brightnessLevel: Float = 0.85f,
    val enhanceColors: Boolean = false,
    val colorFilterEnabled: Boolean = false,
    val colorFilterArgb: Long = 0x4DF5A623L,
    val grayscale: Boolean = false,
    val invertColors: Boolean = false,

    val superResolution: Boolean = false
)
