#!/usr/bin/env python3
"""Diagnostic client for the BeamNG crossover control link (UDP, 127.0.0.1).

Speaks the same protocol as the Minecraft mod (beamng/docs/protocol.md), so BeamNG can be
tested without Minecraft running. Standard library only.

    python beamng/bridge/bngdiag.py telemetry [seconds]   # live vehicle telemetry, one line per state
    python beamng/bridge/bngdiag.py bench [seconds]       # rates, RTT, drops; PASS if state >= 30 Hz
    python beamng/bridge/bngdiag.py ping [count]
    python beamng/bridge/bngdiag.py debug "some text"     # BeamNG logs it as [MCCROSS] debug: ...
    python beamng/bridge/bngdiag.py camtest [seconds]     # BeamNG orbits its camera around the vehicle
    python beamng/bridge/bngdiag.py camfollow [seconds]   # drives BeamNG's camera from here: circles the vehicle
    python beamng/bridge/bngdiag.py ground [radius]       # cast a grid of down rays around the vehicle
    python beamng/bridge/bngdiag.py vehicles              # nearby vehicles
    python beamng/bridge/bngdiag.py reconnect             # connect, drop, reconnect; checks session handling
    python beamng/bridge/bngdiag.py raw [seconds]         # print every message as JSON
    python beamng/bridge/bngdiag.py reload                # BeamNG reloads the mccross extension from disk
    python beamng/bridge/bngdiag.py push [speed]          # push the player vehicle forward (m/s), proxy tests
    python beamng/bridge/bngdiag.py level gridmap_v2      # switch BeamNG to another level (freeroam)
    python beamng/bridge/bngdiag.py place x y z [fx fy fz] # teleport + repair the player vehicle facing (fx, fy, fz)

Exit code 0 = PASS (or command done), 1 = FAIL, 2 = could not connect.
"""
import json
import math
import os
import socket
import sys
import time

VERSION = 1
HOST = "127.0.0.1"
PORT = int(os.environ.get("BNGBRIDGE_PORT", "47020"))
MAX_TO_BEAMNG = 8000


def now_ms():
    return time.perf_counter() * 1000.0


class Link:
    def __init__(self, name="bngdiag"):
        self.name = name
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.bind((HOST, 0))
        self.sock.settimeout(0.02)
        self.peer = (HOST, PORT)
        self.sid = 0
        self.seq = 0
        self.connected = False
        self.welcome = None
        self.last_peer_seq = -1
        self.last_rx = 0.0
        self.rx = self.tx = self.rx_bytes = self.tx_bytes = 0
        self.dropped = self.stale = self.bad = 0
        self.rtt = None

    def send(self, t, payload=None):
        self.seq += 1
        msg = {"v": VERSION, "t": t, "sid": self.sid, "seq": self.seq, "ts": now_ms()}
        if payload:
            msg.update(payload)
        data = json.dumps(msg, separators=(",", ":")).encode()
        if len(data) > MAX_TO_BEAMNG:
            raise ValueError(f"{t} message is {len(data)} bytes, limit {MAX_TO_BEAMNG}")
        try:
            self.sock.sendto(data, self.peer)
        except OSError:
            return False
        self.tx += 1
        self.tx_bytes += len(data)
        return True

    def recv(self):
        """One message from the current session, or None (timeout / stale / not for us)."""
        try:
            data, _ = self.sock.recvfrom(65535)
        except socket.timeout:
            return None
        except ConnectionResetError:  # ICMP port unreachable: BeamNG isn't listening
            return None
        self.rx_bytes += len(data)
        try:
            m = json.loads(data)
            if m.get("v") != VERSION:
                self.bad += 1
                return None
        except ValueError:
            self.bad += 1
            return None
        self.rx += 1
        t = m.get("t")
        if t == "welcome":
            if not (self.connected and m["sid"] == self.sid):
                self.sid = m["sid"]
                self.connected = True
                self.welcome = m
                self.last_peer_seq = m["seq"]
            self.last_rx = now_ms()
            return m
        if t == "error" and m.get("code") == "unknown_session" and m.get("sid") == self.sid:
            self.connected = False
            self.sid = 0
            return m
        if not self.connected or m.get("sid") != self.sid:
            self.stale += 1
            return None
        self.last_rx = now_ms()
        gap = m["seq"] - self.last_peer_seq
        if gap > 1:
            self.dropped += gap - 1
        elif gap <= 0:
            self.stale += 1
            return None
        self.last_peer_seq = m["seq"]
        if t == "pong" and "echo" in m:
            r = now_ms() - m["echo"]
            self.rtt = r if self.rtt is None else self.rtt * 0.8 + r * 0.2
        return m

    def connect(self, timeout=5.0):
        end = time.time() + timeout
        last_hello = 0.0
        while time.time() < end:
            if time.time() - last_hello > 0.5:
                last_hello = time.time()
                self.sid = 0
                self.send("hello", {"client": self.name, "protocol": VERSION})
            m = self.recv()
            if m and m.get("t") == "welcome":
                return True
        return False

    def pump(self, seconds, on_msg=None, ping_every=0.25):
        end = time.time() + seconds
        last_ping = 0.0
        while time.time() < end:
            if time.time() - last_ping > ping_every:
                last_ping = time.time()
                self.send("ping")
            m = self.recv()
            if m and on_msg:
                if on_msg(m) is False:
                    return

    def bye(self):
        if self.connected:
            self.send("bye")


