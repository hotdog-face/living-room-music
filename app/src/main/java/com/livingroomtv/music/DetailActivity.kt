package com.livingroomtv.music

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView

/**
 * A playlist, an album or an artist: the picture and Play / Shuffle (/ Radio) on the left, the songs (or, for an
 * artist, rows of songs, albums and similar artists) on the right.
 */
class DetailActivity : Activity() {
    private lateinit var right: FrameLayout
    private var songs: ListView? = null
    private lateinit var buttons: LinearLayout
    private lateinit var title: android.widget.TextView
    private lateinit var sub: android.widget.TextView
    private lateinit var art: ImageView
    private lateinit var kind: android.widget.TextView
    private lateinit var pageView: android.widget.LinearLayout
    private var page: Page? = null
    private val update = { songs?.let { l -> for (i in 0 until l.childCount) (l.getChildAt(i) as? TrackLine)?.mark() } ?: Unit }

    private val type get() = intent.getStringExtra("type") ?: "playlist"
    private val key get() = intent.getStringExtra("id") ?: ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Config.load(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Colors.BG) }
        pageView = root
        val main = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(96), px(80), px(40), 0) }
        art = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Colors.SURFACE); elevation = px(24).toFloat() }
        left.addView(art, LinearLayout.LayoutParams(px(400), px(400)))
        if (type == "artist") art.oval() else art.round(px(24).toFloat())
        kind = label(19, Accent.color, bold = true, text = when (type) { "album" -> "Album"; "artist" -> "Artist"; "mood" -> "Mood or genre"; "kids" -> "Kids"; else -> "Playlist" })
        left.addView(kind, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(36) })
        title = label(42, lines = 3, text = intent.getStringExtra("title") ?: "", heavy = true)
        left.addView(title, LinearLayout.LayoutParams(px(460), -2).apply { topMargin = px(12) })
        sub = label(21, Colors.DIM, lines = 2)
        left.addView(sub, LinearLayout.LayoutParams(px(460), -2).apply { topMargin = px(12) })
        buttons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(buttons, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(30) })
        main.addView(left, LinearLayout.LayoutParams(px(620), -1))
        right = FrameLayout(this)
        main.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        root.addView(main, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(MiniPlayer(this), LinearLayout.LayoutParams(-1, -2).apply { setMargins(px(80), px(6), px(80), px(30)) })
        setContentView(root)
        load()
    }

    override fun onStart() { super.onStart(); Player.listen(update) }
    override fun onStop() { Player.unlisten(update); super.onStop() }

    private fun load() {
        sub.text = "Loading…"
        Bg.run({ when (type) { "artist" -> Api.artist(key); "mood" -> Api.mood(key, intent.getStringExtra("title") ?: ""); else -> Api.list(type, key) } }) { r ->
            r.onSuccess { show(it) }.onFailure { sub.text = it.message ?: "Couldn't load" }
        }
    }

    private fun show(p: Page) {
        page = p
        title.text = p.title.ifEmpty { title.text }
        sub.text = listOf(p.subtitle, if (p.tracks.isNotEmpty()) "${p.tracks.size} songs" else "").filter { it.isNotEmpty() }
            .distinct().joinToString("  ·  ")
        Images.load(p.art, art, px(400)) { bmp -> glow(bmp) }
        buttons.removeAllViews()
        val top = p.tracks.ifEmpty { p.rows.firstOrNull { it.id == "songs" }?.items?.filterIsInstance<Item.Song>()?.map { it.track } ?: emptyList() }
        fun add(v: View) = buttons.addView(v, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = px(16) })
        if (type == "artist") {
            add(pill(R.drawable.ic_radio, "Radio", primary = true) { Player.play(this, emptyList(), 0, "artist:$key"); Go.nowPlaying(this) })
            if (top.isNotEmpty()) add(pill(R.drawable.ic_play, "Top songs") { Player.play(this, top, 0, "artist:$key"); Go.nowPlaying(this) })
        } else if (top.isNotEmpty()) {
            val after = after()                                   // Liked songs and Kids keep going; others end in radio
            add(pill(R.drawable.ic_play, "Play", primary = true) { Player.play(this, top, 0, after); Go.nowPlaying(this) })
            add(pill(R.drawable.ic_shuffle, "Shuffle") { Player.play(this, top.shuffled(), 0, after); Go.nowPlaying(this) })
            if (type != "kids") add(pill(R.drawable.ic_radio, "Radio") {
                Player.play(this, emptyList(), 0, if (key == "LM") "blend" else "radio:${top.random().v}"); Go.nowPlaying(this)
            })
        }
        right.removeAllViews()
        if (type == "artist" || type == "mood") {
            val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; setPadding(0, px(30), 0, px(60)) }
            for (row in p.rows) rows.addView(RowView(this, row,
                onClick = { r, item -> Go.select(this, r, item) }, onHold = { item -> Go.hold(this, item) }))
            right.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipChildren = false; addView(rows) })
            (rows.getChildAt(0) ?: buttons.getChildAt(0))?.requestFocus()
        } else {
            val l = ListView(this).apply {                       // only the songs on screen are drawn (Liked songs has thousands)
                divider = null
                isVerticalScrollBarEnabled = false
                setPadding(0, px(56), px(60), px(60))
                clipToPadding = false
                selector = GradientDrawable().apply { cornerRadius = px(14).toFloat(); setColor(Colors.SURFACE2) }
                adapter = object : BaseAdapter() {
                    override fun getCount() = p.tracks.size
                    override fun getItem(i: Int) = p.tracks[i]
                    override fun getItemId(i: Int) = i.toLong()
                    override fun getView(i: Int, old: View?, parent: ViewGroup?): View =
                        (old as? TrackLine)?.takeIf { it.track === p.tracks[i] } ?: TrackLine(this@DetailActivity, p.tracks[i], number = i + 1)
                }
                setOnItemClickListener { _, _, i, _ -> Player.play(this@DetailActivity, p.tracks, i, after()); Go.nowPlaying(this@DetailActivity) }
                setOnItemLongClickListener { _, _, i, _ -> Go.menu(this@DetailActivity, p.tracks[i]); true }
            }
            songs = l
            right.addView(l)
            if (p.tracks.isNotEmpty()) l.requestFocus() else buttons.getChildAt(0)?.requestFocus()
        }
    }

    /** What plays once the list runs out: Liked songs keeps shuffling likes, a Kids list keeps to its kids' songs. */
    private fun after(): String? = when { key == "LM" -> "likes"; type == "kids" -> key; else -> null }

    /** A soft wash of the album's colour behind the top of the page. */
    private fun glow(bmp: android.graphics.Bitmap) {
        val c = android.graphics.Bitmap.createScaledBitmap(bmp, 1, 1, true).getPixel(0, 0)
        val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(c, it) }
        hsv[1] = (hsv[1] * 1.2f).coerceAtMost(1f); hsv[2] = 0.45f
        val tint = android.graphics.Color.HSVToColor(hsv)
        pageView.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(
            android.graphics.Color.argb(150, android.graphics.Color.red(tint), android.graphics.Color.green(tint), android.graphics.Color.blue(tint)), Colors.BG, Colors.BG))
        kind.setTextColor(android.graphics.Color.HSVToColor(hsv.also { it[2] = 1f; it[1] = it[1].coerceAtLeast(0.45f) }))
    }

    /** Home, another app: the music stops with the app (the photo screensaver doesn't count: it isn't the user leaving). */
    override fun onUserLeaveHint() {
        Player.stop()
        super.onUserLeaveHint()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = Go.mediaKey(this, keyCode) || super.onKeyDown(keyCode, event)
}
