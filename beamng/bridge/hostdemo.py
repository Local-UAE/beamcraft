#!/usr/bin/env python3
"""Scripted demo of BeamNG cars inside Minecraft (native mode, branch native-car), recorded for sharing.

    python beamng/bridge/hostdemo.py stage            # a fresh car for the next take
    python beamng/bridge/hostdemo.py record [--out DIR] [--only intro,drive,...] [--dry]
    python beamng/bridge/hostdemo.py edit [--out DIR] [--watermark TEXT] [--no-small]
    python beamng/bridge/hostdemo.py review [--out DIR] [--watermark TEXT]

`record` plays the scenes below in Minecraft (through its dev commands; BeamNG only simulates)
with Minecraft fullscreen on the main screen, and captures that screen with ffmpeg: the game's
picture only, no cursor, no audio. Nothing else may be on that screen while it records.
Scene times go to DIR/scenes.json, the capture to DIR/raw.mkv. `edit` and `review` are
demo.py's (the cut, the faint watermark across the middle, metadata stripped, contact sheets).

Needs BeamNG on smallgrid and Minecraft in the host world (scripts/run-minecraft.ps1 -Mode host),
both with the crossover mod, a car Minecraft draws natively in range, and ffmpeg with NVENC.
"""
import argparse
import json
import math
import os
import re
import subprocess
import sys
import threading
import time

import demo
from demo import mc, mc_status

DEFAULT_OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "demo-out", "native")
FPS = 60
SECOND_CAR = "Porsche+911+992+TwiXeR"   # spawned before recording, parked for the last scenes


# ---------------------------------------------------------------- where things are

class World:
    """Canonical (BeamNG) <-> Minecraft for the host region, read off Minecraft's status."""

    def __init__(self, status):
        mcp = re.search(r"player MC: (-?[\d.]+) (-?[\d.]+) (-?[\d.]+)", status)
        can = re.search(r"player canonical \(BeamNG\): \((-?[\d.]+), (-?[\d.]+), (-?[\d.]+)\)", status)
        if not mcp or not can:
            sys.exit("Minecraft's status has no player position (in the host world?)")
        m = [float(v) for v in mcp.groups()]
        c = [float(v) for v in can.groups()]
        # Minecraft (x, y, z) = (s * x, oy + s * z, -s * y): scale from the bigger horizontal axis
        self.s = m[0] / c[0] if abs(c[0]) > abs(c[1]) else -m[2] / c[1]
        self.oy = m[1] - self.s * c[2]

    def mc(self, p):
        return self.s * p[0], self.oy + self.s * p[2], -self.s * p[1]

    def flat(self, v):
        """A canonical direction as a Minecraft horizontal unit vector (x, z)."""
        x, z = v[0], -v[1]
        n = math.hypot(x, z) or 1.0
        return x / n, z / n


def look(frm, to):
    """Minecraft yaw/pitch looking from frm to to (Minecraft coordinates)."""
    dx, dy, dz = (t - f for t, f in zip(to, frm))
    return math.degrees(math.atan2(-dx, dz)), math.degrees(-math.atan2(dy, math.hypot(dx, dz)))


class Cars:
    """BeamNG's cars from the link (demo.Bng keeps it alive and holds the latest vehicles)."""

    def __init__(self, bng, world):
        self.bng, self.w = bng, world

    def all(self):
        return (self.bng.vehicles or {}).get("list", [])

    def get(self, model=None, player=None):
        for v in self.all():
            if (model is None or model.replace("+", " ") == v.get("model")) and (player is None or v.get("player") == player):
                return v
        return None

    def centre(self, v):
        return self.w.mc(v["center"])

    def speed(self):
        return self.bng.speed()


# ---------------------------------------------------------------- the Minecraft window

def _user32():
    import ctypes
    u = ctypes.windll.user32
    u.SetProcessDPIAware()
    return u


def minecraft_window():
    """Minecraft's game window: a visible GLFW window (class GLFW30) titled Minecraft*."""
    import ctypes
    from ctypes import wintypes
    u = _user32()
    found = []

    @ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
    def each(hwnd, _):
        if u.IsWindowVisible(hwnd):
            cls = ctypes.create_unicode_buffer(64)
            u.GetClassNameW(hwnd, cls, 64)
            title = ctypes.create_unicode_buffer(256)
            u.GetWindowTextW(hwnd, title, 256)
            if cls.value == "GLFW30" and title.value.startswith("Minecraft"):
                found.append(hwnd)
        return True

    u.EnumWindows(each, 0)
    return found[0] if len(found) == 1 else None


