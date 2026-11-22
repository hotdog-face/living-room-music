package com.livingroomtv.music

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign

/** One CD case on the shelf: an album Mitchell ripped (cd/cdrip.py). [id] is its Plex album ("plex:123"), empty
 *  while Plex is still adding it. [art] has "front", and "back" / "disc" when the Cover Art Archive had them. */
data class Cd(
    val id: String, val title: String, val artist: String, val year: String, val label: String, val discs: Int,
    val ripped: Int, val tracks: List<String>, val added: Long, val art: Map<String, String>, val needsInfo: Boolean,
    val verified: String,
) {
    val key get() = "$artist|$title"
}

/** What the music server's CD drive is doing: idle, reading, looking, ripping (track/tracks/percent), saving, done, error. */
data class Drive(val state: String, val message: String, val percent: Int, val plugged: Boolean)

object CdApi {
    fun load(): Pair<List<Cd>, Drive> {
        val j = Api.raw("/cds")
        val a = j.optJSONArray("cds")
        val cds = if (a == null) emptyList() else List(a.length()) { i ->
            val c = a.getJSONObject(i)
            val art = c.optJSONObject("art") ?: JSONObject()
            val t = c.optJSONArray("tracks")
            Cd(c.str("id"), c.str("title"), c.str("artist"), c.str("year"), c.str("label"), c.optInt("discs", 1),
                c.optInt("ripped", 1), if (t == null) emptyList() else List(t.length()) { t.optString(it) },
                c.optLong("added"), art.keys().asSequence().associateWith { art.str(it) }, c.optBoolean("needs_info"),
                c.str("verified"))
        }
        val s = j.optJSONObject("status") ?: JSONObject()
        return cds to Drive(s.str("state").ifEmpty { "idle" }, s.str("message"), s.optInt("percent"), s.optBoolean("drive"))
    }
}

/**
 * The CDs tab: Mitchell's CD collection as jewel cases in a cover flow. Left/right flips through them (hold to go
 * faster), OK opens the album, Play plays it, holding OK turns the case over to its back. Along the top: how many CDs,
 * the sort (A–Z or newest), and what the drive is ripping right now. New discs show up by themselves.
 */
class CdShelf(private val activity: Activity) : LinearLayout(activity) {
    private val count = activity.label(21, Colors.DIM, bold = true)
    private val drive = activity.label(21, Colors.TEXT, bold = true)
    private val bar = View(activity)
    private val driveBox = LinearLayout(activity)
    private val flow = CoverFlow(activity, ::opened, ::moved)
    private val title = activity.label(44, Colors.TEXT, heavy = true, lines = 1)
    private val sub = activity.label(24, Colors.DIM, lines = 1)
    private val small = activity.label(19, Colors.FAINT, lines = 1)
    private val empty = activity.label(28, Colors.DIM, lines = 4)
    private val sorts = LinearLayout(activity)
    private var all: List<Cd> = emptyList()
    private var sort = activity.getPreferences(Context.MODE_PRIVATE).getString("cd-sort", "new") ?: "new"
    private var lastState = ""
    private val poll = object : Runnable {
        override fun run() { refresh(); postDelayed(this, if (lastState in setOf("reading", "looking", "ripping", "saving")) 4000 else 15000) }
    }

