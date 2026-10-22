package com.livingroomtv.music

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Layout sizes are designed on a 1920-pixel-wide screen and scaled to the real one. */
fun Context.px(design: Number): Int = (design.toFloat() * resources.displayMetrics.widthPixels / 1920f).toInt()
fun Context.textPx(design: Number): Float = design.toFloat() * resources.displayMetrics.widthPixels / 1920f

/** Modern and dark (Tidal-like): near-black surfaces, white type, and one accent colour taken from what's playing. */
object Colors {
    val BG = Color.rgb(10, 10, 12)
    val SURFACE = Color.rgb(22, 22, 26)
    val SURFACE2 = Color.rgb(32, 32, 38)
    val TEXT = Color.WHITE
    val DIM = Color.rgb(160, 160, 171)
    val FAINT = Color.rgb(107, 107, 118)
    val INK = Color.rgb(12, 12, 14)                              // dark text on a light fill
}

object Fonts {
    val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val bold: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    val light: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
}

/**
 * The accent follows the album art: its boldest colour, lifted so it reads on black (the play button, focus rings,
 * the progress line). [palette] is the art's three main colours, for the player's full-screen wash.
 */
object Accent {
    var color = Color.rgb(255, 154, 31); private set
    var palette = intArrayOf(Color.rgb(255, 120, 40), Color.rgb(230, 60, 120), Color.rgb(250, 200, 60)); private set
    private val listeners = linkedSetOf<() -> Unit>()
    private var from: Bitmap? = null

    fun listen(l: () -> Unit) { listeners += l }
    fun unlisten(l: () -> Unit) { listeners -= l }

    fun from(bmp: Bitmap) {
        if (bmp === from) return
        from = bmp
        val small = Bitmap.createScaledBitmap(bmp, 24, 24, true)
        val buckets = Array(12) { FloatArray(5) }                  // per hue slice: weight, r, g, b, count
        val hsv = FloatArray(3)
        for (y in 0 until 24) for (x in 0 until 24) {
            val c = small.getPixel(x, y)
            Color.colorToHSV(c, hsv)
            val w = hsv[1] * hsv[1] * hsv[2] * (if (hsv[2] < 0.25f) 0.1f else 1f)
            val b = buckets[((hsv[0] / 30f).toInt()).coerceIn(0, 11)]
            b[0] += w; b[1] += Color.red(c) * w; b[2] += Color.green(c) * w; b[3] += Color.blue(c) * w; b[4]++
        }
        val ranked = buckets.filter { it[0] > 0.01f }.sortedByDescending { it[0] }
        fun col(b: FloatArray, sMin: Float, vMin: Float): Int {
            val c = Color.rgb((b[1] / b[0]).toInt(), (b[2] / b[0]).toInt(), (b[3] / b[0]).toInt())
            Color.colorToHSV(c, hsv)
            hsv[1] = hsv[1].coerceAtLeast(sMin)
            hsv[2] = hsv[2].coerceAtLeast(vMin)
            return Color.HSVToColor(hsv)
        }
        if (ranked.isEmpty()) {                                    // black-and-white art: a neutral warm white
            color = Color.rgb(236, 230, 220)
            palette = intArrayOf(Color.rgb(70, 70, 80), Color.rgb(40, 40, 48), Color.rgb(110, 110, 120))
        } else {
            color = col(ranked[0], 0.55f, 0.92f)
            palette = IntArray(3) { i -> col(ranked[i % ranked.size], 0.5f, 0.55f) }
        }
        listeners.toList().forEach { it() }
    }
}

fun Context.label(size: Number, color: Int = Colors.TEXT, bold: Boolean = false, lines: Int = 1, text: CharSequence = "",
                  heavy: Boolean = false) =
    TextView(this).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx(size))
        typeface = if (heavy) Fonts.bold else if (bold) Fonts.medium else Fonts.regular
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
    }

/** A small grey heading above a row or a block ("Up next", "Playing from"). */
fun Context.eyebrow(text: String, color: Int = Colors.DIM, size: Number = 18) = label(size, color, bold = true, text = text)

fun View.round(radius: Float) {
    outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius)
    }
    clipToOutline = true
}

fun View.oval() {
    outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
    }
    clipToOutline = true
}

fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

/** "3:45" */
fun clock(ms: Int): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

