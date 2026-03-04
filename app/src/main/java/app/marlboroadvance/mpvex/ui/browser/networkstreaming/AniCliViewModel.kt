package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.marlboroadvance.mpvex.domain.anicli.AniCliAnime
import app.marlboroadvance.mpvex.domain.anicli.AniCliUiState
import app.marlboroadvance.mpvex.repository.AniCliRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class AniCliViewModel(application: Application) : AndroidViewModel(application), KoinComponent {

    private val repository: AniCliRepository by inject()

    private val _state = MutableStateFlow(AniCliUiState())
    val state: StateFlow<AniCliUiState> = _state.asStateFlow()

    fun updateQuery(query: String) {
        _state.update { it.copy(searchQuery = query, errorMessage = null) }
    }

    fun toggleMode() {
        val newMode = if (_state.value.mode == "sub") "dub" else "sub"
        _state.update {
            it.copy(
                mode = newMode,
                selectedAnime = null,
                episodes = emptyList(),
                searchResults = if (it.hasSearched) it.searchResults else emptyList(),
            )
        }
        // Re-search with new mode if there are results
        if (_state.value.hasSearched && _state.value.searchQuery.isNotBlank()) {
            search()
        }
    }

    fun search() {
        val query = _state.value.searchQuery.trim()
        if (query.isBlank()) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    isSearching = true,
                    searchResults = emptyList(),
                    hasSearched = true,
                    selectedAnime = null,
                    episodes = emptyList(),
                    errorMessage = null,
                )
            }
            repository.searchAnime(query, _state.value.mode)
                .onSuccess { results ->
                    _state.update { it.copy(isSearching = false, searchResults = results) }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(isSearching = false, errorMessage = "Search failed: ${e.message}")
                    }
                }
        }
    }

    fun selectAnime(anime: AniCliAnime) {
        // Tap same anime to collapse
        if (_state.value.selectedAnime?.id == anime.id) {
            _state.update { it.copy(selectedAnime = null, episodes = emptyList()) }
            return
        }
        viewModelScope.launch {
            _state.update {
                it.copy(
                    selectedAnime = anime,
                    isLoadingEpisodes = true,
                    episodes = emptyList(),
                    errorMessage = null,
                )
            }
            repository.getEpisodes(anime.id, _state.value.mode)
                .onSuccess { episodes ->
                    _state.update { it.copy(isLoadingEpisodes = false, episodes = episodes) }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(isLoadingEpisodes = false, errorMessage = "Failed to load episodes: ${e.message}")
                    }
                }
        }
    }

    fun selectEpisode(epNo: String) {
        val anime = _state.value.selectedAnime ?: return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    selectedEpisode = epNo,
                    isLoadingStreams = true,
                    streamLinks = emptyList(),
                    showStreamSheet = true,
                    errorMessage = null,
                )
            }
            repository.getStreamLinks(anime.id, _state.value.mode, epNo)
                .onSuccess { links ->
                    _state.update { it.copy(isLoadingStreams = false, streamLinks = links) }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            isLoadingStreams = false,
                            errorMessage = "No streams found: ${e.message}",
                            showStreamSheet = false,
                        )
                    }
                }
        }
    }

    fun dismissStreamSheet() {
        _state.update { it.copy(showStreamSheet = false, selectedEpisode = null, streamLinks = emptyList()) }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory = viewModelFactory {
            initializer { AniCliViewModel(application) }
        }
    }
}
