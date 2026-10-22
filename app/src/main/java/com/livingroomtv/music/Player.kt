package com.livingroomtv.music

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

/**
 * What's playing and what's next. The UI reads and changes it; [PlayerService] does the playing.
 * A [station] keeps the queue going forever (the server picks the next songs); a plain list that runs out turns into
 * the last song's radio, like YouTube Music's autoplay.
 */
object Player {
    val queue = mutableListOf<Track>()
    var index = 0; private set
    var station: String? = null; private set
    var playing = false; internal set
    var buffering = false; internal set
    var error: String? = null; internal set
    private val listeners = linkedSetOf<() -> Unit>()

    val current: Track? get() = queue.getOrNull(index)
    val upNext: List<Track> get() = queue.drop(index + 1)

    fun listen(l: () -> Unit) { listeners += l }
    fun unlisten(l: () -> Unit) { listeners -= l }
    internal fun changed() = listeners.toList().forEach { it() }

    /** Play [tracks] from [start]; then [station] (if any) keeps it going. With no tracks, the station starts it. */
    fun play(context: Context, tracks: List<Track>, start: Int = 0, station: String? = null) {
        PlayerService.instance?.report(skipped = true)           // the song that was playing, before it's gone
        queue.clear()
        queue += tracks
        index = start.coerceIn(0, (tracks.size - 1).coerceAtLeast(0))
        this.station = station
        error = null
        service(context) { it.load(fresh = true) }
    }

    fun toggle(context: Context) = service(context) { it.toggle() }

    /** Stops the music altogether (leaving the app, the stop button): nothing keeps playing in the background. */
    fun stop() {
        PlayerService.instance?.shutdown()
        queue.clear()
        index = 0
        station = null
        playing = false
        buffering = false
        error = null
        changed()
    }
    fun next(context: Context) = service(context) { it.skip(+1) }
    fun prev(context: Context) = service(context) { it.skip(-1) }
    fun seekBy(ms: Int) = PlayerService.instance?.seekBy(ms)
    fun seekTo(ms: Int) = PlayerService.instance?.seekTo(ms)
    fun position(): Int = PlayerService.instance?.position() ?: 0
    fun duration(): Int = PlayerService.instance?.duration() ?: ((current?.length ?: 0) * 1000)

    /** Jump to an already-queued song. */
    fun jump(context: Context, to: Int) {
        if (to !in queue.indices) return
        PlayerService.instance?.report(skipped = true)
        index = to
        service(context) { it.load(fresh = false) }
    }

    /** Thumbs up/down/none, to YouTube Music too. A thumbs down skips the song. */
    fun vote(context: Context, t: Track, vote: String) {
        t.liked = vote == "like"
        queue.filter { it.v == t.v }.forEach { it.liked = t.liked }
        changed()
        Bg.go { Api.vote(t.v, vote) }
        if (vote == "dislike" && current?.v == t.v) next(context)
    }

    internal fun moveTo(i: Int) { index = i }

    /** Adds songs to the end (the station's next batch). */
    internal fun append(more: List<Track>) {
        val have = queue.map { it.v }.toSet()
        queue += more.filter { it.v !in have }
        changed()
    }

    internal fun continueWith(id: String) { station = id }

    private fun service(context: Context, then: (PlayerService) -> Unit) {
        PlayerService.instance?.let { then(it); return }
        PlayerService.pending = then
        context.startForegroundService(Intent(context, PlayerService::class.java))
    }
}

/**
 * Tells the music server what's playing and its colours, so Home Assistant can give the living room lights the
 * album's colours (only lights already on; they go back when the music stops). [enabled] is the player's Lights button.
 */
object Lights {
    var enabled = true
    private var sent = ""

    fun update(stopped: Boolean = false, force: Boolean = false) {
        if (Config.room != "living room") return
        val t = Player.current
        val playing = Player.playing && t != null && !stopped
        val party = Player.station?.startsWith("kids") == true
        val key = "${t?.v}|$playing|${Accent.color}|$party|$stopped"
        if (key == sent && !force) return
        sent = key
        val colors = Accent.palette.copyOf()
        val accent = Accent.color
        Bg.go { enabled = Api.lights(playing, stopped, colors, accent, party) }
    }
}

