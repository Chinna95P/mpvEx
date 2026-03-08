package app.marlboroadvance.mpvex.domain.anicli

data class AniCliAnime(
    val id: String,
    val name: String,
    val subEpisodes: Int,
    val dubEpisodes: Int,
)

data class AniCliStreamLink(
    val quality: String,
    val url: String,
    val isM3u8: Boolean = false,
    val referer: String? = null,
)

data class AniCliUiState(
    val searchQuery: String = "",
    val mode: String = "sub",
    val isSearching: Boolean = false,
    val searchResults: List<AniCliAnime> = emptyList(),
    val hasSearched: Boolean = false,
    val selectedAnime: AniCliAnime? = null,
    val isLoadingEpisodes: Boolean = false,
    val episodes: List<String> = emptyList(),
    val selectedEpisode: String? = null,
    val isLoadingStreams: Boolean = false,
    val streamLinks: List<AniCliStreamLink> = emptyList(),
    val showStreamSheet: Boolean = false,
    val errorMessage: String? = null,
)
