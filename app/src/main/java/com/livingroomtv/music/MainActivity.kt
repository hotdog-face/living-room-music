package com.livingroomtv.music

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Music's front page: Home (stations, new finds, artists you might love ...), Search and Liked songs along the top,
 * the rows under them, and the playing song along the bottom. Sound only: nothing here ever shows a video.
 */
class MainActivity : Activity() {
    private lateinit var tabs: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var status: TextView
    private lateinit var mini: MiniPlayer
    private var tab = "home"
    private var loadedAt = 0L
    private var searchBox: EditText? = null
    private var lastQuery = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Config.load(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Colors.BG) }
        tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(96), px(52), px(96), px(6))
        }
        tabs.addView(brand(), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = px(54) })
        for ((id, word) in listOf("home" to "Home", "popular" to "Popular", "kids" to "Kids", "shelf" to "Plex", "cds" to "CDs", "liked" to "Liked songs", "search" to ""))
            tabs.addView(tabButton(id, word))
        root.addView(tabs)
        status = label(23, Colors.DIM, lines = 3).apply { setPadding(px(96), px(30), px(96), 0); visibility = View.GONE }
        root.addView(status)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; setPadding(0, 0, 0, px(60)) }
        scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipChildren = false; addView(body) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        mini = MiniPlayer(this)
        root.addView(mini, LinearLayout.LayoutParams(-1, -2).apply { setMargins(px(80), px(6), px(80), px(30)) })
        setContentView(root)
        show("home")
    }

    override fun onResume() {
        super.onResume()
        if (tab == "home" && System.currentTimeMillis() - loadedAt > 30 * 60 * 1000) show("home")   // fresh finds after a while
        mini.refresh()
    }

    /** The app's own mark and name: a sunset tile with five equalizer bars (they dance while music plays), "Living Room Music". */
    private fun brand() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(BrandMark(this@MainActivity), LinearLayout.LayoutParams(px(62), px(62)))
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(15, Colors.DIM, bold = true, text = "LIVING ROOM").apply { letterSpacing = 0.32f; ellipsize = null; setSingleLine(true) },
                LinearLayout.LayoutParams(px(200), -2))
            addView(label(34, Colors.TEXT, heavy = true, text = "Music").apply { letterSpacing = -0.01f },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(2) })
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(18) })
    }

    /** A tab is a pill: the open one filled with the accent, the focused one white. Search is just its icon. */
    private fun tabButton(id: String, word: String) = label(21, Colors.TEXT, bold = true, text = word).apply {
        tag = id
        isFocusable = true
        setPadding(px(30), px(14), px(30), px(14))
        if (word.isEmpty()) {
            contentDescription = "Search"
            setCompoundDrawablesRelative(getDrawable(R.drawable.ic_search)!!.mutate().apply { setBounds(0, 0, px(30), px(30)) }, null, null, null)
            setPadding(px(24), px(13), px(24), px(13))
        }
        background = GradientDrawable().apply { cornerRadius = px(30).toFloat() }
        setOnClickListener { show(id) }
        setOnFocusChangeListener { _, _ -> paintTabs() }
    }

    private fun paintTabs() {
        for (i in 0 until tabs.childCount) {
            val v = tabs.getChildAt(i) as? TextView ?: continue
            val id = v.tag as? String ?: continue
            val open = tab == id
            (v.background as GradientDrawable).setColor(if (v.hasFocus()) Color.WHITE else if (open) Accent.color else Colors.SURFACE)
            v.setTextColor(if (v.hasFocus() || open) Colors.INK else Colors.TEXT)
            v.compoundDrawablesRelative[0]?.setTint(if (v.hasFocus() || open) Colors.INK else Colors.TEXT)
            (v.layoutParams as? LinearLayout.LayoutParams)?.let { if (it.marginEnd == 0) { it.marginEnd = px(14); v.layoutParams = it } }
        }
    }

    private val recolor = { paintTabs() }
    override fun onStart() { super.onStart(); Accent.listen(recolor) }
    override fun onStop() { Accent.unlisten(recolor); super.onStop() }

    private fun show(which: String) {
        tab = which
        tabs.post { paintTabs() }                                // after layout: the underline needs the tab's height
        body.removeAllViews()
        searchBox = null
        scroll.scrollTo(0, 0)
        when (which) {
            "home" -> loadHome()
            "shelf" -> loadShelf()
            "cds" -> loadCds()
            "kids" -> loadKids()
            "popular" -> loadPopular()
            "search" -> searchPage()
            "liked" -> Go.open(this, Card("playlist", "LM", "Liked songs", "", null, null)).also { tab = "home"; paintTabs(); loadHome() }
        }
    }

    private fun say(msg: String?) {
        status.text = msg ?: ""
        status.visibility = if (msg == null) View.GONE else View.VISIBLE
    }

    private fun loadHome() {
        say("Loading…")
        Bg.run({ Api.home() }) { r ->
            if (tab != "home") return@run
            r.onSuccess { (signedIn, rows) ->
                loadedAt = System.currentTimeMillis()
                say(if (signedIn) null else "Not signed in to YouTube Music yet, so your likes and new finds aren't here. " +
                        "Search and radio work. (Sign in once on the music server.)")
                body.removeAllViews()
                addRows(rows.filter { it.items.isNotEmpty() })
                if (!tabs.hasFocus()) body.post { body.getChildAt(0)?.requestFocus() }
            }.onFailure { say(it.message ?: "Couldn't load") }
        }
    }

    /** Your own albums from Plex: recently added, by genre, the artists. Played as the files are (FLAC stays lossless). */
    private fun loadShelf() {
        say("Loading your Plex library…")
        Bg.run({ Api.shelf() }) { r ->
            if (tab != "shelf") return@run
            r.onSuccess { rows ->
                say(if (rows.isEmpty()) "Your Plex music library is empty" else null)
                body.removeAllViews()
                addRows(rows.filter { it.items.isNotEmpty() })
            }.onFailure { say(it.message ?: "Couldn't reach your Plex library") }
        }
    }

    /** Mitchell's CD collection as a cover flow of jewel cases (Cds.kt); new rips appear on their own. */
    private fun loadCds() {
        say(null)
        val shelf = CdShelf(this)
        body.addView(shelf, LinearLayout.LayoutParams(-1, -2))
        if (!tabs.hasFocus()) shelf.post { shelf.focusFlow() }
    }

    /** The kids' dance music from the YouTube history: a Dance party station, most played, Cars, sing-alongs, party hits. */
    private fun loadKids() {
        say("Loading the dance party…")
        Bg.run({ Api.kids() }) { r ->
            if (tab != "kids") return@run
            r.onSuccess { rows ->
                say(if (rows.isEmpty()) "The kids' songs aren't ready yet" else null)
                body.removeAllViews()
                addRows(rows.filter { it.items.isNotEmpty() })
            }.onFailure { say(it.message ?: "Couldn't load the kids' songs") }
        }
    }

    /** What everyone else is playing: trending, the charts, popular-but-new-to-you, top albums and artists, moods and genres. */
    private fun loadPopular() {
        say("Loading what's popular…")
        Bg.run({ Api.popular() }) { r ->
            if (tab != "popular") return@run
            r.onSuccess { rows ->
                say(null)
                body.removeAllViews()
                addRows(rows.filter { it.items.isNotEmpty() })
            }.onFailure { say(it.message ?: "Couldn't load the charts") }
        }
    }

    private fun addRows(rows: List<Row>) {
        for (row in rows) body.addView(RowView(this, row,
            onClick = { r, item -> Go.select(this, r, item) },
            onHold = { item -> Go.hold(this, item) },
            onMore = { card -> Go.open(this, card) }))
    }

    private fun searchPage() {
        say(null)
        val box = EditText(this).apply {
            hint = "Songs, artists, albums"
            setHintTextColor(Colors.FAINT)
            setTextColor(Colors.TEXT)
            typeface = Fonts.regular
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textPx(30))
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(px(30), px(20), px(30), px(20))
            background = GradientDrawable().apply { cornerRadius = px(40).toFloat(); setColor(Colors.SURFACE2) }
            setPadding(px(40), px(22), px(40), px(22))
            setText(lastQuery)
            setOnEditorActionListener { v, action, event ->
                val go = action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE ||
                        (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                if (go) runSearch(v.text.toString())
                go
            }
        }
        searchBox = box
        // Talk instead of typing: the remote's mic button always opens Google Assistant (Android TV keeps
        // KEYCODE_ASSIST for itself), so Search has its own mic. It opens first: OK = speak; RIGHT = type.
        val mic = TextView(this).apply {
            isFocusable = true
            contentDescription = "Search by voice"
            gravity = Gravity.CENTER
            val icon = getDrawable(R.drawable.ic_mic)!!.mutate().apply { setBounds(0, 0, px(40), px(40)) }
            setCompoundDrawablesRelative(icon, null, null, null)
            setPadding(px(22), px(18), px(22), px(18))
            val bg = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Colors.SURFACE2) }
            background = bg
            fun paint(f: Boolean) { bg.setColor(if (f) Color.WHITE else Colors.SURFACE2); icon.setTint(if (f) Colors.INK else Colors.TEXT) }
            paint(false)
            setOnFocusChangeListener { _, f -> paint(f) }
            setOnClickListener { listen() }
        }
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(96), px(30), px(96), px(10))
            addView(mic, LinearLayout.LayoutParams(px(84), px(84)).apply { marginEnd = px(20) })
            addView(box, LinearLayout.LayoutParams(px(900), -2))
        })
        say(if (lastQuery.isEmpty()) "Press OK and say a song, artist or album. Press right to type." else null)
        mic.requestFocus()
        if (lastQuery.isNotEmpty()) runSearch(lastQuery)
    }

    private var recognizer: android.speech.SpeechRecognizer? = null

    /**
     * Listen here, in our own window: Google's "Speak now" screen (RecognizerIntent -> Katniss VoiceInputActivity)
     * opens but never starts listening on the Shields ("call for recognition service without RECORD_AUDIO
     * permissions" - it checks the caller's foreground state). SpeechRecognizer runs as us, in the foreground,
     * with our RECORD_AUDIO, so Google's recognizer hears the remote's mic. Words appear in the box as you talk.
     */
    private fun listen() {
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) { say("Voice search isn't available on this TV"); return }
        if (tab != "search") show("search")
        recognizer?.destroy()
        val r = android.speech.SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) = say("Listening… say a song, artist or album")
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rms: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() = say("Got it…")
            override fun onEvent(t: Int, p: Bundle?) {}
            override fun onPartialResults(p: Bundle?) {
                words(p)?.let { searchBox?.setText(it); searchBox?.setSelection(it.length) }
            }
            override fun onResults(p: Bundle?) {
                val said = words(p)
                if (said.isNullOrEmpty()) { say("I didn't catch that. Press OK to try again."); return }
                searchBox?.setText(said)
                runSearch(said)
            }
            override fun onError(e: Int) {
                android.util.Log.w("MusicVoice", "recognizer error $e")
                say(when (e) {
                    android.speech.SpeechRecognizer.ERROR_NO_MATCH, android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        "I didn't hear anything. Press OK and talk into the remote."
                    android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "The microphone is turned off for Music."
                    android.speech.SpeechRecognizer.ERROR_NETWORK, android.speech.SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Voice search needs the internet."
                    else -> "Voice search didn't work (error $e). Press OK to try again."
                })
            }
        })
        r.startListening(android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(android.speech.RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName))
        say("Starting the microphone…")
    }

    private fun words(b: Bundle?) =
        b?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    override fun onDestroy() { recognizer?.destroy(); recognizer = null; super.onDestroy() }


    private fun runSearch(q: String) {
        if (q.isBlank()) return
        lastQuery = q
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(searchBox?.windowToken, 0)
        while (body.childCount > 1) body.removeViewAt(1)
        say("Searching…")
        Bg.run({ Api.search(q) }) { r ->
            if (tab != "search") return@run
            r.onSuccess { rows ->
                say(if (rows.isEmpty()) "Nothing found for \"$q\"" else null)
                while (body.childCount > 1) body.removeViewAt(1)
                addRows(rows)
                body.post { body.getChildAt(1)?.requestFocus() }
            }.onFailure { say(it.message ?: "Search didn't work") }
        }
    }

    /** Home, another app: the music stops with the app (the photo screensaver doesn't count: it isn't the user leaving). */
    override fun onUserLeaveHint() {
        Player.stop()
        super.onUserLeaveHint()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // A SEARCH key (phone remote app, keyboards; the Shield remote's mic is ASSIST and never reaches us) = talk.
        if (keyCode == KeyEvent.KEYCODE_SEARCH) { listen(); return true }
        return Go.mediaKey(this, keyCode) || super.onKeyDown(keyCode, event)
    }

    @Deprecated("Back from Search returns Home; Back on Home leaves the music playing")
    override fun onBackPressed() {
        if (tab != "home") show("home") else { Player.stop(); super.onBackPressed() }   // Back out of Music: the music stops too
    }

}
