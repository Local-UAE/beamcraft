#!/usr/bin/env python3
"""Scripted feature demo of the crossover, recorded for sharing.

    python beamng/bridge/demo.py record [--out DIR] [--only intro,wall,...]
    python beamng/bridge/demo.py edit [--out DIR] [--watermark TEXT] [--captions] [--no-small]
    python beamng/bridge/demo.py review [--out DIR] [--watermark TEXT]

`record` plays the scenes below in both games (Minecraft through its dev commands, BeamNG
through the control link) while ffmpeg captures BeamNG's client area only: no title bar,
taskbar, cursor or audio. Scene times go to DIR/scenes.json, the capture to DIR/raw.mkv.
`edit` cuts the scenes out, puts a short title over the opening shot (or the scene captions
with --captions), adds a faint watermark across the middle with --watermark, strips all
metadata and writes DIR/crossover-demo[-<watermark>].mp4 (1080p60) and the same name with
-10mb (720p30, small enough for a free Discord upload; skip it with --no-small).
`review` writes contact sheets of the final video for a privacy check:
anything Windows draws over the game (notifications) lands in the capture too.

Needs both games running with the crossover on gridmap_v2 (scenes use its open concrete
south-west of spawn and the slab ramp), Minecraft in the bridge world, overlay on, and ffmpeg
with Intel Quick Sync (the laptop panel hangs off the iGPU, which is also where ddagrab's
frames live).
"""
import argparse
import json
import math
import os
import re
import shutil
import subprocess
import sys
import threading
import time

import bngdiag

DEV_DIR = os.path.join(os.environ["TEMP"], "bngmc")
CMD_FILE = os.path.join(DEV_DIR, "mc_cmd.txt")
OUT_FILE = os.path.join(DEV_DIR, "mc_out.txt")
DEFAULT_OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "demo-out")

GROUND = 100.0                 # gridmap_v2's concrete apron, flat to +-0.3 m (scouted)
REGION_SPACING = 65536
EYE = 1.62
FPS = 60
FONT = r"C:\Windows\Fonts\segoeuib.ttf"
TITLE = "Minecraft \u00d7 BeamNG.drive (early prototype)"
CREDIT = "Built on justbustin/minecraft-crossover-bridge"


# ---------------------------------------------------------------- Minecraft (dev commands)

def mc(*lines):
    os.makedirs(DEV_DIR, exist_ok=True)
    for _ in range(50):                # Minecraft may be reading (and deleting) the file this moment
        try:
            return _append(lines)
        except PermissionError:
            time.sleep(0.02)
    return _append(lines)


