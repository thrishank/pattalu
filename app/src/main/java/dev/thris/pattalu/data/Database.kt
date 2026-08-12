package dev.thris.pattalu.data

import androidx.room.*
import dev.thris.pattalu.model.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "tracks")
data class TrackEntity(@PrimaryKey val videoId: String, val title: String, val artist: String, val durationMs: Long, val audioPath: String, val artworkPath: String?, val fileSize: Long, val downloadedAt: Long)
@Entity(tableName = "playlists", indices = [Index(value = ["name"], unique = true)])
data class PlaylistEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val createdAt: Long)
@Entity(tableName = "playlist_tracks", primaryKeys = ["playlistId", "trackId"], foreignKeys = [ForeignKey(entity = PlaylistEntity::class, parentColumns = ["id"], childColumns = ["playlistId"], onDelete = ForeignKey.CASCADE), ForeignKey(entity = TrackEntity::class, parentColumns = ["videoId"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE)], indices = [Index("trackId")])
data class PlaylistTrackEntity(val playlistId: Long, val trackId: String, val addedAt: Long)
data class PlaylistBackupRow(val name: String, val createdAt: Long, val trackIds: List<String>)
data class PlaylistWithTracks(@Embedded val playlist: PlaylistEntity, @Relation(parentColumn = "id", entityColumn = "videoId", associateBy = Junction(PlaylistTrackEntity::class, parentColumn = "playlistId", entityColumn = "trackId")) val tracks: List<TrackEntity>)
fun TrackEntity.asModel() = Track(videoId, title, artist, durationMs, audioPath, artworkPath, fileSize, downloadedAt)
fun Track.asEntity() = TrackEntity(id, title, artist, durationMs, audioPath, artworkPath, fileSize, downloadedAt)

@Dao interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY downloadedAt DESC") fun observeAll(): Flow<List<TrackEntity>>
    @Query("SELECT * FROM tracks") suspend fun all(): List<TrackEntity>
    @Query("SELECT EXISTS(SELECT 1 FROM tracks WHERE videoId=:id)") suspend fun contains(id: String): Boolean
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(track: TrackEntity)
    @Update suspend fun update(track: TrackEntity)
    @Query("DELETE FROM tracks WHERE videoId=:id") suspend fun delete(id: String)

    @Transaction @Query("SELECT * FROM playlists ORDER BY createdAt DESC") fun observePlaylists(): Flow<List<PlaylistWithTracks>>
    @Transaction @Query("SELECT * FROM playlists ORDER BY createdAt DESC") suspend fun allPlaylists(): List<PlaylistWithTracks>
    @Query("SELECT * FROM playlists WHERE name=:name LIMIT 1") suspend fun playlistNamed(name: String): PlaylistEntity?
    @Insert suspend fun insertPlaylist(playlist: PlaylistEntity): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addToPlaylist(reference: PlaylistTrackEntity)
    @Query("DELETE FROM playlist_tracks WHERE playlistId=:playlistId AND trackId=:trackId") suspend fun removeFromPlaylist(playlistId: Long, trackId: String)
    @Query("DELETE FROM playlists WHERE id=:playlistId") suspend fun deletePlaylist(playlistId: Long)
}
fun PlaylistWithTracks.asModel() = Playlist(playlist.id, playlist.name, playlist.createdAt, tracks.map(TrackEntity::asModel).sortedByDescending(Track::downloadedAt))
@Database(entities = [TrackEntity::class, PlaylistEntity::class, PlaylistTrackEntity::class], version = 2, exportSchema = true)
abstract class PattaluDatabase : RoomDatabase() { abstract fun tracks(): TrackDao }