fun ring(context: Context, radius: Float, oval: Boolean = false) = GradientDrawable().apply {
    if (oval) shape = GradientDrawable.OVAL else cornerRadius = radius
    setStroke(context.px(4), Accent.color)
}

/**
 * Art with its title and subtitle under it (round for an artist); a find also says why it's there. Stations get a
 * play button in the corner. Focus grows it a little and rings it in the accent colour.
 */
class ItemCard(context: Context, val item: Item, showReason: Boolean, onClick: (Item) -> Unit, onHold: (Item) -> Unit) :
    LinearLayout(context) {
    private val size = context.px(236)
    private val art = FrameLayout(context)

    init {
        orientation = VERTICAL
        isFocusable = true
        clipChildren = false
        clipToPadding = false
        val pad = context.px(14)
        setPadding(pad, pad, pad, pad)
        val (src, title, sub, reason, round) = when (item) {
            is Item.Song -> listOf(item.track.art, item.track.title, item.track.artist, item.track.reason.takeIf { showReason }, false)
            is Item.Other -> listOf(item.card.art, item.card.title, item.card.subtitle, item.card.reason.takeIf { showReason }, item.card.type == "artist")
        }
        round as Boolean
        val radius = context.px(18).toFloat()
        val img = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        art.background = GradientDrawable().apply { setColor(Colors.SURFACE); cornerRadius = radius }
        art.addView(img, FrameLayout.LayoutParams(-1, -1))
        val likesTile = item is Item.Other && item.card.type == "station" && item.card.id == "likes"
        if (likesTile) {                                          // all your likes, not one album: a thumbs up, not a cover
            art.addView(View(context).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Color.rgb(132, 86, 255), Color.rgb(214, 72, 190), Color.rgb(255, 128, 96)))
            }, FrameLayout.LayoutParams(-1, -1))
            art.addView(ImageView(context).apply { setImageResource(R.drawable.ic_thumb_up); setColorFilter(Color.WHITE) },
                FrameLayout.LayoutParams(size * 42 / 100, size * 42 / 100, Gravity.CENTER))
        }
        if (item is Item.Other && item.card.type == "mood") {        // no art: a coloured tile with its name, like YouTube Music's
            val tints = listOf(intArrayOf(0xFF5B5BF0.toInt(), 0xFFB04CE0.toInt()), intArrayOf(0xFFFF7A59.toInt(), 0xFFE0407B.toInt()),
                intArrayOf(0xFF22B8A6.toInt(), 0xFF2E73E8.toInt()), intArrayOf(0xFFF5B83D.toInt(), 0xFFEB6A3A.toInt()),
                intArrayOf(0xFF7D5BF0.toInt(), 0xFF3AA0E8.toInt()), intArrayOf(0xFFE04C8C.toInt(), 0xFF8A4CE0.toInt()))
            art.addView(View(context).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, tints[Math.floorMod(item.card.title.hashCode(), tints.size)])
            }, FrameLayout.LayoutParams(-1, -1))
            art.addView(context.label(30, Color.WHITE, heavy = true, lines = 3, text = item.card.title).apply {
                setPadding(context.px(20), 0, context.px(20), context.px(20))
            }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.START))
        }
        if (item is Item.Other && item.card.type == "station" && !likesTile) {
            art.addView(View(context).apply {
                background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(Color.argb(170, 0, 0, 0), Color.TRANSPARENT))
            }, FrameLayout.LayoutParams(-1, -1))
            art.addView(FrameLayout(context).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
                addView(ImageView(context).apply { setImageResource(R.drawable.ic_play); setColorFilter(Colors.INK) },
                    FrameLayout.LayoutParams(context.px(30), context.px(30), Gravity.CENTER))
            }, FrameLayout.LayoutParams(context.px(58), context.px(58), Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, context.px(14), context.px(14))
            })
        }
        if (round) art.oval() else art.round(radius)
        addView(art, LayoutParams(size, size))
        val align = if (round) Gravity.CENTER_HORIZONTAL else Gravity.START
        val titleView = context.label(23, Colors.TEXT, bold = true, text = title as String).apply { gravity = align }
        addView(titleView, LayoutParams(size, -2).apply { topMargin = context.px(16) })
        if ((sub as String).isNotEmpty()) addView(context.label(19, Colors.DIM, text = sub).apply { gravity = align },
            LayoutParams(size, -2).apply { topMargin = context.px(7) })
        (reason as String?)?.let {
            addView(context.label(17, Colors.FAINT, lines = 2, text = it), LayoutParams(size, -2).apply { topMargin = context.px(7) })
        }
        if (!likesTile) Images.load(src as String?, img, size)
        setOnClickListener { onClick(item) }
        setOnLongClickListener { onHold(item); true }
        setOnFocusChangeListener { _, has ->
            translationZ = if (has) context.px(10).toFloat() else 0f
            art.animate().scaleX(if (has) 1.06f else 1f).scaleY(if (has) 1.06f else 1f)
                .setInterpolator(DecelerateInterpolator()).setDuration(180).start()
            art.foreground = if (has) ring(context, radius, round) else null
        }
    }
}

