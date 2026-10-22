package com.livingroomtv.music

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import android.widget.ImageView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

const val TAG = "LivingRoomMusic"

/**
 * Where the music server is (server/music.py on the home server) and its shared secret. scripts/deploy-music.sh pushes
 * {"server": "http://<server-ip>:8097", "secret": "..."} to /sdcard/Android/data/com.livingroomtv.music/files/config.json.
 */
object Config {
    var server: String? = null; private set
    var secret: String? = null; private set
    var room: String = "living room"; private set               // only the living room's Shield colours the living room lights

    fun load(context: Context) {
        try {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val j = JSONObject(File(dir, "config.json").readText())
            server = j.optString("server").trimEnd('/').takeIf { it.isNotEmpty() }
            secret = j.optString("secret").takeIf { it.isNotEmpty() }
            room = j.optString("room", "living room")
        } catch (e: Exception) {
            Log.w(TAG, "no config.json yet: $e")
        }
    }
}

/** A song. [reason] is discovery's "Because you like ..."; [liked] is its thumbs-up state. */
data class Track(
    val v: String, val title: String, val artist: String, val artistId: String?, val album: String?, val albumId: String?,
    val art: String?, val length: Int, val reason: String?, var liked: Boolean,
    val quality: String? = null,                                  // "FLAC · 24-bit / 96 kHz" for the record shelf
    val plex: String? = null,                                     // you own it: play this Plex file instead (lossless)
) {
    fun json(): JSONObject = JSONObject().put("type", "track").put("v", v).put("title", title).put("artist", artist)
        .put("artistId", artistId).put("album", album).put("albumId", albumId).put("art", art).put("length", length)
        .put("reason", reason).put("liked", liked).put("quality", quality).put("plex", plex)

    companion object {
        fun from(j: JSONObject) = Track(
            j.getString("v"), j.str("title"), j.str("artist"), j.str("artistId").ifEmpty { null },
            j.str("album").ifEmpty { null }, j.str("albumId").ifEmpty { null }, j.str("art").ifEmpty { null },
            j.optInt("length"), j.str("reason").ifEmpty { null }, j.optBoolean("liked"), j.str("quality").ifEmpty { null },
            j.str("plex").ifEmpty { null },
        )
    }
}

/** Anything else a row can hold: a playlist, album, artist or station. */
data class Card(val type: String, val id: String, val title: String, val subtitle: String, val art: String?, val reason: String?)

/** One row's item: a song or a card. */
sealed class Item {
    data class Song(val track: Track) : Item()
    data class Other(val card: Card) : Item()
}

/** A song's lyrics: [synced] lines carry their start time in ms. */
data class Lyrics(val synced: Boolean, val lines: List<Pair<Int, String>>, val source: String)

/** About what's playing: the album's (or else the artist's) Wikipedia summary. */
data class Story(val kind: String, val title: String, val text: String)

data class Row(val id: String, val title: String, val items: List<Item>, val more: Card? = null)

data class Page(val title: String, val subtitle: String, val art: String?, val tracks: List<Track>, val rows: List<Row>)

fun JSONObject.str(key: String) = optString(key).takeIf { it != "null" } ?: ""

class ServerDown : Exception("Can't reach the music server")

/** Calls to the music server. All of them block: run them off the main thread ([Bg]). */
object Api {
    fun home(): Pair<Boolean, List<Row>> = get("/home").let { it.optBoolean("signedIn") to rows(it.optJSONArray("rows")) }
    fun list(type: String, id: String): Page = page(get("/list?type=$type&id=${enc(id)}"))
    fun artist(id: String): Page = page(get("/artist?id=${enc(id)}"))
    fun search(q: String): List<Row> = rows(get("/search?q=${enc(q)}").optJSONArray("rows"))
    fun shelf(): List<Row> = rows(get("/shelf").optJSONArray("rows"))
    fun kids(): List<Row> = rows(get("/kids").optJSONArray("rows"))
    fun popular(): List<Row> = rows(get("/popular").optJSONArray("rows"))
    fun mood(id: String, title: String): Page = page(get("/mood?id=${enc(id)}&title=${enc(title)}"))

