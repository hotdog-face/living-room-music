package com.livingroomtv.music

import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.media.audiofx.Visualizer
import android.os.Build
import android.util.Log
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * What the player is sending to the speakers right now, from Android's Visualizer on our own audio session (sound
 * only; it needs the RECORD_AUDIO permission, which scripts/deploy-music.sh grants once with adb).
 */
object Viz {
    @Volatile var wave: ByteArray? = null
    @Volatile var fft: ByteArray? = null
    @Volatile var rateHz = 44100
    private var vis: Visualizer? = null
    private var session = -1
    var wanted = false
        set(v) { field = v; vis?.let { runCatching { it.enabled = v } } }

    fun attach(audioSession: Int) {
        if (audioSession == session && vis != null) return
        release()
        try {
            val v = Visualizer(audioSession)
            v.captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
            v.scalingMode = Visualizer.SCALING_MODE_NORMALIZED
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(vz: Visualizer, data: ByteArray, rate: Int) { wave = data.copyOf(); rateHz = rate / 1000 }
                override fun onFftDataCapture(vz: Visualizer, data: ByteArray, rate: Int) { fft = data.copyOf() }
            }, Visualizer.getMaxCaptureRate(), true, true)
            v.enabled = wanted
            vis = v
            session = audioSession
        } catch (e: Exception) {
            Log.w(TAG, "visualizer: $e (is RECORD_AUDIO granted?)")
        }
    }

    fun release() {
        vis?.let { runCatching { it.enabled = false; it.release() } }
        vis = null
        session = -1
        wave = null
        fft = null
    }

    /** [n] log-spaced bands from 32 Hz to 16 kHz, each in dB (RMS of the band's bins). */
    fun bands(n: Int): FloatArray {
        val f = fft ?: return FloatArray(n) { -80f }
        val bins = f.size / 2
        val hzPerBin = rateHz / 2f / bins
        return FloatArray(n) { i ->
            val lo = 32f * (16000f / 32f).pow(i / n.toFloat())
            val hi = 32f * (16000f / 32f).pow((i + 1) / n.toFloat())
            val a = max(1, (lo / hzPerBin).toInt())
            val b = max(a + 1, min(bins - 1, (hi / hzPerBin).toInt()))
            var e = 0.0
            for (k in a until b) { val m = hypot(f[2 * k].toDouble(), f[2 * k + 1].toDouble()); e += m * m }
            (10 * log10(max(e / (b - a), 1e-6) / (128.0 * 128.0)) + 9 * (i.toDouble() / n)).toFloat()   // tilted so highs show
        }
    }
}

/** The player's display: synced lyrics, or one of the four visualizers. */
enum class Display(val word: String) { LYRICS("Lyrics"), RIBBON("Ribbon"), AURORA("Aurora"), PARTICLES("Particles"), BARS("Bars") }

/** Blue and violet light, as in Mitchell's reference image. */
object Glow {
    val BLUE = Color.rgb(64, 120, 255)
    val CYAN = Color.rgb(96, 200, 255)
    val VIOLET = Color.rgb(124, 92, 255)
    val PURPLE = Color.rgb(180, 96, 255)
    val PINK = Color.rgb(236, 140, 255)
    val NIGHT = Color.rgb(4, 6, 22)
    val all = intArrayOf(BLUE, VIOLET, CYAN, PURPLE, PINK, BLUE)

    fun alpha(c: Int, a: Int) = Color.argb(a.coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

    fun mix(a: Int, b: Int, t: Float) = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt())

    /** Paint that adds light (overlaps get brighter, as real light does). */
    fun additive(p: Paint) = p.apply { if (Build.VERSION.SDK_INT >= 29) blendMode = BlendMode.PLUS }
}

/**
 * Draws every frame while shown, from the music: [level] holds [n] smoothed bands (0..1) in a 40 dB window that
 * follows the loudest band, so quiet and loud records both fill the screen; [bass], [mid], [high] and [beat] summarise them.
 */
abstract class Instrument(context: Context, private val n: Int = 48) : View(context) {
    private var last = 0L
    protected var dt = 1 / 60f
    protected var time = 0f
    protected val level = FloatArray(n)
    protected var bass = 0f
    protected var mid = 0f
    protected var high = 0f
    protected var beat = 0f                                      // 1 on a kick, fading out
    private var top = -30f
    private var bassAvg = 0f
    var running = false
        set(v) { field = v; last = 0L; if (v) postInvalidateOnAnimation() }