/** A titled row that scrolls sideways. */
class RowView(context: Context, val row: Row, onClick: (Row, Item) -> Unit, onHold: (Item) -> Unit,
              onMore: ((Card) -> Unit)? = null) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        clipChildren = false
        val side = context.px(96)
        addView(context.label(31, Colors.TEXT, heavy = true, text = row.title), LayoutParams(-1, -2).apply {
            setMargins(side, context.px(40), side, 0)
        })
        val strip = LinearLayout(context).apply {
            orientation = HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding(side - context.px(14), context.px(10), side, context.px(4))
        }
        val showReason = row.id in setOf("fresh", "deep", "artists")
        for (it in row.items) strip.addView(ItemCard(context, it, showReason, { item -> onClick(row, item) }, onHold),
            LayoutParams(-2, -2).apply { marginEnd = context.px(6) })
        row.more?.let { more ->
            if (onMore != null) strip.addView(moreCard(context, more.title) { onMore(more) })
        }
        addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            isSmoothScrollingEnabled = true
            addView(strip)
        }, LayoutParams(-1, -2))
    }

    private fun moreCard(context: Context, title: String, go: () -> Unit) = LinearLayout(context).apply {
        orientation = VERTICAL
        isFocusable = true
        val pad = context.px(14)
        setPadding(pad, pad, pad, pad)
        val radius = context.px(18).toFloat()
        val box = FrameLayout(context).apply {
            background = GradientDrawable().apply { setColor(Colors.SURFACE); cornerRadius = radius }
            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER
                addView(context.label(24, Colors.TEXT, bold = true, text = "Show all"))
                addView(context.label(19, Colors.DIM, lines = 2, text = title).apply { gravity = Gravity.CENTER },
                    LayoutParams(-2, -2).apply { topMargin = context.px(8) })
            }, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        }
        addView(box, LayoutParams(context.px(236), context.px(236)))
        setOnClickListener { go() }
        setOnFocusChangeListener { _, has -> box.foreground = if (has) ring(context, radius) else null }
    }
}

/**
 * One song in a list: small art, title and artist, a heart-style thumbs up when it's liked, the length. The playing
 * song shows little bars and its title in the accent colour.
 */
class TrackLine(context: Context, val track: Track, number: Int? = null, onClick: (() -> Unit)? = null,
                onHold: (() -> Unit)? = null) : LinearLayout(context) {
    private val title: TextView
    private val playing = ImageView(context).apply { setImageResource(R.drawable.ic_display) }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val pad = context.px(12)
        setPadding(context.px(16), pad, context.px(24), pad)
        number?.let { addView(context.label(21, Colors.FAINT, text = "$it").apply { gravity = Gravity.END },
            LayoutParams(context.px(40), -2).apply { marginEnd = context.px(22) }) }
        val img = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; round(context.px(10).toFloat()) }
        addView(img, LayoutParams(context.px(72), context.px(72)))
        Images.load(track.art, img, context.px(72))
        val words = LinearLayout(context).apply { orientation = VERTICAL }
        title = context.label(25, bold = true, text = track.title)
        words.addView(title)
        words.addView(context.label(20, Colors.DIM, text = listOfNotNull(track.artist, track.album).joinToString("  ·  ")),
            LayoutParams(-2, -2).apply { topMargin = context.px(7) })
        addView(words, LayoutParams(0, -2, 1f).apply { marginStart = context.px(24) })
        addView(playing, LayoutParams(context.px(28), context.px(28)).apply { marginEnd = context.px(26) })
        if (track.liked) addView(ImageView(context).apply { setImageResource(R.drawable.ic_thumb_up); setColorFilter(Colors.DIM) },
            LayoutParams(context.px(26), context.px(26)).apply { marginEnd = context.px(26) })
        if (track.length > 0) addView(context.label(20, Colors.FAINT, text = clock(track.length * 1000)))
        if (onClick != null) {                                   // on its own; in a ListView the list does focus and clicks
            isFocusable = true
            background = GradientDrawable().apply { cornerRadius = context.px(14).toFloat(); setColor(Color.TRANSPARENT) }
            setOnClickListener { onClick() }
            setOnLongClickListener { onHold?.invoke(); true }
            setOnFocusChangeListener { _, has -> (background as GradientDrawable).setColor(if (has) Colors.SURFACE2 else Color.TRANSPARENT) }
        }
        mark()
    }

    fun mark() {
        val now = Player.current?.v == track.v
        title.setTextColor(if (now) Accent.color else Colors.TEXT)
        playing.visibility = if (now) VISIBLE else GONE
        playing.setColorFilter(Accent.color)
    }
}

