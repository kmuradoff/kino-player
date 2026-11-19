"""Material Symbols Rounded SVGs (lumen-icons pin) as Android vector drawables.

Usage: python3 art/symbols.py SVG_DIR name [name ...]
Writes app/src/main/res/drawable/ic_<name>.xml. The SVGs use viewBox 0 -960 960 960 (older ones 0 0 24 24).
"""
import os, re, sys

src, names = sys.argv[1], sys.argv[2:]
out = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'res', 'drawable')
for n in names:
    svg = open(os.path.join(src, n + '.svg')).read()
    vb = re.search(r'viewBox="([^"]+)"', svg)
    x0, y0, w, h = (float(v) for v in (vb.group(1) if vb else '0 0 24 24').split())
    paths = re.findall(r'<path[^>]* d="([^"]+)"', svg)
    body = ''.join(f'\n        <path android:fillColor="#FFFFFFFF" android:pathData="{d}" />' for d in paths)
    xml = (f'<?xml version="1.0" encoding="utf-8"?>\n<!-- Material Symbols Rounded "{n}" (Apache-2.0, Google) -->\n'
           f'<vector xmlns:android="http://schemas.android.com/apk/res/android"\n    android:width="24dp" android:height="24dp"\n'
           f'    android:viewportWidth="{w:g}" android:viewportHeight="{h:g}">\n'
           f'    <group android:translateX="{-x0:g}" android:translateY="{-y0:g}">{body}\n    </group>\n</vector>\n')
    open(os.path.join(out, f'ic_{n}.xml'), 'w').write(xml)
    print('ic_' + n, len(paths))