    final override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        dt = if (last == 0L) 1 / 60f else ((now - last) / 1e9f).coerceIn(0.001f, 0.1f)
        last = now
        time += dt
        listen()
        paint(canvas)
        if (running) postInvalidateOnAnimation()
    }

    private fun listen() {
        val db = Viz.bands(n)
        top = ease(top, db.maxOrNull() ?: -80f, 0.4f, 5f)
        for (i in 0 until n) level[i] = ease(level[i], ((db[i] - (top - 36f)) / 40f).coerceIn(0f, 1f), 0.05f, 0.35f)
        fun avg(a: Int, b: Int): Float { var s = 0f; for (i in a until b) s += level[i]; return s / (b - a) }
        bass = avg(0, n / 6)
        mid = avg(n / 6, n * 2 / 3)
        high = avg(n * 2 / 3, n)
        if (bass > bassAvg * 1.25f + 0.08f && beat < 0.3f) beat = 1f
        beat = max(0f, beat - dt * 2.5f)
        bassAvg = ease(bassAvg, bass, 0.4f, 0.4f)
    }

    /** The band level at [x] (0..1 across), interpolated. */
    protected fun at(x: Float): Float {
        val p = (x.coerceIn(0f, 1f) * (n - 1))
        val i = p.toInt().coerceAtMost(n - 2)
        val f = p - i
        return level[i] * (1 - f) + level[i + 1] * f
    }

    abstract fun paint(canvas: Canvas)

    protected fun ease(from: Float, to: Float, up: Float, down: Float): Float {
        val tau = if (to > from) up else down
        return from + (to - from) * (1 - exp(-dt / tau))
    }

    protected fun night(canvas: Canvas, p: Paint) {
        p.shader = RadialGradient(width / 2f, height / 2f, max(width, height) * 0.75f,
            Color.rgb(14, 18, 52), Glow.NIGHT, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
        p.shader = null
    }
}

/**
 * Layers of glowing translucent ribbon rising from a bright horizon line and mirrored below it, like light on water:
 * each layer follows the music's bands with its own slow sway, so the shape is always moving but never jumpy.
 */
