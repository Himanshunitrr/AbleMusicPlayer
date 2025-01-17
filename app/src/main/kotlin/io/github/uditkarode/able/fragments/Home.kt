/*
    Copyright 2020 Udit Karode <udit.karode@gmail.com>

    This file is part of AbleMusicPlayer.

    AbleMusicPlayer is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, version 3 of the License.

    AbleMusicPlayer is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with AbleMusicPlayer.  If not, see <https://www.gnu.org/licenses/>.
*/

package io.github.uditkarode.able.fragments

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import co.revely.gradient.RevelyGradient
import com.arthenica.mobileffmpeg.Config
import com.arthenica.mobileffmpeg.FFmpeg
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.signature.ObjectKey
import io.github.uditkarode.able.R
import io.github.uditkarode.able.activities.Settings
import io.github.uditkarode.able.adapters.SongAdapter
import io.github.uditkarode.able.models.CacheStatus
import io.github.uditkarode.able.models.Format
import io.github.uditkarode.able.models.Song
import io.github.uditkarode.able.models.SongState
import io.github.uditkarode.able.services.MusicService
import io.github.uditkarode.able.utils.Constants
import io.github.uditkarode.able.utils.Shared
import io.github.uditkarode.able.utils.SwipeController
import kotlinx.android.synthetic.main.home.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.util.*
import kotlin.collections.ArrayList

/**
 * The first fragment. Shows a list of songs present on the user's device.
 */
@Suppress("NAME_SHADOWING")
class Home : Fragment(), CoroutineScope, MusicService.MusicClient {
    private lateinit var okClient: OkHttpClient
    private lateinit var serviceConn: ServiceConnection

    private var songList = ArrayList<Song>()
    private var songId = "temp"

    var isBound = false
    var mService: MusicService? = null

    override val coroutineContext = Dispatchers.Main + SupervisorJob()

    companion object {
        var songAdapter: SongAdapter? = null
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineContext.cancelChildren()
        MusicService.unregisterClient(this)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? =
        inflater.inflate(
            R.layout.home,
            container, false
        )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val songs = view.findViewById<RecyclerView>(R.id.songs)

        okClient = OkHttpClient()

        RevelyGradient
            .linear()
            .colors(
                intArrayOf(
                    Color.parseColor("#7F7FD5"),
                    Color.parseColor("#86A8E7"),
                    Color.parseColor("#91EAE4")
                )
            )
            .on(view.findViewById<TextView>(R.id.able_header))

        settings.setOnClickListener {
            startActivity(Intent(requireContext(), Settings::class.java))
        }

        serviceConn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                mService = (service as MusicService.MusicBinder).getService()
                isBound = true
            }

