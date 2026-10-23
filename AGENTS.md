# Agent notes

Audio-only music app for Android TV (com.livingroomtv.music) plus a home server. Goal: a calm room — never show video. Sources: YouTube Music (unofficial, ytmusicapi + yt-dlp) and a Plex music library. User-facing overview: `README.md`.

## Layout
| Path | What |
|---|---|
| `app/` | Kotlin, no libraries. `Api.kt` (server calls, config), `Ui.kt` (rows, cards; `Go` = what selecting does), `Player.kt` (MediaPlayer service: gapless, crossfade, gain, lights), `NowPlayingActivity.kt`, `DetailActivity.kt` (album/playlist/artist/mood/kids lists), `Visuals.kt` |
| `server/music.py` | HTTP server, port 8097, in `/srv/music` (venv, systemd `livingroomtv-music`). Header `X-Hub-Secret` required except `/health` |
| `server/signin.py` | Saves the YouTube Music sign-in from pasted browser request headers |
| `server/kids.py`, `ythistory.py` | Kids tab from the family's YouTube watch history (run nightly: `--fetch`, then build) |
| `tools/label_kids.py` | Labels new history videos with the `claude` CLI |
| `homeassistant/` | Automation that turns the server's MQTT cue into light scenes |

Endpoints: GET `/home /popular /kids /shelf /wishlist /search /list?type=&id= /artist /mood /lyrics /story /gain /stream?v= /prepare /img /ytimg /lights/state /health`; POST `/station /vote /played /lights /lights/enabled`. `/list` types: album, playlist (`LM` = Liked songs), `plex:<id>`, `kids` / `kids:<group>`.

## Build and deploy
- Server: `MUSIC_HOST=<ssh-host> scripts/install-music.sh`. Config in `~/.config/living-room-tv/` on the server: `hub.secret`, `plex.token`, `ytmusic-browser.json` (sign-in; never commit).
- App: `MUSIC_SERVER=http://<server>:8097 scripts/deploy-music.sh <tv-ip> ["living room"]` (needs `local.properties` with `sdk.dir`, JDK 17+).

## Non-obvious behaviour
- googlevideo URLs return 403 for ~3 s after lookup (server retries); always send a Range header upstream or YouTube throttles to real time.
- Plex files above 16-bit/48 kHz are converted to 16/48 FLAC (soxr + dither) and cached (8 GB): Android TV boxes distort hi-res. Songs owned on Plex play from Plex instead of YouTube.
- Gapless within an album, 4 s crossfade otherwise. Even volume: EBU R128 to −14 LUFS, −12…+6 dB, one gain per album run.
- Calm mode folds the player controls after 12 s; the first key press only wakes them (focus goes to Play). Testing with key presses can pause, skip or vote.
- Leaving the app stops the music (`onUserLeaveHint`); in-app navigation uses `Go.start` (FLAG_ACTIVITY_NO_USER_ACTION).
- Lights: app → POST `/lights` → MQTT `livingroom/music/lights` → Home Assistant (only lights already on change; the previous scene is restored). Only a TV configured with room "living room" sends cues.

## Rules
- Audio only: no video anywhere in the UI.
- Never commit or print sign-in headers, cookies, tokens or home addresses.
- This repo is a public copy of a private home project; keep it free of personal data.
