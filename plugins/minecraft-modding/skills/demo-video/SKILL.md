---
name: demo-video
description: Make a YouTube-ready showcase video of any feature of a Fabric mod, recorded in the real client and edited creator style. Produces a horizontal 1080p video and a vertical 1080x1920 Short by default, or just one when asked. Use when asked for a demo, showcase, trailer, promo, or Short of a mod feature.
---

# Demo Video

Record a scripted scene in the real game, then cut it on the music's beat with captions, effects and real in-game sound. Two parts do all the work:

- `templates/DemoVideoDriver.java`: a temporary client driver. You write the stage and the shot list; it records frames and logs every sound and camera position.
- `scripts/edit.py`: renders each version from one JSON config, along with a thumbnail and contact sheets.

Spend tokens on the shot list and the cut, not on watching footage. Review only the contact sheets the script writes, and run recordings in the background.

## Defaults

- Both versions unless the caller asks for one. Horizontal is about 30 s; the Short is about 20 s and loops.
- Output goes to `<repo>/.claude/tmp/demo-video/` (git-ignored). Working files go to scratch.
- Music: a CC0 track. Ask the user before downloading any file, giving its name, source and size. Proven sources are OpenGameArt (RandomMind's "Medieval" set) and Kenney. Credit them in the upload copy.

## 1. Plan the shots (no game yet)

Read the feature's issue or README. Write 4 to 6 scenes, each showing one claim the feature makes, such as "rises on redstone" or "arrows fly through". For each scene, note the camera eye and target and the events with their game ticks (20 per second). Keep the subject within 16 blocks of the camera, or the server never sends its sounds. Give the caption for each scene now, in 2 to 5 words.

## 2. Record

1. Make a throwaway worktree from `main` with `run/` copied. Never commit anything from it, and remove it at the end.
2. Copy the template into `src/client/java/<pkg>/`, set the constants, and fill in `buildStage()` (use `flatten`, `put`, `at`) and `script()` (use `cut`, `key`, `at`). Add one line to the client initializer: `DemoVideoDriver.register();`.
3. Set `run/options.txt`: `pauseOnLostFocus:false`, `onboardAccessibility:false`, `soundCategory_music:0.0`, `bobView:false`, and the window size. Use `overrideWidth:960`/`overrideHeight:540` for horizontal (1920x1080 on Retina). Use `540`/`960` with `fov:0.375` for vertical. Record vertical natively; never crop it from horizontal.
4. Before every take, restore a clean copy of the save from the main checkout. The stage is built around the player, and a take leaves the player wherever the camera ended.
5. Run `./gradlew runClient -Precipe_viewers=false` (or the repo's equivalent) in the background. Each take writes `<TAKE>.mp4`, `<TAKE>_sounds.csv` and `<TAKE>_camera.csv`. Give every take a unique `TAKE` name so no run overwrites a good one. A take runs about 5x its length at tick rate 4.
6. Check one frame per scene: `ffmpeg -i take.mp4 -vf fps=0.5,scale=320:-2,tile=8x5 -frames:v 1 sheet.png`. Fix framing in the script and re-record rather than rescuing it in the edit.

## 3. Cut

Find timings from the sound log, not by watching: `<TAKE>_sounds.csv` holds `tick,sound,...`, so every door start, impact and stop has an exact time. Then write `demo.json`:

```json
{
  "music": "track.wav",
  "sound_roots": {"<modid>": "/abs/path/src/main/resources/assets/<modid>/sounds/"},
  "skip_sounds": ["minecart"],
  "versions": {
    "horizontal": {
      "take": "take_h", "out": "/abs/out/name.mp4", "hook_beats": 4,
      "clips": [{"raw": [48.0, 50.0, 4], "filter": "crop=1440:810:90:105"}, {"raw": [0.5, 2.2, 4]}],
      "punches": [{"at": "raw:13.82", "amount": 0.08, "shake": 16, "boom": true}],
      "whips": [26, 32],
      "extra_sounds": [{"at": "raw:48.25", "file": "/abs/sound.ogg", "gain": 0.3, "pan": -0.5}],
      "captions": [
        {"from": 4, "to": 8, "lines": [[["MORE ", "white"], ["DOORS", "gold"]]], "size": [150, 72], "y": "center"},
        {"from": "raw:13.82", "to": 26, "lines": [[["SLAM!", "gold"]]], "size": 150, "y": "center"}
      ]
    },
    "vertical": {"take": "take_v", "out": "/abs/out/name-short.mp4", "...": "same keys"}
  },
  "thumbnail": {"frames": [["take_h", 3.6, 960, 520], ["take_h", 23.4, 1000, 600]],
                "lines": ["PORTCULLIS", "& ROLLER SHUTTER"], "tag": "NEW IN MORE DOORS", "out": "/abs/out/thumb.jpg"}
}
```

- **Clips:** `raw: [start_s, end_s, beats]`. Each clip lasts a whole number of beats, and its speed is worked out from that, so every cut lands on the beat. Keep any door or effect moving near 1x, and speed up approaches, turns and holds (2 to 5x). Cut dead time out entirely. Use `filter` for a punch-in crop on a wide shot.
- **Times:** a number is a beat index, `"raw:<seconds>"` is a moment in the take, and `"end"` is the end of the video.
- **Structure:**
  - Clip 1 is a 4-beat hook, the most striking moment, with the music muffled under it.
  - The drop gets a title card, a boom and a zoom.
  - Then one scene per claim.
  - Horizontal ends on a held title of 4 s or more, which leaves room for end-screen cards. The Short ends on a shot that cuts back into its hook, so it loops.
- **Effects:** use `punches` on impacts, adding `shake` and `boom` for the biggest one. Use `whips` (beat indices) at scene changes. `extra_sounds` adds sounds the camera was too far away to receive.
- **Captions:**
  - Text is all caps, 2 to 5 words, with key words in gold.
  - `y` is `head`, `center`, `low`, or a pixel value. In a Short, keep text between `head` and `low`, because YouTube's buttons cover the bottom and right edges.
  - End a pop word such as "SLAM!" on the next cut. The script refuses a caption wider than the frame.

Run `python3 <skill>/scripts/edit.py demo.json [horizontal|vertical]`, then `python3 <skill>/scripts/edit.py demo.json --thumbnail`. A run takes about a minute. Read each `<version>_contact.png` once and fix the config, not the footage. The output is H.264 High at 1080p30 with AAC 48 kHz, mastered to -14 LUFS with peaks at -1.5 dB. The music dips under the sound effects.

## 4. Hand off

Put the videos, the thumbnail and a `youtube-copy.md` in the output folder. The copy holds titles, descriptions, tags and credits, plus `#shorts` for the Short. Send the videos with `SendUserFile`. Say plainly that the mix was checked by measurement, not by ear.

## Gotchas that each cost a run

- **ffmpeg here has no `drawtext`, and `-filter_complex_script` is `-/filter_complex`.** The script draws captions with Pillow and passes graphs as files.
- **`amix` with 100+ inputs crawls for minutes.** The script mixes sound effects in Python instead.
- **Mod sounds sent by a ranged-sound packet log as attenuation `NONE`** with volume already scaled for distance. Vanilla sounds log `LINEAR`.
- **A desert or savanna world tints grass brown.** The driver repaints the stage as plains with `fillbiome`.
- **Stray world sounds (minecarts, mobs) end up in the log.** Add them to `skip_sounds`.
- **A tall entity blocks a closing door before it moves.** Use a short mob (a sheep or chicken) with AI off when showing obstruction.
- **Never write a new take over an old take's file name.** Losing a good take means re-recording it.