/** A round control. Focused: white disc, dark icon, and its name shows in the caption. [hero]: the accent-filled play button. */
class IconButton(context: Context, icon: Int, sizeDesign: Int, val word: String, private val hero: Boolean = false,
                 val onClick: () -> Unit) : FrameLayout(context) {
    val img = ImageView(context).apply { setImageResource(icon) }
    var onFocus: ((IconButton, Boolean) -> Unit)? = null
    /** For an on/off switch: null = an ordinary button; true = on (filled with the accent); false = off (outlined, dim). */
    var toggled: Boolean? = null
        set(v) { field = v; paint() }

    init {
        isFocusable = true
        val s = context.px(sizeDesign)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        addView(img, LayoutParams(s * 42 / 100, s * 42 / 100, Gravity.CENTER))
        layoutParams = ViewGroup.MarginLayoutParams(s, s)
        setOnClickListener { onClick() }
        setOnFocusChangeListener { _, has ->
            paint()
            animate().scaleX(if (has) 1.08f else 1f).scaleY(if (has) 1.08f else 1f).setDuration(150).start()
            onFocus?.invoke(this, has)
        }
        paint()
    }

    fun paint() {
        val bg = background as GradientDrawable
        img.alpha = 1f
        when {
            hero -> { bg.setColor(Accent.color); bg.setStroke(if (hasFocus()) context.px(4) else 0, Color.WHITE); img.setColorFilter(Colors.INK) }
            toggled == true -> {                                  // on: the accent fill; focus adds a white ring
                bg.setColor(Accent.color); bg.setStroke(if (hasFocus()) context.px(4) else 0, Color.WHITE); img.setColorFilter(Colors.INK)
            }
            toggled == false -> {                                 // off: an outline and a dim icon; focus lights the outline
                bg.setColor(if (hasFocus()) Color.argb(40, 255, 255, 255) else Color.TRANSPARENT)
                bg.setStroke(context.px(if (hasFocus()) 4 else 2), if (hasFocus()) Color.WHITE else Color.argb(110, 255, 255, 255))
                img.setColorFilter(Colors.TEXT); img.alpha = if (hasFocus()) 0.9f else 0.5f
            }
            hasFocus() -> { bg.setColor(Color.WHITE); bg.setStroke(0, 0); img.setColorFilter(Colors.INK) }
            else -> { bg.setColor(Color.TRANSPARENT); bg.setStroke(0, 0); img.setColorFilter(Colors.TEXT) }
        }
    }

    fun set(icon: Int) = img.setImageResource(icon)
}

/** A rounded button with an icon and a word ("Play", "Shuffle", "Radio"); [primary] fills it with the accent. */
fun Context.pill(icon: Int, word: String, primary: Boolean = false, onClick: () -> Unit): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    isFocusable = true
    setPadding(px(28), px(16), px(34), px(16))
    val bg = GradientDrawable().apply { cornerRadius = px(40).toFloat() }
    background = bg
    val img = ImageView(this@pill).apply { setImageResource(icon) }
    val text = label(22, bold = true, text = word)
    addView(img, LinearLayout.LayoutParams(px(30), px(30)))
    addView(text, LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(12) })
    fun paint(has: Boolean) {
        val fill = if (has) Color.WHITE else if (primary) Accent.color else Colors.SURFACE2
        val ink = if (has || primary) Colors.INK else Colors.TEXT
        bg.setColor(fill); img.setColorFilter(ink); text.setTextColor(ink)
    }
    paint(false)
    setOnClickListener { onClick() }
    setOnFocusChangeListener { _, has -> paint(has) }
}

