#!/usr/bin/env python3
"""Walk Steve with Minecraft dev commands and compare his feet to BeamNG's ground under him.

    python beamng/bridge/walktest.py <mcX> <mcY> <mcZ> <yaw> <ticks> [--shot DIR]

Needs both games running with the crossover (scripts/run-beamng.ps1, scripts/run-minecraft.ps1).
Prints Steve's feet height and BeamNG's ground height (castRayStatic through the same cell)
for every sample, and the mean/max difference.
"""
import math
import os
import re
import subprocess
import sys
import time

import bngdiag

DIR = os.path.join(os.environ['TEMP'], 'bngmc')
OUT = os.path.join(DIR, 'mc_out.txt')


def cmd(*lines):
    with open(os.path.join(DIR, 'mc_cmd.txt'), 'a', encoding='utf-8') as f:
        f.write('\n'.join(lines) + '\n')


def status():
    before = os.path.getsize(OUT) if os.path.exists(OUT) else 0
    cmd('status')
    time.sleep(0.25)
    with open(OUT, 'rb') as f:
        f.seek(before)
        txt = f.read().decode('utf-8', 'replace')
    m = re.search(r'player canonical \(BeamNG\): \(([-\d.]+), ([-\d.]+), ([-\d.]+)\)', txt)
    return (float(m.group(1)), float(m.group(2)), float(m.group(3)), 'HOLDING' in txt) if m else None


def ground(link, x, y, z, req):
    link.send('raycols', {'req': req, 'bx': math.floor(x), 'by': math.floor(y), 'zTop': z + 40, 'zMid': z + 4, 'zBot': z - 40,
                          'zProbe': z + 1, 'cols': [0, 0]})
    end = time.time() + 2
    while time.time() < end:
        m = link.recv()
        if m and m['t'] == 'rayhits' and m['req'] == req:
            g = m['res'][0] if m['res'][0] != m['miss'] else m['res'][1]
            return None if g == m['miss'] else g
    return None


def main():
    a = sys.argv[1:]
    x, y, z, yaw, ticks = float(a[0]), float(a[1]), float(a[2]), float(a[3]), int(a[4])
    shot = a[a.index('--shot') + 1] if '--shot' in a else None
    cmd(f'run tp @s {x} {y} {z}', f'look {yaw} 10')
    time.sleep(2.0)
    cmd(f'key forward {ticks}')
    path, t0, shot_done = [], time.time(), False
    while time.time() - t0 < ticks / 20 + 0.5:
        s = status()
        if s:
            path.append(s)
        if shot and not shot_done and time.time() - t0 > ticks / 40:
            here = os.path.dirname(os.path.abspath(__file__))
            subprocess.run(['powershell', '-NoProfile', '-File', os.path.join(here, '..', 'scripts', 'screenshot-pair.ps1'), '-Out', shot],
                           capture_output=True)
            shot_done = True
    if not path:
        print('FAIL: no status from Minecraft')
        sys.exit(1)
    link = bngdiag.Link('walktest')
    if not link.connect():
        print('FAIL: BeamNG not answering')
        sys.exit(2)
    diffs = []
    for i, (cx, cy, cz, hold) in enumerate(path):
        g = ground(link, cx, cy, cz, 7000 + i)
        d = None if g is None else cz - g
        if d is not None:
            diffs.append(abs(d))
        print(f'  ({cx:8.2f}, {cy:8.2f})  feet {cz:8.3f}  BeamNG ground {g if g is not None else float("nan"):8.3f}  '
              f'diff {d if d is not None else float("nan"):+6.3f}{"  HOLD" if hold else ""}')
    link.bye()
    dist = math.hypot(path[-1][0] - path[0][0], path[-1][1] - path[0][1])
    print(f'walked {dist:.1f} m, height change {path[-1][2] - path[0][2]:+.2f} m; |feet - ground| mean {sum(diffs) / len(diffs):.3f} m, '
          f'max {max(diffs):.3f} m over {len(diffs)} samples')


if __name__ == '__main__':
    main()