    fun lyrics(t: Track): Lyrics {
        val j = get("/lyrics?v=${enc(t.v)}&title=${enc(t.title)}&artist=${enc(t.artist)}&length=${t.length}")
        val a = j.optJSONArray("lines") ?: JSONArray()
        return Lyrics(j.optBoolean("synced"), List(a.length()) { a.getJSONObject(it).let { l -> l.optInt("t") to l.str("text") } }, j.str("source"))
    }

    /** The next [n] songs of a station, leaving out [exclude] (what's already queued or played). */
    fun station(id: String, exclude: List<String>, n: Int = 25): List<Track> {
        val j = post("/station", JSONObject().put("id", id).put("exclude", JSONArray(exclude)).put("n", n))
        val a = j.optJSONArray("tracks") ?: JSONArray()
        return List(a.length()) { Track.from(a.getJSONObject(it)) }
    }

    fun vote(v: String, vote: String) = post("/vote", JSONObject().put("v", v).put("vote", vote))
    fun played(v: String, seconds: Int, length: Int, skipped: Boolean) =
        post("/played", JSONObject().put("v", v).put("seconds", seconds).put("length", length).put("skipped", skipped))
    fun prepare(v: String) = get("/prepare?v=${enc(v)}")
    fun lights(playing: Boolean, stopped: Boolean, colors: IntArray, accent: Int, party: Boolean): Boolean {
        fun rgb(c: Int) = JSONArray().put(android.graphics.Color.red(c)).put(android.graphics.Color.green(c)).put(android.graphics.Color.blue(c))
        return post("/lights", JSONObject().put("playing", playing).put("stopped", stopped).put("party", party)
            .put("colors", JSONArray().apply { colors.forEach { put(rgb(it)) } }).put("accent", rgb(accent))).optBoolean("enabled", true)
    }
    fun story(t: Track): Story? = get("/story?album=${enc(t.album ?: "")}&artist=${enc(t.artist)}&title=${enc(t.title)}").let {
        if (it.str("text").isEmpty()) null else Story(it.str("kind"), it.str("title"), it.str("text"))
    }
    /** The song's loudness correction in dB; null when it isn't measured yet ([wait] = false: don't wait for it). */
    fun gain(v: String, wait: Boolean): Float? =
        get("/gain?v=${enc(v)}${if (wait) "" else "&wait=0"}").let { if (it.isNull("gain")) null else it.optDouble("gain").toFloat() }
    fun lightsState(): Boolean = get("/lights/state").optBoolean("enabled", true)
    fun lightsEnabled(on: Boolean): Boolean = post("/lights/enabled", JSONObject().put("enabled", on)).optBoolean("enabled", on)

    fun streamUrl(t: Track) = "${Config.server}/stream?v=${enc(t.plex ?: t.v)}"
    fun headers() = mapOf("X-Hub-Secret" to (Config.secret ?: ""))

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun open(path: String): HttpURLConnection {
        val server = Config.server ?: throw ServerDown()
        return (URL(server + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 60000
            setRequestProperty("X-Hub-Secret", Config.secret ?: "")
        }
    }

    private fun read(c: HttpURLConnection): JSONObject = try {
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().decodeToString() } ?: "{}"
        if (code !in 200..299) throw Exception(JSONObject(text).optString("error", "The music server said no ($code)"))
        JSONObject(text)
    } catch (e: java.io.IOException) {
        Log.w(TAG, "music server: $e")
        throw ServerDown()
    } finally {
        c.disconnect()
    }

    private fun get(path: String) = read(open(path))

    private fun post(path: String, body: JSONObject): JSONObject {
        val c = open(path)
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        try {
            c.outputStream.use { it.write(body.toString().toByteArray()) }
        } catch (e: java.io.IOException) {
            throw ServerDown()
        }
        return read(c)
    }