/**
 * A floating bar along the bottom while something plays, tinted with the album's colour: the song (OK opens the
 * player), then its own pause/play and stop buttons, so stopping is one press away on every screen.
 */
class MiniPlayer(context: Context) : LinearLayout(context) {
    private val img = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; round(context.px(10).toFloat()) }
    private val title = context.label(24, bold = true)
    private val sub = context.label(19, Colors.DIM)
    private val bars = ImageView(context).apply { setImageResource(R.drawable.ic_display) }
    private val bar = LinearLayout(context)
    private val playBtn = IconButton(context, R.drawable.ic_pause, 76, "Pause") { Player.toggle(context) }
    private val stopBtn = IconButton(context, R.drawable.ic_stop, 76, "Stop") { Player.stop() }
    private var shown: String? = null
    private val update = { refresh() }
    private val bg = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Colors.SURFACE2, Colors.SURFACE2)).apply {
        cornerRadius = context.px(26).toFloat()
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false                                     // a focused button grows a little: don't cut it off
        clipToPadding = false
        setPadding(context.px(16), context.px(12), context.px(20), context.px(12))
        background = bg
        bar.apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            setPadding(0, 0, context.px(20), 0)
            background = GradientDrawable().apply { cornerRadius = context.px(18).toFloat(); setColor(Color.TRANSPARENT) }
            addView(img, LayoutParams(context.px(72), context.px(72)))
            val words = LinearLayout(context).apply { orientation = VERTICAL }
            words.addView(title)
            words.addView(sub, LayoutParams(-2, -2).apply { topMargin = context.px(6) })
            addView(words, LayoutParams(0, -2, 1f).apply { marginStart = context.px(22) })
            addView(bars, LayoutParams(context.px(30), context.px(30)))
            setOnClickListener { Go.start(context as Activity, Intent(context, NowPlayingActivity::class.java)) }
            setOnFocusChangeListener { _, has ->
                (background as GradientDrawable).setColor(if (has) Color.argb(60, 255, 255, 255) else Color.TRANSPARENT)
            }
        }
        addView(bar, LayoutParams(0, -2, 1f))
        addView(playBtn, LayoutParams(playBtn.layoutParams).apply { marginStart = context.px(12) })
        addView(stopBtn, LayoutParams(stopBtn.layoutParams).apply { marginStart = context.px(8) })
        refresh()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); Player.listen(update); Accent.listen(update); refresh() }
    override fun onDetachedFromWindow() { Player.unlisten(update); Accent.unlisten(update); super.onDetachedFromWindow() }

    private fun tint() {
        val a = Accent.color
        bg.colors = intArrayOf(Color.argb(95, Color.red(a), Color.green(a), Color.blue(a)), Colors.SURFACE2)
        bars.setColorFilter(a)
    }

    fun refresh() {
        val t = Player.current
        visibility = if (t == null) GONE else VISIBLE
        if (t == null) return
        if (shown != t.v) {
            shown = t.v
            title.text = t.title
            sub.text = t.artist
            Images.load(t.art, img, context.px(72)) { Accent.from(it) }
        }
        bars.visibility = if (Player.playing) VISIBLE else INVISIBLE
        playBtn.set(if (Player.playing) R.drawable.ic_pause else R.drawable.ic_play)
        tint()
    }
}

/** What selecting something does, everywhere in the app. */
object Go {
    /** A song in a row: that row from the song on, then more like it (finds keep finding, likes keep shuffling). */
    fun play(activity: Activity, row: Row?, track: Track) {
        val songs = row?.items?.filterIsInstance<Item.Song>()?.map { it.track } ?: listOf(track)
        val at = songs.indexOfFirst { it.v == track.v }.coerceAtLeast(0)
        val id = row?.id ?: ""
        val station = when {
            id == "fresh" || id == "deep" -> "fresh"
            id == "liked" || id == "forgotten" -> "likes"
            id.startsWith("kids-") && id != "kids-top" -> "kids:" + id.removePrefix("kids-")   // kids' songs lead only to kids' songs
            id.startsWith("kids") -> "kids"
            else -> "radio:${track.v}"
        }
        if (id.startsWith("pop-")) {                              // a chart plays in chart order, then its last song's radio
            Player.play(activity, songs.drop(at), 0, null)
            return nowPlaying(activity)
        }
        val queue = if (station.startsWith("radio:")) listOf(track) else songs.drop(at)   // the row from here, then the station
        Player.play(activity, queue, 0, station)
        nowPlaying(activity)
    }

