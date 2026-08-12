package dev.thris.pattalu.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.*
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.thris.pattalu.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class PlaybackState(val queue: List<String> = emptyList(), val currentTrackId: String? = null, val title: String = "", val artist: String = "", val artwork: Uri? = null, val positionMs: Long = 0, val durationMs: Long = 0, val isPlaying: Boolean = false, val repeatOne: Boolean = false)
interface PlaybackController { val state: StateFlow<PlaybackState>; fun playFromLibrary(tracks: List<Track>, selectedId: String); fun playOnce(track: Track); fun play(); fun pause(); fun seek(positionMs: Long); fun next(); fun previous(); fun toggleRepeatOne() }

@Singleton class Media3PlaybackController @Inject constructor(@ApplicationContext context: Context) : PlaybackController, Player.Listener {
    private val mutable = MutableStateFlow(PlaybackState()); override val state: StateFlow<PlaybackState> = mutable
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable { override fun run() { publish(); handler.postDelayed(this, 500) } }
    private val future: ListenableFuture<MediaController> = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
    private val controller get() = if (future.isDone) future.get() else null
    init { future.addListener({ controller?.addListener(this); publish(); handler.post(ticker) }, ContextCompat.getMainExecutor(context)) }
    override fun playFromLibrary(tracks: List<Track>, selectedId: String) { val items = tracks.map(::mediaItem); controller?.apply { setMediaItems(items, tracks.indexOfFirst { it.id == selectedId }.coerceAtLeast(0), 0); prepare(); play() } }
    override fun playOnce(track: Track) { controller?.apply { setMediaItem(mediaItem(track, temporary = true)); prepare(); play() } }
    override fun play() { controller?.play() }; override fun pause() { controller?.pause() }; override fun seek(positionMs: Long) { controller?.seekTo(positionMs) }; override fun next() { controller?.seekToNextMediaItem() }; override fun previous() { controller?.seekToPreviousMediaItem() }
    override fun toggleRepeatOne() { controller?.let { it.repeatMode = if (it.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE; publish() } }
    override fun onEvents(player: Player, events: Player.Events) = publish()
    private fun publish() { controller?.let { p -> val m = p.currentMediaItem?.mediaMetadata; mutable.value = PlaybackState((0 until p.mediaItemCount).map { p.getMediaItemAt(it).mediaId }, p.currentMediaItem?.mediaId, m?.title?.toString().orEmpty(), m?.artist?.toString().orEmpty(), m?.artworkUri, p.currentPosition.coerceAtLeast(0), p.duration.coerceAtLeast(0), p.isPlaying, p.repeatMode == Player.REPEAT_MODE_ONE) } }
    private fun mediaItem(t: Track, temporary: Boolean = false) = MediaItem.Builder().setMediaId(t.id).setUri(if (t.audioPath.startsWith("content://")) Uri.parse(t.audioPath) else Uri.fromFile(java.io.File(t.audioPath))).setMediaMetadata(MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist).setArtworkUri(t.artworkPath?.let { if (it.startsWith("http://") || it.startsWith("https://")) Uri.parse(it) else Uri.fromFile(java.io.File(it)) }).setExtras(if (temporary) Bundle().apply { putString(TEMP_AUDIO_PATH, t.audioPath) } else null).build()).build()
    companion object { const val TEMP_AUDIO_PATH = "dev.thris.pattalu.TEMP_AUDIO_PATH" }
}

@AndroidEntryPoint @UnstableApi class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer; private lateinit var session: MediaSession
    private var temporaryAudioPath: String? = null
    override fun onCreate() { super.onCreate(); player = ExoPlayer.Builder(this).build(); player.addListener(object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val nextPath = mediaItem?.mediaMetadata?.extras?.getString(Media3PlaybackController.TEMP_AUDIO_PATH)
            temporaryAudioPath?.takeIf { it != nextPath }?.let { java.io.File(it).delete() }
            temporaryAudioPath = nextPath
        }
        override fun onPlaybackStateChanged(playbackState: Int) { if (playbackState == Player.STATE_ENDED) clearTemporaryAudio() }
    }); session = MediaSession.Builder(this, player).build() }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session
    override fun onTaskRemoved(rootIntent: android.content.Intent?) { if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf() }
    private fun clearTemporaryAudio() { temporaryAudioPath?.let { java.io.File(it).delete() }; temporaryAudioPath = null }
    override fun onDestroy() { clearTemporaryAudio(); session.release(); player.release(); super.onDestroy() }
}
