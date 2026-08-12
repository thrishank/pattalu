package dev.thris.pattalu.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.os.Build
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.activity.compose.BackHandler
import coil3.compose.AsyncImage
import dev.thris.pattalu.model.*
import dev.thris.pattalu.playback.PlaybackState

@Composable fun PattaluRoot(searchVm: SearchViewModel = hiltViewModel(), libraryVm: LibraryViewModel = hiltViewModel()) {
    val search by searchVm.state.collectAsState(); val tracks by libraryVm.filteredTracks.collectAsState(); val playlists by libraryVm.playlists.collectAsState(); val libraryQuery by libraryVm.libraryQuery.collectAsState(); val player by libraryVm.player.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }; var fullPlayer by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = fullPlayer) { fullPlayer = false }
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFFD0BCFF))
        else -> lightColorScheme(primary = Color(0xFF6750A4))
    }
    MaterialTheme(colorScheme = colors) {
        Scaffold(
            bottomBar = { Column { if (player.currentTrackId != null) MiniPlayer(player, libraryVm::toggle) { fullPlayer = true }; NavigationBar { NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Default.Home, null) }, label = { Text("Home") }); NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Default.LibraryMusic, null) }, label = { Text("Library") }) } } }
        ) { padding ->
            if (fullPlayer) FullPlayer(player, libraryVm, { fullPlayer = false }, Modifier.padding(padding))
            else if (tab == 0) HomeScreen(search, searchVm, Modifier.padding(padding)) else LibraryScreen(tracks, playlists, libraryQuery, libraryVm, Modifier.padding(padding))
        }
    }
}

