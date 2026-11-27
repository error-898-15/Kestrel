// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.data.repository

/**
 * Default pre-configured repositories seeded automatically on first launch
 * so users can immediately install extensions across Vega, Zangetsu,
 * SkyStream, and Hikari alongside CloudStream, Nuvio, and Stremio.
 */
object DefaultRepositories {
    val ALL: List<String> = listOf(
        // Vega Repositories
        "https://raw.githubusercontent.com/DHR-Store/vega-providers/main/manifest.json",
        "https://raw.githubusercontent.com/Zenda-Cross/vega-providers/main/manifest.json",
        // Zangetsu Repository
        "https://raw.githubusercontent.com/Spyou/zangetsu-providers/main/index.json",
        // SkyStream Repositories
        "https://raw.githubusercontent.com/akashdh11/skystream-plugins/main/repo.json",
        "https://raw.githubusercontent.com/akashdh11/sky-universe/main/mega_repo.json",
        // Hikari Repository
        "https://raw.githubusercontent.com/codegeasse1/hikari-extensions/builds/repo.json"
    )
}
