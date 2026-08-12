package dev.thris.pattalu

import android.app.Application
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import dagger.hilt.android.HiltAndroidApp
import dev.thris.pattalu.data.DownloadRepository
import dev.thris.pattalu.data.LibraryRepository
import kotlinx.coroutines.*
import javax.inject.Inject

@HiltAndroidApp class PattaluApp : Application(), DefaultLifecycleObserver {
    @Inject lateinit var downloads: DownloadRepository
    @Inject lateinit var library: LibraryRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onCreate() {
        super<Application>.onCreate()
        YoutubeDL.getInstance().init(this)
        FFmpeg.getInstance().init(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        scope.launch {
            cleanupPartials()
            library.reconcile()
            runCatching { YoutubeDL.getInstance().updateYoutubeDL(this@PattaluApp, YoutubeDL.UpdateChannel._STABLE) }
                .onFailure { Log.w("Pattalu", "Could not refresh yt-dlp at startup", it) }
        }
    }
    override fun onStop(owner: LifecycleOwner) { downloads.cancel(ACTIVE_JOB) }
    private fun cleanupPartials() { val root = getExternalFilesDir(null) ?: filesDir; java.io.File(root, "partial").listFiles()?.forEach { it.delete() } }
    companion object { const val ACTIVE_JOB = "active" }
}
