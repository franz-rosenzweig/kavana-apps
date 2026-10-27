#!/usr/bin/env python3
"""Makes the widgets' previews for the launcher's widget list from the real widgets: opens the Widget lab (a debug
build on the emulator) on each widget's sample, photographs it, crops the widget out and saves it as
android/app/src/main/res/drawable-nodpi/widget_preview_<name>.png.

    KAVANA_ADB=<adb> python3 tools/widget-previews.py

The emulator needs the debug app installed (and the lab's one-time "allow widgets" tapped); light mode.
"""
import io
import os
import subprocess
import sys
import time

from PIL import Image, ImageDraw

ADB = os.environ.get('KAVANA_ADB') or sys.exit('KAVANA_ADB=<path to adb> (an emulator with the debug app)')
PKG = 'xyz.tiresia.kavana.debug'
OUT = os.path.join(os.path.dirname(__file__), '../android/app/src/main/res/drawable-nodpi')
BG = (230, 226, 218)  # the lab's background

# name -> (lab set, which widget in it, from the top)
PREVIEWS = {'window': ('window', 0), 'quickstart': ('quick', 0), 'today': ('today', 0), 'later': ('later', 0), 'sound': ('sound', 0)}


def shot(set_):
    subprocess.run([ADB, 'shell', 'am', 'force-stop', PKG], check=True)
    subprocess.run([ADB, 'shell', 'am', 'start', '-n', f'{PKG}/xyz.tiresia.kavana.widget.WidgetLab', '--es', 'set', set_], check=True, capture_output=True)
    time.sleep(6)
    png = subprocess.run([ADB, 'exec-out', 'screencap', '-p'], check=True, capture_output=True).stdout
    return Image.open(io.BytesIO(png)).convert('RGB')


def cards(im):
    """The widgets on the page: (left, top, right, bottom), top to bottom, found as runs that aren't the lab's background."""
    d = lambda p: sum(abs(a - b) for a, b in zip(p, BG)) > 25
    w, h = im.size
    runs, cur = [], None
    for y in range(110, h - 120):
        v = any(d(im.getpixel((x, y))) for x in range(60, w - 60, 8))
        if v and cur is None:
            cur = y
        if not v and cur is not None:
            if y - cur > 100:
                runs.append((cur, y - 1))
            cur = None
    out = []
    for a, b in runs:
        xs = [x for x in range(w) if any(d(im.getpixel((x, y))) for y in range(a + 10, b - 10, 8))]
        out.append((xs[0], a, xs[-1] + 1, b + 1))
    return out


for name, (set_, i) in PREVIEWS.items():
    im = shot(set_)
    found = cards(im)
    if i >= len(found):
        sys.exit(f'{name}: found {len(found)} widgets on the {set_} page, wanted number {i}')
    crop = im.crop(found[i]).convert('RGBA')
    # the widget's rounded corners (30 dp, at the lab's 2.625 px a dp), with the lab's background cut away
    mask = Image.new('L', crop.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, crop.size[0] - 1, crop.size[1] - 1), radius=min(79, crop.size[1] // 2), fill=255)
    crop.putalpha(mask)
    path = os.path.join(OUT, f'widget_preview_{name}.png')
    crop.save(path, optimize=True)
    print(f'{name}: {crop.size[0]}x{crop.size[1]} -> {os.path.relpath(path)}')