def onto_main_screen(hwnd):
    """Moves the window onto the main screen (where the taskbar is: ddagrab's output 0) and in front,
    so Minecraft's fullscreen happens there."""
    u = _user32()
    u.ShowWindow(hwnd, 9)                                       # SW_RESTORE
    u.SetWindowPos(hwnd, 0, 60, 60, 1280, 760, 0x0004)          # SWP_NOZORDER
    u.SetForegroundWindow(hwnd)


SHOTS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "minecraft", "run", "screenshots")


def capture_shows_minecraft(out):
    """One frame from the capture source against Minecraft's own screenshot of the same moment: the
    recording only starts if they are the same picture. Both files are deleted again."""
    from PIL import Image, ImageChops, ImageStat
    probe = os.path.join(out, "probe.png")
    shot = os.path.join(SHOTS, "demo_probe.png")
    for p in (probe, shot):
        if os.path.exists(p):
            os.remove(p)
    mc("screenshot demo_probe")
    r = subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i",
                        "ddagrab=output_idx=0:draw_mouse=0,hwdownload,format=bgra", "-frames:v", "1", probe], capture_output=True)
    end = time.time() + 5
    while not os.path.exists(shot) and time.time() < end:
        time.sleep(0.1)
    try:
        if r.returncode != 0 or not os.path.exists(probe) or not os.path.exists(shot):
            print("  capture check: no frame or no screenshot")
            return False
        a = Image.open(probe).convert("L").resize((96, 54))
        b = Image.open(shot).convert("L").resize((96, 54))
        diff = ImageStat.Stat(ImageChops.difference(a, b)).mean[0]
        print(f"  capture check: mean difference {diff:.1f} of 255 against Minecraft's own screenshot")
        return diff < 20
    finally:
        for p in (probe, shot):
            if os.path.exists(p):
                os.remove(p)


# ---------------------------------------------------------------- capture

class Capture:
    """ffmpeg: the main screen (Desktop Duplication) -> Quick Sync H.264, no cursor, no audio. The
    laptop panel hangs off the Intel iGPU, where ddagrab's frames live: NVENC can't open them
    ("OpenEncodeSessionEx failed: no encode device", measured), Quick Sync can (demo.py)."""

    def __init__(self, path, log_path):
        src = f"ddagrab=output_idx=0:framerate={FPS}:draw_mouse=0,hwmap=derive_device=qsv,format=qsv"
        cmd = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
               "-init_hw_device", "d3d11va=dx", "-init_hw_device", "qsv=qs@dx", "-filter_hw_device", "qs",
               "-f", "lavfi", "-i", src,
               "-c:v", "h264_qsv", "-preset", "medium", "-global_quality", "18", "-bf", "0",
               "-an", "-map_metadata", "-1", "-progress", "pipe:1", "-stats_period", "0.1", path]
        self.log = open(log_path, "w", encoding="utf-8")
        self.p = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=self.log)
        self.t0 = None
        threading.Thread(target=self._progress, daemon=True).start()
        end = time.time() + 15
        while self.t0 is None:
            if time.time() > end or self.p.poll() is not None:
                self.p.kill()
                sys.exit(f"ffmpeg did not start capturing (see {log_path})")
            time.sleep(0.05)

    def _progress(self):
        for raw in self.p.stdout:
            line = raw.decode("ascii", "replace").strip()
            if line.startswith("out_time_us=") and self.t0 is None:
                v = line.split("=")[1]
                if v.lstrip("-").isdigit() and int(v) > 0:
                    self.t0 = time.time() - int(v) / 1e6

    def now(self):
        return time.time() - self.t0

    def stop(self):
        try:
            self.p.stdin.write(b"q")
            self.p.stdin.flush()
        except OSError:
            pass
        self.p.wait(30)
        self.log.close()


