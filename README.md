# Living Room Music

A calm, audio-only music app for Android TV (built for an NVIDIA Shield), with a small home server behind it.
It plays YouTube Music and a Plex library with no videos. It finds new music from your liked songs, shows synced
karaoke lyrics and four visualizers, and can colour the room's Hue lights to match each album through Home Assistant.
Put a music CD in the server's drive and it rips itself into the library and onto a shelf of jewel cases on the TV,
with star ratings and a delete button for curating the collection.

**Case study and videos:** [mitchellv.me/work/living-room-music](https://www.mitchellv.me/work/living-room-music) · by [Mitchell Vizensky](https://www.mitchellv.me), game UX designer

Personal home project. The YouTube Music side uses unofficial access (ytmusicapi, yt-dlp), so it is not a product
and isn't meant to be offered to others.

![Lyrics, Ribbon, Aurora, Particles and Bars on Daft Punk's One More Time](docs/portfolio/visualizers.gif)

![Home](docs/portfolio/screenshots/01-home.jpg)
![Karaoke lyrics](docs/portfolio/screenshots/03-player-karaoke-lyrics.jpg)
![Aurora in the album's colours](docs/portfolio/screenshots/05-aurora-visualizer-album-colors.jpg)

## How it works

![How it works](docs/portfolio/how-it-works.png)

| Folder | What's in it |
| --- | --- |
| `app/` | The TV app: Kotlin, no third-party libraries. Gapless albums, crossfades, even volume, lyrics, visualizers, calm mode |
| `server/` | `music.py`, the home server (Python, port 8097): stations from your likes, Plex, lyrics, album stories, loudness, light cues. `signin.py` saves the YouTube Music sign-in; `kids.py` and `ythistory.py` build the Kids tab |
| `cd/` | The CD ripper: a disc in the server's USB drive starts `cdrip.py` (udev + systemd). MusicBrainz lookup, AccurateRip-checked FLAC with cyanrip, cover, back and disc art, then a Plex scan. The TV's CDs tab shows the collection as a cover flow |
| `playlists/` | Hand-picked playlists as plain song lists; `server/make_playlist.py` matches them and builds the cover |
| `signin/ytm-signin.mjs` | One-click YouTube Music sign-in: opens a throwaway Chrome profile, waits for you to sign in, sends the session to the server over ssh and deletes the profile |
| `homeassistant/` | The input boolean and automation that turn the server's MQTT cue into Hue scenes (only lights already on change, and the room is restored afterwards) |
| `tools/label_kids.py` | Sorts the family's YouTube watch history into kids' songs with Claude |
| `scripts/` | Install the server over ssh, build and install the TV app over adb |

## Portfolio materials

Everything for the write-up on mitchellv.me is in [`docs/portfolio`](docs/portfolio):

- [Case study](docs/portfolio/case-study.md) ([PDF](docs/portfolio/case-study.pdf)): the pitch, features, design process, engineering notes, shot list and captions
- [Screenshots](docs/portfolio/screenshots): nine captures straight from the TV (1920 × 1080)
- [Screen recordings](docs/portfolio/videos): eight clips recorded on the TV, listed in [CLIPS.txt](docs/portfolio/videos/CLIPS.txt). Best single clip: [06-visualizer-tour.mp4](docs/portfolio/videos/06-visualizer-tour.mp4)

## Setup

Server (a Linux box with Plex on the same machine, Python 3, node and ffmpeg):

1. Make `/srv/music` with a venv: `python3 -m venv /srv/music/venv`.
2. Put a shared secret in `~/.config/living-room-tv/hub.secret` and the Plex token in `~/.config/living-room-tv/plex.token`.
3. `MUSIC_HOST=<ssh-host> scripts/install-music.sh`
4. Sign in to YouTube Music: `MUSIC_HOST=<ssh-host> node signin/ytm-signin.mjs` on a Mac with Chrome (or run
   `/srv/music/venv/bin/python /srv/music/signin.py` on the server and paste the request headers from music.youtube.com).
5. CDs (optional, a USB CD drive on the server): `MUSIC_HOST=<ssh-host> scripts/install-cdrip.sh`. Library paths come
   from `CD_LIBRARY` / `CD_PLEX_LIBRARY` (see `cd/cdrip.py`).
6. For the lights: add `homeassistant/*.yaml` to Home Assistant, with an MQTT broker on the server.

TV (adb over the network enabled):

```
MUSIC_SERVER=http://<server-ip>:8097 scripts/deploy-music.sh <tv-ip> "living room"
```

Built with Claude Code.