            override fun onServiceDisconnected(name: ComponentName) {
                isBound = false
            }
        }

        bindEvent()

        if (songList.isEmpty()) {
            songList = Shared.getSongList(Constants.ableSongDir)
            songList.addAll(Shared.getLocalSongs(requireContext()))
            if (songList.isNotEmpty()) songList = ArrayList(songList.sortedBy {
                it.name.toUpperCase(
                    Locale.getDefault()
                )
            })
        }
        songAdapter = SongAdapter(songList, WeakReference(this@Home), true)
        val lam = LinearLayoutManager(requireContext())
        lam.initialPrefetchItemCount = 12
        lam.isItemPrefetchEnabled = true
        val itemTouchHelper = ItemTouchHelper(SwipeController(context, "Home"))
        songs.adapter = songAdapter
        songs.layoutManager = lam
        itemTouchHelper.attachToRecyclerView(songs)
    }

    override fun onResume() {
        super.onResume()
        MusicService.registerClient(this)
    }

    override fun onPause() {
        super.onPause()
        MusicService.unregisterClient(this)
    }

    fun bindEvent() {
        if (Shared.serviceRunning(MusicService::class.java, requireContext())) {
            try {
                requireContext().bindService(
                    Intent(
                        requireContext(),
                        MusicService::class.java
                    ), serviceConn, 0
                )
            } catch (e: Exception) {
                Log.e("ERR>", e.toString())
            }
        }
    }

    /**
     * A helper function that streams a song
     * This is different from the implementation
     * in MusicService, since this separates
     * the caching proxy implementation
     * from the overly simple method used
     * in the service. Could be unified in
     * the future.
     *
     * @param song the song to stream.
     * @param toCache whether we want to save this to the disk.
     */
    fun streamAudio(song: Song, toCache: Boolean) {
        if (isAdded) {
            if (!Shared.serviceRunning(MusicService::class.java, requireContext())) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    requireActivity().startForegroundService(
                        Intent(
                            requireContext(),
                            MusicService::class.java
                        )
                    )
                } else {
                    requireActivity().startService(
                        Intent(
                            requireContext(),
                            MusicService::class.java
                        )
                    )
                }

                bindEvent()
            }
        } else
            Log.d("ERR", "Context Lost")

        launch(Dispatchers.IO) {
            while (!isBound) {
                Thread.sleep(30)
            }
            mService?.setPlayQueue(
                arrayListOf(
                    Song(
                        name = getString(R.string.loading),
                        artist = ""
                    )
                )
            )
            mService?.setCurrentIndex(0)
            mService?.showNotif()

            val tmp: StreamInfo?

            try {
                tmp = StreamInfo.getInfo(song.youtubeLink)
            } catch (e: Exception) {
                launch(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Something went wrong!", Toast.LENGTH_SHORT)
                        .show()
                    MusicService.registeredClients.forEach { it.isLoading(false) }
                }
                Log.e("ERR>", e.toString())
                return@launch
            }

            val streamInfo = tmp ?: StreamInfo.getInfo(song.youtubeLink)
            val stream = streamInfo.audioStreams.run { this[size - 1] }

            val url = stream.url
            val bitrate = stream.averageBitrate
            val ext = stream.getFormat().suffix
            songId = Shared.getIdFromLink(song.youtubeLink)

            File(Constants.ableSongDir, "$songId.tmp.webm").run {
                if (exists()) delete()
            }

            if (song.ytmThumbnail.isNotBlank()) {
                Glide.with(requireContext())
                    .asBitmap()
                    .load(song.ytmThumbnail)
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .signature(ObjectKey("save"))
                    .skipMemoryCache(true)
                    .listener(object : RequestListener<Bitmap> {
                        override fun onLoadFailed(
                            e: GlideException?,
                            model: Any?,
                            target: Target<Bitmap>?,
                            isFirstResource: Boolean
                        ): Boolean {
                            return false
                        }

                        override fun onResourceReady(
                            resource: Bitmap?,
                            model: Any?,
                            target: Target<Bitmap>?,
                            dataSource: DataSource?,
                            isFirstResource: Boolean
                        ): Boolean {
                            if (resource != null) {
                                if (toCache) {
                                    if (cacheMusic(song, url, ext, bitrate))
                                        Shared.saveAlbumArtToDisk(
                                            resource,
                                            File(Constants.albumArtDir, songId)
                                        )
                                } else {
                                    song.filePath = url

                                    Shared.saveStreamingAlbumArt(
                                        resource,
                                        Shared.getIdFromLink(song.youtubeLink)
                                    )
                                }

                                mService?.setPlayQueue(arrayListOf(song))
                                mService?.setIndex(0)
                                MusicService.registeredClients.forEach { it.isLoading(false) }
                                mService?.setPlayPause(SongState.playing)
                            }
                            return false
                        }
                    }).submit()
            }
        }
    }

    fun cacheMusic(song: Song, songUrl: String, ext: String, bitrate: Int): Boolean {
        if (song.filePath.endsWith(ext)) // edge case when the song is already saved
            return false

        song.cacheStatus = CacheStatus.STARTED

        song.filePath = "caching"
        song.streamMutexes = arrayOf(Mutex(), Mutex())

        GlobalScope.launch(Dispatchers.IO) {
            val req = Request.Builder().url(songUrl).build()
            val resp = okClient.newCall(req).execute()
            val body = resp.body!!
            val iStream = BufferedInputStream(body.byteStream())

            song.internalStream = ByteArray(body.contentLength().toInt())
            song.streams = arrayOf(ByteArray(body.contentLength().toInt()), ByteArray(body.contentLength().toInt()))

            var read: Int
            val data = ByteArray(1024)

            var off = 0
            while (iStream.read(data).let { read = it; read != -1 }) {
                for (i in 0.until(read)) {
                    song.internalStream[i+off] = data[i]
                }
                off += read

                while (song.streamMutexes[0].isLocked and song.streamMutexes[1].isLocked) {
                    Thread.sleep(50)
                }

                val streamNum = if (song.streamMutexes[0].isLocked) 1 else 0
                song.streamMutexes[streamNum].withLock {
                    song.streams[streamNum] = song.internalStream
                }

                song.streamProg = (off*100) / body.contentLength().toInt()
            }

            iStream.close()


            val tempFile = File(
                Constants.ableSongDir.absolutePath
                        + "/" + songId + ".tmp.$ext"
            )
            tempFile.createNewFile()
            FileOutputStream(tempFile).write(song.internalStream)

            val format =
                if (PreferenceManager.getDefaultSharedPreferences(
                        context
                    )
                        .getString(
                            "format_key",
                            "webm"
                        ) == "mp3"
                ) Format.MODE_MP3
                else Format.MODE_WEBM

            var command = "-i " +
                    "\"${tempFile.absolutePath}\" -c copy " +
                    "-metadata title=\"${song.name}\" " +
                    "-metadata artist=\"${song.artist}\" -y "

            if (format == Format.MODE_MP3 || Build.VERSION.SDK_INT <= Build.VERSION_CODES.M)
                command += "-vn -ab ${bitrate}k -c:a mp3 -ar 44100 "

            command += "\"${
                tempFile.absolutePath.replace(
                    "tmp.$ext",
                    ""
                )
            }"

            command += if (format == Format.MODE_MP3) "mp3\"" else "$ext\""

            when (val rc = FFmpeg.execute(command)) {
                Config.RETURN_CODE_SUCCESS -> {
                    tempFile.delete()
                    launch(Dispatchers.Main) {
                        songList =
                            Shared.getSongList(Constants.ableSongDir)
                        songList.addAll(
                            Shared.getLocalSongs(
                                requireContext()
                            )
                        )
                        songList =
                            ArrayList(songList.sortedBy {
                                it.name.toUpperCase(
                                    Locale.getDefault()
                                )
                            })
                        launch(Dispatchers.Main) {
                            songAdapter?.update(songList)
                        }
                        songAdapter?.update(songList)
                        songAdapter?.notifyDataSetChanged()
                    }
                }
                Config.RETURN_CODE_CANCEL -> {
                    Log.e(
                        "ERR>",
                        "Command execution cancelled by user."
                    )
                }
                else -> {
                    Log.e(
                        "ERR>",
                        String.format(
                            "Command execution failed with rc=%d and the output below.",
                            rc
                        )
                    )
                }
            }

            song.filePath = tempFile.absolutePath.replace(
                "tmp.$ext",
                ext
            )
            song.cacheStatus = CacheStatus.NULL

            // Destroy mutexes and stream buffers (I don't trust the GC here for some reason)
            song.streamMutexes = arrayOf()
            song.internalStream = ByteArray(0)
            song.streams = arrayOf()
        }

         return true
    }

    fun updateSongList() {
        songList = Shared.getSongList(Constants.ableSongDir)
        if (context != null) songList.addAll(Shared.getLocalSongs(context as Context))
        songList = ArrayList(songList.sortedBy {
            it.name.toUpperCase(
                Locale.getDefault()
            )
        })
        launch(Dispatchers.Main) {
            songAdapter?.update(songList)
        }
    }

    override fun playStateChanged(state: SongState) {}

    override fun songChanged() {}

    override fun durationChanged(duration: Int) {}

    override fun isExiting() {}

    override fun queueChanged(arrayList: ArrayList<Song>) {}

    override fun shuffleRepeatChanged(onShuffle: Boolean, onRepeat: Boolean) {}

    override fun indexChanged(index: Int) {}

    override fun isLoading(doLoad: Boolean) {}

    override fun spotifyImportChange(starting: Boolean) {}

    override fun serviceStarted() {
        bindEvent()
    }
}