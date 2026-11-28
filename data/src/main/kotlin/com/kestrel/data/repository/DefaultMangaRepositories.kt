// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.data.repository

/**
 * Default Aniyomi & Keiyoushi manga extension repositories pre-installed in
 * Settings -> Manga Source.
 */
object DefaultMangaRepositories {
    val ALL: List<String> = listOf(
        "https://raw.githubusercontent.com/aniyomiorg/aniyomi-extensions/repo/index.min.json",
        "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.json",
        "https://raw.githubusercontent.com/salmanbappi/extensions-repo/repo/index.json",
        "https://raw.githubusercontent.com/bubz727/anime-repo-id/repo/index.json"
    )
}
