// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kestrel.domain.model.MangaExtensionMetadata
import com.kestrel.domain.model.MangaExtensionTestResult
import com.kestrel.domain.model.MangaRepoConfig
import com.kestrel.domain.repository.MangaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject

data class MangaSourceUiState(
    val repos: List<MangaRepoConfig> = emptyList(),
    val extensions: List<MangaExtensionMetadata> = emptyList(),
    val repoInput: String = "",
    val searchQuery: String = "",
    val selectedRepoId: String? = null,
    val isAddingRepo: Boolean = false,
    val refreshingRepoIds: Set<String> = emptySet(),
    val installingAllRepoIds: Set<String> = emptySet(),
    val testingAllRepoIds: Set<String> = emptySet(),
    val testResults: Map<String, MangaExtensionTestResult> = emptyMap(),
    val testingIds: Set<String> = emptySet(),
    val busyIds: Set<String> = emptySet(),
    val bannerMessage: String? = null,
    val bannerIsError: Boolean = false
)

/**
 * Drives Settings -> Manga Source (Aniyomi / Keiyoushi repositories) and the repo detail page.
 */
@HiltViewModel
class MangaSourceViewModel @Inject constructor(
    private val mangaRepository: MangaRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MangaSourceUiState())
    val uiState: StateFlow<MangaSourceUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { mangaRepository.ensureDefaultMangaRepositoriesSeeded() }
        }
        viewModelScope.launch {
            combine(mangaRepository.repositories, mangaRepository.extensions) { repos, exts ->
                repos to exts
            }.collect { (repos, exts) ->
                _uiState.update { s ->
                    s.copy(
                        repos = repos,
                        extensions = exts,
                        selectedRepoId = s.selectedRepoId?.takeIf { id -> repos.any { it.id == id } }
                    )
                }
            }
        }
    }

    private fun showBanner(message: String, isError: Boolean = false) {
        _uiState.update { it.copy(bannerMessage = message, bannerIsError = isError) }
    }

    fun dismissBanner() {
        _uiState.update { it.copy(bannerMessage = null, bannerIsError = false) }
    }

    fun onMangaRepoInputChanged(value: String) {
        _uiState.update { it.copy(repoInput = value) }
    }

    fun onMangaExtensionSearchChanged(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun selectMangaRepo(repoId: String) {
        _uiState.update { it.copy(selectedRepoId = repoId, searchQuery = "") }
    }

    fun addMangaRepository() {
        val input = _uiState.value.repoInput.trim()
        if (input.isEmpty() || _uiState.value.isAddingRepo) return
        viewModelScope.launch {
            _uiState.update { it.copy(isAddingRepo = true) }
            val result = mangaRepository.addRepository(input)
            result.onSuccess { repo ->
                _uiState.update { it.copy(repoInput = "", isAddingRepo = false) }
                showBanner("Added ${repo.name}")
            }
            result.onFailure { err ->
                _uiState.update { it.copy(isAddingRepo = false) }
                showBanner(err.message ?: "Failed to add repository", isError = true)
            }
        }
    }

    fun refreshMangaRepository(repoId: String) {
        if (repoId in _uiState.value.refreshingRepoIds) return
        viewModelScope.launch {
            _uiState.update { it.copy(refreshingRepoIds = it.refreshingRepoIds + repoId) }
            val result = mangaRepository.refreshRepository(repoId)
            _uiState.update { it.copy(refreshingRepoIds = it.refreshingRepoIds - repoId) }
            result.onSuccess { repo -> showBanner("Refreshed ${repo.name}") }
            result.onFailure { err -> showBanner(err.message ?: "Refresh failed", isError = true) }
        }
    }

    fun removeMangaRepository(repoId: String) {
        viewModelScope.launch {
            mangaRepository.removeRepository(repoId)
            showBanner("Repository removed")
        }
    }

    fun restoreDefaultMangaRepositories() {
        viewModelScope.launch {
            val result = mangaRepository.restoreDefaultMangaRepositories()
            result.onSuccess { added ->
                showBanner(
                    if (added == 0) "Default repositories already present"
                    else "Restored $added default repositories"
                )
            }
            result.onFailure { err ->
                showBanner(err.message ?: "Failed to restore defaults", isError = true)
            }
        }
    }

    fun installMangaExtension(extension: MangaExtensionMetadata) {
        if (extension.id in _uiState.value.busyIds) return
        viewModelScope.launch {
            _uiState.update { it.copy(busyIds = it.busyIds + extension.id) }
            showBanner("Installing ${extension.name}...")
            val result = mangaRepository.installExtension(extension.id)
            _uiState.update { it.copy(busyIds = it.busyIds - extension.id) }
            result.onSuccess { installed ->
                val count = installed.sources.size.coerceAtLeast(1)
                showBanner("Installed ${extension.name} ($count providers)")
            }
            result.onFailure { err ->
                showBanner(err.message ?: "Failed to install ${extension.name}", isError = true)
            }
        }
    }

    fun uninstallMangaExtension(extensionId: String) {
        if (extensionId in _uiState.value.busyIds) return
        viewModelScope.launch {
            _uiState.update { it.copy(busyIds = it.busyIds + extensionId) }
            mangaRepository.uninstallExtension(extensionId)
            _uiState.update { it.copy(busyIds = it.busyIds - extensionId) }
            showBanner("Extension uninstalled")
        }
    }

    fun installAllMangaExtensions(repoId: String) {
        if (repoId in _uiState.value.installingAllRepoIds) return
        viewModelScope.launch {
            _uiState.update { it.copy(installingAllRepoIds = it.installingAllRepoIds + repoId) }
            showBanner("Installing all extensions...")
            val result = mangaRepository.installAllInRepo(repoId)
            _uiState.update { it.copy(installingAllRepoIds = it.installingAllRepoIds - repoId) }
            result.onSuccess { count -> showBanner("Installed $count extensions") }
            result.onFailure { err -> showBanner(err.message ?: "Install all failed", isError = true) }
        }
    }

    fun testMangaExtension(extensionId: String) {
        if (extensionId in _uiState.value.testingIds) return
        viewModelScope.launch {
            _uiState.update { it.copy(testingIds = it.testingIds + extensionId) }
            val result = mangaRepository.testExtension(extensionId)
            _uiState.update {
                it.copy(
                    testingIds = it.testingIds - extensionId,
                    testResults = it.testResults + (extensionId to result)
                )
            }
        }
    }

    fun testAllMangaExtensions(repoId: String) {
        if (repoId in _uiState.value.testingAllRepoIds) return
        val targets = _uiState.value.extensions.filter { it.repoId == repoId }
        if (targets.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(testingAllRepoIds = it.testingAllRepoIds + repoId) }
            showBanner("Testing ${targets.size} extensions...")
            val semaphore = Semaphore(4)
            coroutineScope {
                targets.map { ext ->
                    async {
                        semaphore.withPermit {
                            _uiState.update { it.copy(testingIds = it.testingIds + ext.id) }
                            val result = mangaRepository.testExtension(ext.id)
                            _uiState.update {
                                it.copy(
                                    testingIds = it.testingIds - ext.id,
                                    testResults = it.testResults + (ext.id to result)
                                )
                            }
                        }
                    }
                }.awaitAll()
            }
            val results = _uiState.value.testResults
            val ok = targets.count { results[it.id]?.isReachable == true }
            _uiState.update { it.copy(testingAllRepoIds = it.testingAllRepoIds - repoId) }
            showBanner("Test finished: $ok / ${targets.size} reachable", isError = ok == 0)
        }
    }
}