class PlayerService : Service() {
    companion object {
        var instance: PlayerService? = null
        var pending: ((PlayerService) -> Unit)? = null
        private const val CHANNEL = "music"
        private const val NOTE = 1
    }

    private var mp: MediaPlayer? = null
    private var prepared = false
    private lateinit var session: MediaSession
    private lateinit var audio: AudioManager
    private var focus: AudioFocusRequest? = null
    private var wifi: WifiManager.WifiLock? = null
    private var startedAt = 0L                                  // for "played": how long this song really played
    private var playedMs = 0L
    private var fetching = false
    private var failures = 0
    private val tick = object : Runnable {
        override fun run() {
            Player.changed()
            Bg.main.postDelayed(this, 500)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Config.load(this)
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Music", NotificationManager.IMPORTANCE_LOW))
        session = MediaSession(this, "LivingRoomMusic").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { if (!Player.playing) toggle() }
                override fun onPause() { if (Player.playing) toggle() }
                override fun onSkipToNext() = skip(+1)
                override fun onSkipToPrevious() = skip(-1)
                override fun onSeekTo(pos: Long) { mp?.takeIf { prepared }?.seekTo(pos.toInt()) }
                override fun onFastForward() = seekBy(15000)
                override fun onRewind() = seekBy(-15000)
                override fun onStop() { if (Player.playing) toggle() }
            })
            setSessionActivity(PendingIntent.getActivity(this@PlayerService, 0,
                Intent(this@PlayerService, NowPlayingActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            isActive = true
        }
        wifi = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "LivingRoomMusic")
        startForeground(NOTE, note(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        Bg.main.post(tick)
        Bg.main.post(fader)
        Player.listen(lightsWatch)
        Accent.listen(lightsWatch)
    }

    private val lightsWatch = { Lights.update() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        pending?.let { pending = null; it(this) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Bg.main.removeCallbacks(tick)
        Bg.main.removeCallbacks(fader)
        Player.unlisten(lightsWatch)
        Accent.unlisten(lightsWatch)
        dropNext()
        report(skipped = false)
        Viz.release()
        kill(mp)
        mp = null
        session.release()
        wifi?.takeIf { it.isHeld }?.release()
        focus?.let { audio.abandonAudioFocusRequest(it) }
        instance = null
        Player.playing = false
        Player.changed()
        super.onDestroy()
    }

    // ---- even volume: each song played at about the same loudness (the server measures them, EBU R128) -------------

    private val gainDb = HashMap<String, Float>()                 // measured gain per song (stream id)
    private val level = HashMap<MediaPlayer, Float>()            // each player's volume after its gain (≤ 1)
    private val lift = HashMap<MediaPlayer, android.media.audiofx.LoudnessEnhancer>()
    private var albumRun: Pair<String, Float>? = null            // an album played straight through keeps one level

    private fun key(t: Track) = t.plex ?: t.v

    /** Asks the server to measure a song (the next one, while this one plays). */
    private fun measure(t: Track) {
        val k = key(t)
        if (gainDb.containsKey(k)) return
        Bg.go { Api.gain(k, wait = true)?.let { g -> Bg.main.post { gainDb[k] = g } } }
    }

    /** Sets [m]'s level for [t]: loud songs turned down, quiet ones lifted (up to 6 dB, through Android's limiter). */
    private fun applyGain(m: MediaPlayer, t: Track) {
        val db = albumRun?.takeIf { t.albumId != null && it.first == t.albumId }?.second
            ?: (gainDb[key(t)] ?: 0f).also { g -> albumRun = t.albumId?.let { it to g } }
        val v = Math.pow(10.0, (minOf(db, 0f) / 20.0)).toFloat()
        level[m] = v
        runCatching { m.setVolume(v, v) }
        val boost = maxOf(db, 0f)
        try {
            val e = lift.getOrPut(m) { android.media.audiofx.LoudnessEnhancer(m.audioSessionId) }
            e.setTargetGain((boost * 100).toInt())
            e.enabled = boost > 0.1f
        } catch (ex: Exception) {
            Log.w(TAG, "loudness: $ex")
        }
    }

    private fun levelOf(m: MediaPlayer?) = level[m] ?: 1f

    /** Lets go of a player and its loudness helper. */
    private fun kill(m: MediaPlayer?) {
        m ?: return
        lift.remove(m)?.let { runCatching { it.release() } }
        level.remove(m)
        runCatching { m.setOnCompletionListener(null); m.release() }
    }

    // ---- the next song, ready before it's needed: gapless within an album, a crossfade otherwise -----------------

    private var nxt: MediaPlayer? = null                         // the next song, loading or ready
    private var nxtFor: String? = null                           // ... for which track
    private var nxtReady = false
    private var chained = false                                  // nxt is set as mp's next player: gapless hand-off
    private var fading = false
    private val FADE_MS = 4000

    private fun newPlayer(): MediaPlayer = MediaPlayer().apply {
        setWakeMode(this@PlayerService, PowerManager.PARTIAL_WAKE_LOCK)
        setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
    }

    /** mp's listeners: when it ends, hand over to the next song (already playing if it was chained). */
    private fun wire(m: MediaPlayer) {
        m.setOnPreparedListener { onPrepared() }
        m.setOnCompletionListener {
            if (chained && nxt != null) handOff(gapless = true) else { report(skipped = false); skip(+1, auto = true) }
        }
        m.setOnErrorListener { _, what, extra -> Log.w(TAG, "player error $what/$extra"); failed(); true }
    }

    private fun dropNext() {
        kill(nxt)
        nxt = null; nxtFor = null; nxtReady = false; chained = false
    }

    /** Starts loading the song after this one, so it can follow without a gap. */
    private fun prepareNext() {
        val n = Player.queue.getOrNull(Player.index + 1) ?: return
        measure(n)
        if (nxtFor == n.v) return
        dropNext()
        val m = newPlayer()
        nxt = m
        nxtFor = n.v
        try {
            m.setOnPreparedListener { if (nxt === m) { applyGain(m, n); nxtReady = true; chainIfAlbum() } }
            m.setOnErrorListener { _, _, _ -> if (nxt === m) dropNext(); true }
            m.setDataSource(this, Uri.parse(Api.streamUrl(n)), Api.headers())
            m.prepareAsync()
        } catch (e: Exception) {
            Log.w(TAG, "next: $e")
            dropNext()
        }
    }

    /** The next track of the same album starts on the exact sample the last one ends (no gap, no fade). */
    private fun chainIfAlbum() {
        val cur = Player.current ?: return
        val n = Player.queue.getOrNull(Player.index + 1) ?: return
        val m = mp ?: return
        if (prepared && nxtReady && nxtFor == n.v && cur.albumId != null && cur.albumId == n.albumId) {
            runCatching { m.setNextMediaPlayer(nxt); chained = true }
        }
    }

    /** The ready next song becomes the current one. [gapless]: it's already playing (Android started it). */
    private fun handOff(gapless: Boolean) {
        val old = mp
        val m = nxt ?: return
        if (gapless) report(skipped = false)                     // it ended on its own (a skip or new queue reported already)
        Player.moveTo(Player.index + 1)
        mp = m
        nxt = null; nxtFor = null; nxtReady = false; chained = false
        prepared = true
        wire(m)
        Viz.attach(m.audioSessionId)
        if (!gapless) { levelOf(m).let { v -> m.setVolume(v, v) }; m.start() }
        old?.let { if (!fading) kill(it) }
        startedAt = System.currentTimeMillis()
        playedMs = 0
        Player.playing = true
        Player.buffering = false
        afterStart()
    }

    private fun afterStart() {
        meta(Player.current ?: return)
        Player.changed()
        session()
        startForeground(NOTE, note(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        topUp()
        prepareNext()
    }

    /** Four seconds before a song ends (when the next isn't the same album's next track), fade one into the other. */
    private val fader = object : Runnable {
        override fun run() {
            Bg.main.postDelayed(this, 150)
            val m = mp ?: return
            if (fading || chained || !nxtReady || !prepared || !Player.playing) return
            val dur = runCatching { m.duration }.getOrDefault(0)
            val pos = runCatching { m.currentPosition }.getOrDefault(0)
            if (dur < FADE_MS * 3 || dur - pos > FADE_MS) return
            crossfade()
        }
    }

    private fun crossfade() {
        val out = mp ?: return
        val inn = nxt ?: return
        fading = true
        out.setOnCompletionListener(null)
        out.setOnErrorListener { _, _, _ -> true }
        inn.setVolume(0f, 0f)
        inn.start()
        handOff(gapless = true)                                  // already started; takes over as the current song
        val t0 = System.currentTimeMillis()
        val step = object : Runnable {
            override fun run() {
                val f = ((System.currentTimeMillis() - t0) / FADE_MS.toFloat()).coerceIn(0f, 1f)
                val up = kotlin.math.sin(f * Math.PI / 2).toFloat()        // equal power: no dip in the middle
                val down = kotlin.math.cos(f * Math.PI / 2).toFloat()
                val vi = up * levelOf(inn)
                val vo = down * levelOf(out)
                runCatching { inn.setVolume(vi, vi) }
                runCatching { out.setVolume(vo, vo) }
                if (f < 1f && fading) Bg.main.postDelayed(this, 40)
                else { kill(out); levelOf(inn).let { v -> runCatching { inn.setVolume(v, v) } }; fading = false }
            }
        }
        Bg.main.post(step)
    }

    /** Start the current song. [fresh]: a new queue (an empty one waits for its station's first songs). */
    fun load(fresh: Boolean) {
        val t = Player.current
        if (t == null) {
            val st = Player.station ?: return
            Player.buffering = true
            Player.changed()
            refill(st) { if (Player.current != null) load(false) else fail("Nothing to play on this station yet") }
            return
        }
        fading = false
        if (nxtReady && nxtFor == t.v) {                         // already loaded: start it now
            Player.moveTo(Player.index - 1)
            kill(mp)
            mp = null
            handOff(gapless = false)
            return
        }
        dropNext()
        if (!gainDb.containsKey(key(t))) Bg.go { Api.gain(key(t), wait = false)?.let { g -> Bg.main.post { gainDb[key(t)] = g } } }
        prepared = false
        Player.buffering = true
        Player.error = null
        Player.changed()
        val player = mp ?: newPlayer().also { m -> mp = m; wire(m); Viz.attach(m.audioSessionId) }
        try {
            player.reset()
            wire(player)
            player.setDataSource(this, Uri.parse(Api.streamUrl(t)), Api.headers())
            player.prepareAsync()
        } catch (e: Exception) {
            Log.w(TAG, "load: $e")
            failed()
        }
        meta(t)
        session()
        startForeground(NOTE, note(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        topUp()
    }

    private fun onPrepared() {
        prepared = true
        failures = 0
        if (!takeFocus()) { Player.buffering = false; Player.playing = false; Player.changed(); return }
        Player.current?.let { t -> mp?.let { m -> applyGain(m, t) } }
        mp?.start()
        startedAt = System.currentTimeMillis()
        playedMs = 0
        Player.playing = true
        Player.buffering = false
        wifi?.takeIf { !it.isHeld }?.acquire()
        afterStart()
    }

    /** A song that won't play: try the next one (three in a row and it stops, with a message). */
    private fun failed() {
        failures++
        if (failures >= 3) return fail("Songs aren't playing right now. Is the music server on?")
        Bg.main.postDelayed({ skip(+1, auto = true) }, 1500)
    }

    private fun fail(msg: String) {
        Player.error = msg
        Player.playing = false
        Player.buffering = false
        Player.changed()
        session()
    }

    fun toggle() {
        val m = mp
        if (m == null || !prepared) { if (Player.current != null && m == null) load(false); return }
        if (m.isPlaying) {
            fading = false                                       // a pause mid-crossfade silences the old song too
            m.pause()
            playedMs += System.currentTimeMillis() - startedAt
            Player.playing = false
        } else {
            if (!takeFocus()) return
            m.start()
            startedAt = System.currentTimeMillis()
            Player.playing = true
        }
        Player.changed()
        session()
        startForeground(NOTE, note(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }

    /** Next (+1) or previous (-1). Previous restarts the song when it's more than 5 seconds in. Next is instant when it's loaded. */
    fun skip(dir: Int, auto: Boolean = false) {
        if (dir < 0 && position() > 5000) { mp?.seekTo(0); return }
        if (!auto) report(skipped = dir > 0)
        val to = Player.index + dir
        when {
            to < 0 -> mp?.seekTo(0)
            dir > 0 && nxtReady && nxtFor == Player.queue.getOrNull(to)?.v -> {
                kill(mp)
                mp = null
                handOff(gapless = false)
            }
            to < Player.queue.size -> { Player.moveTo(to); load(false) }
            else -> {                                              // ran out: the station, or this song's radio
                val st = Player.station ?: Player.current?.let { "radio:${it.v}" }.also { it?.let(Player::continueWith) }
                if (st == null) return
                Player.buffering = true
                Player.changed()
                refill(st) { if (to < Player.queue.size) { Player.moveTo(to); load(false) } else fail("That's the end of this station") }
            }
        }
    }

    fun seekTo(ms: Int) {
        val m = mp?.takeIf { prepared } ?: return
        m.seekTo(ms.coerceIn(0, m.duration))
        Player.changed()
        session()
    }

    fun seekBy(ms: Int) {
        val m = mp?.takeIf { prepared } ?: return
        m.seekTo((m.currentPosition + ms).coerceIn(0, m.duration))
        Player.changed()
        session()
    }

    fun shutdown() {
        Lights.update(stopped = true)
        report(skipped = false)
        fading = false
        dropNext()
        mp?.let { runCatching { it.stop() } }
        kill(mp)
        mp = null
        prepared = false
        Player.playing = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun reattachViz() { mp?.let { Viz.attach(it.audioSessionId) } }

    fun position() = mp?.takeIf { prepared }?.currentPosition ?: 0
    fun duration() = mp?.takeIf { prepared }?.duration?.takeIf { it > 0 } ?: ((Player.current?.length ?: 0) * 1000)

    /** Keeps five songs ahead on a station. */
    private fun topUp() {
        val st = Player.station ?: return
        if (Player.queue.size - Player.index <= 5) refill(st) {}
    }

    private fun refill(st: String, then: () -> Unit) {
        if (fetching) { Bg.main.postDelayed({ then() }, 1500); return }
        fetching = true
        val exclude = Player.queue.takeLast(300).map { it.v }
        Bg.run({ Api.station(st, exclude) }) { r ->
            fetching = false
            r.onSuccess { Player.append(it); if (prepared) prepareNext() }.onFailure { Log.w(TAG, "station $st: $it") }
            then()
        }
    }

    /** Tells the server how long the song played and whether it was skipped (discovery learns from early skips). */
    fun report(skipped: Boolean) {
        val t = Player.current ?: return
        if (!prepared && startedAt == 0L) return
        val ms = playedMs + if (Player.playing) System.currentTimeMillis() - startedAt else 0
        startedAt = 0
        playedMs = 0
        if (ms < 3000) return
        val len = duration() / 1000
        Bg.go { Api.played(t.v, (ms / 1000).toInt(), len, skipped) }
    }

    private fun takeFocus(): Boolean {
        val req = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    if (Player.playing) toggle()                    // another app (Plex, YouTube) started: pause
                }
            }.build().also { focus = it }
        return audio.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun session() {
        val state = when {
            Player.buffering -> PlaybackState.STATE_BUFFERING
            Player.playing -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_PAUSED
        }
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO or
                PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND or PlaybackState.ACTION_STOP)
            .setState(state, position().toLong(), 1f).build())
    }

    private fun meta(t: Track) {
        val b = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, t.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, t.artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, t.album ?: "")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, t.length * 1000L)
        session.setMetadata(b.build())
        Bg.go {
            val bmp = Images.get(t.art, 512) ?: return@go
            Bg.main.post {
                if (Player.current?.v == t.v) {
                    session.setMetadata(b.putBitmap(MediaMetadata.METADATA_KEY_ART, bmp).build())
                    Accent.from(bmp)                             // the colours for the lights, even with no screen showing
                }
            }
        }
    }

    private fun note(): Notification {
        val t = Player.current
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_radio)
            .setContentTitle(t?.title ?: "Music")
            .setContentText(t?.artist ?: "")
            .setOngoing(Player.playing)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, NowPlayingActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken))
            .build()
    }
}