    init {
        orientation = VERTICAL
        clipChildren = false
        val top = LinearLayout(activity).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.px(96), activity.px(26), activity.px(96), 0)
        }
        top.addView(count, LayoutParams(-2, -2).apply { marginEnd = activity.px(28) })
        sorts.orientation = HORIZONTAL
        for ((id, word) in listOf("new" to "Newest", "az" to "A–Z")) sorts.addView(pill(id, word))
        top.addView(sorts)
        top.addView(activity.label(19, Colors.TEXT, bold = true, text = "★  Weakest songs").apply {   // for curating: lowest-rated first
            isFocusable = true
            setPadding(activity.px(22), activity.px(10), activity.px(22), activity.px(10))
            background = GradientDrawable().apply { cornerRadius = activity.px(24).toFloat(); setColor(Colors.SURFACE) }
            setOnFocusChangeListener { v, has ->
                ((v as TextView).background as GradientDrawable).setColor(if (has) Color.WHITE else Colors.SURFACE)
                v.setTextColor(if (has) Colors.INK else Colors.TEXT)
            }
            setOnClickListener { Go.open(activity, Card("playlist", "cd-weakest", "Weakest songs on your CDs", "", null, null)) }
        }, LayoutParams(-2, -2).apply { marginStart = activity.px(20) })
        top.addView(View(activity), LayoutParams(0, 1, 1f))
        driveBox.apply {
            orientation = VERTICAL
            setPadding(activity.px(26), activity.px(14), activity.px(26), activity.px(14))
            background = GradientDrawable().apply { cornerRadius = activity.px(22).toFloat(); setColor(Colors.SURFACE) }
            addView(drive, LayoutParams(-2, -2))
            addView(FrameLayout(activity).apply {
                background = GradientDrawable().apply { cornerRadius = activity.px(3).toFloat(); setColor(Colors.SURFACE2) }
                addView(bar, FrameLayout.LayoutParams(0, -1))
            }, LayoutParams(-1, activity.px(6)).apply { topMargin = activity.px(10) })
            visibility = GONE
        }
        top.addView(driveBox, LayoutParams(-2, -2))
        addView(top, LayoutParams(-1, -2))
        addView(flow, LayoutParams(-1, activity.px(600)).apply { topMargin = activity.px(10) })
        val info = LinearLayout(activity).apply { orientation = VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        for (v in listOf(title, sub, small)) { v.gravity = Gravity.CENTER; info.addView(v, LayoutParams(-1, -2).apply { topMargin = activity.px(8) }) }
        addView(info, LayoutParams(-1, -2).apply { setMargins(activity.px(200), 0, activity.px(200), 0) })
        empty.gravity = Gravity.CENTER
        empty.visibility = GONE
        addView(empty, LayoutParams(-1, -2).apply { setMargins(activity.px(260), activity.px(-420), activity.px(260), 0) })
        paintPills()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(poll) }
    override fun onDetachedFromWindow() { removeCallbacks(poll); super.onDetachedFromWindow() }

    fun focusFlow() = flow.requestFocus()

    private fun pill(id: String, word: String) = activity.label(19, Colors.TEXT, bold = true, text = word).apply {
        tag = id
        isFocusable = true
        setPadding(activity.px(22), activity.px(10), activity.px(22), activity.px(10))
        background = GradientDrawable().apply { cornerRadius = activity.px(24).toFloat() }
        setOnClickListener {
            sort = id
            activity.getPreferences(Context.MODE_PRIVATE).edit().putString("cd-sort", id).apply()
            paintPills()
            show(all, keep = null)
        }
        setOnFocusChangeListener { _, _ -> paintPills() }
    }

    private fun paintPills() {
        for (i in 0 until sorts.childCount) {
            val v = sorts.getChildAt(i) as TextView
            val on = v.tag == sort
            (v.background as GradientDrawable).setColor(if (v.hasFocus()) Color.WHITE else if (on) Colors.SURFACE2 else Color.TRANSPARENT)
            v.setTextColor(if (v.hasFocus()) Colors.INK else if (on) Colors.TEXT else Colors.DIM)
            (v.layoutParams as? LayoutParams)?.let { if (it.marginEnd == 0) { it.marginEnd = activity.px(8); v.layoutParams = it } }
        }
    }

    private fun refresh() {
        Bg.run({ CdApi.load() }) { r ->
            r.onSuccess { (cds, d) ->
                lastState = d.state
                showDrive(d)
                if (cds.map { it.key + it.id + it.art.size } != all.map { it.key + it.id + it.art.size }) {
                    val newest = cds.maxByOrNull { it.added }
                    val fresh = all.isNotEmpty() && newest != null && all.none { it.key == newest.key }
                    show(cds, keep = if (fresh) newest!!.key else flow.current()?.key)   // a disc just finished: show it
                }
            }.onFailure {
                if (all.isEmpty()) { empty.text = it.message ?: "Couldn't reach the music server"; empty.visibility = VISIBLE }
            }
        }
    }

    private fun show(cds: List<Cd>, keep: String?) {
        all = cds
        val sorted = if (sort == "az") cds.sortedWith(compareBy({ sortName(it.artist) }, { it.year }, { it.title.lowercase() }))
                     else cds.sortedByDescending { it.added }
        flow.set(sorted, keep)
        count.text = when (cds.size) { 0 -> "No CDs yet"; 1 -> "1 CD"; else -> "${cds.size} CDs" }
        empty.visibility = if (cds.isEmpty()) VISIBLE else GONE
        if (cds.isEmpty()) {
            empty.text = "Put a CD in the music server's drive.\nIt rips by itself, then shows up here as a CD on your shelf."
            title.text = ""; sub.text = ""; small.text = ""
        }
    }

    private fun showDrive(d: Drive) {
        val busy = d.state in setOf("reading", "looking", "ripping", "saving")
        val text = when {
            d.message.isNotEmpty() && d.state != "idle" -> d.message
            !d.plugged -> "CD drive not plugged in"
            else -> ""
        }
        driveBox.visibility = if (text.isEmpty()) GONE else VISIBLE
        drive.text = (if (busy) "●  " else "") + text
        drive.setTextColor(when (d.state) { "error" -> Color.rgb(255, 140, 120); "done" -> Colors.TEXT; else -> if (busy) Colors.TEXT else Colors.DIM })
        bar.parent?.let { (it as View).visibility = if (d.state == "ripping") VISIBLE else GONE }
        bar.setBackgroundColor(Accent.color)
        bar.post {
            val w = (bar.parent as View).width
            bar.layoutParams = (bar.layoutParams as FrameLayout.LayoutParams).apply { width = w * d.percent.coerceIn(0, 100) / 100 }
        }
    }

    private fun moved(cd: Cd?) {
        if (cd == null) return
        title.text = cd.title
        sub.text = listOf(cd.artist, cd.year, if (cd.discs > 1) "${cd.discs} discs" else "").filter { it.isNotEmpty() }.joinToString("  ·  ")
        small.text = listOf(
            if (cd.tracks.isNotEmpty()) "${cd.tracks.size} songs" else "",
            if (cd.discs > 1 && cd.ripped < cd.discs) "${cd.ripped} of ${cd.discs} discs ripped" else "",
            cd.label, cd.verified,
            if (cd.needsInfo) "Needs a name" else "",
            if (cd.id.isEmpty()) "Adding to Plex…" else "",
        ).filter { it.isNotEmpty() }.joinToString("  ·  ")
    }

    private fun opened(cd: Cd, play: Boolean) {
        if (cd.id.isEmpty()) { activity.toast("Plex is still adding this one. Try again in a minute."); return }
        if (!play) { Go.open(activity, Card("album", cd.id, cd.title, cd.artist, cd.art["front"], null)); return }
        Bg.run({ Api.list("album", cd.id) }) { r ->
            r.onSuccess { p -> if (p.tracks.isNotEmpty()) { Player.play(activity, p.tracks, 0, null); Go.nowPlaying(activity) } }
                .onFailure { activity.toast(it.message ?: "Couldn't play it") }
        }
    }

    private fun sortName(s: String) = s.lowercase().removePrefix("the ").trim()
}

