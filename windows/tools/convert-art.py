# The web's paintings, as the Mac carries them (mac/Chataltul/Resources/cats and cafe/gone-1.webp), made into PNG
# files for Windows, which can't read WebP on every PC (Windows Server and some editions have no WebP codec).
# Nothing is painted or changed: the same pixels, another file format. Run by windows/build.ps1 and the
# Windows workflow before the app is built; git leaves the result (windows/art) out.
#
#   python windows/tools/convert-art.py [out folder]
#
# Needs Pillow (pip install pillow).
import pathlib
import sys

from PIL import Image

root = pathlib.Path(__file__).resolve().parents[2]
res = root / 'mac' / 'Chataltul' / 'Resources'
out = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else root / 'windows' / 'art'
files = sorted((res / 'cats').glob('*.webp')) + [res / 'cafe' / 'gone-1.webp']
made = 0
for f in files:
    dest = out / f.relative_to(res).with_suffix('.png')
    if dest.exists() and dest.stat().st_mtime >= f.stat().st_mtime:
        continue
    dest.parent.mkdir(parents=True, exist_ok=True)
    with Image.open(f) as im:
        im.convert('RGBA').save(dest, optimize=True)
    made += 1
if not files:
    sys.exit(f'no paintings in {res}')
print(f'{len(files)} paintings in {out} ({made} made now)')
