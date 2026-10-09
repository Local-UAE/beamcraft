#!/usr/bin/env python3
"""Crossover diagnostics: samples BeamNG over the control link and Minecraft through its dev
channel, prints one dashboard, and grades realtime operation against fixed thresholds.

    python beamng/bridge/diagnose.py [seconds]      (or scripts/diagnose.ps1)

Exit code 0 = every check passed, 1 = a check failed, 2 = a game isn't reachable.
BeamNG is sampled as a separate observer client, so Minecraft's own session isn't disturbed.
"""
import os
import re
import statistics
import sys
import time

import bngdiag

DIR = os.path.join(os.environ.get('TEMP', '.'), 'bngmc')
OUT = os.path.join(DIR, 'mc_out.txt')

# Thresholds for "realtime operation" with both games running on one PC.
MIN_BNG_FPS = 30
MIN_MC_FPS = 30
MIN_STATE_HZ = 30
MAX_RTT_MS = 10
MAX_DROP_RATE = 0.01
MAX_LUA_MS = 1.0
MAX_STATE_GAP_MS = 250


def minecraft_status():
    """Runs the 'status' dev command; returns the HUD lines, or None if Minecraft didn't answer."""
    if not os.path.isdir(DIR):
        return None
    before = os.path.getsize(OUT) if os.path.exists(OUT) else 0
    with open(os.path.join(DIR, 'mc_cmd.txt'), 'a', encoding='utf-8') as f:
        f.write('status\n')
    end = time.time() + 3
    while time.time() < end:
        time.sleep(0.2)
        if os.path.exists(OUT) and os.path.getsize(OUT) > before:
            time.sleep(0.2)
            with open(OUT, 'rb') as f:
                f.seek(before)
                txt = f.read().decode('utf-8', 'replace')
            if 'STATUS' in txt:
                return [ln.strip() for ln in txt.splitlines() if ln.startswith('  ')]
    return None


def grab(lines, pattern, cast=float):
    for ln in lines or []:
        m = re.search(pattern, ln)
        if m:
            return cast(m.group(1))
    return None


def main():
    seconds = float(sys.argv[1]) if len(sys.argv) > 1 else 5.0
    link = bngdiag.Link('diagnose')
    if not link.connect():
        print('BeamNG: not reachable on the control link (is it running with the mccross mod?)')
        sys.exit(2)
    states, times, cams = [], [], []
    rx0 = link.rx_bytes
    t0 = time.time()

    def take(m):
        if m['t'] == 'state':
            states.append(m)
            times.append(bngdiag.now_ms())

    link.pump(seconds, take)
    dt = time.time() - t0
    link.bye()
    mc = minecraft_status()

    last = states[-1] if states else {}
    fps = statistics.mean(s.get('fps', 0) for s in states) if states else 0
    lua_ms = statistics.mean(s.get('luaMs', 0) for s in states) if states else float('nan')
    rate = len(states) / dt
    gaps = [b - a for a, b in zip(times, times[1:])]
    worst_gap = max(gaps) if gaps else float('inf')
    total = len(states) + link.dropped
    drop_rate = link.dropped / total if total else 0
    cam = last.get('cam') or {}
    driven = sum(1 for s in states if (s.get('cam') or {}).get('ovr')) / max(1, len(states))
    veh = last.get('veh') or {}

    mc_fps = grab(mc, r'^fps: (\d+)', int)
    print('=== MINECRAFT ===')
    if mc is None:
        print('connected: NO (no answer on the dev channel; is the dev client running?)')
    else:
        print(f"connected: {grab(mc, r'BEAMNG CONNECTED: (\w+)', str)}")
        print(f'FPS: {mc_fps}')
        for key in ('player MC', 'player canonical', 'camera sent', 'overlay', 'fps: '):
            ln = next((x for x in mc if x.startswith(key)), None)
            if ln and key != 'fps: ':
                print(ln)
    print()
    print('=== BEAMNG ===')
    w = link.welcome or {}
    print(f"connected: YES   version: {w.get('bngVersion')}   map: {last.get('level')}   paused: {last.get('paused')}")
    print(f'FPS: {fps:.0f}   crossover Lua per frame: {lua_ms:.3f} ms')
    print(f"camera: {cam.get('mode')} {[round(x, 2) for x in cam.get('pos', [])]} fov {cam.get('fov', 0):.1f}   "
          f"driven by Minecraft in {driven * 100:.0f}% of frames")
    print(f"active vehicle: {veh.get('id')} {veh.get('model')} at {[round(x, 1) for x in veh.get('pos', [])]}, "
          f"{veh.get('speed', 0):.1f} m/s")
    print()
    print('=== BRIDGE ===')
    print(f'protocol: v{bngdiag.VERSION}   session: {w.get("sid")}   RTT: {link.rtt:.2f} ms' if link.rtt else 'RTT: no pong')
    print(f'state rate: {rate:.1f} Hz   worst gap {worst_gap:.0f} ms   bytes/sec: {(link.rx_bytes - rx0) / dt / 1024:.1f} KiB')
    print(f'dropped: {link.dropped} ({drop_rate * 100:.2f}%)   stale: {link.stale}   bad: {link.bad}')
    if mc:
        for key in ('terrain:', 'vehicles:', 'round-trip', 'rx '):
            ln = next((x for x in mc if x.startswith(key)), None)
            if ln:
                print(f'minecraft side {ln}')
    print()
    print('=== RENDERING ===')
    print('backend: none yet (overlay window mode; D3D11 compositor not built)')
    if mc:
        ln = next((x for x in mc if x.startswith('overlay')), None)
        print(ln or 'overlay: ?')
    print()

    checks = [
        (f'BeamNG fps >= {MIN_BNG_FPS}', fps >= MIN_BNG_FPS, f'{fps:.0f}'),
        (f'Minecraft fps >= {MIN_MC_FPS}', mc_fps is not None and mc_fps >= MIN_MC_FPS, str(mc_fps)),
        (f'state rate >= {MIN_STATE_HZ} Hz', rate >= MIN_STATE_HZ, f'{rate:.1f}'),
        (f'worst state gap <= {MAX_STATE_GAP_MS} ms', worst_gap <= MAX_STATE_GAP_MS, f'{worst_gap:.0f}'),
        (f'RTT <= {MAX_RTT_MS} ms', link.rtt is not None and link.rtt <= MAX_RTT_MS, f'{link.rtt:.2f}' if link.rtt else '-'),
        (f'dropped <= {MAX_DROP_RATE * 100:.0f}%', drop_rate <= MAX_DROP_RATE, f'{drop_rate * 100:.2f}%'),
        (f'crossover Lua <= {MAX_LUA_MS} ms/frame', lua_ms <= MAX_LUA_MS, f'{lua_ms:.3f}'),
        ('camera driven by Minecraft (>= 95% of frames)', driven >= 0.95, f'{driven * 100:.0f}%'),
    ]
    ok = True
    for name, passed, val in checks:
        ok &= passed
        print(f"  {'PASS' if passed else 'FAIL'}  {name:42s} {val}")
    print('REALTIME: PASS' if ok else 'REALTIME: FAIL')
    sys.exit(0 if ok else 1)


if __name__ == '__main__':
    main()