def fmt3(v):
    return "(" + ", ".join(f"{x:8.2f}" for x in v) + ")" if v else "-"


def need(link):
    if not link.connect():
        print(f"FAIL: no WELCOME from BeamNG on {HOST}:{PORT} within 5 s. Is BeamNG running with the mccross mod and a level loaded?")
        sys.exit(2)
    w = link.welcome
    print(f"connected: session {w['sid']}  BeamNG {w.get('bngVersion')}  level {w.get('level')}  protocol v{w['v']}")


def cmd_telemetry(link, seconds=20.0):
    need(link)
    count = [0]
    t0 = time.time()

    def show(m):
        if m["t"] != "state":
            return
        count[0] += 1
        v = m.get("veh") or {}
        c = m.get("cam") or {}
        hz = count[0] / max(time.time() - t0, 1e-6)
        rtt = f"{link.rtt:5.1f}" if link.rtt is not None else "  -  "
        print(f"#{m['seq']:6d} {hz:5.1f}Hz rtt {rtt}ms  map {m.get('level')}  veh {v.get('id')} {v.get('model', '')}"
              f"  pos {fmt3(v.get('pos'))}  speed {v.get('speed', 0):6.2f} m/s  cam {fmt3(c.get('pos'))} fov {c.get('fov', 0):5.1f}"
              f"  paused {m.get('paused')}")

    link.pump(seconds, show)
    link.bye()


def cmd_bench(link, seconds=10.0):
    need(link)
    states = []
    sizes = []

    def note(m):
        if m["t"] == "state":
            states.append(now_ms())

    rx_bytes0 = link.rx_bytes
    t0 = time.time()
    link.pump(seconds, note)
    dt = time.time() - t0
    link.bye()
    rate = len(states) / dt
    gaps = [b - a for a, b in zip(states, states[1:])]
    worst = max(gaps) if gaps else float("nan")
    print(f"state messages: {len(states)} in {dt:.1f} s = {rate:.1f} Hz (worst gap {worst:.1f} ms)")
    print(f"rx: {link.rx} msgs, {(link.rx_bytes - rx_bytes0) / dt / 1024:.1f} KiB/s   tx: {link.tx} msgs")
    print(f"rtt: {link.rtt:.2f} ms" if link.rtt is not None else "rtt: no pong received")
    print(f"dropped: {link.dropped}  stale: {link.stale}  bad: {link.bad}")
    ok = rate >= 30.0 and link.rtt is not None
    print("PASS" if ok else "FAIL", "(need >= 30 Hz state and a working ping)")
    sys.exit(0 if ok else 1)


