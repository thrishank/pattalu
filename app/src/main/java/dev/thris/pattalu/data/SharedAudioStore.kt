package dev.thris.pattalu.data

import android.Manifest
import android.content.ContentValues
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.media.MediaMetadataRetriever
import androidx.core.content.ContextCompat
import java.io.File
import dev.thris.pattalu.model.Track
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray
import org.json.JSONObject

@Singleton class SharedAudioStore @Inject constructor(private val context: Context) {
    fun publish(source: File, displayName: String, title: String, artist: String): String {
        if (Build.VERSION.SDK_INT < 29) {
            check(ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) { "Storage permission is required" }
            val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), FOLDER).apply { mkdirs() }
            val target = File(directory, displayName)
            source.inputStream().use { input -> target.outputStream().use(input::copyTo) }
            MediaStore.Audio.Media.getContentUriForPath(target.absolutePath)?.let { context.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(target))) }
            return target.absolutePath
        }
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.TITLE, title)
            put(MediaStore.Audio.Media.ARTIST, artist)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$FOLDER")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)) { "Could not create shared music file" }
        try {
            resolver.openOutputStream(uri, "w")!!.use { output -> source.inputStream().use { it.copyTo(output) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            return uri.toString()
        } catch (t: Throwable) { resolver.delete(uri, null, null); throw t }
    }

    fun exists(location: String): Boolean = if (location.startsWith("content://")) runCatching { context.contentResolver.openFileDescriptor(Uri.parse(location), "r")?.use { true } ?: false }.getOrDefault(false) else File(location).isFile
    fun delete(location: String) { if (location.startsWith("content://")) context.contentResolver.delete(Uri.parse(location), null, null) else File(location).delete() }
    fun isPrivateAudio(location: String): Boolean = !location.startsWith("content://") && location.contains("/Android/data/${context.packageName}/files/audio/")

    fun writePlaylists(playlists: List<PlaylistBackupRow>) {
        val json = JSONObject().put("version", 1).put("playlists", JSONArray().apply {
            playlists.forEach { playlist -> put(JSONObject().put("name", playlist.name).put("createdAt", playlist.createdAt).put("trackIds", JSONArray(playlist.trackIds))) }
        }).toString(2)
        if (Build.VERSION.SDK_INT < 29) {
            val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), FOLDER).apply { mkdirs() }
            File(directory, PLAYLIST_FILE).writeText(json)
        } else {
            val resolver = context.contentResolver
            val uri = findPlaylistFile() ?: checkNotNull(resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, PLAYLIST_FILE)
                put(MediaStore.Audio.Media.MIME_TYPE, PLAYLIST_MIME)
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$FOLDER")
            })) { "Could not create playlist backup" }
            resolver.openOutputStream(uri, "wt")!!.bufferedWriter().use { it.write(json) }
        }
    }

    fun readPlaylists(): List<PlaylistBackupRow>? = runCatching {
        val json = if (Build.VERSION.SDK_INT < 29) {
            File(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), FOLDER), PLAYLIST_FILE).takeIf(File::isFile)?.readText()
        } else findPlaylistFile()?.let { context.contentResolver.openInputStream(it)?.bufferedReader()?.use { reader -> reader.readText() } }
        if (json.isNullOrBlank()) return null
        val rows = JSONObject(json).getJSONArray("playlists")
        List(rows.length()) { index ->
            val row = rows.getJSONObject(index); val ids = row.getJSONArray("trackIds")
            PlaylistBackupRow(row.getString("name"), row.optLong("createdAt", System.currentTimeMillis()), List(ids.length()) { ids.getString(it) })
        }
    }.getOrNull()

    private fun findPlaylistFile(): Uri? {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Audio.Media._ID)
        val selection = "${MediaStore.Audio.Media.RELATIVE_PATH}=? AND ${MediaStore.Audio.Media.DISPLAY_NAME}=?"
        return context.contentResolver.query(collection, projection, selection, arrayOf("${Environment.DIRECTORY_MUSIC}/$FOLDER/", PLAYLIST_FILE), null)?.use { cursor ->
            if (cursor.moveToFirst()) ContentUris.withAppendedId(collection, cursor.getLong(0)) else null
        }
    }

    fun discoverTracks(): List<Track> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_ADDED
        )
        val (selection, args) = if (Build.VERSION.SDK_INT >= 29) {
            "${MediaStore.Audio.Media.RELATIVE_PATH}=?" to arrayOf("${Environment.DIRECTORY_MUSIC}/$FOLDER/")
        } else {
            "${MediaStore.Audio.Media.DATA} LIKE ?" to arrayOf("%/${Environment.DIRECTORY_MUSIC}/$FOLDER/%")
        }
        return buildList {
            context.contentResolver.query(collection, projection, selection, args, "${MediaStore.Audio.Media.DATE_ADDED} DESC")?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                while (cursor.moveToNext()) {
                    val fileName = cursor.getString(nameColumn) ?: continue
                    val videoId = fileName.removeSuffix(".m4a")
                    if (!Regex("^[A-Za-z0-9_-]{6,32}$").matches(videoId)) continue
                    val uri = ContentUris.withAppendedId(collection, cursor.getLong(idColumn))
                    val artworkPath = extractArtwork(uri, videoId)
                    add(Track(
                        id = videoId,
                        title = cursor.getString(titleColumn).takeUnless { it.isNullOrBlank() } ?: videoId,
                        artist = cursor.getString(artistColumn).takeUnless { it.isNullOrBlank() || it == "<unknown>" } ?: "Unknown artist",
                        durationMs = cursor.getLong(durationColumn).coerceAtLeast(0),
                        audioPath = uri.toString(), artworkPath = artworkPath,
                        fileSize = cursor.getLong(sizeColumn).coerceAtLeast(0),
                        downloadedAt = cursor.getLong(dateColumn).coerceAtLeast(0) * 1000
                    ))
                }
            }
        }
    }

    private fun extractArtwork(uri: Uri, videoId: String): String? = runCatching {
        val retriever = MediaMetadataRetriever()
        val bytes = try { retriever.setDataSource(context, uri); retriever.embeddedPicture } finally { retriever.release() } ?: return null
        File(context.filesDir, "restored-artwork").apply { mkdirs() }.resolve("$videoId.jpg").also { it.writeBytes(bytes) }.absolutePath
    }.getOrNull()
    companion object { const val FOLDER = "Pattalu"; const val PLAYLIST_FILE = "pattalu-playlists.m3u"; const val PLAYLIST_MIME = "audio/x-mpegurl" }
}
