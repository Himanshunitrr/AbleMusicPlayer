/*
    Copyright 2020 Udit Karode <udit.karode@gmail.com>

    This file is part of AbleMusicPlayer.

    AbleMusicPlayer is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, version 3 of the License.

    AbleMusicPlayer is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with AbleMusicPlayer.  If not, see <https://www.gnu.org/licenses/>.
*/

package io.github.uditkarode.able.activities

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.os.*
import android.text.Html
import android.view.TouchDelegate
import android.view.View
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import androidx.core.text.HtmlCompat
import androidx.preference.PreferenceManager
import androidx.viewpager.widget.ViewPager
import com.bumptech.glide.Glide
import com.flurry.android.FlurryAgent
import com.google.android.material.bottomnavigation.BottomNavigationView
import io.github.inflationx.calligraphy3.CalligraphyConfig
import io.github.inflationx.calligraphy3.CalligraphyInterceptor
import io.github.inflationx.viewpump.ViewPump
import io.github.inflationx.viewpump.ViewPumpContextWrapper
import io.github.uditkarode.able.R
import io.github.uditkarode.able.adapters.ViewPagerAdapter
import io.github.uditkarode.able.fragments.Home
import io.github.uditkarode.able.fragments.Search
import io.github.uditkarode.able.models.MusicMode
import io.github.uditkarode.able.models.Song
import io.github.uditkarode.able.models.SongState
import io.github.uditkarode.able.services.DownloadService
import io.github.uditkarode.able.services.DownloadService.Companion.enqueueDownload
import io.github.uditkarode.able.services.MusicService
import io.github.uditkarode.able.services.ServiceResultReceiver
import io.github.uditkarode.able.utils.Constants
import io.github.uditkarode.able.utils.CustomDownloader
import io.github.uditkarode.able.utils.MusicClientActivity
import io.github.uditkarode.able.utils.Shared
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import java.io.ByteArrayOutputStream
import java.util.*
import kotlin.collections.ArrayList

/**
 * First activity that shows up when the user opens the application
 */
class MainActivity : MusicClientActivity(), Search.SongCallback, ServiceResultReceiver.Receiver {
    private lateinit var mServiceResultReceiver: ServiceResultReceiver
    private lateinit var bottomNavigation: BottomNavigationView
    private lateinit var okClient: OkHttpClient
    private lateinit var serviceConn: ServiceConnection
    private lateinit var mainContent: ViewPager
    private lateinit var timer: Timer
    private lateinit var home: Home

    private var mService: MusicService? = null
    private var scheduled = false
    private var playing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        NewPipe.init(CustomDownloader.getInstance())

        launch {
            if (checkCallingOrSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                startActivity(Intent(this@MainActivity, Welcome::class.java))
            }
        }

        launch(Dispatchers.Main) {
            Shared.defBitmap = (ResourcesCompat.getDrawable(
                resources,
                R.drawable.def_albart, null
            ) as BitmapDrawable).bitmap
            val outputStream = ByteArrayOutputStream()
            Shared.defBitmap.compress(Bitmap.CompressFormat.JPEG, 20, outputStream)
            val byte = outputStream.toByteArray()
            Shared.defBitmap = BitmapFactory.decodeByteArray(
                byte,
                0, byte.size
            )
            Shared.setupFetch(this@MainActivity)

            okClient = OkHttpClient()
            mServiceResultReceiver = ServiceResultReceiver(Handler(Looper.getMainLooper()))
            mServiceResultReceiver.setReceiver(this@MainActivity)

            FlurryAgent.Builder()
                .withLogEnabled(false)
                .build(this@MainActivity, Constants.FLURRY_KEY)
        }

        super.onCreate(savedInstanceState)

        ViewPump.init(
            ViewPump.builder()
                .addInterceptor(
                    CalligraphyInterceptor(
                        CalligraphyConfig.Builder()
                            .setDefaultFontPath("fonts/inter.otf")
                            .setFontAttrId(R.attr.fontPath)
                            .build()
                    )
                )
                .build()
        )

        setContentView(R.layout.activity_main)

        mainContent = main_content
        bb_icon.setOnClickListener {
            if (Shared.serviceRunning(MusicService::class.java, this@MainActivity)) {
                if (playing) mService?.setPlayPause(SongState.paused)
                else mService?.setPlayPause(SongState.playing)
            }
        }
        // extend the touchable area for the play button, since it's so small.
        (bb_icon.parent as View).post {
            val rect = Rect().also {
                bb_icon.getHitRect(it)
                it.top -= 200
                it.left -= 200
                it.bottom += 200
                it.right += 200
            }

            (bb_icon.parent as View).touchDelegate = TouchDelegate(rect, bb_icon)
        }

        home = Home()
        mainContent.adapter = ViewPagerAdapter(supportFragmentManager, home)
        mainContent.setPageTransformer(false) { page, _ ->
            page.alpha = 0f
            page.visibility = View.VISIBLE

            page.animate()
                .alpha(1f).duration = 200
        }

