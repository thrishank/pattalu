package dev.thris.pattalu.model

data class SearchResult(val videoId: String, val sourceUrl: String, val title: String, val artist: String, val durationMs: Long, val thumbnailUrl: String?)
data class PlaylistSearchResult(val playlistId: String, val title: String, val owner: String, val songCount: Int?, val thumbnailUrl: String?)
data class Track(val id: String, val title: String, val artist: String, val durationMs: Long, val audioPath: String, val artworkPath: String?, val fileSize: Long, val downloadedAt: Long)
data class Playlist(val id: Long, val name: String, val createdAt: Long, val tracks: List<Track>)

sealed interface DownloadState {
    data class Queued(val jobId: String) : DownloadState
    data class Downloading(val jobId: String, val progress: Float, val etaSeconds: Long?) : DownloadState
    data class Completed(val track: Track) : DownloadState
    data object Cancelled : DownloadState
    data class Failed(val errorCode: String, val message: String) : DownloadState
}

fun formatDuration(ms: Long): String { val total = ms.coerceAtLeast(0) / 1000; return "%d:%02d".format(total / 60, total % 60) }
fun safeAudioName(videoId: String): String = requireNotNull(Regex("^[A-Za-z0-9_-]{6,32}$").matchEntire(videoId)) { "Unsafe video id" }.value + ".m4a"
