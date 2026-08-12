package dev.thris.pattalu.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.thris.pattalu.data.*
import dev.thris.pattalu.playback.Media3PlaybackController
import dev.thris.pattalu.playback.PlaybackController
import javax.inject.Singleton

@Module @InstallIn(SingletonComponent::class) object DatabaseModule {
    @Provides @Singleton fun database(@ApplicationContext context: Context) = Room.databaseBuilder(context, PattaluDatabase::class.java, "pattalu.db").addMigrations(MIGRATION_1_2).build()
    @Provides fun dao(database: PattaluDatabase) = database.tracks()
    @Provides fun context(@ApplicationContext context: Context): Context = context
}

private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS playlists (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, createdAt INTEGER NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_playlists_name ON playlists(name)")
        db.execSQL("CREATE TABLE IF NOT EXISTS playlist_tracks (playlistId INTEGER NOT NULL, trackId TEXT NOT NULL, addedAt INTEGER NOT NULL, PRIMARY KEY(playlistId, trackId), FOREIGN KEY(playlistId) REFERENCES playlists(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(trackId) REFERENCES tracks(videoId) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_playlist_tracks_trackId ON playlist_tracks(trackId)")
    }
}
@Module @InstallIn(SingletonComponent::class) abstract class RepositoryModule {
    @Binds abstract fun search(impl: YtDlpSearchRepository): SongSearchRepository
    @Binds abstract fun downloads(impl: YtDlpDownloadRepository): DownloadRepository
    @Binds abstract fun library(impl: RoomLibraryRepository): LibraryRepository
    @Binds abstract fun playback(impl: Media3PlaybackController): PlaybackController
}
