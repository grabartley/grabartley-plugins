---
name: demo-video
description: Make a YouTube-ready showcase video of any feature of a Fabric mod, recorded in the real client and edited creator style. Produces a horizontal 1080p video and a vertical 1080x1920 Short by default, or just one when asked. Use when asked for a demo, showcase, trailer, promo, or Short of a mod feature.
---

# Demo Video

Record a scripted scene in the real game, then cut it on the music's beat with captions, effects and real in-game sound.

- `templates/DemoVideoDriver.java` is a temporary client driver. You write the stage and the shot list; it records frames, sounds and camera positions.
- `scripts/edit.py` renders each version from one JSON config, plus a thumbnail and contact sheets. It needs ffmpeg 7 or newer (for `-/filter_complex`) and Pillow 10.1 or newer. It does not need ffmpeg's `drawtext`.

Spend tokens on the shot list and the cut. Never watch footage: read the sound log for timings and the contact sheets for framing, and run recordings in the background.

## Defaults

- Both versions unless the caller asks for one. Horizontal runs about 30 s; the Short runs about 20 s and loops.
- Deliverables go in `<repo>/.claude/tmp/demo-video/`. Check with `git check-ignore` that the path is ignored; if not, use the scratch directory. Working files go in `work_dir`.
- Music is a CC0 track. Ask the user before downloading any file, giving its name, source and size. Proven sources are OpenGameArt (RandomMind's "Medieval" set) and kenney.nl. Credit the track in the upload copy.

## 1. Plan the shots

Read the feature's issue or README. Write 4 to 6 scenes, each showing one claim the feature makes. For each scene, note the camera eye and target and the events with their game ticks (20 per second), and draft its caption in 2 to 5 words. Keep the subject within 16 blocks of the camera: the server sends no sound to a listener further away.

## 2. Record

1. Make a throwaway worktree with the `dev-workflow:worktree` skill and copy `run/` into it. Commit nothing from it, and remove it at the end.
2. Copy the template into `src/client/java/<pkg>/`. Set the constants: `WORLD`, `OUT` (an absolute path to the takes folder), `TAKE`, `END`, and `BIOME` (it repaints the stage so grass isn't tinted; null keeps the world's colours). Fill in `buildStage()` using `flatten`, `put` and `at`, and `script()` using `cut`, `key` and `at`. Add `DemoVideoDriver.register();` to the client initializer.
3. Set these in `run/options.txt`: `pauseOnLostFocus:false`, `onboardAccessibility:false`, `soundCategory_music:0.0` and `bobView:false`. Set the window to `overrideWidth:960`/`overrideHeight:540` for horizontal, or `540`/`960` plus `fov:0.375` for vertical. Record vertical natively; never crop it from horizontal.
4. Before every take, restore a clean copy of the save from the main checkout. The stage is built around the player, and a take leaves the player wherever the camera ended.
5. Disable any recipe viewer mods, then run `./gradlew runClient` in the background. Wait for `[DEMO] DONE` in its log. Each take writes `<TAKE>.mp4`, `<TAKE>_sounds.csv` and `<TAKE>_camera.csv` to `OUT`. Give every take a unique `TAKE` name (for example `take_h`, `take_v`) so no run overwrites a good one. A take takes about 5 times its length to record.
6. Check framing on one sheet per take: `ffmpeg -i take.mp4 -vf fps=0.5,scale=320:-2,tile=8x5 -frames:v 1 sheet.png`. Fix framing by changing the script and re-recording.

## 3. Cut

Take timings from the sound log, not the footage. Each row of `<TAKE>_sounds.csv` is `tick,sound,...`, so every start, impact and stop has an exact time (divide the tick by 20). Put `demo.json` in the takes folder and write:

```json
{
  "music": "track.wav",
  "sound_roots": {"<modid>": "/abs/path/src/main/resources/assets/<modid>/sounds/"},
  "skip_sounds": ["minecart"],
  "versions": {
    "horizontal": {
      "take": "take_h", "out": "/abs/out/<name>.mp4",
      "clips": [{"raw": [48.0, 50.0, 4], "filter": "crop=1440:810:90:105"}, {"raw": [0.5, 2.2, 4]}],
      "punches": [{"at": "raw:13.82", "amount": 0.08, "shake": 16, "boom": true}],
      "whips": [26, 32],
      "extra_sounds": [{"at": "raw:48.25", "file": "/abs/sound.ogg", "gain": 0.3, "pan": -0.5}],
      "captions": [
        {"from": 4, "to": 8, "lines": [[["<MOD>", "white"]], [["<FEATURE>", "gold"]]], "size": [150, 72], "y": "center"},
        {"from": 8, "to": 14, "lines": [[["SHORT ", "white"], ["CLAIM", "gold"]]], "size": 78}
      ]
    },
    "vertical": {"take": "take_v", "out": "/abs/out/<name>-short.mp4", "clips": [{"raw": [12.2, 13.9, 4]}]}
  },
  "thumbnail": {"frames": [["take_h", 3.6, 960, 520], ["take_h", 23.4, 1000, 600]],
                "lines": ["<FEATURE ONE>", "& <FEATURE TWO>"], "tag": "NEW IN <MOD>", "out": "/abs/out/thumb.jpg"}
}
```

| Key | Meaning |
|---|---|
| `versions.<name>` | `horizontal` and/or `vertical`. Each version needs its own `take` and `clips`; every other key is optional. |
| `clips[].raw` | `[start_s, end_s, beats]`. The clip lasts exactly that many beats, so every cut lands on the beat and the speed is derived. `filter` is an optional ffmpeg crop that punches in on a wide shot. |
| Times | A number is a beat index, `"raw:<s>"` is a moment in the take, and `"end"` is the end of the video. Sounds play in every clip containing their raw time. A raw punch or caption uses the last clip that contains it. |
| `punches` | Zoom on an impact. `shake` is in pixels; `boom` adds a sub hit. |
| `whips` | Beat indices for a whoosh-zoom transition. |
| `captions` | `lines` is a list of lines, each a list of `[text, "white"\|"gold"]` runs. `size` is one number, or one per line. `y` is `head`, `center`, `low` (the default) or pixels. `pop` defaults to true. The script refuses a caption that would leave the frame. |
| `hook_beats` | Length of the muffled hook before the music drops. Defaults to clip 1's beats. |
| `end_fade`, `music_fade` | Seconds. The defaults suit each version. |
| `beat`, `first_beat` | Seconds. These override beat detection when it misreads the track; check it with `--beats`. |
| `takes_dir`, `work_dir` | Default to the folder holding `demo.json`, and its `work/` subfolder. |
| `thumbnail.frames` | `[take, second, centre_x, centre_y]`. Two frames make a diagonal split; the last 2 `lines` are drawn in white then gold. |

**Edit style:**
- Clip 1 is a 4-beat hook, the most striking moment.
- The drop gets the title card.
- Each scene after that shows one claim.
- Keep the feature itself moving at about 1x speed. Speed up approaches and holds 2 to 5x, and cut dead time out completely.
- Put a `whip` at each scene change, and give the biggest impact `shake` and `boom`.
- Captions are all caps, with key words in gold.
- End a pop word such as "SLAM!" on the next cut.
- Horizontal ends on a title held for 4 s or more, which leaves room for end-screen cards. The Short ends on a shot that leads back into its hook, so it loops. Keep its text between `head` and `low`.

Run `python3 <skill>/scripts/edit.py demo.json [horizontal|vertical]`, then `python3 <skill>/scripts/edit.py demo.json --thumbnail`. A run takes about a minute. Read each `work/<version>_contact.png` once, and fix the config rather than the footage.

## 4. Hand off

Put the videos, the thumbnail and a `youtube-copy.md` in the deliverables folder. The copy holds titles, descriptions, tags and credits, and `#shorts` for the Short. Send them with `SendUserFile`. Say that the mix was checked by measurement, not by ear.

## Gotchas

- **Stray world sounds (minecarts, mobs) land in the log.** List them in `skip_sounds`.
- **A tall entity standing in the way of a closing or moving block stops it before it visibly moves.** For obstruction shots, use a short mob with AI off.