def _append(lines):
    with open(CMD_FILE, "a", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")


def mc_status(timeout=3.0):
    """Status lines Minecraft answers with, as one string ('' if it didn't answer)."""
    before = os.path.getsize(OUT_FILE) if os.path.exists(OUT_FILE) else 0
    mc("status")
    end = time.time() + timeout
    while time.time() < end:
        time.sleep(0.1)
        with open(OUT_FILE, "rb") as f:
            f.seek(before)
            txt = f.read().decode("utf-8", "replace")
        if "camera sent" in txt:
            return txt
    return ""


def region_offset(status):
    m = re.search(r"region (\d+)", status)
    if not m:
        sys.exit("Minecraft is not in the bridge world (no region in its status)")
    return int(m.group(1)) * REGION_SPACING


def overlay_rect(status):
    m = re.search(r"glued to BeamNG (\d+)x(\d+) at (-?\d+),(-?\d+)", status)
    if not m:
        sys.exit("the overlay is not glued to BeamNG (press F6 in Minecraft, BeamNG not minimised)")
    w, h, x, y = (int(g) for g in m.groups())
    return x, y, w - w % 2, h - h % 2


class Steve:
    """Puts Steve somewhere in canonical (BeamNG) coordinates."""

    def __init__(self, ox):
        self.ox = ox

    def mc_pos(self, p):
        return p[0] + self.ox, p[2], -p[1]

    def mc_cell(self, x, y, z):
        """Minecraft block for the canonical 1 m cell with min corner (x, y, z)."""
        return x + self.ox, z, -y - 1

    def tp(self, feet, look_at):
        yaw, pitch = look_angles((feet[0], feet[1], feet[2] + EYE), look_at)
        x, y, z = self.mc_pos(feet)
        mc(f"run tp @s {x:.2f} {y:.2f} {z:.2f} {yaw:.1f} {pitch:.1f}")

    def hold(self, item):
        mc(f"run item replace entity @s weapon.mainhand with {item}")

    def summon(self, what, p, nbt=""):
        x, y, z = self.mc_pos(p)
        mc(f"run summon {what} {x:.2f} {y:.2f} {z:.2f} {nbt}".rstrip())


def look_angles(eye, target):
    """Minecraft yaw/pitch looking from eye to target (canonical), see docs/coordinates.md."""
    dx, dy, dz = (t - e for t, e in zip(target, eye))
    yaw = math.degrees(math.atan2(-dx, -dy))
    pitch = math.degrees(math.atan2(-dz, math.hypot(dx, dy)))
    return yaw, pitch


# ---------------------------------------------------------------- BeamNG (control link)

class Bng:
    """A bngdiag link kept alive by a reader thread that also tracks the latest state."""

    def __init__(self):
        self.link = bngdiag.Link("demo")
        if not self.link.connect():
            sys.exit("BeamNG is not answering on the control link")
        self.lock = threading.Lock()
        self.state = None
        self.vehicles = None
        self.alive = True
        threading.Thread(target=self._read, daemon=True).start()

    def send(self, t, payload=None):
        with self.lock:
            self.link.send(t, payload)

    def _read(self):
        last_ping = 0.0
        while self.alive:
            try:
                if time.time() - last_ping > 0.25:
                    last_ping = time.time()
                    self.send("ping")
                m = self.link.recv()
                if not m:
                    continue
                if m["t"] == "state":
                    self.state = m
                elif m["t"] == "vehicles":
                    self.vehicles = m
                elif m["t"] == "error" and m.get("code") == "unknown_session":
                    with self.lock:
                        self.link.connect()
            except (OSError, ValueError, KeyError) as e:
                print(f"    BeamNG link: {e!r}")
                time.sleep(0.1)

    def close(self):
        self.drive(throttle=0, brake=0, steering=0)
        self.alive = False
        time.sleep(0.1)
        self.link.bye()

    def veh(self):
        return (self.state or {}).get("veh")

    def box(self):
        """(center, half axes) of the player's car, from the 20 Hz vehicles message."""
        for v in (self.vehicles or {}).get("list", []):
            if v.get("player"):
                return v["center"], v["axes"]
        return None, None

    def car(self, timeout=3.0):
        """(state veh, center, half axes) of the player's car, waiting for fresh data."""
        end = time.time() + timeout
        while time.time() < end:
            v, (center, axes) = self.veh(), self.box()
            if v and center:
                return v, center, axes
            time.sleep(0.05)
        raise RuntimeError("no data for the player's car from BeamNG")

    def place(self, pos, fwd, settle=1.5):
        self.send("vehicle_place", {"pos": list(pos), "fwd": list(fwd)})
        time.sleep(settle)
        self.drive(throttle=0, brake=0, steering=0, parkingbrake=1)

    def drive(self, **inputs):
        self.send("vehicle_drive", inputs)

    def stop(self, timeout=4.0):
        """Brake to a standstill, then hold the car with the parking brake. Holding the brake
        pedal at rest makes BeamNG's automatic gearbox shift into reverse and back away."""
        self.drive(throttle=0, brake=0.7, steering=0)
        end = time.time() + timeout
        while time.time() < end and self.speed() > 0.5:
            time.sleep(0.02)
        self.drive(brake=0, parkingbrake=1)

    def speed(self):
        v = self.veh()
        return v["speed"] if v else 0.0


# ---------------------------------------------------------------- capture

class Capture:
    """ffmpeg ddagrab -> Quick Sync H.264, BeamNG's client area only, no cursor, no audio."""

    def __init__(self, path, rect, log_path):
        x, y, w, h = rect
        src = (f"ddagrab=output_idx=0:framerate={FPS}:draw_mouse=0:offset_x={x}:offset_y={y}:video_size={w}x{h},"
               "hwmap=derive_device=qsv,format=qsv")
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
                us = int(line.split("=")[1]) if line.split("=")[1].lstrip("-").isdigit() else 0
                if us > 0:
                    self.t0 = time.time() - us / 1e6

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


class Rehearsal:
    """Stands in for Capture with --dry: same clock, nothing recorded."""

    def __init__(self):
        self.t0 = time.time()

    def now(self):
        return time.time() - self.t0

    def stop(self):
        pass


# ---------------------------------------------------------------- scenes

class Director:
    def __init__(self, bng, steve, cap):
        self.bng, self.steve, self.cap = bng, steve, cap
        self.scenes = []
        self.current = None

    def start(self, name):
        mc("clearchat")
        time.sleep(0.15)
        self.current = {"name": name, "start": self.cap.now(), "captions": []}

    def caption(self, text, pos="top"):
        self.current["captions"].append({"at": self.cap.now() - self.current["start"], "text": text, "pos": pos})

    def end(self):
        self.current["end"] = self.cap.now()
        self.scenes.append(self.current)
        print(f"  {self.current['name']:8s} {self.current['start']:7.2f} -> {self.current['end']:7.2f} s")
        self.current = None

    def wait_impact(self, timeout):
        """Until the car's speed collapses (it hit something) or timeout; returns the top speed."""
        top, end = 0.0, time.time() + timeout
        while time.time() < end:
            s = self.bng.speed()
            top = max(top, s)
            if top > 4 and s < top * 0.55:
                break
            time.sleep(0.02)
        return top


def scene_intro(d):
    s, b = d.steve, d.bng
    b.place((-100.0, -160.0, GROUND + 0.3), (1, 0, 0))
    s.hold("minecraft:air")
    s.tp((-108.0, -171.0, GROUND), (-100.0, -160.0, GROUND + 0.8))
    time.sleep(2.0)
    d.start("intro")
    d.caption(TITLE + "\nYou're Steve. BeamNG draws the world and runs the cars")
    time.sleep(1.0)
    mc("key forward 40")
    time.sleep(2.4)
    mc("turn 35 -4 70")
    time.sleep(4.0)
    d.end()


def scene_ramp(d):
    s = d.steve
    s.tp((-24.5, -240.5, GROUND + 1.0), (-24.5, -260.5, GROUND + 1.0))
    time.sleep(3.5)          # terrain around the ramp gets sampled; Steve is held until it is
    d.start("ramp")
    d.caption("BeamNG's ramps and walls become invisible blocks you walk on")
    mc("key forward 210")
    time.sleep(10.0)
    d.end()


def scene_wall(d):
    s, b = d.steve, d.bng
    b.place((-100.5, -182.0, GROUND + 0.3), (0, 1, 0))
    s.tp((-114.0, -150.6, GROUND), (-100.0, -153.0, GROUND + 1.0))
    s.hold("minecraft:bricks")
    time.sleep(2.0)
    d.start("wall")
    d.caption("Blocks placed in Minecraft become collision in BeamNG")
    time.sleep(0.8)
    for z in range(3):                       # canonical cells x -104..-97, y -150..-149
        for xa, xb in ((-104, -101), (-100, -97)):
            ax, ay, az = s.mc_cell(xa, -150, GROUND + z)
            bx, by, bz = s.mc_cell(xb, -149, GROUND + z)
            mc(f"run fill {ax:.0f} {ay:.0f} {az} {bx:.0f} {by:.0f} {bz} minecraft:bricks")
            time.sleep(0.4)
    time.sleep(1.2)          # BlockSync flush + BeamNG collision rebuild (<= 0.5 s)
    d.caption("so a pickup at full throttle hits the wall")
    b.drive(throttle=1, brake=0, parkingbrake=0, steering=0)
    top = d.wait_impact(9.0)
    print(f"    impact at {top:.1f} m/s")
    b.stop()
    time.sleep(3.0)
    d.end()
    a, c = s.mc_cell(-104, -150, GROUND), s.mc_cell(-97, -149, GROUND + 2)
    mc(f"run fill {a[0]} {a[1]:.0f} {a[2]} {c[0]} {c[1]:.0f} {c[2]} minecraft:air")


def scene_punch(d):
    s, b = d.steve, d.bng
    b.place((-100.0, -168.0, GROUND + 0.3), (1, 0, 0))
    s.tp((-100.0, -171.6, GROUND), (-100.0, -168.0, GROUND + 0.9))
    s.hold("minecraft:netherite_sword")
    b.drive(parkingbrake=0)
    time.sleep(1.5)
    d.start("punch")
    d.caption("Hit a car and it gets shoved")
    time.sleep(0.8)
    for _ in range(3):
        mc("click attack")
        time.sleep(0.9)
    time.sleep(2.0)
    d.end()


def scene_mobs(d):
    s, b = d.steve, d.bng
    b.place((-86.0, -196.0, GROUND + 0.3), (0, 1, 0))
    s.tp((-96.5, -168.0, GROUND), (-86.0, -172.0, GROUND + 0.8))
    s.hold("minecraft:wheat")
    time.sleep(1.5)
    d.start("mobs")
    d.caption("Cars run into Minecraft mobs")
    for mob, x, y in (("cow", -87.0, -172.0), ("pig", -85.8, -172.5), ("sheep", -84.9, -171.5), ("cow", -86.2, -169.5)):
        s.summon(f"minecraft:{mob}", (x, y, GROUND))   # the car's path is x -87..-85
    time.sleep(0.6)
    b.drive(throttle=0.85, brake=0, parkingbrake=0, steering=0)
    end = time.time() + 6
    while time.time() < end and (b.veh() or {}).get("pos", [0, -999])[1] < -164:
        time.sleep(0.02)
    b.stop()
    time.sleep(1.0)
    d.end()


def scene_tnt(d):
    s, b = d.steve, d.bng
    b.place((-86.0, -140.0, GROUND + 0.3), (0, 1, 0))
    s.tp((-98.0, -147.0, GROUND), (-86.0, -140.0, GROUND + 0.8))
    s.hold("minecraft:tnt")
    b.drive(parkingbrake=0)
    time.sleep(1.5)
    d.start("tnt")
    d.caption("TNT throws cars around")
    time.sleep(0.5)
    for dy in (-1.0, 1.0):
        s.summon("minecraft:tnt", (-88.7, -140.0 + dy, GROUND), "{fuse:45}")
    time.sleep(2.25 + 3.5)
    d.end()


def scene_roof(d):
    s, b = d.steve, d.bng
    b.place((-140.0, -205.0, GROUND + 0.3), (0, 1, 0))
    time.sleep(0.6)
    _, center, axes = b.car()
    top = center[2] + sum(abs(a[2]) for a in axes)
    s.hold("minecraft:air")
    # Over the bed, looking down past the cab roof at the hood, so the car is in the shot.
    s.tp((center[0], center[1] - 1.6, top + 0.05), (center[0], center[1] + 3.5, top - 1.3))
    time.sleep(1.5)
    d.start("roof")
    d.caption("Stand on a car and it carries you")
    time.sleep(0.5)
    b.drive(throttle=0.35, brake=0, parkingbrake=0, steering=0.12)
    time.sleep(6.0)
    b.stop()
    time.sleep(0.8)
    d.end()


def scene_enter(d):
    s, b = d.steve, d.bng
    b.stop()
    v, center, axes = b.car()
    fx, fy = v["fwd"][0], v["fwd"][1]
    right = (fy, -fx)
    s.hold("minecraft:air")
    feet = (center[0] + right[0] * 3.3, center[1] + right[1] * 3.3, GROUND)
    s.tp(feet, (center[0], center[1], GROUND + 1.0))
    time.sleep(1.5)
    d.start("enter")
    d.caption("Right-click a car to drive it in BeamNG. F4 hops back out")
    time.sleep(1.0)
    mc("click use")
    time.sleep(1.5)
    b.drive(throttle=0.6, brake=0, parkingbrake=0, steering=-0.35)
    time.sleep(3.5)
    b.stop()
    time.sleep(0.5)
    mc("switch mc")
    time.sleep(2.5)
    d.end()


def scene_stats(d):
    s, b = d.steve, d.bng
    v, center, _ = b.car()
    right = (v["fwd"][1], -v["fwd"][0])
    s.tp((center[0] + right[0] * 9.0, center[1] + right[1] * 9.0, GROUND), (center[0], center[1], GROUND + 0.9))
    time.sleep(1.5)
    d.start("stats")
    mc("hud on")   # F9 also outlines each car's Minecraft collision box in green
    d.caption("F9: link stats (about 1 ms round trip, 60 Hz) and the car's hitbox", pos="bottom")
    time.sleep(4.5)
    mc("hud off")
    d.caption(CREDIT)
    time.sleep(2.5)
    d.end()


SCENES = {"intro": scene_intro, "ramp": scene_ramp, "wall": scene_wall, "punch": scene_punch, "mobs": scene_mobs,
          "tnt": scene_tnt, "roof": scene_roof, "enter": scene_enter, "stats": scene_stats}


def setup(steve):
    mc("recall")               # Steve back beside the car if he wandered off the region (or respawned)
    time.sleep(2.0)
    mc("run gamerule sendCommandFeedback false", "run gamerule doDaylightCycle false", "run gamerule doMobSpawning false",
       "run gamerule doWeatherCycle false", "run gamemode creative", "run time set noon", "run weather clear",
       "run kill @e[type=!minecraft:player,type=!bngbridge:vehicle]",
       "run recipe give @s *", "hud off", "hitboxes off", "cam drive", "switch mc")
    # Earlier demo walls and alignment markers (wool) around the stage.
    x0 = -160 + steve.ox       # canonical x -160..-80, y -121..-270, 4 m up: 16200 blocks per fill
    for z0 in range(120, 221, 50):
        for what in ("minecraft:bricks", "#minecraft:wool"):
            mc(f"run fill {x0} {GROUND:.0f} {z0} {x0 + 80} {GROUND + 3:.0f} {z0 + 49} minecraft:air replace {what}")
    time.sleep(6.0)            # the one recipe toast fades out before the first scene
    mc("clearchat")


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
    steve = Steve(region_offset(status))
    rect = overlay_rect(status)
    bng = Bng()
    end = time.time() + 3
    while bng.state is None and time.time() < end:
        time.sleep(0.05)
    if (bng.state or {}).get("level") != "gridmap_v2":
        sys.exit("the demo is staged on gridmap_v2")
    setup(steve)
    if args.dry:
        print("rehearsal: nothing is recorded")
        cap = Rehearsal()
    else:
        print(f"recording BeamNG's client area {rect[2]}x{rect[3]} at {rect[0]},{rect[1]} -> {out}")
        cap = Capture(os.path.join(out, "raw.mkv"), rect, os.path.join(out, "ffmpeg-record.log"))
    d = Director(bng, steve, cap)
    try:
        for n in names:
            SCENES[n](d)
    finally:
        cap.stop()
        mc("hud off", "clearchat")
        bng.close()
        if not args.dry:
            with open(os.path.join(out, "scenes.json"), "w", encoding="utf-8") as f:
                json.dump({"fps": FPS, "rect": rect, "scenes": d.scenes}, f, indent=1)
    print(f"done: {len(d.scenes)} scenes; next: demo.py edit")


# ---------------------------------------------------------------- edit

FADE = 0.25
LEAD = 0.15
SHORT_TITLE = "Minecraft \u00d7 BeamNG"
WATERMARK_SIZE = 200       # px on a 1008 px tall frame: one big mark across the middle
WATERMARK_ALPHA = 0.13     # faint, but not croppable like a corner logo


def caption_filters(caps, dur, files, height):
    """drawtext filters for one segment: each caption shows from its time until the next one."""
    out = []
    for i, c in enumerate(caps):
        t0 = max(0.0, c["at"] + LEAD)
        t1 = caps[i + 1]["at"] + LEAD if i + 1 < len(caps) else dur
        alpha = f"if(lt(t,{t0 + 0.3:.3f}),(t-{t0:.3f})/0.3,if(gt(t,{t1 - 0.3:.3f}),({t1:.3f}-t)/0.3,1))"
        lines = c["text"].split("\n")
        y = 34 if c.get("pos", "top") == "top" else height - 150 - (len(lines) - 1) * 68
        for j, line in enumerate(lines):
            name = text_file(files, line)
            size = 44 if (j == 0 and line == TITLE) else 34
            out.append(f"drawtext=fontfile=font.ttf:textfile={name}:expansion=none:fontsize={size}:fontcolor=white"
                       f":box=1:boxcolor=black@0.55:boxborderw=14:x=(w-text_w)/2:y={y}"
                       f":enable='between(t,{t0:.3f},{t1:.3f})':alpha='{alpha}'")
            y += size + 34
    return out


def text_file(files, text):
    """drawtext reads its text from a file (no escaping problems); returns the file name."""
    name = f"txt{len(files)}.txt"
    files.append((name, text))
    return name


def title_filter(files):
    """The short title over the opening shot: fades in, holds, fades away by 3.4 s."""
    alpha = "if(lt(t,0.6),max(0,(t-0.2)/0.4),if(gt(t,2.6),max(0,(3.4-t)/0.8),1))"
    return (f"drawtext=fontfile=font.ttf:textfile={text_file(files, SHORT_TITLE)}:expansion=none:fontsize=96"
            f":fontcolor=white:shadowcolor=black@0.6:shadowx=3:shadowy=3:x=(w-text_w)/2:y=(h-text_h)/2-60"
            f":enable='lt(t,3.4)':alpha='{alpha}'")


def watermark_filter(files, text):
    return (f"drawtext=fontfile=font.ttf:textfile={text_file(files, text)}:expansion=none:fontsize={WATERMARK_SIZE}"
            f":fontcolor=white@{WATERMARK_ALPHA}:bordercolor=black@{WATERMARK_ALPHA * 0.6:.3f}:borderw=3"
            f":x=(w-text_w)/2:y=(h-text_h)/2")


def output_names(args):
    tag = "".join(ch for ch in (args.watermark or "").lower() if ch.isalnum())
    base = "crossover-demo" + (f"-{tag}" if tag else "")
    return base + ".mp4", base + "-10mb.mp4"


def edit_graph(meta, args, files):
    """Cut the scenes out of raw.mkv, fade between them, then title or captions and watermark."""
    scenes = meta["scenes"]
    n = len(scenes)
    chains = [f"[0:v]split={n}" + "".join(f"[s{i}]" for i in range(n))]
    for i, sc in enumerate(scenes):
        start = max(0.0, sc["start"] - LEAD)
        dur = sc["end"] - start
        f = [f"trim=start={start:.3f}:end={sc['end']:.3f}", "setpts=PTS-STARTPTS",
             f"fade=t=in:st=0:d={FADE}", f"fade=t=out:st={dur - FADE:.3f}:d={FADE}"]
        if args.captions:
            f += caption_filters(sc["captions"], dur, files, meta["rect"][3])
        elif i == 0:
            f.append(title_filter(files))
        chains.append(f"[s{i}]" + ",".join(f) + f"[v{i}]")
    tail = [f"concat=n={n}:v=1:a=0"]
    if args.watermark:
        tail.append(watermark_filter(files, args.watermark))
    tail.append("format=yuv420p")
    return ";".join(chains) + ";" + "".join(f"[v{i}]" for i in range(n)) + ",".join(tail) + "[out]"


def cmd_edit(args):
    out = os.path.abspath(args.out)
    with open(os.path.join(out, "scenes.json"), encoding="utf-8") as f:
        meta = json.load(f)
    files = []
    graph = edit_graph(meta, args, files)
    for name, text in files:
        with open(os.path.join(out, name), "w", encoding="utf-8") as fh:
            fh.write(text)
    shutil.copyfile(FONT, os.path.join(out, "font.ttf"))
    with open(os.path.join(out, "graph.txt"), "w", encoding="utf-8") as fh:
        fh.write(graph)
    hq, small_name = output_names(args)
    made = [hq] if args.no_small else [hq, small_name]
    clean = ["-map_metadata", "-1", "-map_chapters", "-1", "-fflags", "+bitexact", "-flags:v", "+bitexact", "-an"]
    try:
        run(["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-i", "raw.mkv", "-/filter_complex", "graph.txt",
             "-map", "[out]", "-c:v", "h264_nvenc", "-preset", "p6", "-rc", "vbr", "-cq", "23", "-b:v", "0",
             "-maxrate", "14M", "-bufsize", "28M", "-profile:v", "high", *clean, "-movflags", "+faststart", hq], out)
        if not args.no_small:
            encode_small(out, hq, small_name, clean)
    finally:
        temp = [name for name, _ in files] + ["font.ttf", "graph.txt"] + [x for x in os.listdir(out) if x.startswith("x264pass")]
        for name in temp:
            p = os.path.join(out, name)
            if os.path.exists(p):
                os.remove(p)
    for name in made:
        p = os.path.join(out, name)
        print(f"{p}  {os.path.getsize(p) / 1e6:.1f} MB")


def encode_small(out, hq, small_name, clean):
    """720p30, two-pass x264 sized to fit a free Discord upload (10 MB)."""
    dur = float(run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", hq], out).strip())
    kbps = int(8.8 * 8 * 1024 / dur)          # 8.8 MB of video, room for MP4 overhead under 10 MB
    small = ["-vf", "scale=1280:-2:flags=lanczos,fps=30", "-c:v", "libx264", "-preset", "slow", "-b:v", f"{kbps}k"]
    run(["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-i", hq, *small, "-pass", "1",
         "-passlogfile", "x264pass", "-an", "-f", "null", "NUL"], out)
    run(["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-i", hq, *small, "-pass", "2",
         "-passlogfile", "x264pass", *clean, "-movflags", "+faststart", small_name], out)


def run(cmd, cwd):
    r = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit(f"{' '.join(cmd[:6])} ... failed:\n{r.stderr[-2000:]}")
    return r.stdout


def cmd_review(args):
    """Contact sheets (one frame per second, 4x4 per sheet) of the final video, for a privacy check."""
    out = os.path.abspath(args.out)
    hq, _ = output_names(args)
    prefix = hq[:-4] + "-review-"
    run(["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-i", hq,
         "-vf", "fps=1,scale=480:-2,tile=4x4:padding=4", prefix + "%02d.png"], out)
    print("\n".join(os.path.join(out, x) for x in sorted(os.listdir(out)) if x.startswith(prefix)))


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("action", choices=("record", "edit", "review"))
    ap.add_argument("--out", default=DEFAULT_OUT)
    ap.add_argument("--only", help="comma-separated scenes: " + ",".join(SCENES))
    ap.add_argument("--dry", action="store_true", help="record: rehearse the scenes without capturing")
    ap.add_argument("--captions", action="store_true", help="edit: burn in the scene captions (default: title only)")
    ap.add_argument("--watermark", help="edit/review: faint text across the middle of the whole video")
    ap.add_argument("--no-small", action="store_true", help="edit: skip the 10 MB cut")
    args = ap.parse_args()
    {"record": cmd_record, "edit": cmd_edit, "review": cmd_review}[args.action](args)


if __name__ == "__main__":
    main()
