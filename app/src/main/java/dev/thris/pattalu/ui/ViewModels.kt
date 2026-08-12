package dev.thris.pattalu.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.thris.pattalu.data.*
import dev.thris.pattalu.model.*
import dev.thris.pattalu.playback.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext

enum class SearchType { Songs, Playlists }
data class SearchUiState(val query: String = "", val searchType: SearchType = SearchType.Songs, val releaseTopic: Boolean = true, val loading: Boolean = false, val featuredLoading: Boolean = true, val results: List<SearchResult> = emptyList(), val playlistResults: List<PlaylistSearchResult> = emptyList(), val openedPlaylist: PlaylistSearchResult? = null, val playlistSongs: List<SearchResult> = emptyList(), val topSongs: List<SearchResult> = emptyList(), val topWorld: List<SearchResult> = emptyList(), val downloads: Map<String, DownloadState> = emptyMap(), val listens: Map<String, DownloadState> = emptyMap(), val message: String? = null)
@HiltViewModel class SearchViewModel @Inject constructor(private val search: SongSearchRepository, private val downloads: DownloadRepository, private val playback: PlaybackController, @ApplicationContext context: Context) : ViewModel() {
    private val preferences = context.getSharedPreferences("search_preferences", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(SearchUiState(releaseTopic = preferences.getBoolean("release_topic", true))); val state = mutable.asStateFlow()
    init { loadFeatured() }
    fun query(value: String) { mutable.update { it.copy(query = value) } }
    fun setSearchType(type: SearchType) { mutable.update { it.copy(searchType = type, results = emptyList(), playlistResults = emptyList(), openedPlaylist = null, playlistSongs = emptyList(), message = null) } }
    fun setReleaseTopic(enabled: Boolean) { preferences.edit().putBoolean("release_topic", enabled).apply(); mutable.update { it.copy(releaseTopic = enabled) } }
    fun search() { val entered = state.value.query.trim(); if (entered.isEmpty()) return; viewModelScope.launch {
        mutable.update { it.copy(loading = true, message = null, openedPlaylist = null, playlistSongs = emptyList()) }
        if (state.value.searchType == SearchType.Playlists) search.searchPlaylists(entered).fold(
            { rows -> mutable.update { it.copy(loading = false, playlistResults = rows, results = emptyList(), message = if (rows.isEmpty()) "No playlists found" else null) } }, ::searchFailed
        ) else { val q = if (state.value.releaseTopic) "$entered song Release topic" else entered; search.search(q).fold(
            { rows -> mutable.update { it.copy(loading = false, results = rows, playlistResults = emptyList(), message = if (rows.isEmpty()) "No results" else null) } }, ::searchFailed
        ) }
    } }
    fun openPlaylist(playlist: PlaylistSearchResult) { viewModelScope.launch { mutable.update { it.copy(openedPlaylist = playlist, playlistSongs = emptyList(), loading = true, message = null) }; search.playlist(playlist.playlistId, 0).fold({ songs -> mutable.update { it.copy(loading = false, playlistSongs = songs, message = if (songs.isEmpty()) "This playlist has no available songs" else null) } }, ::searchFailed) } }
    fun closePlaylist() { mutable.update { it.copy(openedPlaylist = null, playlistSongs = emptyList(), message = null) } }
    private fun searchFailed(error: Throwable) { mutable.update { it.copy(loading = false, message = error.message ?: "Search failed. Check your connection and retry.") } }
    fun download(result: SearchResult) { viewModelScope.launch { downloads.download(result).collect { event -> mutable.update { s -> s.copy(downloads = s.downloads + (result.videoId to event), message = (event as? DownloadState.Failed)?.message) } } } }
    fun listenOnce(result: SearchResult) { viewModelScope.launch { downloads.listenOnce(result).collect { event ->
        mutable.update { s -> s.copy(listens = s.listens + (result.videoId to event), message = (event as? DownloadState.Failed)?.message) }
        if (event is DownloadState.Completed) playback.playOnce(event.track)
    } } }
    fun cancel(id: String) = downloads.cancel(id)
    fun loadFeatured() { viewModelScope.launch {
        mutable.update { it.copy(featuredLoading = true) }
        val top = search.playlist(TOP_SONGS).getOrDefault(emptyList())
        val world = search.playlist(TOP_WORLD).getOrDefault(emptyList())
        mutable.update { it.copy(featuredLoading = false, topSongs = top, topWorld = world, message = if (top.isEmpty() && world.isEmpty()) "Charts unavailable. Pull back online and retry." else it.message) }
    } }
    companion object { const val TOP_SONGS = "RDCLAK5uy_kCicKSTh7ylcZSwvrN0vV4dI3eqEpXR4A"; const val TOP_WORLD = "RDCLAK5uy_kmPRjHDECIcuVwnKsx2Ng7fyNgFKWNJFs" }
}
@HiltViewModel class LibraryViewModel @Inject constructor(private val library: LibraryRepository, private val playback: PlaybackController) : ViewModel() {
    val tracks = library.observeTracks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val playlists = library.observePlaylists().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val query = MutableStateFlow(""); val libraryQuery = query.asStateFlow()
    val filteredTracks = combine(tracks, query) { rows, value -> if (value.isBlank()) rows else rows.filter { it.title.contains(value, true) || it.artist.contains(value, true) } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val player = playback.state
    fun play(id: String) = playback.playFromLibrary(tracks.value, id); fun delete(id: String) { viewModelScope.launch { library.delete(id) } }
    fun setQuery(value: String) { query.value = value }
    fun createPlaylist(name: String, trackId: String? = null) { viewModelScope.launch { runCatching { library.createPlaylist(name) }.onSuccess { id -> trackId?.let { library.addToPlaylist(id, it) } } } }
    fun addToPlaylist(playlistId: Long, trackId: String) { viewModelScope.launch { library.addToPlaylist(playlistId, trackId) } }
    fun removeFromPlaylist(playlistId: Long, trackId: String) { viewModelScope.launch { library.removeFromPlaylist(playlistId, trackId) } }
    fun deletePlaylist(playlistId: Long) { viewModelScope.launch { library.deletePlaylist(playlistId) } }
    fun playPlaylist(playlist: Playlist, trackId: String) = playback.playFromLibrary(playlist.tracks, trackId)
    fun toggle() { if (player.value.isPlaying) playback.pause() else playback.play() }; fun seek(ms: Long) = playback.seek(ms); fun next() = playback.next(); fun previous() = playback.previous()
    fun toggleRepeatOne() = playback.toggleRepeatOne()
}
