---
name: mp3-to-ogg
description: Convert MP3 audio files into OGG assets for Minecraft resource packs. Use when adding or converting sound assets for a mod.
---

# mp3-to-ogg

Convert MP3 audio files into OGG assets for Minecraft resource packs.

## Workflow

1. Confirm source files exist with `ls`.
2. Create target directory with `mkdir -p`.
3. Convert one file:
	- `ffmpeg -y -i "input.mp3" -c:a libvorbis -q:a 5 "output.ogg"`
4. Convert a numbered batch:
	- `for i in {1..14}; do ffmpeg -y -i "${SRC}/${i}.mp3" -c:a libvorbis -q:a 5 "${DST}/sound${i}.ogg"; done`
5. Verify outputs exist:
	- `ls -la "${DST}"`

## Notes

- Use `.ogg` files under `src/main/resources/assets/<modid>/sounds/...`.
- Keep naming aligned with `sounds.json` event entries.
- Default quality uses Vorbis quality mode `-q:a 5`.