    private fun rows(a: JSONArray?): List<Row> = if (a == null) emptyList() else List(a.length()) { i ->
        val r = a.getJSONObject(i)
        Row(r.str("id"), r.str("title"), items(r.optJSONArray("items")), r.optJSONObject("more")?.let { card(it) })
    }

    private fun items(a: JSONArray?): List<Item> = if (a == null) emptyList() else List(a.length()) { i ->
        val j = a.getJSONObject(i)
        if (j.optString("type") == "track") Item.Song(Track.from(j)) else Item.Other(card(j))
    }.filter { it !is Item.Other || it.card.id.isNotEmpty() }

    private fun card(j: JSONObject) = Card(j.str("type"), j.str("id"), j.str("title"), j.str("subtitle"),
        j.str("art").ifEmpty { null }, j.str("reason").ifEmpty { null })

    private fun page(j: JSONObject): Page {
        val t = j.optJSONArray("tracks") ?: JSONArray()
        return Page(j.str("title"), j.str("subtitle"), j.str("art").ifEmpty { null },
            List(t.length()) { Track.from(t.getJSONObject(it)) }, rows(j.optJSONArray("rows")))
    }
}

/** Runs [work] off the main thread and hands its result (or failure) back on it. */
object Bg {
    private val pool = Executors.newFixedThreadPool(4)
    val main = Handler(Looper.getMainLooper())

    fun <T> run(work: () -> T, done: (Result<T>) -> Unit) {
        pool.execute {
            val r = runCatching(work)
            main.post { done(r) }
        }
    }

    fun go(work: () -> Unit) = pool.execute { runCatching(work).onFailure { Log.w(TAG, "background: $it") } }
}

/** Album art, off the main thread, with a memory cache so scrolling back is instant. */
object Images {
    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(4)

    /**
     * [url] at about [size] pixels: Google's image server resizes square art ("=w544-h544" in the address); the record
     * shelf's art ("/img?p=...") comes resized from the music server, which needs the secret.
     */
    fun sized(url: String, size: Int) =
        if (url.startsWith("/")) "${Config.server}$url&s=${size.coerceAtMost(1600)}"
        else url.replace(Regex("=w\\d+-h\\d+"), "=w$size-h$size").replace(Regex("=s\\d+$"), "=s$size")

    fun load(url: String?, into: ImageView, size: Int, done: ((Bitmap) -> Unit)? = null) {
        into.tag = url
        if (url == null) { into.setImageDrawable(null); return }
        val src = sized(url, size)
        cache.get(src)?.let { into.setImageBitmap(it); done?.invoke(it); return }
        into.setImageDrawable(null)
        pool.execute {
            val bmp = fetch(src, size) ?: return@execute
            cache.put(src, bmp)
            Bg.main.post {
                if (into.tag == url) {
                    into.alpha = 0f
                    into.setImageBitmap(bmp)
                    into.animate().alpha(1f).setDuration(180).start()
                    done?.invoke(bmp)
                }
            }
        }
    }

    /** Blocking, for the player's lock-screen art. */
    fun get(url: String?, size: Int): Bitmap? {
        if (url == null) return null
        val src = sized(url, size)
        return cache.get(src) ?: fetch(src, size)?.also { cache.put(src, it) }
    }

    private fun fetch(src: String, size: Int): Bitmap? {
        val c = URL(src).openConnection() as HttpURLConnection
        if (Config.server != null && src.startsWith(Config.server!!)) c.setRequestProperty("X-Hub-Secret", Config.secret ?: "")
        return try {
            c.connectTimeout = 5000
            c.readTimeout = 15000
            if (c.responseCode != 200) return null
            val bytes = c.inputStream.use { it.readBytes() }
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            var s = 1
            while (o.outWidth / (s * 2) >= size) s *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
        } catch (e: Exception) {
            null
        } finally {
            c.disconnect()
        }
    }
}