class Director:
    def __init__(self, cars, world, cap):
        self.cars, self.w, self.cap = cars, world, cap
        self.scenes = []
        self.current = None

    def start(self, name):
        mc("clearchat")
        time.sleep(0.15)
        self.current = {"name": name, "start": self.cap.now(), "captions": []}

    def end(self):
        self.current["end"] = self.cap.now()
        self.scenes.append(self.current)
        print(f"  {self.current['name']:10s} {self.current['start']:7.2f} -> {self.current['end']:7.2f} s")
        self.current = None

    def stand(self, v, side_m, back_m, eye_on=None, height=0.0):
        """Steve on the ground beside car v: side_m to its left, back_m behind its centre (blocks),
        looking at its centre (or eye_on)."""
        c = self.cars.centre(v)
        fx, fz = self.w.flat(v["fwd"])
        lx, lz = fz, -fx                     # left of a car facing (fx, fz), Minecraft axes
        x, z = c[0] + lx * side_m - fx * back_m, c[2] + lz * side_m - fz * back_m
        ground = self.w.oy + self.w.s * 2.14 + 0.02   # the grass top: BeamNG z = 3 blocks / scale
        target = eye_on or (c[0], c[1] + height, c[2])
        yaw, pitch = look((x, ground + 1.62, z), target)
        mc(f"run tp @s {x:.2f} {ground:.2f} {z:.2f} {yaw:.1f} {pitch:.1f}")

    def keys(self, *spec):
        """Hold movement keys: ("forward", 2.0), ("left", 0.6) ... seconds, all at once."""
        mc(*[f"key {k} {max(1, int(s * 20))}" for k, s in spec])

    def wait_impact(self, timeout):
        top, end = 0.0, time.time() + timeout
        while time.time() < end:
            s = self.cars.speed()
            top = max(top, s)
            if top > 4 and s < top * 0.55:
                break
            time.sleep(0.02)
        return top


# ---------------------------------------------------------------- scenes

def scene_intro(d):
    v = d.cars.get(player=True)
    d.stand(v, 5.0, -4.0)                       # ahead and to the left: a three-quarter view
    time.sleep(0.6)
    d.start("intro")
    mc("turn 8 0 100")                          # a slow pan across the car
    time.sleep(5.0)
    d.end()


def scene_getin(d):
    v = d.cars.get(player=True)
    d.stand(v, 2.6, 0.6)                        # by the driver's door
    time.sleep(0.5)
    d.start("getin")
    time.sleep(1.0)
    mc("click use")                             # empty hand on the car: in (BngVehicleEntity.interact)
    time.sleep(0.6)
    mc("view back")
    time.sleep(2.4)
    d.end()


def scene_drive(d):
    d.start("drive")
    d.keys(("forward", 9.5))
    time.sleep(2.5)
    d.keys(("left", 1.2))
    time.sleep(2.8)
    d.keys(("right", 1.4))
    time.sleep(4.2)
    d.end()


def scene_cockpit(d):
    mc("view first")
    time.sleep(0.4)
    d.start("cockpit")
    d.keys(("forward", 8.0))
    time.sleep(2.0)
    d.keys(("right", 1.0))
    time.sleep(3.0)
    d.keys(("left", 1.0))
    time.sleep(3.0)
    d.end()


def scene_front(d):
    mc("view front")
    time.sleep(0.3)
    d.start("front")
    d.keys(("forward", 4.5))
    time.sleep(4.5)
    d.end()


def scene_crash(d):
    # stop (brake, then the handbrake: holding S at rest reverses), a brick wall 30 blocks ahead
    # across the car's way, then floor it into the wall
    d.keys(("back", 1.6))
    time.sleep(1.6)
    d.keys(("jump", 1.5))
    time.sleep(1.6)
    v = d.cars.get(player=True)
    c = d.cars.centre(v)
    fx, fz = d.w.flat(v["fwd"])
    y0 = math.floor(d.w.oy + d.w.s * 2.14 + 0.01)
    wx, wz = math.floor(c[0] + fx * 30), math.floor(c[2] + fz * 30)
    if abs(fx) > abs(fz):                       # one block thick along the heading, 13 wide, 3 high
        mc(f"run fill {wx} {y0} {wz - 6} {wx} {y0 + 2} {wz + 6} minecraft:bricks")
    else:
        mc(f"run fill {wx - 6} {y0} {wz} {wx + 6} {y0 + 2} {wz} minecraft:bricks")
    mc("view back")
    time.sleep(2.0)                             # BeamNG mirrors the new blocks as collision cubes
    d.start("crash")
    d.keys(("forward", 6.0))
    d.wait_impact(8.0)
    time.sleep(2.5)
    d.end()


