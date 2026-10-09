#!/usr/bin/env python3
"""MOCK BeamNG: runs the real beamng-mod Lua (bridge.lua, rays.lua, the camera filter) under LuaJIT
with stubbed engine APIs (mock_engine.lua) and a real UDP socket.

It exists so the protocol code can be tested without the game: Lua errors, the handshake,
sessions, timeouts, raycast batching, camera override plumbing. It says nothing about how BeamNG
itself behaves; every such claim is checked in the real game. Needs `pip install lupa`.

    python beamng/bridge/mock_beamng.py [--port 47020] [--seconds 0] [--fps 60] [--quiet]
"""
import argparse
import json
import os
import socket
import sys
import time

try:
    import lupa.luajit21 as lupa
except ImportError:  # pragma: no cover
    sys.exit("mock_beamng needs lupa with LuaJIT: pip install lupa")

HERE = os.path.dirname(os.path.abspath(__file__))
MOD_LUA = os.path.join(os.path.dirname(HERE), "beamng-mod", "lua")


class UdpShim:
    def __init__(self):
        self.socks = {}
        self.next = 0

    def new(self):
        self.next += 1
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.setblocking(False)
        self.socks[self.next] = s
        return self.next

    def bind(self, h, ip, port):
        try:
            self.socks[h].bind((ip.decode() if isinstance(ip, bytes) else ip, int(port)))
            return 1
        except OSError as e:
            return None, str(e)

    def recv(self, h, n):
        try:
            data, (ip, port) = self.socks[h].recvfrom(int(n))
            return data, ip.encode(), port
        except BlockingIOError:
            return None, "timeout"
        except ConnectionResetError:
            return None, "connection refused"

    def send(self, h, data, ip, port):
        try:
            return self.socks[h].sendto(data, (ip.decode() if isinstance(ip, bytes) else ip, int(port)))
        except OSError as e:
            return None, str(e)

    def close(self, h):
        s = self.socks.pop(h, None)
        if s:
            s.close()


def lua_to_py(lua, v):
    if lupa.lua_type(v) == "table":
        keys = list(v.keys())
        if keys and all(isinstance(k, int) for k in keys) and sorted(keys) == list(range(1, len(keys) + 1)):
            return [lua_to_py(lua, v[i]) for i in range(1, len(keys) + 1)]
        return {(k.decode() if isinstance(k, bytes) else str(k)): lua_to_py(lua, x) for k, x in v.items()}
    if isinstance(v, bytes):
        return v.decode("utf-8", "replace")
    return v


