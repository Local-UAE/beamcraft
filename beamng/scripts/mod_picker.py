#!/usr/bin/env python3
"""Pick which of your BeamNG car mods the crossover gets: a small web page on this PC only.

It reads the mods in your normal BeamNG profile (read-only) and copies the ones you tick into the
test profile the crossover runs on. BeamNG mounts a new zip while it runs, and the Minecraft car
picker (B) lists it. "Remove" only deletes copies this page made.

    python beamng/scripts/mod_picker.py              # opens http://127.0.0.1:47088
    python beamng/scripts/mod_picker.py --no-browser --port 47090

Your profile: %LOCALAPPDATA%\\BeamNG\\BeamNG.drive\\current\\mods (BNG_REAL_USERPATH overrides the
"current" folder). Test profile: BNG_USERPATH, else <repo>\\bng-userfolder (or an older checkout's
<repo>\\..\\bng-userfolder), plus \\current\\mods.
"""
import argparse
import html
import json
import os
import re
import shutil
import threading
import time
import webbrowser
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
IMAGE_EXT = (".jpg", ".jpeg", ".png")


def real_mods_dir():
    base = os.environ.get("BNG_REAL_USERPATH") or os.path.join(os.environ.get("LOCALAPPDATA", ""), "BeamNG", "BeamNG.drive", "current")
    return Path(base) / "mods"


def test_mods_dir():
    """The crossover's test profile, as scripts/common.ps1 picks it (Get-BeamngUserRoot)."""
    root = os.environ.get("BNG_USERPATH")
    if not root:
        old = REPO.parent / "bng-userfolder"
        root = str(old if (old / "current").is_dir() else REPO / "bng-userfolder")
    return Path(root) / "current" / "mods"


# -- scanning ------------------------------------------------------------------------------------

def scan_zip(path):
    """The cars one mod zip adds: [{key, name, brand, type, preview}]. A folder under vehicles/ counts
    only with its own info.json: mods that patch other cars (headlights, sounds) aren't cars."""
    cars = []
    with zipfile.ZipFile(path) as z:
        names = z.namelist()
        keys = sorted({m.group(1) for n in names for m in [re.match(r"vehicles/([^/]+)/", n)] if m and m.group(1).lower() != "common"})
        for key in keys:
            if f"vehicles/{key}/info.json" not in names:
                continue
            try:
                info = json.loads(z.read(f"vehicles/{key}/info.json").decode("utf-8", "replace"))
            except (ValueError, KeyError):
                info = {}
            if not isinstance(info, dict):
                info = {}
            preview = None
            for cand in (f"vehicles/{key}/default.jpg", f"vehicles/{key}/default.png"):
                if cand in names:
                    preview = cand
                    break
            if not preview:
                pics = [n for n in names if n.startswith(f"vehicles/{key}/") and n.lower().endswith(IMAGE_EXT) and n.count("/") == 2]
                preview = pics[0] if pics else None
            cars.append({"key": key, "name": str(info.get("Name") or key), "brand": str(info.get("Brand") or ""),
                         "type": str(info.get("Type") or ""), "preview": preview})
    return cars


