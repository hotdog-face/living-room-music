package com.livingroomtv.music

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * The player, full screen in the album's own colours: synced lyrics in the middle (the current line big and white,
 * the lines around it faded), or the album art when a song has none; or one of the blue-and-violet visualizers.
 * A floating card at the bottom holds the song, the progress line and the buttons, which say their names when chosen.
 * After 12 quiet seconds the buttons fade and only the music, the lyrics and the song's name stay.
 */
class NowPlayingActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var wash: AlbumWash
    private lateinit var stage: FrameLayout
    private lateinit var middle: FrameLayout
    private lateinit var bigArt: ImageView
    private lateinit var lyricsBox: FrameLayout
    private lateinit var lyricsCol: LinearLayout
    private lateinit var badge: TextView
    private lateinit var card: LinearLayout
    private lateinit var thumb: ImageView
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var caption: TextView
    private lateinit var nextUp: TextView
    private lateinit var progress: Progress
    private lateinit var elapsed: TextView
    private lateinit var total: TextView
    private lateinit var timesRow: LinearLayout
    private lateinit var controls: LinearLayout
    private lateinit var playBtn: IconButton
    private lateinit var upBtn: IconButton
    private lateinit var displayBtn: IconButton
    private lateinit var lightsBtn: IconButton
    private lateinit var storyBtn: IconButton
    private lateinit var storyPanel: LinearLayout
    private lateinit var storyArt: ImageView
    private lateinit var storyKind: TextView
    private lateinit var storyTitle: TextView
    private lateinit var storyText: TextView
    private var story: Story? = null
    private var storyFor: String? = null
    private var showStory = false
    private var instrument: Instrument? = null
    private var display = Display.LYRICS
    private var shown: String? = null
    private var lyrics: Lyrics? = null
    private var lyricFor: String? = null
    private var lineAt = -1
    private var armed: String? = null
    private var scrubTo: Int? = null                             // where the progress bar is being moved to                            // thumbs down pressed once for this song
    private val update = { refresh() }
    private val recolor = { recolour() }
    private val calm = Runnable { fade(true) }
    private val lyricTick = object : Runnable {
        override fun run() { follow(); root.postDelayed(this, 50) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Config.load(this)
        root = FrameLayout(this).apply { setBackgroundColor(Colors.BG) }
        wash = AlbumWash(this)
        root.addView(wash, FrameLayout.LayoutParams(-1, -1))
        stage = FrameLayout(this)                                 // the visualizer goes here, full screen
        root.addView(stage, FrameLayout.LayoutParams(-1, -1))

        val middle = FrameLayout(this)
        bigArt = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; round(px(28).toFloat()); elevation = px(30).toFloat() }
        middle.addView(bigArt, FrameLayout.LayoutParams(px(560), px(560), Gravity.CENTER))
        lyricsBox = FadeFrame(this).apply { clipChildren = true }
        lyricsCol = LinearLayout(this).apply {                    // side room: the line being sung is shown a little larger
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(px(110), 0, px(110), 0)
        }
        lyricsBox.addView(lyricsCol, FrameLayout.LayoutParams(-1, -2))
        middle.addView(lyricsBox, FrameLayout.LayoutParams(px(1720), -1, Gravity.CENTER_HORIZONTAL))
        storyPanel = LinearLayout(this).apply {                  // About: the album art with its story beside it
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        storyArt = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; round(px(24).toFloat()); elevation = px(24).toFloat() }
        storyPanel.addView(storyArt, LinearLayout.LayoutParams(px(460), px(460)))
        val storyWords = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        storyKind = label(18, Color.argb(200, 255, 255, 255), bold = true)
        storyTitle = label(40, heavy = true, lines = 2)
        storyText = label(23, Color.argb(225, 255, 255, 255), lines = 11).apply { setLineSpacing(0f, 1.25f) }
        storyWords.addView(storyKind)
        storyWords.addView(storyTitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10) })
        storyWords.addView(storyText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(18) })
        storyWords.addView(label(16, Color.argb(140, 255, 255, 255), text = "From Wikipedia"), LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(16) })
        storyPanel.addView(storyWords, LinearLayout.LayoutParams(px(900), -2).apply { marginStart = px(64) })
        middle.addView(storyPanel, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        root.addView(middle, FrameLayout.LayoutParams(-1, -1).apply { setMargins(0, px(80), 0, px(410)) })
        this.middle = middle

        badge = label(17, Color.WHITE, bold = true).apply {
            setPadding(px(18), px(9), px(18), px(9))
            background = GradientDrawable().apply { cornerRadius = px(20).toFloat(); setColor(Color.argb(90, 0, 0, 0)) }
            visibility = View.GONE
        }
        root.addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, px(48), px(80), 0) })

        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            setPadding(px(34), px(28), px(34), px(26))
            background = GradientDrawable().apply { cornerRadius = px(34).toFloat(); setColor(Color.argb(150, 10, 10, 14)) }
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        thumb = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; round(px(14).toFloat()) }
        head.addView(thumb, LinearLayout.LayoutParams(px(96), px(96)))
        val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        title = label(32, heavy = true)
        artist = label(22, Color.argb(200, 255, 255, 255))
        words.addView(title)
        words.addView(artist, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(8) })
        head.addView(words, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = px(26) })
        val side = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        caption = label(19, Color.WHITE, bold = true).apply { gravity = Gravity.END }
        nextUp = label(18, Color.argb(170, 255, 255, 255)).apply { gravity = Gravity.END }
        side.addView(caption)
        side.addView(nextUp, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(8) })
        head.addView(side, LinearLayout.LayoutParams(px(560), -2))
        card.addView(head)
        progress = Progress(this).apply {
            isFocusable = true
            setOnFocusChangeListener { _, has -> invalidate(); if (has) caption.text = "◀ ▶ to move through the song" }
            setOnKeyListener { _, code, ev -> scrub(code, ev) }
        }
        card.addView(progress, LinearLayout.LayoutParams(-1, px(30)).apply { topMargin = px(18) })
        timesRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        elapsed = label(18, Color.argb(190, 255, 255, 255))
        total = label(18, Color.argb(190, 255, 255, 255)).apply { gravity = Gravity.END }
        timesRow.addView(elapsed, LinearLayout.LayoutParams(0, -2, 1f))
        timesRow.addView(total, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(timesRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(6) })
        controls = LinearLayout(this).apply {                    // room above and below: a focused button grows 8%
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            clipChildren = false
            clipToPadding = false
            setPadding(0, px(12), 0, px(12))
        }
        displayBtn = IconButton(this, R.drawable.ic_display, 80, "Display") { cycleDisplay() }
        lateinit var downBtn: IconButton
        downBtn = IconButton(this, R.drawable.ic_thumb_down, 80, "Not for me") {   // two presses: it's for good
            val t = Player.current ?: return@IconButton
            if (armed == t.v) {
                armed = null
                Player.vote(this, t, "dislike")
                toast("Got it. It won't come up again.")
            } else {
                armed = t.v
                caption.text = "Press again to never play this song"
                root.postDelayed({ if (armed == t.v) { armed = null; if (downBtn.hasFocus()) caption.text = captionFor(downBtn) } }, 4000)
            }
        }
        playBtn = IconButton(this, R.drawable.ic_pause, 112, "Pause", hero = true) { Player.toggle(this) }
        upBtn = IconButton(this, R.drawable.ic_thumb_up, 80, "Love it") {
            Player.current?.let { t -> Player.vote(this, t, if (t.liked) "none" else "like"); toast(if (t.liked) "Added to Liked songs" else "Removed from Liked songs") }
        }
        lightsBtn = IconButton(this, R.drawable.ic_bulb, 80, "Lights") {
            val on = !Lights.enabled
            Lights.enabled = on
            showLights()
            caption.text = captionFor(lightsBtn)
            Bg.go { val now = Api.lightsEnabled(on); Bg.main.post { Lights.enabled = now; showLights(); if (now) Lights.update(force = true) } }
        }
        storyBtn = IconButton(this, R.drawable.ic_info, 80, "About") {
            showStory = !showStory
            getPreferences(MODE_PRIVATE).edit().putBoolean("story", showStory).apply()
            storyBtn.toggled = showStory
            caption.text = captionFor(storyBtn)
            middleForLyrics()
        }
        if (Config.room != "living room") lightsBtn.visibility = View.GONE   // the lights are the living room's
        val buttons = listOf(displayBtn, lightsBtn, storyBtn, IconButton(this, R.drawable.ic_prev, 92, "Previous") { Player.prev(this) }, playBtn,
            IconButton(this, R.drawable.ic_next, 92, "Next") { Player.next(this) }, upBtn, downBtn)
        for (b in buttons) {
            b.onFocus = { btn, has -> if (has) caption.text = captionFor(btn); if (!has && btn === downBtn) armed = null }
            controls.addView(b, LinearLayout.LayoutParams(b.layoutParams).apply { setMargins(px(18), 0, px(18), 0) })
        }
        card.addView(controls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(6) })
        root.addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(px(80), 0, px(80), px(44)) })
        setContentView(root)
        playBtn.requestFocus()
        showStory = getPreferences(MODE_PRIVATE).getBoolean("story", false)
        storyBtn.toggled = showStory
        display = runCatching { Display.valueOf(intent.getStringExtra("display") ?: getPreferences(MODE_PRIVATE).getString("display", "LYRICS")!!) }
            .getOrDefault(Display.LYRICS)
        showDisplay()
    }

    /** The Lights switch: a solid bulb on an accent disc when on, an outlined dim bulb when off. */
    private fun showLights() {
        lightsBtn.set(if (Lights.enabled) R.drawable.ic_bulb else R.drawable.ic_bulb_off)
        lightsBtn.toggled = Lights.enabled
    }

    private fun captionFor(b: IconButton) = when (b) {
        displayBtn -> "Display: ${display.word}"
        storyBtn -> if (showStory) (if (story == null) "About: on · nothing found for this one" else "About: on · the story of what's playing")
                    else "About: off · press to read about the album"
        lightsBtn -> if (Lights.enabled) "Lights: on · the room follows the music" else "Lights: off · the room stays as it is"
        playBtn -> if (Player.playing) "Pause" else "Play"
        upBtn -> if (Player.current?.liked == true) "In Liked songs · press to remove" else "Love it · adds to Liked songs"
        else -> b.word
    }

    /**
     * The progress bar, focused: ◀ ▶ move 5 seconds a press (15 when held), shown at once; the jump happens when the key
     * is let go, so holding it doesn't stutter the music. Down goes back to the buttons.
     */
    private fun scrub(code: Int, ev: KeyEvent): Boolean {
        if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return false
        val dur = Player.duration()
        if (dur <= 0) return true
        if (ev.action == KeyEvent.ACTION_DOWN) {
            val step = if (ev.repeatCount > 6) 15000 else 5000
            val to = ((scrubTo ?: Player.position()) + if (code == KeyEvent.KEYCODE_DPAD_RIGHT) step else -step).coerceIn(0, dur - 1000)
            scrubTo = to
            progress.fraction = to.toFloat() / dur
            elapsed.text = clock(to)
        } else if (ev.action == KeyEvent.ACTION_UP) {
            scrubTo?.let { Player.seekTo(it) }
            scrubTo = null
            lineAt = -1                                          // the lyrics jump to the new place too
        }
        return true
    }

    /** Lyrics → ribbon → aurora → particles → bars → lyrics. */
    private fun cycleDisplay() {
        display = Display.values()[(display.ordinal + 1) % Display.values().size]
        getPreferences(MODE_PRIVATE).edit().putString("display", display.name).apply()
        if (display != Display.LYRICS && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1)
        }
        showDisplay()
        caption.text = captionFor(displayBtn)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        PlayerService.instance?.let { Viz.release(); it.reattachViz() }
        showDisplay()
    }

    private fun showDisplay() {
        instrument?.let { it.running = false; stage.removeView(it) }
        instrument = when (display) {
            Display.LYRICS -> null
            Display.RIBBON -> Ribbon(this)
            Display.AURORA -> Aurora(this)
            Display.PARTICLES -> Particles(this)
            Display.BARS -> Bars(this)
        }
        instrument?.let { v ->
            stage.addView(v, FrameLayout.LayoutParams(-1, -1))
            v.alpha = 0f
            v.animate().alpha(1f).setDuration(600).start()
            v.running = window.decorView.isShown
        }
        Viz.wanted = instrument != null
        wash.visibility = if (instrument == null) View.VISIBLE else View.INVISIBLE
        middleForLyrics()
    }

    override fun onStart() {
        super.onStart()
        Player.listen(update)
        Accent.listen(recolor)
        refresh()
        recolour()
        poke()
        instrument?.running = true
        wash.running = true
        Viz.wanted = instrument != null
        root.post(lyricTick)
        showLights()
        Bg.run({ Api.lightsState() }) { r -> r.onSuccess { Lights.enabled = it; showLights() } }   // the real state, from the server
    }

    override fun onStop() {
        Player.unlisten(update)
        Accent.unlisten(recolor)
        root.removeCallbacks(calm)
        root.removeCallbacks(lyricTick)
        instrument?.running = false
        wash.running = false
        Viz.wanted = false
        super.onStop()
    }

    private fun refresh() {
        val t = Player.current
        if (t == null) {
            title.text = if (Player.buffering) "Finding songs…" else "Nothing playing"
            artist.text = ""
        } else if (shown != t.v) {
            shown = t.v
            title.text = t.title
            artist.text = listOfNotNull(t.artist, t.album).joinToString("  ·  ")
            badge.text = t.quality ?: ""
            badge.visibility = if (t.quality == null) View.GONE else View.VISIBLE
            Images.load(t.art, thumb, px(96))
            Images.load(t.art, bigArt, px(560)) { bmp -> Accent.from(bmp); wash.colors(bmp) }
            loadLyrics(t)
            loadStory(t)
        }
        playBtn.set(if (Player.playing) R.drawable.ic_pause else R.drawable.ic_play)
        playBtn.alpha = if (Player.buffering) 0.6f else 1f
        upBtn.img.alpha = if (t?.liked == true || upBtn.hasFocus()) 1f else 0.55f
        (controls.focusedChild as? IconButton)?.let { caption.text = captionFor(it) }
        val dur = Player.duration()
        val pos = scrubTo ?: Player.position()
        progress.fraction = if (dur > 0) pos.toFloat() / dur else 0f
        elapsed.text = clock(pos)
        total.text = if (dur > 0) clock(dur) else ""
        nextUp.text = Player.error ?: Player.upNext.firstOrNull()?.let { "Next: ${it.title} · ${it.artist}" } ?: ""
    }

    private fun recolour() {
        playBtn.paint()
        lightsBtn.paint()
        progress.invalidate()
        wash.invalidate()
    }

    // ---- lyrics -----------------------------------------------------------------------------------------------

    private fun loadLyrics(t: Track) {
        lyrics = null
        lyricFor = t.v
        lineAt = -1
        lyricsCol.removeAllViews()
        middleForLyrics()
        Bg.run({ Api.lyrics(t) }) { r ->
            if (lyricFor != t.v) return@run
            r.onSuccess { l ->
                lyrics = l.takeIf { it.synced && it.lines.any { line -> line.second.isNotBlank() } }
                lyrics?.lines?.forEach { (_, text) ->
                    lyricsCol.addView(label(44, Color.WHITE, heavy = true, lines = 3, text = text.ifBlank { "♪" }).apply {
                        gravity = Gravity.CENTER
                        alpha = 0.32f
                        setLineSpacing(0f, 1.05f)
                    }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, px(18), 0, px(18)) })
                }
                middleForLyrics()
                lyricsCol.post { follow(force = true) }
            }
        }
    }

    private fun loadStory(t: Track) {
        val key = "${t.album}|${t.artist}"
        if (key == storyFor) return
        storyFor = key
        story = null
        middleForLyrics()
        Images.load(t.art, storyArt, px(460))
        Bg.run({ Api.story(t) }) { r ->
            if (storyFor != key) return@run
            story = r.getOrNull()
            story?.let {
                storyKind.text = if (it.kind == "album") "ABOUT THE ALBUM" else "ABOUT THE ARTIST"
                storyKind.letterSpacing = 0.18f
                storyTitle.text = it.title.replace(Regex("\\s*\\(.*\\)$"), "")
                storyText.text = it.text
            }
            middleForLyrics()
            (controls.focusedChild as? IconButton)?.let { b -> caption.text = captionFor(b) }
        }
    }

    /** Lyrics when the song has synced ones and the display is Lyrics; otherwise the album art (or nothing, for a visualizer). */
    private fun middleForLyrics() {
        val about = display == Display.LYRICS && showStory && story != null
        storyPanel.visibility = if (about) View.VISIBLE else View.GONE
        val showLyrics = display == Display.LYRICS && lyrics != null && !about
        val was = lyricsBox.visibility
        lyricsBox.visibility = if (showLyrics) View.VISIBLE else View.GONE
        if (showLyrics && was != View.VISIBLE) { lineAt = -1; lyricsCol.post { follow(force = true) } }   // re-centre once laid out
        bigArt.visibility = if (display == Display.LYRICS && !showLyrics && !about) View.VISIBLE else View.GONE
    }

    /** Keeps the line being sung in the middle, big and bright; the others stay faded. */
    private fun follow(force: Boolean = false) {
        val l = lyrics ?: return
        if (lyricsBox.visibility != View.VISIBLE) return
        val pos = Player.position() + 250                        // a quarter second early reads naturally
        var i = l.lines.indexOfLast { it.first <= pos }.coerceAtLeast(0)
        if (i >= lyricsCol.childCount) i = lyricsCol.childCount - 1
        if (i < 0) return
        sing(l, i, pos)
        if (i == lineAt && !force) return
        (lyricsCol.getChildAt(lineAt) as? TextView)?.let { it.tag = null; it.text = l.lines.getOrNull(lineAt)?.second?.ifBlank { "♪" } ?: it.text }
        lineAt = i
        for (k in 0 until lyricsCol.childCount) {
            val v = lyricsCol.getChildAt(k)
            val now = k == i
            v.animate().alpha(if (now) 1f else if (k == i + 1 || k == i - 1) 0.42f else 0.22f)
                .scaleX(if (now) 1.08f else 1f).scaleY(if (now) 1.08f else 1f).setDuration(380).start()
        }
        val line = lyricsCol.getChildAt(i) ?: return
        if (lyricsBox.height == 0 || line.height == 0) {          // not laid out yet: try again in a moment
            lineAt = -1
            return
        }
        val target = lyricsBox.height / 2f - (line.top + line.height / 2f)
        lyricsCol.animate().translationY(target).setInterpolator(DecelerateInterpolator()).setDuration(if (force) 0 else 520).start()
    }

    /**
     * Karaoke: the line being sung fills with white from left to right as it's sung. Lyrics are timed per line, so the
     * fill runs across the line's own time (ending a little before the next line starts).
     */
    private fun sing(l: Lyrics, i: Int, pos: Int) {
        val v = lyricsCol.getChildAt(i) as? TextView ?: return
        val text = l.lines[i].second.ifBlank { "♪" }
        val start = l.lines[i].first
        val next = l.lines.getOrNull(i + 1)?.first ?: (start + 4000)
        val dur = ((next - start) * 0.9f).coerceIn(600f, 7000f)
        val frac = ((pos - start) / dur).coerceIn(0f, 1f)
        val sung = (text.length * frac).toInt()
        if (v.tag == sung) return
        v.tag = sung
        v.text = android.text.SpannableString(text).apply {
            if (sung < text.length) setSpan(android.text.style.ForegroundColorSpan(Color.argb(95, 255, 255, 255)),
                sung, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    // ---- calm -------------------------------------------------------------------------------------------------

    /** Calm: the buttons, progress and captions fade and fold away, leaving the card as just the song; any key unfolds it. */
    private fun fade(quiet: Boolean) {
        val parts = listOf<View>(controls, progress, timesRow, caption, nextUp)
        if (quiet) {
            for (v in parts) v.animate().alpha(0f).setDuration(900).withEndAction { if (calmNow) v.visibility = View.GONE }.start()
            calmNow = true
            middle.animate().translationY(px(110).toFloat()).setDuration(900).start()   // the lyrics take the room
        } else {
            val wasCalm = calmNow
            calmNow = false
            for (v in parts) { v.visibility = View.VISIBLE; v.animate().alpha(1f).setDuration(180).start() }
            middle.animate().translationY(0f).setDuration(250).start()
            if (wasCalm) playBtn.requestFocus()
        }
    }

    private var calmNow = false

    private fun poke() {
        fade(false)
        root.removeCallbacks(calm)
        root.postDelayed(calm, 12000)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val wasQuiet = calmNow
            poke()
            if (wasQuiet && event.keyCode in setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) {
                return true                                          // the first press only wakes the buttons
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Home, another app: the music stops with the app (the photo screensaver doesn't count: it isn't the user leaving). */
    override fun onUserLeaveHint() {
        Player.stop()
        super.onUserLeaveHint()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = Go.mediaKey(this, keyCode) || super.onKeyDown(keyCode, event)
}

/**
 * The album's colours, full screen and slowly moving: a diagonal blend of its two main colours with soft clouds of
 * all three drifting over it, darkened toward the bottom so the card and lyrics stay readable.
 */
class AlbumWash(context: Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var cols = Accent.palette
    private var t = 0f
    private var last = 0L
    var running = false
        set(v) { field = v; last = 0L; if (v) postInvalidateOnAnimation() }

    fun colors(bmp: Bitmap) { cols = Accent.palette; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        if (last != 0L) t += (now - last) / 1e9f
        last = now
        val w = width.toFloat()
        val h = height.toFloat()
        val (a, b, c) = Triple(cols[0], cols[1], cols[2])
        p.shader = LinearGradient(w * (0.5f + 0.4f * sin(t * 0.05f)), 0f, w * (0.5f - 0.4f * sin(t * 0.05f)), h, a, b, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, p)
        for ((i, col) in listOf(c, a, b).withIndex()) {
            val x = w * (0.5f + 0.38f * sin(t * (0.07f + i * 0.02f) + i * 2.1f))
            val y = h * (0.42f + 0.3f * cos(t * (0.05f + i * 0.015f) + i * 1.3f))
            val r = max(w, h) * 0.55f
            p.shader = RadialGradient(x, y, r, Color.argb(150, Color.red(col), Color.green(col), Color.blue(col)), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawCircle(x, y, r, p)
        }
        p.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(Color.argb(70, 0, 0, 0), Color.argb(60, 0, 0, 0), Color.argb(215, 0, 0, 0)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, p)
        if (running) postInvalidateDelayed(33)                    // 30 fps is plenty for something this slow
    }
}

/** A thin rounded line: faint track, the played part in the accent colour, a white knob; focused, it thickens and the knob grows. */
class Progress(context: Context) : View(context) {
    var fraction = 0f
        set(v) { if (field != v) { field = v.coerceIn(0f, 1f); invalidate() } }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 255, 255, 255) }
    private val fg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val focused = hasFocus()
        val cy = height / 2f
        val line = height * (if (focused) 0.32f else 0.2f)
        val r = height * (if (focused) 0.5f else 0.3f)
        val x = r + (width - 2 * r) * fraction
        fg.color = Accent.color
        canvas.drawRoundRect(r, cy - line / 2, width - r, cy + line / 2, line, line, bg)
        canvas.drawRoundRect(r, cy - line / 2, x, cy + line / 2, line, line, fg)
        if (focused) {
            halo.color = Color.argb(90, Color.red(Accent.color), Color.green(Accent.color), Color.blue(Accent.color))
            canvas.drawCircle(x, cy, r * 1.0f, halo)
        }
        canvas.drawCircle(x, cy, if (focused) r * 0.7f else r, knob)
    }
}

/** A frame whose contents fade out softly toward its top and bottom (the lyrics scroll through it with no hard edge). */
class FadeFrame(context: Context) : FrameLayout(context) {
    /** The lyrics column is as tall as all its lines (not cut to this frame's height); the frame shows a window of it. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val w = MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(w, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
    }

    private val mask = Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN) }

    override fun dispatchDraw(canvas: Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        val layer = canvas.saveLayer(0f, 0f, w, h, null)
        super.dispatchDraw(canvas)
        mask.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(Color.TRANSPARENT, Color.BLACK, Color.BLACK, Color.TRANSPARENT),
            floatArrayOf(0f, 0.22f, 0.78f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, mask)
        canvas.restoreToCount(layer)
    }
}
