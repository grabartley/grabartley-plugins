---
name: mp3-to-ogg
description: Convert MP3 audio files into OGG assets for Minecraft resource packs. Use when adding or converting sound assets for a mod.
---

# mp3-to-ogg

Convert MP3 audio files into OGG assets for Minecraft resource packs.

Minecraft plays Vorbis in an Ogg container, so the encoder matters. `oggenc` from `vorbis-tools`
does the encoding and `ffmpeg` only decodes the source, because `ffmpeg -c:a libvorbis` needs
libvorbis compiled into that particular ffmpeg build and many are built without it. `oggenc` links
libvorbisenc directly, which is the same encoder that flag wraps, so the output is equivalent and
the command works on any ffmpeg.

## Workflow

1. Confirm the tools are present: `oggenc --version` and `ffmpeg -version`. Install with
	`brew install vorbis-tools` if `oggenc` is missing.
2. Confirm source files exist with `ls`.
3. Create target directory with `mkdir -p`.
4. Convert one file:
	- `ffmpeg -v error -i "input.mp3" -f wav - | oggenc -Q -q 5 -o "output.ogg" -`
5. Convert a numbered batch:
	- `for i in {1..14}; do ffmpeg -v error -i "${SRC}/${i}.mp3" -f wav - | oggenc -Q -q 5 -o "${DST}/sound${i}.ogg" -; done`
6. Verify each output is really Vorbis, and at the length and channel count expected:
	- `ffprobe -v error -show_entries format=duration -show_entries stream=codec_name,sample_rate,channels -of default=nw=1 "output.ogg"`
	- `codec_name=vorbis` is the check that matters. A file named `.ogg` holding anything else is
	  silent in game rather than broken at load, which is the hardest failure to spot.

## Notes

- Use `.ogg` files under `src/main/resources/assets/<modid>/sounds/...`.
- Keep naming aligned with `sounds.json` event entries.
- Default quality uses Vorbis quality mode `-q 5`.
- Prefer **mono** for anything that happens somewhere in the world. A stereo file is played without
	a position, so the sound is heard from nowhere in particular. Downmix a stereo source by adding
	`-ac 1` to the ffmpeg side: `ffmpeg -v error -i "input.mp3" -ac 1 -f wav - | oggenc -Q -q 5 -o "output.ogg" -`.
- Match the level of the sounds already in the set rather than normalising each file on its own:
	`ffmpeg -hide_banner -i "output.ogg" -af volumedetect -f null /dev/null` reports `mean_volume`
	and `max_volume` for comparison.
- `oggenc` prints `Skipping chunk of type "LIST"` when ffmpeg's wav carries a metadata chunk. That
	is a note about the container, not an encoding failure.