class Ribbon(context: Context) : Instrument(context) {
    private val bg = Paint()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)               // plain blending: the TV's GPU skips additive paths
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val line = Glow.additive(Paint(Paint.ANTI_ALIAS_FLAG))
    private val path = Path()
    private val curve = Path()                                   // just the wave's top edge, for the glowing outline
    private val fade = Paint()
    private val pts = 96

    override fun paint(canvas: Canvas) {
        night(canvas, bg)
        val w = width.toFloat()
        val cy = height * 0.55f
        val maxH = height * 0.36f
        val left = -w * 0.02f                                     // past both edges: no end to the ribbon on screen
        val span = w * 1.04f
        val layers = 7
        for (layer in 0 until layers) {
            val col = Glow.all[layer % Glow.all.size]
            val phase = time * (0.35f + layer * 0.07f) + layer * 1.7f
            val k = 1.6f + layer * 0.55f
            val ys = FloatArray(pts)
            for (j in 0 until pts) {
                val x = j / (pts - 1f)
                val env = 0.25f + 0.75f * sin(PI.toFloat() * x).coerceAtLeast(0f).pow(1.2f)   // never 0 (sin(π) dips below 0: NaN)
                val band = at(((x - 0.5f) * (0.8f + layer * 0.06f) + 0.5f + 0.04f * sin(phase * 0.5f)).coerceIn(0f, 1f))
                val sway = 0.55f + 0.45f * sin(x * k * 2 * PI.toFloat() + phase)
                ys[j] = maxH * env * (0.12f + 0.95f * band) * sway * (0.75f + 0.25f * beat)
            }
            for (mirror in 0..1) {
                val dir = if (mirror == 0) -1f else 1f
                val scale = if (mirror == 0) 1f else 0.7f
                val bright = if (mirror == 0) 1f else 0.5f
                curve.reset()
                curve.moveTo(left, cy)
                var px = left
                var py = cy
                for (j in 0 until pts) {
                    val x = left + span * j / (pts - 1f)
                    val y = cy + dir * ys[j] * scale
                    curve.quadTo(px, py, (px + x) / 2, (py + y) / 2)
                    px = x; py = y
                }
                path.set(curve)
                path.lineTo(left + span, cy)
                path.close()
                val peak = cy + dir * maxH * scale
                fill.shader = LinearGradient(0f, peak, 0f, cy, Glow.alpha(col, (70 * bright).toInt()), Glow.alpha(col, (14 * bright).toInt()), Shader.TileMode.CLAMP)
                canvas.drawPath(path, fill)
                edge.color = Glow.alpha(col, (40 * bright).toInt())
                edge.strokeWidth = height * 0.018f
                canvas.drawPath(curve, edge)                     // the soft glow around the edge
                edge.color = Glow.alpha(Glow.mix(col, Color.WHITE, 0.35f), (150 * bright).toInt())
                edge.strokeWidth = max(1.5f, height * 0.0025f)
                canvas.drawPath(curve, edge)                     // the bright filament
            }
        }
        line.shader = LinearGradient(0f, 0f, w, 0f,
            intArrayOf(Glow.alpha(Glow.CYAN, 120), Glow.alpha(Glow.CYAN, 200), Glow.alpha(Color.WHITE, 230), Glow.alpha(Glow.VIOLET, 200), Glow.alpha(Glow.VIOLET, 120)),
            floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, cy - height * 0.0025f, w, cy + height * 0.0025f, line)
        line.shader = RadialGradient(w / 2, cy, w * 0.5f, Glow.alpha(Glow.VIOLET, (40 + 60 * bass).toInt()), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, height.toFloat(), line)
        val edge = w * 0.16f                                     // dissolve into the dark at both sides (no hard edge)
        fade.shader = LinearGradient(0f, 0f, edge, 0f, Glow.NIGHT, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, edge, height.toFloat(), fade)
        fade.shader = LinearGradient(w - edge, 0f, w, 0f, Color.TRANSPARENT, Glow.NIGHT, Shader.TileMode.CLAMP)
        canvas.drawRect(w - edge, 0f, w, height.toFloat(), fade)
    }
}

/**
 * Soft fog of light drifting slowly, in the album's own colours: the same palette the living room lights are set to,
 * so the screen and the room match. Each cloud swells and brightens with its part of the music.
 */
class Aurora(context: Context) : Instrument(context) {
    private val bg = Paint()
    private val blob = Glow.additive(Paint(Paint.ANTI_ALIAS_FLAG))
    private val clouds = List(7) { i -> floatArrayOf(Random.nextFloat(), Random.nextFloat(), 0.05f + Random.nextFloat() * 0.08f, Random.nextFloat() * 6f, i.toFloat()) }