def cmd_ping(link, count=10):
    need(link)
    rtts = []
    for _ in range(int(count)):
        t = now_ms()
        link.send("ping")
        end = time.time() + 1.0
        while time.time() < end:
            m = link.recv()
            if m and m["t"] == "pong":
                rtts.append(now_ms() - m["echo"])
                break
        time.sleep(0.05)
    link.bye()
    if rtts:
        print(f"{len(rtts)}/{count} pongs  min {min(rtts):.2f} ms  avg {sum(rtts) / len(rtts):.2f} ms  max {max(rtts):.2f} ms")
    else:
        print("FAIL: no pong")
        sys.exit(1)


def cmd_debug(link, *words):
    need(link)
    text = " ".join(words) or "hello from bngdiag"
    link.send("debug", {"text": text})
    link.pump(0.3)
    link.bye()
    print(f"sent debug message: {text!r} (check beamng.log for [MCCROSS])")


def cmd_camtest(link, seconds=8.0):
    need(link)
    link.send("camera_test", {"pattern": "orbit", "seconds": float(seconds)})
    link.pump(float(seconds) + 0.5, lambda m: print(f"cam {fmt3((m.get('cam') or {}).get('pos'))}") if m["t"] == "state" and m["seq"] % 10 == 0 else None)
    link.bye()


def cmd_camfollow(link, seconds=10.0):
    """Drives BeamNG's camera from this process: a circle around the vehicle, looking at it."""
    need(link)
    center = None
    t0 = time.time()
    cseq = 0
    end = t0 + float(seconds)
    last = 0.0
    while time.time() < end:
        m = link.recv()
        if m and m["t"] == "state" and m.get("veh"):
            center = m["veh"]["pos"]
        if center and time.time() - last > 1 / 60:
            last = time.time()
            a = (time.time() - t0) * 0.6
            pos = [center[0] + 8 * math.cos(a), center[1] + 8 * math.sin(a), center[2] + 2.5]
            fwd = [center[0] - pos[0], center[1] - pos[1], center[2] + 0.8 - pos[2]]
            n = math.sqrt(sum(x * x for x in fwd))
            fwd = [x / n for x in fwd]
            cseq += 1
            link.send("camera", {"cseq": cseq, "pos": pos, "fwd": fwd, "up": [0, 0, 1], "fovV": 60.0})
    link.send("camera_release")
    link.pump(0.2)
    link.bye()
    print("camera released")


def cmd_ground(link, radius=4):
    need(link)
    veh = None
    link.pump(0.5, lambda m: (globals().__setitem__("_veh", m.get("veh")) if m["t"] == "state" else None))
    veh = globals().get("_veh")
    if not veh:
        print("FAIL: no vehicle in state")
        sys.exit(1)
    r = int(radius)
    bx, by = math.floor(veh["pos"][0]), math.floor(veh["pos"][1])
    z = veh["pos"][2]
    cols = []
    for dx in range(-r, r + 1):
        for dy in range(-r, r + 1):
            cols += [dx, dy]
    link.send("raycols", {"req": 1, "bx": bx, "by": by, "zTop": z + 40, "zMid": z + 4, "zBot": z - 40, "zProbe": z + 1, "cols": cols})
    got = []
    link.pump(3.0, lambda m: (got.append(m), False)[1] if m["t"] == "rayhits" and m.get("req") == 1 else None)
    link.bye()
    if not got:
        print("FAIL: no rayhits answer")
        sys.exit(1)
    m = got[0]
    print(f"{m['n']} columns in {m.get('ms', 0):.2f} ms on BeamNG's side; stride {m['stride']}")
    res = m["res"]
    s = m["stride"]
    for i in range(m["n"]):
        print(f"  col ({cols[2 * i]:3d},{cols[2 * i + 1]:3d}): " + " ".join(f"{x:8.3f}" if isinstance(x, (int, float)) else str(x) for x in res[i * s:(i + 1) * s]))


def cmd_vehicles(link):
    need(link)
    got = []
    link.pump(1.5, lambda m: (got.append(m), False)[1] if m["t"] == "vehicles" else None)
    link.bye()
    if not got:
        print("no vehicles message within 1.5 s")
        sys.exit(1)
    for v in got[0].get("list", []):
        print(f"  id {v['id']:6d} {v.get('model', ''):16s} pos {fmt3(v['pos'])} vel {fmt3(v.get('vel'))} half {fmt3(v.get('half'))} active {v.get('active')}")