class Library:
    """Your mods (scanned once, cached by size and time) and which of them the test profile has."""

    def __init__(self, real, test):
        self.real, self.test = real, test
        self.cache_file = test.parent / "temp" / "mccross" / "modpicker-cache.json"
        self.copied_file = test.parent / "temp" / "mccross" / "modpicker-copied.json"
        self.lock = threading.Lock()
        self.mods = {}          # file name -> {file, size, cars, error}
        self.jobs = {}          # file name -> {"state": copying|done|failed, "done": bytes, "total": bytes}

    def _load_json(self, p, default):
        try:
            return json.loads(p.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return default

    def scan(self):
        cache = self._load_json(self.cache_file, {})
        mods = {}
        for p in sorted(self.real.glob("*.zip"), key=lambda q: q.name.lower()):
            st = p.stat()
            stamp = f"v2:{st.st_size}:{int(st.st_mtime)}"   # v2: only folders with an info.json
            hit = cache.get(p.name)
            if hit and hit.get("stamp") == stamp:
                cars, error = hit["cars"], hit.get("error")
            else:
                try:
                    cars, error = scan_zip(p), None
                except (zipfile.BadZipFile, OSError) as e:
                    cars, error = [], str(e)
                cache[p.name] = {"stamp": stamp, "cars": cars, "error": error}
            mods[p.name] = {"file": p.name, "size": st.st_size, "cars": cars, "error": error}
        self.cache_file.parent.mkdir(parents=True, exist_ok=True)
        self.cache_file.write_text(json.dumps(cache), encoding="utf-8")
        with self.lock:
            self.mods = mods

    def copied(self):
        return set(self._load_json(self.copied_file, []))

    def state(self):
        copied = self.copied()
        owners = {}
        for m in self.mods.values():
            for c in m["cars"]:
                owners.setdefault(c["key"], []).append(m["file"])
        out = []
        for m in self.mods.values():
            if not m["cars"]:
                continue   # maps, UI apps, sounds: not cars
            installed = (self.test / m["file"]).exists()
            dupes = sorted({f for c in m["cars"] for f in owners[c["key"]] if f != m["file"]})
            out.append({**m, "installed": installed, "ours": m["file"] in copied, "same_car_as": dupes,
                        "job": self.jobs.get(m["file"])})
        return out

    def add(self, name):
        src, dst = self.real / name, self.test / name
        if dst.exists() or self.jobs.get(name, {}).get("state") == "copying":
            return
        self.jobs[name] = {"state": "copying", "done": 0, "total": src.stat().st_size}
        threading.Thread(target=self._copy, args=(src, dst, name), daemon=True).start()

    def _copy(self, src, dst, name):
        tmp = dst.with_name(dst.name + ".part")
        try:
            self.test.mkdir(parents=True, exist_ok=True)
            with open(src, "rb") as a, open(tmp, "wb") as b:
                while True:
                    chunk = a.read(8 << 20)
                    if not chunk:
                        break
                    b.write(chunk)
                    self.jobs[name]["done"] += len(chunk)
            os.replace(tmp, dst)   # BeamNG only ever sees the whole zip
            with self.lock:
                copied = self.copied()
                copied.add(name)
                self.copied_file.parent.mkdir(parents=True, exist_ok=True)
                self.copied_file.write_text(json.dumps(sorted(copied)), encoding="utf-8")
            self.jobs[name]["state"] = "done"
        except OSError as e:
            self.jobs[name] = {"state": "failed", "error": str(e)}
            try:
                tmp.unlink()
            except OSError:
                pass

    def remove(self, name):
        """Deletes the test profile's copy, only if this page made it."""
        with self.lock:
            copied = self.copied()
            if name not in copied:
                return False
            try:
                (self.test / name).unlink()
            except FileNotFoundError:
                pass
            copied.discard(name)
            self.copied_file.write_text(json.dumps(sorted(copied)), encoding="utf-8")
        self.jobs.pop(name, None)
        return True

    def preview(self, name, member):
        m = self.mods.get(name)
        if not m or member not in {c["preview"] for c in m["cars"]}:
            return None
        with zipfile.ZipFile(self.real / name) as z:
            return z.read(member)


# -- web page --------------------------------------------------------------------------------------

PAGE = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>BeamNG car mods</title>
<style>
:root { --bg:#f4f4f2; --card:#fff; --ink:#1d1d1b; --mute:#6b6b66; --line:#e2e2dd; --accent:#2f6fde; --ok:#1f8a4c; --warn:#b5651d; }
@media (prefers-color-scheme: dark) { :root { --bg:#141413; --card:#1e1e1c; --ink:#ececea; --mute:#9a9a94; --line:#2e2e2b; --accent:#6c9cf0; --ok:#4cc27e; --warn:#e0954f; } }
* { box-sizing:border-box; }
body { margin:0; background:var(--bg); color:var(--ink); font:15px/1.45 system-ui, sans-serif; }
header { position:sticky; top:0; background:var(--bg); padding:18px 24px 12px; border-bottom:1px solid var(--line); z-index:1; }
h1 { margin:0 0 4px; font-size:20px; }
.sub { color:var(--mute); font-size:13px; }
.bar { display:flex; gap:10px; margin-top:12px; flex-wrap:wrap; }
input[type=search] { flex:1; min-width:200px; padding:8px 12px; border:1px solid var(--line); border-radius:8px; background:var(--card); color:var(--ink); font:inherit; }
.chip { padding:7px 12px; border:1px solid var(--line); border-radius:8px; background:var(--card); color:var(--ink); cursor:pointer; font:inherit; }
.chip.on { border-color:var(--accent); color:var(--accent); }
main { padding:20px 24px 40px; display:grid; grid-template-columns:repeat(auto-fill, minmax(250px, 1fr)); gap:16px; }
.card { background:var(--card); border:1px solid var(--line); border-radius:12px; overflow:hidden; display:flex; flex-direction:column; }
.card.in { border-color:var(--ok); }
.pic { aspect-ratio:16/9; background:var(--line) center/cover no-repeat; }
.body { padding:12px 14px 14px; display:flex; flex-direction:column; gap:4px; flex:1; }
.name { font-weight:600; }
.meta { color:var(--mute); font-size:12px; word-break:break-all; }
.warn { color:var(--warn); font-size:12px; }
.row { margin-top:auto; padding-top:10px; display:flex; align-items:center; gap:8px; }
button.act { padding:7px 14px; border-radius:8px; border:0; font:inherit; cursor:pointer; background:var(--accent); color:#fff; }
button.act.rm { background:transparent; color:var(--mute); border:1px solid var(--line); }
.state { font-size:13px; color:var(--ok); }
progress { flex:1; }
@media (max-width:600px) { header, main { padding-left:16px; padding-right:16px; } }
</style></head><body>
<header><h1>Your BeamNG car mods</h1>
<div class="sub">Add a car and BeamNG loads it while it runs; it shows up in Minecraft's car picker (B). Your own BeamNG profile is only read, never changed.</div>
<div class="bar"><input type="search" id="q" placeholder="Search cars or files">
<button class="chip" id="only">Only the ones in Minecraft</button></div></header>
<main id="grid"></main>
<script>
let mods = [], onlyIn = false;
const grid = document.getElementById('grid'), q = document.getElementById('q'), only = document.getElementById('only');
const mb = n => (n / 1e6).toFixed(0) + ' MB';
const esc = s => String(s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
async function load() { mods = await (await fetch('api/mods')).json(); draw(); }
function draw() {
  const words = q.value.toLowerCase().split(/\\s+/).filter(Boolean);
  grid.innerHTML = '';
  for (const m of mods) {
    const hay = (m.file + ' ' + m.cars.map(c => c.brand + ' ' + c.name + ' ' + c.key).join(' ')).toLowerCase();
    if (words.some(w => !hay.includes(w)) || (onlyIn && !m.installed)) continue;
    const car = m.cars[0];
    const pic = car.preview ? `api/preview?file=${encodeURIComponent(m.file)}&member=${encodeURIComponent(car.preview)}` : '';
    const job = m.job || {};
    let row;
    if (job.state === 'copying') row = `<progress max="${job.total}" value="${job.done}"></progress><span class="meta">copying</span>`;
    else if (m.installed && m.ours) row = `<span class="state">In Minecraft</span><button class="act rm" data-rm="${esc(m.file)}">Remove</button>`;
    else if (m.installed) row = `<span class="state">In Minecraft</span><span class="meta">(put there by hand)</span>`;
    else row = `<button class="act" data-add="${esc(m.file)}">Add to Minecraft</button>${job.state === 'failed' ? '<span class="warn">copy failed</span>' : ''}`;
    const el = document.createElement('div');
    el.className = 'card' + (m.installed ? ' in' : '');
    el.innerHTML = `<div class="pic" style="${pic ? `background-image:url('${pic}')` : ''}"></div>
      <div class="body"><div class="name">${esc([car.brand, car.name].filter(Boolean).join(' '))}${m.cars.length > 1 ? ` <span class="meta">+${m.cars.length - 1} more</span>` : ''}</div>
      <div class="meta">${esc(m.file)} · ${mb(m.size)} · ${esc(m.cars.map(c => c.key).join(', '))}</div>
      ${m.same_car_as.length ? `<div class="warn">Same car as ${esc(m.same_car_as.join(', '))}: add only one</div>` : ''}
      <div class="row">${row}</div></div>`;
    grid.appendChild(el);
  }
}
grid.addEventListener('click', async e => {
  const add = e.target.dataset.add, rm = e.target.dataset.rm;
  if (!add && !rm) return;
  e.target.disabled = true;
  await fetch(add ? 'api/add' : 'api/remove', {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({file: add || rm})});
  load();
});
q.addEventListener('input', draw);
only.addEventListener('click', () => { onlyIn = !onlyIn; only.classList.toggle('on', onlyIn); draw(); });
setInterval(() => { if (mods.some(m => m.job && m.job.state === 'copying')) load(); }, 700);
load();
</script></body></html>
"""


def make_handler(lib, port):
    origin = f"http://127.0.0.1:{port}"

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, fmt, *args):
            pass

        def _send(self, code, body, ctype="application/json"):
            data = body if isinstance(body, bytes) else body.encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self):
            u = urlparse(self.path)
            if u.path == "/":
                self._send(200, PAGE, "text/html; charset=utf-8")
            elif u.path == "/api/mods":
                self._send(200, json.dumps(lib.state()))
            elif u.path == "/api/preview":
                qs = parse_qs(u.query)
                img = lib.preview(qs.get("file", [""])[0], qs.get("member", [""])[0])
                if img is None:
                    self._send(404, "{}")
                else:
                    member = qs["member"][0].lower()
                    self._send(200, img, "image/png" if member.endswith(".png") else "image/jpeg")
            else:
                self._send(404, "{}")

        def do_POST(self):
            # Only this page may change things: same origin, JSON body, a file name from the scan.
            if self.headers.get("Origin") not in (None, origin) or "application/json" not in (self.headers.get("Content-Type") or ""):
                self._send(403, '{"error":"not from this page"}')
                return
            try:
                body = json.loads(self.rfile.read(min(int(self.headers.get("Content-Length") or 0), 4096)) or b"{}")
            except ValueError:
                self._send(400, "{}")
                return
            name = body.get("file") if isinstance(body, dict) else None
            if name not in lib.mods:
                self._send(400, '{"error":"unknown mod"}')
                return
            if self.path == "/api/add":
                lib.add(name)
                self._send(200, "{}")
            elif self.path == "/api/remove":
                self._send(200 if lib.remove(name) else 409, "{}")
            else:
                self._send(404, "{}")

    return Handler


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--port", type=int, default=47088)
    ap.add_argument("--no-browser", action="store_true")
    args = ap.parse_args()
    real, test = real_mods_dir(), test_mods_dir()
    if not real.is_dir():
        raise SystemExit(f"No BeamNG mods folder at {real}")
    lib = Library(real, test)
    t0 = time.time()
    lib.scan()
    cars = sum(1 for m in lib.mods.values() if m["cars"])
    print(f"{cars} car mods of {len(lib.mods)} in {real} ({time.time() - t0:.1f} s); copies go to {test}", flush=True)
    server = ThreadingHTTPServer(("127.0.0.1", args.port), make_handler(lib, args.port))
    url = f"http://127.0.0.1:{args.port}/"
    print("Open", url, "(Ctrl+C to stop)", flush=True)
    if not args.no_browser:
        webbrowser.open(url)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