        bottomNavigation = bottom_navigation
        bottomNavigation.setOnNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.home_menu -> main_content.currentItem = 0
                R.id.search_menu -> main_content.currentItem = 1
                R.id.settings_menu -> main_content.currentItem = 2
            }
            true
        }

        activity_seekbar.thumb.alpha = 0

        bb_song.isSelected = true

        bb_song.setOnClickListener {
            if (Shared.serviceRunning(MusicService::class.java, this@MainActivity))
                startActivity(Intent(this@MainActivity, Player::class.java))
        }

        bb_expand.setOnClickListener {
            if (Shared.serviceRunning(MusicService::class.java, this@MainActivity))
                startActivity(Intent(this@MainActivity, Player::class.java))
        }

        serviceConn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                mService = (service as MusicService.MusicBinder).getService()
                songChange()
                playPauseEvent(service.getService().getMediaPlayer().run {
                    if (this.isPlaying) SongState.playing
                    else SongState.paused
                })
            }

            override fun onServiceDisconnected(name: ComponentName) {}
        }
    }

    private fun loadingEvent(loading: Boolean) {
        bb_ProgressBar?.visibility = if (loading) View.VISIBLE else View.GONE
        if (!
            loading
        ) {
            activity_seekbar.visibility = View.VISIBLE
            bn_parent.invalidate()
        } else {
            activity_seekbar.visibility = View.GONE
            bn_parent.invalidate()
        }
    }

    private fun bindService() {
        if (Shared.serviceRunning(MusicService::class.java, this@MainActivity))
            bindService(
                Intent(this@MainActivity, MusicService::class.java),
                serviceConn,
                Context.BIND_IMPORTANT
            )
    }

    fun playPauseEvent(state: SongState) {
        launch(Dispatchers.Main) {
            if (state == SongState.playing) {
                Glide.with(this@MainActivity).load(R.drawable.pause)
                    .into(bb_icon)

                playing = true
            } else {
                playing = false
                Glide.with(this@MainActivity).load(R.drawable.play).into(bb_icon)
            }

            if (state == SongState.playing) startSeekbarUpdates()
            else {
                if (scheduled) {
                    scheduled = false
                    timer.cancel()
                    timer.purge()
                }
            }
        }
    }

    private fun startSeekbarUpdates() {
        if (!scheduled) {
            scheduled = true
            timer = Timer()
            timer.schedule(object : TimerTask() {
                override fun run() {
                    activity_seekbar.progress =
                        mService?.getMediaPlayer()?.currentPosition ?: 0 //todo fix
                }
            }, 0, 1000)
        }
    }

    @SuppressLint("SetTextI18n")
    fun songChange() {
        if(mService != null) {
            launch(Dispatchers.Main) {
                activity_seekbar.progress = 0
                activity_seekbar.max = mService!!.getMediaPlayer().duration

                startSeekbarUpdates()
                val song = mService!!.getPlayQueue()[mService!!.getCurrentIndex()]

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    bb_song.text = Html.fromHtml(
                        "${song.name} <font color=\"#5e92f3\">•</font> ${song.artist}",
                        HtmlCompat.FROM_HTML_MODE_LEGACY
                    )
                } else {
                    bb_song.text = "${song.name} • ${song.artist}"
                }
            }
        }
    }

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(ViewPumpContextWrapper.wrap(newBase!!))
    }

    override fun onPause() {
        super.onPause()
        if (scheduled) {
            scheduled = false
            timer.cancel()
            timer.purge()
        }
    }

    override fun onResume() {
        super.onResume()
        bindService()
    }

    override fun sendItem(song: Song, mode: String) {
        var currentMode = PreferenceManager.getDefaultSharedPreferences(this@MainActivity)
            .getString("mode_key", MusicMode.download)
        if (mode.isNotEmpty())
            currentMode = mode
        when (currentMode) {
            MusicMode.download -> {
                val songL = ArrayList<String>()
                songL.add(song.name)
                songL.add(song.youtubeLink)
                songL.add(song.artist)
                songL.add(song.ytmThumbnail)
                val serviceIntentService = Intent(this@MainActivity, DownloadService::class.java)
                    .putStringArrayListExtra("song", songL)
                    .putExtra("receiver", mServiceResultReceiver)
                enqueueDownload(this, serviceIntentService)
                Toast.makeText(
                    this@MainActivity,
                    "${song.name} ${getString(R.string.dl_added)}",
                    Toast.LENGTH_SHORT
                ).show()
                /*
                    * takes user back to the home screen when download starts *
                    mainContent.currentItem = -1
                    bottomNavigation.menu.findItem(R.id.home_menu)?.isChecked = true
                 */
            }

            MusicMode.stream -> {
                home.streamAudio(song, false)
                runOnUiThread {
                    loadingEvent(true)
                }
            }

            MusicMode.both -> {
                home.streamAudio(song, true)
                runOnUiThread {
                    loadingEvent(true)
                }
            }
        }
    }

    override fun onReceiveResult(resultCode: Int) {
        home.updateSongList()
    }

    override fun playStateChanged(state: SongState) {
        playPauseEvent(state)
    }

    override fun songChanged() {
        songChange()
    }

    override fun durationChanged(duration: Int) {
        launch(Dispatchers.Main) {
            activity_seekbar.max = duration
        }
    }

    override fun isExiting() {
        finish()
    }

    override fun queueChanged(arrayList: ArrayList<Song>) {}

    override fun shuffleRepeatChanged(onShuffle: Boolean, onRepeat: Boolean) {}

    override fun indexChanged(index: Int) {}

    override fun isLoading(doLoad: Boolean) = runOnUiThread {
        loadingEvent(doLoad)
    }

    override fun spotifyImportChange(starting: Boolean) {}

    override fun serviceStarted() {
        bindService()
    }
}