    override fun paint(canvas: Canvas) {
        val pal = Accent.palette
        val cols = intArrayOf(pal[0], Accent.color, pal[1], pal[2], pal[0], Accent.color, pal[1])
        val deep = Glow.mix(Color.BLACK, pal[0], 0.16f)          // a deep shade of the album, not night blue
        bg.shader = RadialGradient(width / 2f, height / 2f, max(width, height) * 0.75f, Glow.mix(Color.BLACK, pal[0], 0.24f), deep, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        val w = width.toFloat()
        val h = height.toFloat()
        for (c in clouds) {
            val (fx, fy, speed, seed, idx) = c.toList()
            val x = w * (0.15f + 0.7f * (0.5f + 0.5f * sin(time * speed + seed) * 0.9f + (fx - 0.5f) * 0.4f).coerceIn(0f, 1f))
            val y = h * (0.2f + 0.6f * (0.5f + 0.5f * cos(time * speed * 0.8f + seed * 1.3f) * 0.8f + (fy - 0.5f) * 0.3f).coerceIn(0f, 1f))
            val part = when (idx.toInt() % 3) { 0 -> bass; 1 -> mid; else -> high }
            val r = max(w, h) * (0.16f + 0.30f * part + 0.06f * beat)
            val col = cols[idx.toInt() % cols.size]
            blob.shader = RadialGradient(x, y, r, intArrayOf(Glow.alpha(col, (70 + 110 * part).toInt()), Glow.alpha(col, (20 + 30 * part).toInt()), Color.TRANSPARENT),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(x, y, r, blob)
        }
    }
}

/** A field of tiny lights flying gently toward you, faster and brighter as the music swells, flashing on the kick. */
class Particles(context: Context) : Instrument(context) {
    private val bg = Paint()
    private val dot = Glow.additive(Paint(Paint.ANTI_ALIAS_FLAG))
    private val core = Glow.additive(Paint(Paint.ANTI_ALIAS_FLAG))
    private val count = 520
    private val px = FloatArray(count) { Random.nextFloat() * 2 - 1 }
    private val py = FloatArray(count) { Random.nextFloat() * 2 - 1 }
    private val pz = FloatArray(count) { Random.nextFloat() }
    private val hue = FloatArray(count) { Random.nextFloat() }

    override fun paint(canvas: Canvas) {
        night(canvas, bg)
        val cx = width / 2f
        val cy = height / 2f
        val energy = 0.5f * bass + 0.3f * mid + 0.2f * high
        core.shader = RadialGradient(cx, cy, height * (0.35f + 0.25f * bass), Glow.alpha(Glow.VIOLET, (60 + 120 * beat).toInt()), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, height * (0.35f + 0.25f * bass), core)
        val speed = 0.06f + 0.55f * energy + 0.4f * beat
        val scale = height * 0.5f
        for (i in 0 until count) {
            pz[i] -= dt * speed
            if (pz[i] <= 0.03f) { pz[i] = 1f; px[i] = Random.nextFloat() * 2 - 1; py[i] = Random.nextFloat() * 2 - 1 }
            val z = pz[i]
            val sx = cx + px[i] / z * scale
            val sy = cy + py[i] / z * scale
            if (sx < 0 || sx > width || sy < 0 || sy > height) { pz[i] = 1f; continue }
            val near = 1 - z
            val r = height * (0.0012f + 0.006f * near * near) * (1 + 0.8f * beat)
            val col = Glow.mix(Glow.BLUE, if (hue[i] > 0.5f) Glow.PURPLE else Glow.CYAN, hue[i])
            dot.color = Glow.alpha(col, (40 + 215 * near * (0.6f + 0.4f * energy)).toInt())
            canvas.drawCircle(sx, sy, r, dot)
            if (near > 0.6f) { dot.color = Glow.alpha(col, (30 * near).toInt()); canvas.drawCircle(sx, sy, r * 3.2f, dot) }
        }
    }
}

/**
 * Clean rounded bars across the whole width, mirrored about the middle (bass in the centre, highs out to the sides),
 * blue at the centre line fading to violet at the tips; they fade away softly at the screen's edges instead of stopping.
 */
class Bars(context: Context) : Instrument(context, 40) {
    private val bg = Paint()
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Glow.additive(Paint(Paint.ANTI_ALIAS_FLAG))
    private val count = 72

    override fun paint(canvas: Canvas) {
        night(canvas, bg)
        val col = width.toFloat() / count
        val bw = col * 0.44f
        val cy = height / 2f
        val maxH = height * 0.34f
        bar.shader = LinearGradient(0f, cy - maxH, 0f, cy + maxH, intArrayOf(Glow.PURPLE, Glow.BLUE, Glow.CYAN, Glow.BLUE, Glow.PURPLE),
            floatArrayOf(0f, 0.35f, 0.5f, 0.65f, 1f), Shader.TileMode.CLAMP)
        val half = count / 2f
        for (i in 0 until count) {
            val centre = i + 0.5f
            val fromMiddle = kotlin.math.abs(centre - half) / half          // 0 in the middle, 1 at the edges
            val v = at(fromMiddle)
            val edge = ((1f - fromMiddle) / 0.18f).coerceIn(0f, 1f)        // the outer 18% fades to nothing
            val fade = edge * edge * (3 - 2 * edge)
            val h = max(bw * 0.5f, maxH * v * (0.35f + 0.65f * fade))
            val x = i * col + (col - bw) / 2
            bar.alpha = (255 * fade).toInt()
            canvas.drawRoundRect(x, cy - h, x + bw, cy + h, bw / 2, bw / 2, bar)
            glow.color = Glow.alpha(Glow.VIOLET, (22 * v * fade).toInt())
            canvas.drawRoundRect(x - bw, cy - h - bw, x + 2 * bw, cy + h + bw, bw, bw, glow)
        }
    }
}