@Composable private fun HomeScreen(state: SearchUiState, vm: SearchViewModel, modifier: Modifier = Modifier) {
    val keyboard = LocalSoftwareKeyboardController.current
    val submit = { keyboard?.hide(); vm.search() }
    BackHandler(enabled = state.openedPlaylist != null) { vm.closePlaylist() }
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (state.openedPlaylist != null) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(vm::closePlaylist) { Icon(Icons.Default.ArrowBack, "Back to playlist results") }; Column { Text(state.openedPlaylist.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(state.openedPlaylist.owner, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) } }
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { items(state.playlistSongs, key = { it.videoId }) { result -> SearchRow(result, state.downloads[result.videoId], state.listens[result.videoId], vm) } }
            return@Column
        }
        Text("Find your next song", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 20.dp)); Text("Download once, listen anywhere.", color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(16.dp))
        OutlinedTextField(state.query, vm::query, Modifier.fillMaxWidth(), label = { Text("Search YouTube") }, placeholder = { Text("Song, artist, or album") }, leadingIcon = { Icon(Icons.Default.Search, null) }, trailingIcon = { if (state.query.isNotEmpty()) IconButton({ vm.query("") }) { Icon(Icons.Default.Close, "Clear") } }, singleLine = true, shape = RoundedCornerShape(28.dp), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { submit() }, onDone = { submit() }))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) { FilterChip(state.searchType == SearchType.Songs, { vm.setSearchType(SearchType.Songs) }, label = { Text("Songs") }, leadingIcon = { Icon(Icons.Default.MusicNote, null, Modifier.size(18.dp)) }); FilterChip(state.searchType == SearchType.Playlists, { vm.setSearchType(SearchType.Playlists) }, label = { Text("Playlists") }, leadingIcon = { Icon(Icons.Default.QueueMusic, null, Modifier.size(18.dp)) }) }
        if (state.searchType == SearchType.Songs) FilterChip(selected = state.releaseTopic, onClick = { vm.setReleaseTopic(!state.releaseTopic) }, label = { Text("Prefer official Release topics") }, leadingIcon = { Icon(if (state.releaseTopic) Icons.Default.Check else Icons.Default.Tune, null, Modifier.size(18.dp)) })
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
        if (state.loading || state.featuredLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.results.isNotEmpty() || state.playlistResults.isNotEmpty() || state.query.isNotBlank()) LazyColumn(contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.searchType == SearchType.Songs) items(state.results, key = { it.videoId }) { result -> SearchRow(result, state.downloads[result.videoId], state.listens[result.videoId], vm) }
            else items(state.playlistResults, key = { it.playlistId }) { playlist -> PlaylistSearchRow(playlist) { vm.openPlaylist(playlist) } }
        }
        else LazyColumn(contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item { FeaturedSection("Top songs", state.topSongs, state.downloads, vm) }
            item { FeaturedSection("Top world", state.topWorld, state.downloads, vm) }
            if (!state.featuredLoading && state.topSongs.isEmpty() && state.topWorld.isEmpty()) item { OutlinedButton(vm::loadFeatured) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(8.dp)); Text("Retry charts") } }
        }
    }
}
@Composable private fun PlaylistSearchRow(playlist: PlaylistSearchResult, open: () -> Unit) { ElevatedCard(Modifier.fillMaxWidth().clickable(onClick = open), shape = RoundedCornerShape(18.dp)) { ListItem(leadingContent = { if (playlist.thumbnailUrl != null) AsyncImage(playlist.thumbnailUrl, null, Modifier.size(64.dp)) else Icon(Icons.Default.QueueMusic, null, Modifier.size(48.dp)) }, headlineContent = { Text(playlist.title, maxLines = 2, overflow = TextOverflow.Ellipsis) }, supportingContent = { Text(buildString { append(playlist.owner); playlist.songCount?.let { append(" • $it songs") } }) }, trailingContent = { Icon(Icons.Default.ChevronRight, "Open playlist") }) } }
@Composable private fun FeaturedSection(title: String, songs: List<SearchResult>, downloads: Map<String, DownloadState>, vm: SearchViewModel) { val ui by vm.state.collectAsState(); Column { Text(title, style = MaterialTheme.typography.titleLarge); Spacer(Modifier.height(10.dp)); LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(songs, key = { it.videoId }) { song -> ElevatedCard(Modifier.width(180.dp), shape = RoundedCornerShape(20.dp)) { Column { AsyncImage(song.thumbnailUrl, null, Modifier.fillMaxWidth().aspectRatio(1f)); Column(Modifier.padding(12.dp)) { Text(song.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall); Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall); Spacer(Modifier.height(6.dp)); OnlineSongActions(song, downloads[song.videoId], ui.listens[song.videoId], vm) } } } } } } }
@Composable private fun SearchRow(result: SearchResult, state: DownloadState?, listenState: DownloadState?, vm: SearchViewModel) {
    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) { ListItem(
        leadingContent = { AsyncImage(result.thumbnailUrl, null, Modifier.size(64.dp)) },
        headlineContent = { Text(result.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text("${result.artist} • ${formatDuration(result.durationMs)}") },
        trailingContent = { OnlineSongActions(result, state, listenState, vm) }
    ) }
}
@Composable private fun OnlineSongActions(result: SearchResult, downloadState: DownloadState?, listenState: DownloadState?, vm: SearchViewModel) { Row(verticalAlignment = Alignment.CenterVertically) {
    when (listenState) {
        is DownloadState.Downloading -> IconButton({ vm.cancel(result.videoId) }) { CircularProgressIndicator(progress = { listenState.progress }, Modifier.size(28.dp)) }
        is DownloadState.Queued -> IconButton({ vm.cancel(result.videoId) }) { CircularProgressIndicator(Modifier.size(28.dp)) }
        else -> IconButton({ vm.listenOnce(result) }) { Icon(Icons.Default.MusicNote, "Listen once") }
    }
    when (downloadState) {
        is DownloadState.Downloading -> IconButton({ vm.cancel(result.videoId) }) { CircularProgressIndicator(progress = { downloadState.progress }, Modifier.size(28.dp)) }
        is DownloadState.Queued -> IconButton({ vm.cancel(result.videoId) }) { CircularProgressIndicator(Modifier.size(28.dp)) }
        is DownloadState.Completed -> Icon(Icons.Default.Check, "Downloaded", Modifier.padding(12.dp))
        else -> IconButton({ vm.download(result) }) { Icon(Icons.Default.Download, "Download") }
    }
} }
@Composable private fun LibraryScreen(tracks: List<Track>, playlists: List<Playlist>, query: String, vm: LibraryViewModel, modifier: Modifier = Modifier) {
    var section by rememberSaveable { mutableIntStateOf(0) }; var addTrack by remember { mutableStateOf<Track?>(null) }; var newPlaylist by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) { Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) { Text("Your library", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f)); IconButton({ newPlaylist = true }) { Icon(Icons.Default.PlaylistAdd, "New playlist") } }; TabRow(section) { Tab(section == 0, { section = 0 }, text = { Text("Songs") }); Tab(section == 1, { section = 1 }, text = { Text("Playlists") }) }; Spacer(Modifier.height(12.dp))
        if (section == 0) { OutlinedTextField(query, vm::setQuery, Modifier.fillMaxWidth(), placeholder = { Text("Search downloaded songs") }, leadingIcon = { Icon(Icons.Default.Search, null) }, trailingIcon = { if (query.isNotEmpty()) IconButton({ vm.setQuery("") }) { Icon(Icons.Default.Close, "Clear") } }, singleLine = true, shape = RoundedCornerShape(28.dp)); Spacer(Modifier.height(10.dp)); if (tracks.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(if (query.isBlank()) "Your offline library is empty" else "No downloaded songs match") }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 12.dp)) { items(tracks, key = { it.id }) { track -> ElevatedCard(shape = RoundedCornerShape(16.dp)) { ListItem(modifier = Modifier.clickable { vm.play(track.id) }, leadingContent = { AsyncImage(track.artworkPath, null, Modifier.size(56.dp)) }, headlineContent = { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }, supportingContent = { Text("${track.artist} • ${formatDuration(track.durationMs)}") }, trailingContent = { Row { IconButton({ addTrack = track }) { Icon(Icons.Default.PlaylistAdd, "Add to playlist") }; IconButton({ vm.delete(track.id) }) { Icon(Icons.Default.Delete, "Delete") } } }) } } }
        } else PlaylistList(playlists, vm)
    }
    addTrack?.let { track -> AddToPlaylistDialog(track, playlists, vm, { addTrack = null }) }
    if (newPlaylist) NewPlaylistDialog({ name -> vm.createPlaylist(name); newPlaylist = false }, { newPlaylist = false })
}
@Composable private fun PlaylistList(playlists: List<Playlist>, vm: LibraryViewModel) { if (playlists.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Create a playlist to organize your songs") } else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 12.dp)) { items(playlists, key = { it.id }) { playlist -> var expanded by rememberSaveable(playlist.id) { mutableStateOf(false) }; ElevatedCard(shape = RoundedCornerShape(18.dp)) { Column { ListItem(modifier = Modifier.clickable { expanded = !expanded }, leadingContent = { Icon(Icons.Default.QueueMusic, null) }, headlineContent = { Text(playlist.name) }, supportingContent = { Text("${playlist.tracks.size} song${if (playlist.tracks.size == 1) "" else "s"}") }, trailingContent = { Row { Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null); IconButton({ vm.deletePlaylist(playlist.id) }) { Icon(Icons.Default.Delete, "Delete playlist") } } }); if (expanded) playlist.tracks.forEach { track -> ListItem(modifier = Modifier.clickable { vm.playPlaylist(playlist, track.id) }, leadingContent = { AsyncImage(track.artworkPath, null, Modifier.size(44.dp)) }, headlineContent = { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }, supportingContent = { Text(track.artist, maxLines = 1) }, trailingContent = { IconButton({ vm.removeFromPlaylist(playlist.id, track.id) }) { Icon(Icons.Default.RemoveCircleOutline, "Remove from playlist") } }) } } } } } }
@Composable private fun AddToPlaylistDialog(track: Track, playlists: List<Playlist>, vm: LibraryViewModel, dismiss: () -> Unit) { var creating by remember { mutableStateOf(false) }; AlertDialog(onDismissRequest = dismiss, title = { Text("Add to playlist") }, text = { Column { Text(track.title, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(8.dp)); playlists.forEach { playlist -> ListItem(modifier = Modifier.clickable { vm.addToPlaylist(playlist.id, track.id); dismiss() }, headlineContent = { Text(playlist.name) }, leadingContent = { Icon(Icons.Default.QueueMusic, null) }, supportingContent = { Text("${playlist.tracks.size} songs") }) }; TextButton({ creating = true }) { Icon(Icons.Default.Add, null); Text("New playlist") } } }, confirmButton = {}, dismissButton = { TextButton(dismiss) { Text("Cancel") } }); if (creating) NewPlaylistDialog({ name -> vm.createPlaylist(name, track.id); creating = false; dismiss() }, { creating = false }) }
@Composable private fun NewPlaylistDialog(create: (String) -> Unit, dismiss: () -> Unit) { var name by remember { mutableStateOf("") }; AlertDialog(onDismissRequest = dismiss, icon = { Icon(Icons.Default.PlaylistAdd, null) }, title = { Text("New playlist") }, text = { OutlinedTextField(name, { name = it }, label = { Text("Playlist name") }, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { if (name.isNotBlank()) create(name) })) }, confirmButton = { Button({ create(name) }, enabled = name.isNotBlank()) { Text("Create") } }, dismissButton = { TextButton(dismiss) { Text("Cancel") } }) }
@Composable private fun MiniPlayer(state: PlaybackState, toggle: () -> Unit, open: () -> Unit) { Surface(tonalElevation = 3.dp) { Row(Modifier.fillMaxWidth().clickable(onClick = open).padding(10.dp), verticalAlignment = Alignment.CenterVertically) { AsyncImage(state.artwork, null, Modifier.size(44.dp)); Column(Modifier.weight(1f).padding(horizontal = 10.dp)) { Text(state.title, maxLines = 1); Text(state.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1) }; IconButton(toggle) { Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null) } } } }
@Composable private fun FullPlayer(state: PlaybackState, vm: LibraryViewModel, back: () -> Unit, modifier: Modifier = Modifier) { Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) { Row(Modifier.fillMaxWidth()) { IconButton(back) { Icon(Icons.Default.KeyboardArrowDown, "Close player") } }; AsyncImage(state.artwork, null, Modifier.fillMaxWidth().heightIn(max = 360.dp).aspectRatio(1f)); Spacer(Modifier.height(20.dp)); Text(state.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis); Text(state.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(12.dp)); Slider(state.positionMs.toFloat(), { vm.seek(it.toLong()) }, valueRange = 0f..state.durationMs.coerceAtLeast(1).toFloat()); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(formatDuration(state.positionMs)); Text(formatDuration(state.durationMs)) }; Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) { IconButton(vm::previous) { Icon(Icons.Default.SkipPrevious, "Previous") }; FilledIconButton(vm::toggle, Modifier.size(64.dp)) { Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null) }; IconButton(vm::next) { Icon(Icons.Default.SkipNext, "Next") }; IconButton(vm::toggleRepeatOne) { Icon(Icons.Default.RepeatOne, if (state.repeatOne) "Repeat song on" else "Repeat song off", tint = if (state.repeatOne) MaterialTheme.colorScheme.primary else LocalContentColor.current) } }; Spacer(Modifier.height(12.dp)); Text("${state.queue.size} track${if (state.queue.size == 1) "" else "s"} in queue", style = MaterialTheme.typography.bodySmall); Spacer(Modifier.height(24.dp)) } }
