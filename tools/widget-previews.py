#!/usr/bin/env python3
"""Makes the widgets' previews for the launcher's widget list from the real widgets: opens the Widget lab (a debug
build on the emulator) on each widget's sample, photographs it, crops the widget out and saves it as
android/app/src/main/res/drawable-nodpi/widget_preview_<name>.png.

    KAVANA_ADB=<adb> python3 tools/widget-previews.py [name ...]

The emulator needs the debug app installed (and the lab's one-time "allow widgets" tapped); light mode. The café
preview wants a café with cats in it: play a few blocks first (tools/check-widgets.py cafe does, and leaves them).
"""
import io
import os
import re
import subprocess
import sys
import time

from PIL import Image, ImageDraw

ADB = os.environ.get('KAVANA_ADB') or sys.exit('KAVANA_ADB=<path to adb> (an emulator with the debug app)')
PKG = 'xyz.tiresia.kavana.debug'
OUT = os.path.join(os.path.dirname(__file__), '../android/app/src/main/res/drawable-nodpi')
BG = (230, 226, 218)  # the lab's background

# name -> (lab set, which part of it (four widgets at a time, two for the café), which widget in that part)
PREVIEWS = {
    'window': ('window', 0, 0), 'quickstart': ('quick', 0, 0), 'today': ('today', 0, 0), 'later': ('later', 0, 0), 'sound': ('sound', 0, 0),
    'streak': ('stats', 0, 0), 'goal': ('stats', 0, 3), 'missions': ('stats', 1, 3),
    'pet': ('pet', 0, 0), 'clock': ('clock', 0, 0), 'constellation': ('sky', 0, 0), 'weather': ('sky', 1, 0),
    'cafe': ('cafe', 0, 0), 'friends': ('friends', 0, 0),
}


def adb(*a):
    return subprocess.run([ADB, *a], check=True, capture_output=True).stdout


def shot(set_, part):
    adb('shell', 'am', 'force-stop', PKG)
    adb('shell', 'am', 'start', '-n', f'{PKG}/xyz.tiresia.kavana.widget.WidgetLab', '--es', 'set', set_, '--ei', 'part', str(part), '--ei', 'per', '2' if set_ == 'cafe' else '4')
    time.sleep(7)
    return Image.open(io.BytesIO(adb('exec-out', 'screencap', '-p'))).convert('RGB')


def cards(im):
    """The widgets on the page: (left, top, right, bottom), top to bottom, found as runs that aren't the lab's background."""
    d = lambda p: sum(abs(a - b) for a, b in zip(p, BG)) > 24
    w, h = im.size
    band = range(w // 2 - 130, w // 2 + 130, 5)
    runs, cur = [], None
    for y in range(0, h):
        v = any(d(im.getpixel((x, y))) for x in band)
        if v and cur is None:
            cur = y
        if not v and cur is not None:
            if y - cur >= 80 and cur >= 100:
                runs.append((cur, y))
            cur = None
    out = []
    for a, b in runs:
        xs = [x for x in range(0, w, 2) if any(d(im.getpixel((x, y))) for y in range(a + 6, b - 6, 6))]
        out.append((xs[0] - 3, a - 2, xs[-1] + 4, b + 2))
    return out


px_per_dp = int(re.search(r'(\d+)', adb('shell', 'wm', 'density').decode().split('Physical density:')[-1]).group(1)) / 160
wanted = sys.argv[1:] or list(PREVIEWS)
pages = {}
for name in wanted:
    set_, part, i = PREVIEWS[name]
    if (set_, part) not in pages:
        pages[(set_, part)] = shot(set_, part)
    im = pages[(set_, part)]
    found = cards(im)
    if i >= len(found):
        sys.exit(f'{name}: found {len(found)} widgets on part {part} of the {set_} set, wanted number {i}')
    crop = im.crop(found[i]).convert('RGBA')
    # the widget's rounded corners (30 dp), with the lab's background cut away
    mask = Image.new('L', crop.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, crop.size[0] - 1, crop.size[1] - 1), radius=min(30 * px_per_dp, crop.size[1] / 2.2), fill=255)
    crop.putalpha(mask)
    path = os.path.join(OUT, f'widget_preview_{name}.png')
    crop.save(path, optimize=True)
    print(f'{name}: {crop.size[0]}x{crop.size[1]} -> {os.path.relpath(path)}')
