"""Edit recorded demo takes into YouTube-ready videos from a JSON config.

Usage: python3 edit.py demo.json [horizontal|vertical ...]   (default: every version in the config)
       python3 edit.py demo.json --thumbnail
       python3 edit.py demo.json --beats                      (print the music's beat grid and exit)
Requires ffmpeg and Pillow. ffmpeg needs no drawtext: captions are drawn with Pillow.
"""
import array
import bisect
import csv
import glob
import json
import math
import os
import struct
import subprocess
import sys

from PIL import Image, ImageDraw, ImageEnhance, ImageFilter, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
FONT_BLACK = '/System/Library/Fonts/Supplemental/Arial Black.ttf'
COLOURS = {'white': (255, 255, 255), 'gold': (243, 196, 82)}
RATE = 48000


def run(*args):
    subprocess.run(['ffmpeg', '-v', 'error', '-y', *args], check=True)


def asset_index():
    found = sorted(glob.glob(os.path.expanduser('~/.gradle/caches/fabric-loom/assets/indexes/*.json')))
    return json.load(open(found[-1]))['objects'] if found else {}


def beat_grid(music):
    raw = subprocess.run(['ffmpeg', '-v', 'error', '-t', '60', '-i', music, '-ac', '1', '-ar', '16000',
                          '-f', 's16le', '-'], capture_output=True, check=True).stdout
    x = struct.unpack('<%dh' % (len(raw) // 2), raw)
    env = [sum(abs(v) for v in x[i:i + 16]) for i in range(0, len(x) - 16, 16)]
    on = [max(0, env[i] - env[i - 1]) for i in range(1, len(env))]
    mean = sum(on) / len(on)
    on = [v - mean for v in on]
    coarse = max((sum(on[i] * on[i + lag] for i in range(0, len(on) - lag, 7)), lag) for lag in range(300, 1000, 5))[1]
    period = max((sum(on[i] * on[i + lag] for i in range(0, len(on) - lag, 3)), lag)
                 for lag in range(coarse - 6, coarse + 7))[1]
    while period > 600:
        period //= 2
    phase = max((sum(on[p + k * period] for k in range((len(on) - p) // period)), p) for p in range(period))[1]
    return period / 1000.0, phase / 1000.0


def decode(path, cache={}):
    if path not in cache:
        raw = subprocess.run(['ffmpeg', '-v', 'error', '-i', path, '-ac', '1', '-ar', str(RATE), '-f', 'f32le', '-'],
                             capture_output=True, check=True).stdout
        cache[path] = array.array('f', raw)
    return cache[path]


def mix(events, duration, out):
    n = int(duration * RATE) + 1
    left, right = array.array('f', bytes(4 * n)), array.array('f', bytes(4 * n))
    for start, path, gain, pitch, pan in events:
        src = decode(path)
        gl, gr = gain * math.cos((pan + 1) * math.pi / 4), gain * math.sin((pan + 1) * math.pi / 4)
        offset = int(start * RATE)
        for i in range(max(0, -offset), min(int((len(src) - 1) / pitch), n - offset)):
            k = int(i * pitch)
            v = src[k] + (src[k + 1] - src[k]) * (i * pitch - k)
            left[offset + i] += v * gl
            right[offset + i] += v * gr
    both = array.array('f', bytes(8 * n))
    both[0::2], both[1::2] = left, right
    with open(out + '.f32', 'wb') as f:
        both.tofile(f)
    run('-f', 'f32le', '-ar', str(RATE), '-ac', '2', '-i', out + '.f32', out)


class Version:
    def __init__(self, cfg, name, beat, first_beat):
        self.cfg, self.v, self.name = cfg, cfg['versions'][name], name
        self.dir = cfg['dir']
        self.W, self.H = self.v.get('size', (1920, 1080) if name == 'horizontal' else (1080, 1920))
        self.beat, self.first_beat = beat, first_beat
        self.clips, at = [], 0
        for clip in self.v['clips']:
            a, b, beats = clip['raw']
            self.clips.append(dict(clip, a=a, b=b, beats=beats, start=at * beat, speed=(b - a) / (beats * beat)))
            at += beats
        self.duration = at * beat
        self.events, self.zooms, self.shakes, self.captions = [], [], [], []

    def time(self, spec):
        if isinstance(spec, str) and spec.startswith('raw:'):
            raw = float(spec[4:])
            for c in self.clips:
                if c['a'] <= raw < c['b']:
                    return c['start'] + (raw - c['a']) / c['speed']
            return None
        if spec == 'end':
            return self.duration
        return float(spec) * self.beat

    def path(self, name):
        return name if os.path.isabs(name) else os.path.join(self.dir, name)

    def game_sounds(self, index):
        roots = self.cfg.get('sound_roots', {})
        camera = sorted(tuple(map(float, r)) for r in csv.reader(open(self.path(self.v['take'] + '_camera.csv'))))
        times = [c[0] for c in camera]
        skip = self.cfg.get('skip_sounds', [])
        for t, loc, volume, pitch, x, y, z, attenuation, _ in csv.reader(open(self.path(self.v['take'] + '_sounds.csv'))):
            if float(t) < 0 or any(s in loc for s in skip):
                continue
            when = self.time(f'raw:{float(t) / 20.0}')
            ns, rel = loc.split(':', 1)
            if when is None or (ns == 'minecraft' and 'minecraft/' + rel not in index) or (ns != 'minecraft' and ns not in roots):
                continue
            if ns == 'minecraft':
                h = index['minecraft/' + rel]['hash']
                file = os.path.expanduser(f'~/.gradle/caches/fabric-loom/assets/objects/{h[:2]}/{h}')
            else:
                file = os.path.join(roots[ns], rel.removeprefix('sounds/'))
            volume, pitch, x, y, z = map(float, (volume, pitch, x, y, z))
            _, cx, cy, cz, yaw = camera[max(0, min(len(camera) - 1, bisect.bisect_left(times, float(t))))]
            dx, dy, dz = x - cx, y - cy, z - cz
            dist = math.sqrt(dx * dx + dy * dy + dz * dz)
            gain = (min(1, volume) * max(0, 1 - dist / (16 * max(1, volume)))) if attenuation == 'LINEAR' else min(1, volume)
            if gain > 0.003:
                yr = math.radians(yaw)
                pan = max(-1, min(1, 0.6 * (-dx * math.cos(yr) - dz * math.sin(yr)) / (math.hypot(dx, dz) or 1)))
                self.events.append((when, file, gain, pitch, pan))

    def text_image(self, lines, sizes, name):
        sizes = sizes if isinstance(sizes, list) else [sizes] * len(lines)
        fonts = [ImageFont.truetype(FONT_BLACK, s) for s in sizes]
        stroke = max(6, max(sizes) // 9)
        probe = ImageDraw.Draw(Image.new('RGBA', (4, 4)))
        widths = [sum(probe.textlength(t, font=fonts[i]) for t, _ in line) for i, line in enumerate(lines)]
        heights = [int(s * 1.12) for s in sizes]
        img = Image.new('RGBA', (int(max(widths) + stroke * 4 + 40), int(sum(heights) + stroke * 4 + 30)), (0, 0, 0, 0))
        d, y = ImageDraw.Draw(img), 15 + stroke
        for i, line in enumerate(lines):
            x = (img.width - widths[i]) / 2
            for text, colour in line:
                d.text((x, y), text, font=fonts[i], fill=COLOURS.get(colour, (255, 255, 255)) + (255,),
                       stroke_width=stroke, stroke_fill=(12, 10, 8, 255))
                x += probe.textlength(text, font=fonts[i])
            y += heights[i]
        if img.width > self.W * 0.94:
            raise SystemExit(f'caption {lines} is {img.width}px wide, wider than the frame: shrink its size')
        out = Image.new('RGBA', img.size, (0, 0, 0, 0))
        shadow = Image.new('RGBA', img.size, (0, 0, 0, 0))
        shadow.paste((0, 0, 0, 150), (0, 0), img.split()[3].filter(ImageFilter.GaussianBlur(7)))
        out.alpha_composite(shadow, (5, 7))
        out.alpha_composite(img)
        path = os.path.join(self.dir, f'{self.name}_cap_{name}.png')
        out.save(path)
        return path

    def build(self, index):
        v = self.v
        self.game_sounds(index)
        for s in v.get('extra_sounds', []):
            when = self.time(s['at'])
            if when is not None:
                self.events.append((when, self.path(s['file']), s.get('gain', 0.3), s.get('pitch', 1.0), s.get('pan', 0.0)))
        drop = self.time(v.get('hook_beats', 4))
        self.events.append((drop, os.path.join(HERE, 'fx_boom.wav'), 0.9, 1.0, 0.0))
        self.zooms.append((drop, 0.10))
        for p in v.get('punches', []):
            when = self.time(p['at'])
            if when is None:
                continue
            self.zooms.append((when, p.get('amount', 0.06)))
            if p.get('shake'):
                self.shakes.append((when, p['shake']))
            if p.get('boom'):
                self.events.append((when, os.path.join(HERE, 'fx_boom.wav'), 0.55, 1.0, 0.0))
        for i, b in enumerate(v.get('whips', [])):
            c = self.time(b)
            self.zooms.append((c, -0.16))
            self.events.append((c - 0.30, os.path.join(HERE, f'fx_whoosh{i % 2 + 1}.wav'), 0.55, 1.0, 0.0))
        anchors = {'head': self.H * 0.19 if self.H > self.W else self.H * 0.2, 'center': self.H / 2 - (60 if self.H > self.W else 40),
                   'low': self.H * 0.645 if self.H > self.W else self.H - 170}
        for i, c in enumerate(v.get('captions', [])):
            a, b = self.time(c['from']), self.time(c['to'])
            if a is None or b is None:
                continue
            y = anchors.get(c.get('y', 'low'), c.get('y'))
            self.captions.append((self.text_image(c['lines'], c.get('size', 84), i), a, b, y, c.get('pop', True)))

    def zoom_expr(self):
        terms = [f'{a}*if(lt(t,{c:.4f}),exp(-pow((t-{c:.4f})/0.035,2)),exp(-pow((t-{c:.4f})/0.22,2)))' if a >= 0
                 else f'{-a}*exp(-pow((t-{c:.4f})/0.07,2))' for c, a in self.zooms]
        terms += [f'{amp * 2.6 / self.W:.5f}*if(gte(t,{c:.4f}),exp(-(t-{c:.4f})/0.3),0)' for c, amp in self.shakes]
        return '1+' + '+'.join(terms) if terms else '1'

    def shake_expr(self, freq):
        return '+'.join(f'{amp}*if(gte(t,{c:.4f}),exp(-(t-{c:.4f})/0.22)*sin(2*PI*{freq}*(t-{c:.4f})),0)'
                        for c, amp in self.shakes) or '0'

    def render(self):
        W, H, v = self.W, self.H, self.v
        parts = [f'[0:v]split={len(self.clips)}' + ''.join(f'[s{i}]' for i in range(len(self.clips)))]
        for i, c in enumerate(self.clips):
            extra = ',' + c['filter'] if c.get('filter') else ''
            parts.append(f"[s{i}]trim=start={c['a']}:end={c['b']},setpts=(PTS-STARTPTS)/{c['speed']:.5f},fps=30{extra},"
                         f"scale={W}:{H}:flags=lanczos,setsar=1,trim=duration={c['beats'] * self.beat:.4f},setpts=PTS-STARTPTS[c{i}]")
        parts.append(''.join(f'[c{i}]' for i in range(len(self.clips))) + f'concat=n={len(self.clips)}:v=1:a=0[cat]')
        z = self.zoom_expr()
        parts.append(f"[cat]scale=w='2*trunc({W}*({z})/2)':h='2*trunc({H}*({z})/2)':eval=frame:flags=bicubic,"
                     f"crop={W}:{H}:x='(iw-{W})/2+({self.shake_expr(23)})':y='(ih-{H})/2+({self.shake_expr(31)})'[fx]")
        cur = 'fx'
        for i, (path, a, b, y, pop) in enumerate(self.captions):
            p = (f'if(lt(t,{a:.3f}),0.02,if(lt(t,{a + 0.11:.3f}),0.55+0.6*(t-{a:.3f})/0.11,'
                 f'if(lt(t,{a + 0.2:.3f}),1.15-0.15*(t-{a + 0.11:.3f})/0.09,1)))') if pop else '1'
            parts.append(f"[{i + 2}:v]format=rgba,scale=w='max(2,2*trunc(iw*({p})/2))':h=-2:eval=frame,"
                         f'fade=t=out:st={b - 0.12:.3f}:d=0.12:alpha=1[t{i}]')
            parts.append(f"[{cur}][t{i}]overlay=x='(W-w)/2':y='{y}-h/2':enable='between(t,{a:.3f},{b:.3f})'[o{i}]")
            cur = f'o{i}'
        end_fade = v.get('end_fade', 0.6 if self.W > self.H else 0)
        tail = f'fade=t=out:st={self.duration - end_fade:.3f}:d={end_fade},' if end_fade else ''
        parts.append(f'[{cur}]{tail}format=yuv420p[vout]')
        graph = os.path.join(self.dir, f'{self.name}_graph.txt')
        open(graph, 'w').write(';'.join(parts))

        sfx = os.path.join(self.dir, f'{self.name}_sfx.wav')
        mix(self.events, self.duration, sfx)
        drop = self.time(v.get('hook_beats', 4))
        music_fade = v.get('music_fade', 2.4 if self.W > self.H else 0.5)
        music = os.path.join(self.dir, f'{self.name}_music.wav')
        run('-i', self.path(self.cfg['music']), '-filter_complex',
            f'[0:a]atrim=start={self.first_beat},asetpts=PTS-STARTPTS,aresample={RATE},asplit=2[m1][m2];'
            f'[m1]atrim=0:{drop:.4f},lowpass=f=650,lowpass=f=650,volume=-3dB[hook];'
            f'[m2]atrim=start={drop:.4f},asetpts=PTS-STARTPTS[main];'
            f'[hook][main]concat=n=2:v=0:a=1,atrim=0:{self.duration:.4f},volume=-11dB,'
            f'afade=t=out:st={self.duration - music_fade:.3f}:d={music_fade}[m]', '-map', '[m]', music)
        pre = os.path.join(self.dir, f'{self.name}_premaster.wav')
        run('-i', music, '-i', sfx, '-filter_complex',
            '[1:a]asplit=2[key][fx];[0:a][key]sidechaincompress=threshold=0.04:ratio=4:attack=10:release=280[bed];'
            '[fx]volume=2.0[fxl];[bed][fxl]amix=inputs=2:normalize=0,alimiter=limit=0.95:level=false[m]', '-map', '[m]', pre)
        m = subprocess.run(['ffmpeg', '-hide_banner', '-i', pre, '-af', 'loudnorm=I=-14:TP=-1.5:LRA=11:print_format=json',
                            '-f', 'null', '-'], capture_output=True, text=True).stderr
        s = json.loads(m[m.rindex('{'):m.rindex('}') + 1])
        master = os.path.join(self.dir, f'{self.name}_master.wav')
        run('-i', pre, '-af', f"loudnorm=I=-14:TP=-1.5:LRA=11:measured_I={s['input_i']}:measured_TP={s['input_tp']}:"
                              f"measured_LRA={s['input_lra']}:measured_thresh={s['input_thresh']}:offset={s['target_offset']}:linear=true",
            '-ar', str(RATE), master)
        caps = [x for path, *_ in self.captions for x in ('-loop', '1', '-t', f'{self.duration:.3f}', '-i', path)]
        out = self.path(v['out'])
        run('-i', self.path(v['take'] + '.mp4'), '-i', master, *caps, '-/filter_complex', graph, '-map', '[vout]', '-map', '1:a',
            '-c:v', 'libx264', '-preset', 'slow', '-crf', '16', '-profile:v', 'high', '-level', '4.2', '-g', '15', '-bf', '2',
            '-r', '30', '-c:a', 'aac', '-b:a', '384k', '-ar', str(RATE), '-movflags', '+faststart', '-t', f'{self.duration:.3f}', out)
        sheet = os.path.join(self.dir, f'{self.name}_contact.png')
        tw = 320 if W > H else 216
        run('-i', out, '-vf', f'fps=2,scale={tw}:-2,tile=10x{math.ceil(self.duration * 2 / 10)}', '-frames:v', '1', sheet)
        print(f'{self.name}: {out} ({self.duration:.2f}s, {len(self.events)} sounds). Contact sheet: {sheet}')


def thumbnail(cfg):
    t = cfg['thumbnail']
    W, H = 1280, 720
    shots = []
    for take, second, cx, cy in t['frames']:
        png = os.path.join(cfg['dir'], f'thumb_{len(shots)}.png')
        run('-ss', str(second), '-i', os.path.join(cfg['dir'], take + '.mp4'), '-frames:v', '1', png)
        im = Image.open(png).convert('RGB')
        x0, y0 = max(0, min(im.width - 900, cx - 450)), max(0, min(im.height - 1012, cy - 506))
        shots.append(im.crop((x0, y0, x0 + 900, y0 + 1012)).resize((848, H), Image.LANCZOS))
    canvas = Image.new('RGB', (W, H))
    canvas.paste(shots[-1], (W - 848, 0))
    mask = Image.new('L', (848, H), 0)
    ImageDraw.Draw(mask).polygon([(0, 0), (W * 0.56, 0), (W * 0.44, H), (0, H)], fill=255)
    canvas.paste(shots[0], (0, 0), mask)
    if len(shots) > 1:
        ImageDraw.Draw(canvas).line([(W * 0.56, 0), (W * 0.44, H)], fill=(232, 176, 74), width=12)
    canvas = ImageEnhance.Color(ImageEnhance.Contrast(canvas).enhance(1.12)).enhance(1.2).convert('RGBA')
    shade = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    for y in range(260):
        ImageDraw.Draw(shade).line([(0, H - 260 + y), (W, H - 260 + y)], fill=(0, 0, 0, int(220 * (y / 260) ** 1.4)))
    canvas = Image.alpha_composite(canvas, shade)
    big, small = ImageFont.truetype(FONT_BLACK, 96), ImageFont.truetype(FONT_BLACK, 40)
    txt = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    td = ImageDraw.Draw(txt)
    lines = t['lines'][-2:]
    for i, line in enumerate(lines):
        w = td.textlength(line, font=big)
        td.text(((W - w) / 2, H - 105 * (len(lines) - i) - 40), line, font=big,
                fill=(255, 255, 255) if i == 0 else COLOURS['gold'], stroke_width=7, stroke_fill=(16, 12, 8))
    if t.get('tag'):
        tw = td.textlength(t['tag'], font=small)
        td.rounded_rectangle((30, 28, 74 + tw, 92), radius=12, fill=(232, 176, 74, 255), outline=(16, 12, 8, 255), width=4)
        td.text((52, 34), t['tag'], font=small, fill=(20, 16, 10))
    glow = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    glow.paste((0, 0, 0, 170), (0, 0), txt.split()[3].filter(ImageFilter.GaussianBlur(9)))
    out = os.path.join(cfg['dir'], t.get('out', 'thumbnail.jpg'))
    Image.alpha_composite(Image.alpha_composite(canvas, glow), txt).convert('RGB').save(out, quality=93)
    print('thumbnail:', out)


def main():
    cfg = json.load(open(sys.argv[1]))
    cfg.setdefault('dir', os.path.dirname(os.path.abspath(sys.argv[1])))
    args = sys.argv[2:]
    if '--thumbnail' in args:
        return thumbnail(cfg)
    music = cfg['music'] if os.path.isabs(cfg['music']) else os.path.join(cfg['dir'], cfg['music'])
    beat, first = cfg.get('beat') or beat_grid(music)
    if '--beats' in args:
        return print(f'beat {beat:.4f}s ({60 / beat:.1f} bpm), first beat {first:.3f}s')
    index = asset_index()
    for name in (args or list(cfg['versions'])):
        version = Version(cfg, name, beat, first)
        version.build(index)
        version.render()


if __name__ == '__main__':
    main()