    fun open(activity: Activity, card: Card) {
        when (card.type) {
            "station" -> { Player.play(activity, emptyList(), 0, card.id); nowPlaying(activity) }
            else -> start(activity, Intent(activity, DetailActivity::class.java)
                .putExtra("type", card.type).putExtra("id", card.id).putExtra("title", card.title))
        }
    }

    fun nowPlaying(activity: Activity) = start(activity, Intent(activity, NowPlayingActivity::class.java))

    /** Moving between Music's own screens isn't "leaving": only Home, another app or Back out of Music stops the music. */
    fun start(activity: Activity, intent: Intent) = activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION))

    /** Hold OK on a song: radio, its artist, its album, thumbs. */
    fun menu(activity: Activity, t: Track) {
        val options = mutableListOf<Pair<String, () -> Unit>>()
        options += "Play this song's radio" to { Player.play(activity, listOf(t), 0, "radio:${t.v}"); nowPlaying(activity) }
        t.artistId?.let { a -> options += "Go to ${t.artist.substringBefore(",")}" to { open(activity, Card("artist", a, t.artist, "", null, null)) } }
        t.albumId?.let { a -> options += "Go to the album" to { open(activity, Card("album", a, t.album ?: "", "", null, null)) } }
        options += (if (t.liked) "Remove thumbs up" else "Thumbs up (add to Liked songs)") to {
            Player.vote(activity, t, if (t.liked) "none" else "like")
            activity.toast(if (t.liked) "Added to Liked songs" else "Removed from Liked songs")
        }
        options += "Thumbs down (never play it again)" to { Player.vote(activity, t, "dislike"); activity.toast("Got it. It won't come up again.") }
        AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(t.title)
            .setItems(options.map { it.first }.toTypedArray()) { _, i -> options[i].second() }
            .show()
    }

    fun hold(activity: Activity, item: Item) {
        when (item) {
            is Item.Song -> menu(activity, item.track)
            is Item.Other -> open(activity, item.card)
        }
    }

    fun select(activity: Activity, row: Row?, item: Item) {
        when (item) {
            is Item.Song -> play(activity, row, item.track)
            is Item.Other -> open(activity, item.card)
        }
    }

    /** The remote's media keys work on every screen, even when nothing has the media session's attention. */
    fun mediaKey(activity: Activity, keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE ->
            { if (Player.current != null) Player.toggle(activity); Player.current != null }
        KeyEvent.KEYCODE_MEDIA_NEXT -> { Player.next(activity); true }
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { Player.prev(activity); true }
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { Player.seekBy(15000); true }
        KeyEvent.KEYCODE_MEDIA_REWIND -> { Player.seekBy(-15000); true }
        else -> false
    }
}

/** The app's mark: a rounded tile in the sunset gradient with five white equalizer bars, which move gently while music plays. */
class BrandMark(context: Context) : View(context) {
    private val tile = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val bar = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private var t = 0f
    private var last = 0L
    private val rest = floatArrayOf(0.42f, 0.72f, 1f, 0.62f, 0.36f)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        tile.shader = android.graphics.LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(Color.rgb(132, 86, 255), Color.rgb(214, 72, 190), Color.rgb(255, 128, 96)), null, android.graphics.Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val now = System.nanoTime()
        if (last != 0L && Player.playing) t += (now - last) / 1e9f
        last = now
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRoundRect(0f, 0f, w, h, w * 0.28f, w * 0.28f, tile)
        val bw = w * 0.09f
        val gap = w * 0.055f
        val left = (w - (5 * bw + 4 * gap)) / 2
        for (i in 0 until 5) {
            val move = if (Player.playing) 0.25f * kotlin.math.sin(t * (2.2f + i * 0.7f) + i) else 0f
            val bh = h * 0.56f * (rest[i] + move).coerceIn(0.2f, 1f)
            val x = left + i * (bw + gap)
            canvas.drawRoundRect(x, (h - bh) / 2, x + bw, (h + bh) / 2, bw / 2, bw / 2, bar)
        }
        if (Player.playing) postInvalidateDelayed(40) else postInvalidateDelayed(500)
    }
}