def scene_dents(d):
    mc("key sneak 4")                           # out of the car
    time.sleep(1.0)
    mc("view first")
    mc("run item replace entity @s weapon.mainhand with minecraft:diamond_sword")
    v = d.cars.get(player=True)
    d.stand(v, 2.4, -0.5, height=0.2)
    time.sleep(0.6)
    d.start("dents")
    for _ in range(5):
        mc("click attack")
        time.sleep(0.75)
    time.sleep(1.0)
    d.end()


def scene_tnt(d):
    v = d.cars.get(player=True)
    c = d.cars.centre(v)
    d.stand(v, 14.0, -6.0, height=0.5)          # far enough to see the whole blast
    time.sleep(0.4)
    d.start("tnt")
    mc(f"run summon minecraft:tnt {c[0]:.2f} {c[1] + 0.5:.2f} {c[2]:.2f} {{fuse:40}}")
    time.sleep(5.5)
    d.end()


def scene_picker(d):
    mc("run item replace entity @s weapon.mainhand with minecraft:air", "picker")
    time.sleep(1.5)                             # the list and its previews load
    d.start("picker")
    time.sleep(3.5)
    d.end()
    mc("closescreen")


def scene_porsche(d):
    v = d.cars.get(model=SECOND_CAR)
    if v is None:
        print("  (no second car: skipped)")
        return
    d.stand(v, 4.5, -3.5)
    time.sleep(0.6)
    d.start("porsche")
    mc("turn -6 0 80")
    time.sleep(4.0)
    d.end()
    d.stand(v, 2.6, 0.6)
    time.sleep(0.4)
    mc("click use")
    time.sleep(0.8)
    mc("view front")
    d.start("outro")
    d.keys(("forward", 4.5))
    time.sleep(5.0)
    d.end()


SCENES = {"intro": scene_intro, "getin": scene_getin, "drive": scene_drive, "cockpit": scene_cockpit, "front": scene_front,
          "crash": scene_crash, "dents": scene_dents, "tnt": scene_tnt, "picker": scene_picker, "porsche": scene_porsche}


def setup(d):
    mc("run gamerule sendCommandFeedback false", "run gamerule doDaylightCycle false", "run gamerule doMobSpawning false",
       "run gamerule doWeatherCycle false", "run gamemode creative", "run time set noon", "run weather clear",
       "run kill @e[type=!minecraft:player,type=!bngbridge:vehicle]", "run clear @s", "hud off", "hitboxes off", "switch mc",
       "view first")                            # on foot in first person (getting out goes back to it)
    tidy(d)
    if d.cars.get(model=SECOND_CAR) is None:
        v = d.cars.get(player=True)
        d.stand(v, 0.0, 30.0)                   # the second car spawns 8 ahead of Steve: 22 behind the first
        time.sleep(0.5)
        mc(f"pick {SECOND_CAR} - new")
        print(f"spawning {SECOND_CAR.replace('+', ' ')} and waiting for Minecraft to draw it")
        end = time.time() + 120
        while time.time() < end and "Porsche" not in native_line():
            time.sleep(1.0)
        time.sleep(4.0)
    mc("clearchat")


def tidy(d):
    """Earlier takes: bricks gone, TNT craters filled (superflat: dirt at y -63 and -62, grass at -61),
    in 80 x 80 blocks around the car (each fill stays under Minecraft's 32768 blocks)."""
    c = d.cars.centre(d.cars.get(player=True))
    x0, z0 = int(c[0]) - 40, int(c[2]) - 40
    mc(f"run fill {x0} -60 {z0} {x0 + 79} -57 {z0 + 79} minecraft:air replace minecraft:bricks",
       f"run fill {x0} -63 {z0} {x0 + 79} -62 {z0 + 79} minecraft:dirt replace minecraft:air",
       f"run fill {x0} -61 {z0} {x0 + 79} -61 {z0 + 79} minecraft:grass_block replace minecraft:air")


def native_line():
    st = mc_status()
    m = re.search(r"native cars: (.*)", st)
    return m.group(1) if m and "READY" in m.group(1) else ""


FIRST_CAR = "g87st"


