package dev.thris.pattalu.data

import android.content.Context
import android.os.StatFs
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import dev.thris.pattalu.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface SongSearchRepository {
    suspend fun search(query: String, limit: Int = 15): Result<List<SearchResult>>
    suspend fun searchPlaylists(query: String, limit: Int = 15): Result<List<PlaylistSearchResult>>
    suspend fun playlist(playlistId: String, limit: Int = 15): Result<List<SearchResult>>
}
interface DownloadRepository { fun download(result: SearchResult): Flow<DownloadState>; fun listenOnce(result: SearchResult): Flow<DownloadState>; fun cancel(jobId: String) }
interface LibraryRepository { fun observeTracks(): Flow<List<Track>>; fun observePlaylists(): Flow<List<Playlist>>; suspend fun createPlaylist(name: String): Long; suspend fun addToPlaylist(playlistId: Long, trackId: String); suspend fun removeFromPlaylist(playlistId: Long, trackId: String); suspend fun deletePlaylist(playlistId: Long); suspend fun delete(trackId: String); suspend fun reconcile() }

@Singleton class YtDlpSearchRepository @Inject constructor(private val context: Context) : SongSearchRepository {
    @Volatile private var refreshed = false
    override suspend fun search(query: String, limit: Int) = if (query.isBlank()) Result.failure(IllegalArgumentException("Enter a search term")) else extract("ytsearch${limit.coerceIn(1,15)}:$query", limit)
    override suspend fun searchPlaylists(query: String, limit: Int) = if (query.isBlank()) Result.failure(IllegalArgumentException("Enter a search term")) else runCatching { withContext(Dispatchers.IO) {
        refreshExtractor()
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val request = YoutubeDLRequest("https://www.youtube.com/results?search_query=$encoded&sp=EgIQAw%3D%3D").apply {
            addOption("--dump-single-json"); addOption("--flat-playlist"); addOption("--playlist-end", limit.coerceIn(1, 30)); addOption("--no-warnings")
        }
        val entries = JSONObject(YoutubeDL.getInstance().execute(request).out).getJSONArray("entries")
        buildList {
            for (i in 0 until minOf(entries.length(), limit)) {
                val o = entries.getJSONObject(i)
                val rawUrl = o.optString("url")
                val id = o.optString("id").ifBlank { Regex("[?&]list=([^&]+)").find(rawUrl)?.groupValues?.get(1).orEmpty() }
                if (id.isNotBlank()) add(PlaylistSearchResult(id, o.optString("title", "Untitled playlist"), o.optString("uploader", o.optString("channel", "YouTube")), o.optInt("playlist_count", -1).takeIf { it >= 0 }, o.optString("thumbnail").takeIf(String::isNotBlank)))
            }
        }
    }}
    override suspend fun playlist(playlistId: String, limit: Int) = extract("https://www.youtube.com/playlist?list=$playlistId", limit)
    private suspend fun extract(source: String, limit: Int) = runCatching { withContext(Dispatchers.IO) {
        refreshExtractor()
        val request = YoutubeDLRequest(source).apply { addOption("--dump-single-json"); addOption("--flat-playlist"); if (limit > 0) addOption("--playlist-end", limit); addOption("--no-warnings") }
        val root = JSONObject(YoutubeDL.getInstance().execute(request).out)
        val entries = root.getJSONArray("entries")
        List(if (limit > 0) minOf(entries.length(), limit) else entries.length()) { i ->
            val o = entries.getJSONObject(i); val id = o.getString("id")
            SearchResult(id, "https://www.youtube.com/watch?v=$id", o.optString("title", "Untitled"), o.optString("uploader", o.optString("channel", "Unknown artist")), (o.optDouble("duration", 0.0) * 1000).toLong(), o.optString("thumbnail").takeIf(String::isNotBlank) ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg")
        }
    }}
    private fun refreshExtractor() {
        if (!refreshed) synchronized(this@YtDlpSearchRepository) { if (!refreshed) { YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel._STABLE); refreshed = true } }
    }
}