def cmd_reconnect(link):
    need(link)
    sid1 = link.sid
    link.pump(1.0)
    link.bye()
    link.pump(0.3)
    link2 = Link("bngdiag-2")
    if not link2.connect():
        print("FAIL: second connect failed")
        sys.exit(1)
    sid2 = link2.sid
    # A message with the old session must be rejected with unknown_session.
    link.sid = sid1
    link.connected = True
    link.send("ping")
    rejected = False
    end = time.time() + 1.0
    while time.time() < end:
        m = link.recv()
        if m and m.get("t") == "error" and m.get("code") == "unknown_session":
            rejected = True
            break
    link2.bye()
    print(f"session 1 = {sid1}, session 2 = {sid2}, old session rejected after bye: {rejected}")
    ok = sid1 != sid2 and rejected
    print("PASS" if ok else "FAIL")
    sys.exit(0 if ok else 1)


def cmd_reload(link):
    need(link)
    link.send("reload")
    link.pump(0.5)
    print("reload requested; reconnecting...")
    time.sleep(1.0)
    link2 = Link("bngdiag-after-reload")
    if not link2.connect():
        print("FAIL: BeamNG did not answer after the reload (check scripts/logs.ps1)")
        sys.exit(1)
    print(f"PASS: reconnected, new session {link2.sid}")
    link2.bye()


def cmd_push(link, speed=8.0):
    need(link)
    link.send("vehicle_test", {"speed": float(speed)})
    link.pump(0.3)
    link.bye()
    print(f"pushed the player vehicle to {float(speed):.1f} m/s")


def latest_veh(link, seconds=1.0):
    got = []
    link.pump(seconds, lambda m: got.append(m["veh"]) if m["t"] == "state" and m.get("veh") else None)
    return got[-1] if got else None


def place(link, pos, fwd):
    """Teleport + repair the player vehicle; returns how well its forward matches fwd (cosine)."""
    link.send("vehicle_place", {"pos": list(pos), "fwd": list(fwd)})
    link.pump(1.5)
    v = latest_veh(link)
    if not v:
        return None
    n = math.sqrt(sum(c * c for c in fwd[:2])) or 1.0
    return (v["fwd"][0] * fwd[0] + v["fwd"][1] * fwd[1]) / n


def cmd_place(link, x, y, z, fx=0.0, fy=1.0, fz=0.0):
    need(link)
    pos, fwd = (float(x), float(y), float(z)), (float(fx), float(fy), float(fz))
    dot = place(link, pos, fwd)
    link.bye()
    print(f"vehicle placed at {fmt3(pos)}; forward matches the request: cos = {dot if dot is not None else float('nan'):.3f}")
    sys.exit(0 if dot is not None and dot > 0.9 else 1)


def cmd_level(link, name):
    need(link)
    link.send("load_level", {"level": str(name)})
    errs = []
    link.pump(0.5, lambda m: errs.append(m) if m["t"] == "error" else None)
    link.bye()
    if errs:
        print(f"FAIL: {errs[0].get('msg')}")
        sys.exit(1)
    print(f"BeamNG is loading {name}")


def cmd_raw(link, seconds=3.0):
    need(link)
    link.pump(float(seconds), lambda m: print(json.dumps(m)))
    link.bye()


COMMANDS = {
    "telemetry": cmd_telemetry, "bench": cmd_bench, "ping": cmd_ping, "debug": cmd_debug,
    "camtest": cmd_camtest, "camfollow": cmd_camfollow, "ground": cmd_ground, "vehicles": cmd_vehicles,
    "reconnect": cmd_reconnect, "raw": cmd_raw, "reload": cmd_reload, "push": cmd_push, "level": cmd_level,
    "place": cmd_place,
}


def main():
    if len(sys.argv) < 2 or sys.argv[1] not in COMMANDS:
        print(__doc__)
        sys.exit(0)
    fn = COMMANDS[sys.argv[1]]
    args = sys.argv[2:]
    conv = []
    for a in args:
        try:
            conv.append(float(a) if fn not in (cmd_debug, cmd_level) else a)
        except ValueError:
            conv.append(a)
    fn(Link(), *conv)


if __name__ == "__main__":
    main()