def cmd_stage(args):
    """A fresh stage: the player's car replaced by a new FIRST_CAR (the car picker's replace, in
    place), every other car in range removed, Steve beside it. Run before each take."""
    status = mc_status()
    if not status:
        sys.exit("Minecraft did not answer")
    world = World(status)
    bng = demo.Bng()
    end = time.time() + 3
    while bng.vehicles is None and time.time() < end:
        time.sleep(0.05)
    cars = Cars(bng, world)
    old = {v["id"] for v in cars.all()}
    mc("key sneak 4", "view first", "fullscreen off")
    time.sleep(1.0)
    mc(f"pick {FIRST_CAR} - replace")
    print(f"replacing the player's car with a new {FIRST_CAR}")
    end = time.time() + 120
    fresh = None
    while time.time() < end and fresh is None:
        time.sleep(1.0)
        v = cars.get(player=True)
        if v and v.get("model") == FIRST_CAR and FIRST_CAR in native_line():
            fresh = v
    if fresh is None:
        sys.exit("the new car didn't come")
    for v in cars.all():
        if v["id"] != fresh["id"]:
            bng.send("vehicle_remove", {"id": v["id"]})
            print(f"removed {v['id']} {v['model']}")
    time.sleep(1.0)
    mc("recall")
    time.sleep(2.0)
    bng.alive = False
    print(f"stage ready: {FIRST_CAR} {fresh['id']} (was {sorted(old)})")


def cmd_record(args):
    out = os.path.abspath(args.out)
    os.makedirs(out, exist_ok=True)
    names = args.only.split(",") if args.only else list(SCENES)
    unknown = [n for n in names if n not in SCENES]
    if unknown:
        sys.exit(f"unknown scenes {unknown}; choose from {', '.join(SCENES)}")
    status = mc_status()
    if not status:
        sys.exit("Minecraft did not answer (is it running with the crossover mod?)")
    world = World(status)
    bng = demo.Bng()
    end = time.time() + 3
    while bng.vehicles is None and time.time() < end:
        time.sleep(0.05)
    cars = Cars(bng, world)
    if cars.get(player=True) is None:
        sys.exit("BeamNG has no player car")
    d = Director(cars, world, None)
    setup(d)
    hwnd = minecraft_window()
    if hwnd is None:
        sys.exit("can't tell which window is Minecraft's")
    onto_main_screen(hwnd)
    time.sleep(1.0)
    mc("fullscreen on")
    time.sleep(3.0)
    if not args.dry and not capture_shows_minecraft(out):
        mc("fullscreen off")
        sys.exit("the main screen isn't showing Minecraft: nothing recorded")
    if args.dry:
        print("rehearsal: nothing is recorded")
        d.cap = demo.Rehearsal()
    else:
        print(f"recording the main screen (Minecraft fullscreen) -> {out}")
        d.cap = Capture(os.path.join(out, "raw.mkv"), os.path.join(out, "ffmpeg-record.log"))
    try:
        for n in names:
            SCENES[n](d)
    finally:
        d.cap.stop()
        mc("fullscreen off", "hud off", "clearchat")
        bng.alive = False
        if not args.dry:
            with open(os.path.join(out, "scenes.json"), "w", encoding="utf-8") as f:
                json.dump({"fps": FPS, "rect": [0, 0, 1920, 1080], "scenes": d.scenes}, f, indent=1)
    print(f"done: {len(d.scenes)} scenes; next: hostdemo.py edit")


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("action", choices=("stage", "record", "edit", "review"))
    ap.add_argument("--out", default=DEFAULT_OUT)
    ap.add_argument("--only", help="comma-separated scenes: " + ",".join(SCENES))
    ap.add_argument("--dry", action="store_true", help="record: rehearse without capturing")
    ap.add_argument("--captions", action="store_true", help=argparse.SUPPRESS)
    ap.add_argument("--watermark", help="edit/review: faint text across the middle of the whole video")
    ap.add_argument("--no-small", action="store_true", help="edit: skip the 10 MB cut")
    args = ap.parse_args()
    if args.action == "stage":
        cmd_stage(args)
    elif args.action == "record":
        cmd_record(args)
    elif args.action == "edit":
        demo.cmd_edit(args)
    else:
        demo.cmd_review(args)


if __name__ == "__main__":
    main()