@Singleton class RoomLibraryRepository @Inject constructor(private val dao: TrackDao, private val audioStore: SharedAudioStore) : LibraryRepository {
    private val playlistLock = Mutex()
    override fun observeTracks() = dao.observeAll().map { rows -> rows.map(TrackEntity::asModel) }
    override fun observePlaylists() = dao.observePlaylists().map { rows -> rows.map(PlaylistWithTracks::asModel) }
    override suspend fun createPlaylist(name: String): Long = playlistLock.withLock { dao.insertPlaylist(PlaylistEntity(name = name.trim().also { require(it.isNotEmpty()) { "Playlist name cannot be empty" } }, createdAt = System.currentTimeMillis())).also { backupPlaylists() } }
    override suspend fun addToPlaylist(playlistId: Long, trackId: String) = playlistLock.withLock { dao.addToPlaylist(PlaylistTrackEntity(playlistId, trackId, System.currentTimeMillis())); backupPlaylists() }
    override suspend fun removeFromPlaylist(playlistId: Long, trackId: String) = playlistLock.withLock { dao.removeFromPlaylist(playlistId, trackId); backupPlaylists() }
    override suspend fun deletePlaylist(playlistId: Long) = playlistLock.withLock { dao.deletePlaylist(playlistId); backupPlaylists() }
    override suspend fun delete(trackId: String) { playlistLock.withLock { dao.all().firstOrNull { it.videoId == trackId }?.let { audioStore.delete(it.audioPath); it.artworkPath?.let(::File)?.delete() }; dao.delete(trackId); backupPlaylists() } }
    override suspend fun reconcile() { dao.all().forEach { row ->
        if (!audioStore.exists(row.audioPath)) dao.delete(row.videoId)
        else if (audioStore.isPrivateAudio(row.audioPath)) {
            val file = File(row.audioPath); val shared = audioStore.publish(file, file.name, row.title, row.artist)
            dao.update(row.copy(audioPath = shared)); file.delete()
        }
    }
        audioStore.discoverTracks().forEach { track -> if (!dao.contains(track.id)) dao.insert(track.asEntity()) }
        playlistLock.withLock {
            val savedPlaylists = audioStore.readPlaylists() ?: return@withLock
            savedPlaylists.forEach { saved ->
                val playlistId = dao.playlistNamed(saved.name)?.id ?: dao.insertPlaylist(PlaylistEntity(name = saved.name, createdAt = saved.createdAt))
                saved.trackIds.filter { dao.contains(it) }.forEach { dao.addToPlaylist(PlaylistTrackEntity(playlistId, it, saved.createdAt)) }
            }
            backupPlaylists()
        }
    }
    private suspend fun backupPlaylists() = audioStore.writePlaylists(dao.allPlaylists().map { row -> PlaylistBackupRow(row.playlist.name, row.playlist.createdAt, row.tracks.map(TrackEntity::videoId)) })
}

@Singleton class YtDlpDownloadRepository @Inject constructor(private val context: Context, private val dao: TrackDao, private val audioStore: SharedAudioStore) : DownloadRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO); private var active: Pair<String, Job>? = null
    init { File(context.cacheDir, "listen-once").apply { mkdirs() }.listFiles()?.forEach(File::delete) }
    override fun download(result: SearchResult): Flow<DownloadState> = fetch(result, persist = true)
    override fun listenOnce(result: SearchResult): Flow<DownloadState> = fetch(result, persist = false)
    private fun fetch(result: SearchResult, persist: Boolean): Flow<DownloadState> = callbackFlow {
        val id = result.videoId
        if (active != null) { trySend(DownloadState.Failed("busy", "Another download is in progress")); close(); return@callbackFlow }
        if (persist && dao.contains(id)) { trySend(DownloadState.Failed("duplicate", "Already in your library")); close(); return@callbackFlow }
        val job = scope.launch {
            val root = context.getExternalFilesDir(null) ?: context.filesDir
            val artDir = File(root, "artwork").apply { mkdirs() }; val tempDir = File(if (persist) root else context.cacheDir, if (persist) "partial" else "listen-once").apply { mkdirs() }
            val partial = File(tempDir, safeAudioName(id)); var sharedLocation: String? = null
            try {
                trySend(DownloadState.Queued(id))
                if (StatFs(root.path).availableBytes < 25L * 1024 * 1024) error("Insufficient storage")
                val request = YoutubeDLRequest(result.sourceUrl).apply { addOption("-x"); addOption("--audio-format", "m4a"); addOption("--audio-quality", "192K"); addOption("--embed-thumbnail"); addOption("--embed-metadata"); addOption("--no-playlist"); addOption("-o", partial.absolutePath) }
                YoutubeDL.getInstance().execute(request, id) { progress, eta, _ -> trySend(DownloadState.Downloading(id, progress / 100f, eta)) }
                ensureActive(); check(partial.isFile && partial.length() > 0) { "Downloaded file is missing or corrupt" }
                if (persist) {
                    sharedLocation = audioStore.publish(partial, safeAudioName(id), result.title, result.artist)
                    val artwork = result.thumbnailUrl?.let { url -> File(artDir, "$id.jpg").also { URL(url).openStream().use { input -> it.outputStream().use(input::copyTo) } } }
                    val track = Track(id, result.title, result.artist, result.durationMs, checkNotNull(sharedLocation), artwork?.absolutePath, partial.length(), System.currentTimeMillis())
                    dao.insert(track.asEntity()); trySend(DownloadState.Completed(track))
                } else {
                    val track = Track(id, result.title, result.artist, result.durationMs, partial.absolutePath, result.thumbnailUrl, partial.length(), System.currentTimeMillis())
                    trySend(DownloadState.Completed(track))
                }
            } catch (_: CancellationException) { partial.delete(); trySend(DownloadState.Cancelled) }
            catch (t: Throwable) { partial.delete(); sharedLocation?.takeIf { !dao.contains(id) }?.let(audioStore::delete); trySend(DownloadState.Failed(mapError(t), t.message ?: "Download failed")) }
            finally { if (persist) partial.delete(); active = null; close() }
        }
        active = id to job; awaitClose { if (active?.first == id) job.cancel() }
    }
    override fun cancel(jobId: String) { active?.takeIf { jobId == "active" || it.first == jobId }?.second?.cancel() }
    private fun mapError(t: Throwable) = when { t.message?.contains("storage", true) == true -> "storage"; t.message?.contains("unavailable", true) == true -> "unavailable"; else -> "extractor" }
}