class MockBeamng:
    def __init__(self, port, fps, quiet, level="smallgrid", log_path=None):
        self.lua = lupa.LuaRuntime(unpack_returned_tuples=True, encoding=None)
        self.fps = fps
        self.quiet = quiet
        self.t0 = time.perf_counter()
        self.log_file = open(log_path, "w", encoding="utf-8") if log_path else None
        self.errors = []
        udp = UdpShim()
        g = self.lua.globals()
        mock = self.lua.table()
        mock.args = self.lua.table_from([b"-mccrossport", str(port).encode()])
        mock.level = level.encode()
        mock.paused = False
        mock.t = 0.0
        mock.clock = lambda: time.perf_counter()
        mock.log = self.log
        mock.udp_new = udp.new
        mock.udp_bind = udp.bind
        mock.udp_recv = udp.recv
        mock.udp_send = udp.send
        mock.udp_close = udp.close
        g.mock = mock
        g.jsonEncode = lambda t: json.dumps(lua_to_py(self.lua, t), separators=(",", ":")).encode()
        j = self.lua.table()
        j.decode = lambda s: self.lua.table_from(self._bytes_keys(json.loads(s)), recursive=True)
        g.json = j
        self.lua.execute(f"package.path = [[{MOD_LUA}/ge/?.lua;{MOD_LUA}/ge/?/init.lua;]] .. package.path")
        self.lua.execute("""
            package.preload['socket.socket'] = function()
              return { udp = function()
                local h = mock.udp_new()
                local s = {}
                function s:setsockname(ip, port) return mock.udp_bind(h, ip, port) end
                function s:settimeout(t) end
                function s:receivefrom(n) return mock.udp_recv(h, n) end
                function s:sendto(d, ip, port) return mock.udp_send(h, d, ip, port) end
                function s:close() mock.udp_close(h) end
                return s
              end }
            end
        """)
        with open(os.path.join(HERE, "mock_engine.lua"), encoding="utf-8") as f:
            self.lua.execute(f.read())
        self.lua.execute("""
            mccross_bridge = require('extensions/mccross/bridge')
            local camCtor = require('extensions/core/cameraModes/mccrossCamera')
            mock.camFilter = camCtor()
            function mock.step(dt)
              mock.t = mock.t + dt
              mccross_bridge.onUpdate(dt, dt, dt)
              if mccross_bridge.onPreRender then mccross_bridge.onPreRender(dt, dt, dt) end
              -- what cameraUpdate.lua does each frame: active camera, then running filters, then output
              local data = { renderView = 'main', res = { pos = vec3(0, -10, 2), rot = quat(0, 0, 0, 1), fov = 60 } }
              mock.camFilter:update(data)
              mock.cam.pos = vec3(data.res.pos)
              mock.cam.rot = quat(data.res.rot.x, data.res.rot.y, data.res.rot.z, data.res.rot.w)
              mock.cam.fov = data.res.fov
            end
        """)
        self.step = g.mock.step
        g.mccross_bridge.onExtensionLoaded()

    @classmethod
    def _bytes_keys(cls, v):
        # With encoding=None, Lua strings are bytes on the Python side; keys must match that.
        if isinstance(v, dict):
            return {k.encode(): cls._bytes_keys(x) for k, x in v.items()}
        if isinstance(v, list):
            return [cls._bytes_keys(x) for x in v]
        if isinstance(v, str):
            return v.encode()
        return v

    def log(self, level, tag, msg):
        t = time.perf_counter() - self.t0
        lvl = level.decode() if isinstance(level, bytes) else str(level)
        line = f"{t:10.5f}|{lvl}|GELua.{tag.decode() if isinstance(tag, bytes) else tag}| {msg.decode() if isinstance(msg, bytes) else msg}"
        if lvl == "E":
            self.errors.append(line)
        if self.log_file:
            self.log_file.write(line + "\n")
            self.log_file.flush()
        if not self.quiet or lvl in ("E", "W"):
            print(line, flush=True)

    def run(self, seconds):
        dt = 1.0 / self.fps
        end = time.perf_counter() + seconds if seconds > 0 else float("inf")
        nxt = time.perf_counter()
        while time.perf_counter() < end:
            try:
                self.step(dt)
            except lupa.LuaError as e:
                self.log("E", "mock", f"Lua error in frame: {e}")
                raise
            nxt += dt
            delay = nxt - time.perf_counter()
            if delay > 0:
                time.sleep(delay)
            else:
                nxt = time.perf_counter()
        self.lua.globals().mccross_bridge.onExtensionUnloaded()


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port", type=int, default=int(os.environ.get("BNGBRIDGE_PORT", "47020")))
    ap.add_argument("--seconds", type=float, default=0, help="0 = run until Ctrl+C")
    ap.add_argument("--fps", type=float, default=60)
    ap.add_argument("--quiet", action="store_true")
    ap.add_argument("--log", default=None, help="also write the log to this file")
    a = ap.parse_args()
    print(f"MOCK BeamNG on 127.0.0.1:{a.port} at {a.fps:.0f} fps (not the real game)", flush=True)
    m = MockBeamng(a.port, a.fps, a.quiet, log_path=a.log)
    try:
        m.run(a.seconds)
    except KeyboardInterrupt:
        pass
    sys.exit(1 if m.errors else 0)


if __name__ == "__main__":
    main()