/**
 * The cover flow itself: the chosen CD faces you in the middle, big; the others stand angled on each side, getting
 * darker further out, each with a reflection on the shiny floor. A soft glow behind the middle CD takes its colours.
 * Drawn with android.graphics.Camera (3-D turn about each case's middle); positions glide with a short animation.
 */
class CoverFlow(context: Context, private val onOpen: (Cd, Boolean) -> Unit, private val onMove: (Cd?) -> Unit) : View(context) {
    private var cds: List<Cd> = emptyList()
    private var pos = 0f                                          // where the flow is now (fractional while moving)
    private var target = 0
    private var anim: ValueAnimator? = null
    private var flip = 0f                                         // 0 = front, 1 = turned over to the back
    private var flipAnim: ValueAnimator? = null
    private val caseSize = context.px(420)
    private val cases = object : android.util.LruCache<String, Bitmap>(28) {}
    private val loading = HashSet<String>()
    private val camera = Camera()
    private val m = Matrix()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private var glowColor = Color.rgb(40, 40, 48)
    private var heldOk = false

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        camera.setLocation(0f, 0f, -24f)                          // a far camera: gentle perspective on a big screen
    }

    fun current(): Cd? = cds.getOrNull(target)

    fun set(list: List<Cd>, keep: String?) {
        cds = list
        target = list.indexOfFirst { it.key == keep }.takeIf { it >= 0 } ?: target.coerceIn(0, (list.size - 1).coerceAtLeast(0))
        pos = target.toFloat()
        flip = 0f
        cases.evictAll()                                          // art may have arrived for a new disc
        loading.clear()
        onMove(current())
        invalidate()
    }

    private fun go(to: Int, fast: Boolean = false) {
        val t = to.coerceIn(0, (cds.size - 1).coerceAtLeast(0))
        if (t == target) return
        target = t
        if (flip > 0f) unflip()
        anim?.cancel()
        anim = ValueAnimator.ofFloat(pos, t.toFloat()).apply {
            duration = if (fast) 120 else 320
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { pos = it.animatedValue as Float; invalidate() }
            start()
        }
        onMove(current())
    }

    private fun unflip() = turn(0f)
    private fun turn(to: Float) {
        flipAnim?.cancel()
        flipAnim = ValueAnimator.ofFloat(flip, to).apply {
            duration = 520
            interpolator = DecelerateInterpolator()
            addUpdateListener { flip = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (cds.isEmpty()) return super.onKeyDown(keyCode, event)
        val step = when {                                         // hold to speed up through a big shelf
            event.repeatCount > 24 -> 5
            event.repeatCount > 8 -> 2
            else -> 1
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { go(target - step, event.repeatCount > 0); true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { go(target + step, event.repeatCount > 0); true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { go(target - 10, true); true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { go(target + 10, true); true }
            KeyEvent.KEYCODE_MOVE_HOME -> { go(0); true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (event.repeatCount == 0) { event.startTracking(); heldOk = false }
                true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ->
                if (Player.current == null) { current()?.let { onOpen(it, true) }; true } else super.onKeyDown(keyCode, event)
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            heldOk = true
            turn(if (flip > 0.5f) 0f else 1f)                     // hold OK: turn the case over (and back)
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) && cds.isNotEmpty()) {
            if (!heldOk && !event.isCanceled) current()?.let { onOpen(it, false) }
            heldOk = false
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); tint() }

    private fun tint() {
        if (width == 0) return
        glow.shader = RadialGradient(width / 2f, height * 0.42f, width * 0.36f,
            intArrayOf(Color.argb(150, Color.red(glowColor), Color.green(glowColor), Color.blue(glowColor)), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        if (cds.isEmpty()) return
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), glow)
        val cx = width / 2f
        val cy = context.px(40) + caseSize / 2f
        val first = (pos - 7).toInt().coerceAtLeast(0)
        val last = (pos + 7).toInt().coerceAtMost(cds.size - 1)
        val order = (first..last).sortedByDescending { abs(it - pos) }   // far ones first, the middle one on top
        for (i in order) {
            val d = i - pos
            val ad = abs(d)
            val near = min(ad, 1f)
            val x = cx + sign(d) * (near * caseSize * 0.62f + (ad - near).coerceAtLeast(0f) * caseSize * 0.27f)
            val angle = -sign(d) * near * 62f
            val scale = 1f - near * 0.16f
            val bmp = caseFor(cds[i], back = false) ?: continue
            camera.save()
            if (ad < 0.5f && flip > 0f) camera.rotateY(180f * flip)
            camera.rotateY(angle)
            camera.getMatrix(m)
            camera.restore()
            val showBack = ad < 0.5f && flip > 0.5f
            val img = if (showBack) caseFor(cds[i], back = true) ?: bmp else bmp
            m.preScale(if (showBack) -1f else 1f, 1f, img.width / 2f, 0f)   // the back reads the right way round
            m.preTranslate(-img.width / 2f, -caseSize / 2f)
            m.postScale(scale, scale)
            m.postTranslate(x, cy)
            val shade = (255 * (1f - 0.55f * min(ad / 3f, 1f))).toInt()
            paint.colorFilter = android.graphics.LightingColorFilter(Color.rgb(shade, shade, shade), 0)
            paint.alpha = if (ad > 6f) ((7f - ad) * 255).toInt().coerceIn(0, 255) else 255
            canvas.drawBitmap(img, m, paint)
        }
        val now = cds.getOrNull(target)
        if (now != null && cases.get(now.key + "|f") != null && pendingGlow != now.key) {
            pendingGlow = now.key
            glowColor = average(cases.get(now.key + "|f"))
            tint()
            postInvalidate()
        }
        // focus: a thin accent line under the middle case
        if (hasFocus()) {
            paint.colorFilter = null; paint.alpha = 255
            val r = RectF(cx - context.px(60), cy + caseSize / 2f + context.px(118), cx + context.px(60), cy + caseSize / 2f + context.px(124))
            paint.color = Accent.color
            canvas.drawRoundRect(r, r.height(), r.height(), paint)
        }
    }

    private var pendingGlow: String? = null

    private fun average(b: Bitmap): Int {
        val s = Bitmap.createScaledBitmap(b, 8, 8, true)
        var r = 0; var g = 0; var bl = 0
        for (y in 0 until 6) for (x in 0 until 8) { val c = s.getPixel(x, y); r += Color.red(c); g += Color.green(c); bl += Color.blue(c) }
        return Color.rgb(r / 48, g / 48, bl / 48)
    }

    /** The case picture (with its reflection), drawn once per CD; the art loads in the background. */
    private fun caseFor(cd: Cd, back: Boolean): Bitmap? {
        val k = cd.key + if (back) "|b" else "|f"
        cases.get(k)?.let { return it }
        val url = if (back) cd.art["back"] ?: return null else cd.art["front"]
        if (k in loading) return if (back) null else placeholder(cd)
        loading += k
        Bg.go {
            val art = url?.let { Images.get(it, caseSize) }
            val bmp = drawCase(art, cd, back)
            post { cases.put(k, bmp); loading -= k; invalidate() }
        }
        return if (back) null else placeholder(cd)
    }

    private var blank: Bitmap? = null
    private fun placeholder(cd: Cd): Bitmap = blank ?: drawCase(null, null, false).also { blank = it }   // an empty case while the art loads

    /**
     * A jewel case: the cover under clear plastic, the hinge down the left side, a glint across the front, and a
     * fading reflection under it. No art yet (or none found): a dark case with the album and artist printed on it.
     */
    private fun drawCase(art: Bitmap?, cd: Cd?, back: Boolean): Bitmap {
        val w = (caseSize * 1.08f).toInt()
        val h = caseSize
        val reflect = (h * 0.32f).toInt()
        val out = Bitmap.createBitmap(w, h + reflect + 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val hinge = (w - h).toFloat()
        val radius = h * 0.012f
        // the case body
        p.color = Color.rgb(18, 18, 22)
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radius, radius, p)
        // the cover (a back cover is wider in real life; here it fills the same window)
        val window = RectF(hinge, h * 0.012f, w - h * 0.012f, h - h * 0.012f)
        if (art != null) {
            val src = Rect(0, 0, art.width, art.height)
            val ratio = window.width() / window.height()
            if (art.width.toFloat() / art.height > ratio) { val cw = (art.height * ratio).toInt(); src.left = (art.width - cw) / 2; src.right = src.left + cw }
            else { val ch = (art.width / ratio).toInt(); src.top = (art.height - ch) / 2; src.bottom = src.top + ch }
            c.drawBitmap(art, src, window, p)
        } else {
            p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), Color.rgb(46, 46, 56), Color.rgb(24, 24, 30), Shader.TileMode.CLAMP)
            c.drawRect(window, p)
            p.shader = null
        }
        if (art == null && cd != null) {
            p.color = Colors.TEXT
            p.textSize = h * 0.075f
            p.typeface = Fonts.bold
            p.textAlign = Paint.Align.CENTER
            c.drawText(cd.title.take(22), window.centerX(), window.centerY() - h * 0.02f, p)
            p.color = Colors.DIM
            p.textSize = h * 0.05f
            p.typeface = Fonts.regular
            c.drawText(cd.artist.take(30), window.centerX(), window.centerY() + h * 0.07f, p)
        }
        // the hinge: ridged plastic down the spine
        p.shader = LinearGradient(0f, 0f, hinge, 0f, intArrayOf(Color.rgb(70, 70, 78), Color.rgb(28, 28, 32), Color.rgb(54, 54, 60)),
            null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, hinge, h.toFloat(), p)
        p.shader = null
        p.color = Color.argb(60, 255, 255, 255)
        var y = h * 0.04f
        while (y < h * 0.96f) { c.drawRect(hinge * 0.3f, y, hinge * 0.7f, y + 1.5f, p); y += h * 0.018f }
        // clear plastic: a soft glint across the front and a bright edge
        p.shader = LinearGradient(0f, 0f, w * 0.9f, h.toFloat(),
            intArrayOf(Color.argb(70, 255, 255, 255), Color.argb(10, 255, 255, 255), Color.TRANSPARENT, Color.argb(26, 255, 255, 255)),
            floatArrayOf(0f, 0.35f, 0.62f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(hinge, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = h * 0.005f
        p.color = Color.argb(90, 255, 255, 255)
        c.drawRoundRect(RectF(1f, 1f, w - 1f, h - 1f), radius, radius, p)
        p.style = Paint.Style.FILL
        // reflection: the case upside down, fading into the floor
        val r = Matrix().apply { preScale(1f, -1f); postTranslate(0f, h * 2f + 2) }
        val sc = c.saveLayer(0f, h + 2f, w.toFloat(), (h + 2 + reflect).toFloat(), null)
        c.drawBitmap(Bitmap.createBitmap(out, 0, 0, w, h), r, p)
        p.shader = LinearGradient(0f, h + 2f, 0f, (h + 2 + reflect).toFloat(), Color.argb(90, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        c.drawRect(0f, h + 2f, w.toFloat(), (h + 2 + reflect).toFloat(), p)
        p.xfermode = null
        p.shader = null
        c.restoreToCount(sc)
        return out
    }
}
