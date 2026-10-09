#!/usr/bin/env python3
"""Unit tests for the pure Lua modules in beamng-mod (no engine APIs), run under LuaJIT via lupa.

    python beamng/bridge/test_lua.py
"""
import os
import unittest

import lupa.luajit21 as lupa

HERE = os.path.dirname(os.path.abspath(__file__))
MCCROSS = os.path.join(os.path.dirname(HERE), "beamng-mod", "lua", "ge", "extensions", "mccross")


def load(name):
    lua = lupa.LuaRuntime(unpack_returned_tuples=True)
    with open(os.path.join(MCCROSS, name + ".lua"), encoding="utf-8") as f:
        return lua, lua.execute(f.read())


class CollisionScheduleTest(unittest.TestCase):
    def setUp(self):
        self.lua, self.S = load("collsched")
        self.st = self.S.new()

    def cars(self, *cars):
        return self.lua.table_from([self.lua.table_from(c) for c in cars])

    def due(self, cars, t, last_reload=0.0):
        return self.S.due(self.st, cars, t, last_reload)

    def test_nothing_changed_means_no_rebuild(self):
        self.assertIsNone(self.due(self.cars(), 100.0))

    def test_change_far_from_cars_waits_for_a_quiet_moment(self):
        self.S.mark(self.st, 10, 10, 100, 100.0)
        far = self.cars([60.0, 10.0, 100.0, 0.0])
        self.assertIsNone(self.due(far, 100.0 + self.S.QUIET - 0.1))
        self.assertEqual(self.due(far, 100.0 + self.S.QUIET), "quiet")

    def test_each_new_change_restarts_the_quiet_wait(self):
        self.S.mark(self.st, 10, 10, 100, 100.0)
        self.S.mark(self.st, 11, 10, 100, 101.5)
        self.assertIsNone(self.due(self.cars(), 100.0 + self.S.QUIET))
        self.assertEqual(self.due(self.cars(), 101.5 + self.S.QUIET), "quiet")

    def test_changes_that_never_pause_still_rebuild_after_max_wait(self):
        # a redstone clock or a long build: changes keep coming less than QUIET apart
        t = 100.0
        while t < 100.0 + self.S.MAX_WAIT:
            self.S.mark(self.st, 10, 10, 100, t)
            self.assertIsNone(self.due(self.cars(), t))
            t += 1.0
        self.S.mark(self.st, 10, 10, 100, t)
        self.assertEqual(self.due(self.cars(), t), "max wait")

    def test_parked_car_next_to_a_change_rebuilds_at_once(self):
        self.S.mark(self.st, 10, 10, 100, 100.0)
        beside = self.cars([13.0, 10.5, 100.3, 0.0])
        self.assertEqual(self.due(beside, 100.0), "car near")

    def test_fast_car_heading_for_a_change_rebuilds_early(self):
        self.S.mark(self.st, 10, 10, 100, 100.0)
        # 30 m away at 25 m/s: inside LOOKAHEAD seconds of travel
        self.assertEqual(self.due(self.cars([10.5, -20.0, 100.3, 25.0]), 100.0), "car near")
        # the same car parked does not
        self.assertIsNone(self.due(self.cars([10.5, -20.0, 100.3, 0.0]), 100.0))

    def test_distance_is_to_the_whole_changed_box(self):
        # cells span [min, max + 1]; a car between two far-apart changes is inside the box
        self.S.mark(self.st, 0, 0, 100, 100.0)
        self.S.mark(self.st, 40, 0, 100, 100.0)
        self.assertEqual(self.due(self.cars([20.0, 0.5, 100.5, 0.0]), 100.0), "car near")

    def test_terrain_patches_go_into_physics_soon_but_not_every_frame(self):
        # BeamNG's physics takes up terrain heights only ~0.7 s after the last updateGrid anywhere, so a
        # steady stream of patches kept it on the old ground (a car fell through a deck, 2026-10-05);
        # a collision rebuild pushes them at once
        self.assertIsNone(self.due(self.cars(), 100.0, 99.0))
        self.S.markTerrain(self.st)
        self.assertIsNone(self.due(self.cars(), 100.0, 100.0 - self.S.TERRAIN_INTERVAL / 2))
        self.assertEqual(self.due(self.cars(), 100.0, 100.0 - self.S.TERRAIN_INTERVAL), "terrain")
        self.S.done(self.st)
        self.assertIsNone(self.due(self.cars(), 101.0, 100.0))

    def test_terrain_rebuilds_stop_on_a_level_where_they_are_slow(self):
        self.S.markTerrain(self.st)
        self.S.reloaded(self.st, self.S.TERRAIN_MAX_MS + 1)   # a big level: ~530 ms a rebuild
        self.S.markTerrain(self.st)
        self.assertIsNone(self.due(self.cars(), 100.0, 90.0))

    def test_full_resend_rebuilds_at_once(self):
        self.S.markAll(self.st, 100.0)
        self.assertEqual(self.due(self.cars(), 100.0), "resend")

    def test_never_more_often_than_min_interval(self):
        self.S.mark(self.st, 10, 10, 100, 100.0)
        beside = self.cars([13.0, 10.5, 100.3, 0.0])
        self.assertIsNone(self.due(beside, 100.0, 100.0 - self.S.MIN_INTERVAL + 0.01))
        self.assertEqual(self.due(beside, 100.0, 100.0 - self.S.MIN_INTERVAL), "car near")

    def test_done_clears_the_batch(self):
        self.S.mark(self.st, 10, 10, 100, 100.0)
        self.S.markAll(self.st, 100.0)
        self.S.done(self.st)
        self.assertIsNone(self.due(self.cars([10.5, 10.5, 100.0, 0.0]), 200.0))
        # a later change starts a fresh box, not one grown from the old cells
        self.S.mark(self.st, 500, 500, 100, 200.0)
        self.assertIsNone(self.due(self.cars([10.5, 10.5, 100.0, 0.0]), 200.0))


FAKE_CARS = """
local fake = { sent = {}, queued = {} }
function fake.car(id, px, py, pz, n)
  local car = { id = id, px = px, py = py, pz = pz, n = n }
  function car:getID() return self.id end
  function car:getPositionXYZ() return self.px, self.py, self.pz end
  function car:getNodeCount() return self.n end
  function car:getNodePositionXYZ(i) return i * 0.01 + 0.004, -1.126, 2 end
  function car:getDirectionVectorXYZ() return 0, 1, 0 end
  function car:getDirectionVectorUpXYZ() return 0, 0, 1 end
  function car:queueLuaCommand(s) fake.queued[#fake.queued + 1] = { id = self.id, cmd = s } end
  return car
end
function fake.send(c, t, payload)
  fake.sent[#fake.sent + 1] = { c = c, t = t, p = payload }
end
function fake.count(t, c)
  local n = 0
  for _, s in ipairs(fake.sent) do if s.t == t and (c == nil or s.c == c) then n = n + 1 end end
  return n
end
function fake.last(t)
  for i = #fake.sent, 1, -1 do if fake.sent[i].t == t then return fake.sent[i].p end end
end
function fake.clear() fake.sent = {}; fake.queued = {} end
return fake
"""


class VehicleMeshTest(unittest.TestCase):
    def setUp(self):
        self.lua, self.V = load("vmesh")
        self.fake = self.lua.execute(FAKE_CARS)
        self.st = self.V.new()
        self.client = self.lua.table_from({"name": "mc"})
        self.near = self.fake.car(7, 10.0, 20.0, 0.3, 3)
        self.far = self.fake.car(8, 900.0, 20.0, 0.3, 3)

    def tick(self, *cars):
        self.V.tick(self.st, self.lua.table_from(list(cars)), 0.0, 0.0, 0.0, self.fake.send)

    def test_unsubscribed_clients_get_nothing(self):
        self.tick(self.near)
        self.assertEqual(self.fake.count("vmesh"), 0)

    def test_sends_node_offsets_in_whole_millimetres(self):
        self.V.subscribe(self.st, self.client, 200)
        self.tick(self.near)
        m = self.fake.last("vmesh")
        self.assertEqual(m.id, 7)
        self.assertEqual(m.n, 3)
        self.assertEqual(list(m.pos.values()), [10.0, 20.0, 0.3])
        # offsets i*0.01+0.004, -1.126, 2 -> mm, rounded; q says so
        self.assertEqual(m.q, 1000)
        self.assertEqual(list(m.p.values()), [4, -1126, 2000, 14, -1126, 2000, 24, -1126, 2000])
        self.assertEqual(list(m.fwd.values()), [0, 1, 0])
        self.assertEqual(list(m.up.values()), [0, 0, 1])

    def test_cars_out_of_range_are_skipped(self):
        self.V.subscribe(self.st, self.client, 200)
        self.tick(self.near, self.far)
        self.assertEqual(self.fake.count("vmesh"), 1)
        self.assertEqual(self.fake.last("vmesh").id, 7)

    def test_triangles_are_fetched_once_and_sent_once_per_client(self):
        self.V.subscribe(self.st, self.client, 200)
        self.tick(self.near)
        self.tick(self.near)
        self.assertEqual(len(self.fake.queued), 1)        # one fetch while pending
        self.assertEqual(self.fake.count("vtris"), 0)
        self.assertEqual(self.fake.last("vmesh").tv, 0)   # no shape yet
        self.V.onTris(self.st, 7, 3, self.lua.table_from([0, 1, 2]))
        self.tick(self.near)
        self.tick(self.near)
        self.assertEqual(self.fake.count("vtris"), 1)
        t = self.fake.last("vtris")
        self.assertEqual((t.id, t.n), (7, 3))
        self.assertEqual(list(t.tris.values()), [0, 1, 2])
        self.assertEqual(self.fake.last("vmesh").tv, t.tv)

    def test_a_client_can_ask_for_the_triangles_again(self):
        self.V.subscribe(self.st, self.client, 200)
        self.tick(self.near)
        self.V.onTris(self.st, 7, 3, self.lua.table_from([0, 1, 2]))
        self.tick(self.near)
        self.V.resendTris(self.st, self.client, 7)
        self.tick(self.near)
        self.assertEqual(self.fake.count("vtris"), 2)

    def test_a_new_node_count_means_a_new_shape(self):
        self.V.subscribe(self.st, self.client, 200)
        self.tick(self.near)
        self.V.onTris(self.st, 7, 3, self.lua.table_from([0, 1, 2]))
        self.tick(self.near)
        self.near.n = 4   # the car was reloaded with other parts
        self.tick(self.near)
        self.assertEqual(len(self.fake.queued), 2)
        self.assertEqual(self.fake.last("vmesh").tv, 0)

    def test_unsubscribe_stops_the_stream(self):
        self.V.subscribe(self.st, self.client, 200)
        self.tick(self.near)
        self.V.unsubscribe(self.st, self.client)
        self.fake.clear()
        self.tick(self.near)
        self.assertEqual(self.fake.count("vmesh"), 0)


# A fake core_vehicles and file system, shaped like core/vehicles.lua's getModel results.
FAKE_SELECT_ENV = """
local files = { ['/vehicles/pickup/default.jpg'] = 'JPEGDATA', ['/vehicles/pickup/d15_4wd_A.jpg'] = 'JPG2' }
local written, spawned, placed, replaced = {}, {}, {}, {}
local models = {
  pickup = { model = { key = 'pickup', Name = 'D-Series', Brand = 'Gavril', Type = 'Truck', default_pc = 'd15_4wd_A',
                       preview = '/vehicles/pickup/default.jpg' },
             configs = { d15_4wd_A = { key = 'd15_4wd_A', Configuration = 'D15 4WD (A)', is_default_config = true,
                                       preview = '/vehicles/pickup/d15_4wd_A.jpg' },
                         d35_race = { key = 'd35_race', Configuration = 'D35 Race', is_default_config = false,
                                      preview = '/ui/images/appDefault.png' } } },
  scintilla = { model = { key = 'scintilla', Name = 'Scintilla', Brand = 'Civetta', Type = 'Car', default_pc = 'gt',
                          preview = '/ui/images/appDefault.png' },
                configs = { gt = { key = 'gt', Configuration = 'GT', is_default_config = true } } },
}
local veh = { id = 4242 }
function veh:getID() return self.id end
local env = {
  userPath = 'C:/bng/',
  exists = function(p) return files[p] ~= nil or written[p] ~= nil end,
  read = function(p) return files[p] end,
  write = function(p, d) written[p] = d end,
  place = function(v, pos, fwd) placed[#placed + 1] = { v = v, pos = pos, fwd = fwd } end,
  vehicles = {
    getModelList = function(array)
      local out = {}
      for _, m in pairs(models) do out[#out + 1] = m.model end
      return { models = out }
    end,
    getModel = function(key) return models[key] or {} end,
    spawnNewVehicle = function(model, opt) spawned[#spawned + 1] = { model = model, opt = opt }; return veh end,
    replaceVehicle = function(model, opt) replaced[#replaced + 1] = { model = model, opt = opt }; return veh end,
  },
}
return env, written, spawned, placed, replaced
"""


class CarSelectTest(unittest.TestCase):
    def setUp(self):
        self.lua, self.S = load("carselect")
        self.env, self.written, self.spawned, self.placed, self.replaced = self.lua.execute(FAKE_SELECT_ENV)

    def spawn(self, m):
        # spawn returns one value on success; lupa would unpack that table, so return both in one
        r = self.lua.eval("function(S, env, m) local v, e = S.spawn(env, m) return {v = v, e = e} end")(self.S, self.env, m)
        return r.v, r.e

    def msg(self, **kw):
        t = self.lua.table()
        for k, v in kw.items():
            t[k] = self.lua.table_from(v) if isinstance(v, list) else v
        return t

    def test_models_are_sorted_by_brand_with_counts_and_thumbnails(self):
        models = self.S.models(self.env)
        self.assertEqual([models[1].k, models[2].k], ["scintilla", "pickup"])   # Civetta before Gavril
        pickup = models[2]
        self.assertEqual((pickup.name, pickup.brand, pickup.type, pickup.configs, pickup["def"]),
                         ("D-Series", "Gavril", "Truck", 2, "d15_4wd_A"))
        self.assertEqual(pickup.thumb, "C:/bng/temp/mccross/thumbs/vehicles_pickup_default.jpg")
        self.assertEqual(self.written["/temp/mccross/thumbs/vehicles_pickup_default.jpg"], "JPEGDATA")

    def test_the_placeholder_preview_is_not_copied(self):
        models = self.S.models(self.env)
        self.assertIsNone(models[1].thumb)

    def test_a_thumbnail_is_copied_only_once(self):
        self.S.models(self.env)
        self.written["/temp/mccross/thumbs/vehicles_pickup_default.jpg"] = "CACHED"
        self.S.models(self.env)
        self.assertEqual(self.written["/temp/mccross/thumbs/vehicles_pickup_default.jpg"], "CACHED")

    def test_configs_list_the_default_first(self):
        configs = self.S.configs(self.env, "pickup")
        self.assertEqual([configs[1].k, configs[2].k], ["d15_4wd_A", "d35_race"])
        self.assertTrue(configs[1]["def"])
        self.assertEqual(configs[1].name, "D15 4WD (A)")
        self.assertIsNone(configs[2].thumb)

    def test_unknown_model_has_no_configs(self):
        self.assertIsNone(self.S.configs(self.env, "nope"))

    def test_spawn_new_puts_the_car_beside_the_player_without_entering_it(self):
        veh, err = self.spawn(self.msg(model="pickup", config="d35_race", mode="new", pos=[1, 2, 0], fwd=[0, 1, 0]))
        self.assertIsNone(err)
        self.assertEqual(veh.id, 4242)
        self.assertEqual(self.spawned[1].model, "pickup")
        self.assertEqual(self.spawned[1].opt.config, "d35_race")
        self.assertFalse(self.spawned[1].opt.autoEnterVehicle)
        self.assertEqual(self.placed[1].pos[1], 1)

    def test_replace_swaps_the_player_car(self):
        veh, err = self.spawn(self.msg(model="scintilla", config="gt", mode="replace"))
        self.assertIsNone(err)
        self.assertEqual(self.replaced[1].model, "scintilla")
        self.assertEqual(len(self.spawned), 0)

    def test_missing_config_means_the_default(self):
        self.spawn(self.msg(model="pickup", mode="replace"))
        self.assertEqual(self.replaced[1].opt.config, "d15_4wd_A")

    def test_only_listed_models_and_configs_spawn(self):
        for m in (self.msg(model="../../evil", mode="replace"),
                  self.msg(model="pickup", config="/levels/x.pc", mode="replace"),
                  self.msg(model=7, mode="replace")):
            veh, err = self.spawn(m)
            self.assertIsNone(veh)
            self.assertTrue(err)
        self.assertEqual(len(self.replaced), 0)

    def test_a_default_config_the_mod_doesnt_ship_falls_back_to_one_it_does(self):
        env, spawned = self.lua.execute("""
          local spawned = {}
          local veh = { id = 7 }
          local m = { model = { key = 'svct5', default_pc = 'base_v6_A' },
                      configs = { blackwing = { key = 'blackwing', Configuration = 'Blackwing', is_default_config = false },
                                  base = { key = 'base', Configuration = 'Base', is_default_config = false } } }
          return { userPath = 'C:/bng/', exists = function() return false end, place = function() end,
                   vehicles = { getModel = function(k) return k == 'svct5' and m or {} end,
                                spawnNewVehicle = function(model, opt) spawned[#spawned + 1] = opt.config; return veh end } }, spawned""")
        r = self.lua.eval("function(S, env, m) local v, e = S.spawn(env, m) return {v = v, e = e} end")(
            self.S, env, self.msg(model="svct5", mode="new", pos=[0, 0, 0], fwd=[0, 1, 0]))
        self.assertIsNone(r.e)
        self.assertEqual(spawned[1], "base")
        r = self.lua.eval("function(S, env, m) local v, e = S.spawn(env, m) return {v = v, e = e} end")(
            self.S, env, self.msg(model="svct5", config="base_v6_A", mode="new", pos=[0, 0, 0], fwd=[0, 1, 0]))
        self.assertIn("unknown config", r.e)   # asked for by name it still has to exist

    def test_spawn_new_needs_a_position(self):
        veh, err = self.spawn(self.msg(model="pickup", mode="new"))
        self.assertIsNone(veh)
        self.assertIn("pos", err)


FAKE_GROUND_ENV = """
local made, deleted, cars, moves = {}, {}, { { id = 1, z = 0.1 }, { id = 2, z = -0.2 }, { id = 3, z = 8.0 } }, {}
local nextId = 100
local env = {
  box = function(x0, y0, z0, x1, y1, z1) nextId = nextId + 1; made[nextId] = { x0, y0, z0, x1, y1, z1 }; return nextId end,
  delete = function(id) deleted[#deleted + 1] = id; made[id] = nil end,
  cars = function() return cars end,
  moveCar = function(id, dz) moves[#moves + 1] = { id = id, dz = dz }; for _, c in ipairs(cars) do if c.id == id then c.z = c.z + dz end end end,
}
return env, made, deleted, cars, moves
"""


class GroundTest(unittest.TestCase):
    def setUp(self):
        self.lua, self.G = load("ground")
        self.env, self.made, self.deleted, self.cars, self.moves = self.lua.execute(FAKE_GROUND_ENV)
        self.st = self.G.new()

    def boxes(self, *b):
        return self.lua.table_from([v for box in b for v in box])

    @staticmethod
    def count(t):
        return sum(1 for _ in t.keys())   # len() of a Lua table is its border, 0 for keys from 101

    def test_a_flat_chunk_is_one_box(self):
        n = self.G.setChunk(self.st, self.env, 0, 0, self.boxes([0, -16, 0, 16, 0, 3]))
        self.assertEqual(n, 1)
        self.assertEqual(self.st.count, 1)
        self.assertEqual(list(self.made[101].values()), [0, -16, 0, 16, 0, 3])

    def test_new_boxes_for_a_chunk_replace_the_old_ones(self):
        self.G.setChunk(self.st, self.env, 0, 0, self.boxes([0, -16, 0, 16, 0, 3]))
        self.G.setChunk(self.st, self.env, 0, 0, self.boxes([0, -16, 0, 16, 0, 2], [0, -16, 2, 8, 0, 3]))
        self.assertEqual(list(self.deleted.values()), [101])
        self.assertEqual(self.st.count, 2)

    def test_broken_or_oversized_boxes_are_skipped(self):
        n = self.G.setChunk(self.st, self.env, 0, 0, self.boxes([0, 0, 0, 0, 1, 1], [0, 0, 0, 40, 1, 1], [0, 0, 0, 1, 1, 1]))
        self.assertEqual(n, 1)

    def test_turning_on_lifts_cars_on_the_floor_once(self):
        lifted = self.G.turnOn(self.st, self.env, 3.0, 3.0)
        self.assertEqual(lifted, 2)   # the car up on a tower (z 8) stays
        self.assertEqual([(m.id, m.dz) for m in self.moves.values()], [(1, 3.0), (2, 3.0)])
        self.assertEqual(self.G.turnOn(self.st, self.env, 3.0, 3.0), 0)

    def test_restore_lowers_cars_on_the_ground_and_removes_the_boxes(self):
        self.G.setChunk(self.st, self.env, 2, 3, self.boxes([32, -64, 0, 48, -48, 3]))
        self.G.turnOn(self.st, self.env, 3.0, 3.0)
        self.cars[2].z = 0.1   # car 2 fell into a hole: it stays on the floor
        self.assertTrue(self.G.restore(self.st, self.env))
        self.assertEqual([(m.id, m.dz) for m in self.moves.values()][-2:], [(1, -3.0), (3, -3.0)])
        self.assertEqual(self.st.count, 0)
        self.assertEqual(self.count(self.made), 0)
        self.assertFalse(self.st.on)

    def test_restore_with_nothing_in_place_changes_nothing(self):
        self.assertFalse(self.G.restore(self.st, self.env))

    def test_restore_reports_removed_boxes_even_when_never_turned_on(self):
        self.G.setChunk(self.st, self.env, 0, 0, self.boxes([0, -16, 0, 16, 0, 3]))
        self.assertTrue(self.G.restore(self.st, self.env))
        self.assertEqual(self.count(self.moves), 0)


class CarFxTest(unittest.TestCase):
    def setUp(self):
        self.lua, self.F = load("carfx")

    def cmd(self, **kw):
        t = self.lua.table()
        for k, v in kw.items():
            t[k] = self.lua.table_from(v) if isinstance(v, list) else v
        r = self.lua.eval("function(F, m) local c, e = F.command(m) return {c = c, e = e} end")(self.F, t)
        return r.c, r.e

    def test_fire_effects_are_beamngs_own_calls(self):
        self.assertEqual(self.cmd(kind="ignite")[0], "fire.igniteVehicle()")
        self.assertEqual(self.cmd(kind="extinguish")[0], "fire.extinguishVehicle()")

    def test_a_dent_pushes_the_nodes_at_the_hit_in_and_balances_it_over_the_rest(self):
        c, e = self.cmd(kind="dent", pos=[10.0, 0.0, 1.0], dir=[0.0, 2.0, 0.0], damage=7)
        self.assertIsNone(e)
        self.assertIn("local hx, hy, hz, dx, dy, dz, R, DV, T = 10.000, 0.000, 1.000, 0.0000, 1.0000, 0.0000, 0.300, 56.000, 0.0150", c)
        self.assertIn("obj:getNodeMass(cid)", c)   # a speed change: light nodes pushed less hard
        self.assertIn("obj:applyForceVectorTime(cid, f and dir * f or back, T)", c)
        self.assertNotIn("deflateTire", c)

    def test_a_hit_near_a_wheel_can_pop_the_tire(self):
        c, _ = self.cmd(kind="dent", pos=[1.0, 2.0, 0.4], dir=[1.0, 0.0, 0.0], damage=2, tire=True)
        self.assertIn("beamstate.deflateTire(best)", c)
        self.assertIn("local tx, ty, tz = 1.000, 2.000, 0.400", c)

    def test_damage_is_capped_and_strings_never_reach_the_command(self):
        c, _ = self.cmd(kind="dent", pos=[0.0, 0.0, 0.0], dir=[1.0, 0.0, 0.0], damage=1000)
        self.assertIn(", 120.000, ", c)   # 40 x 8 m/s, capped
        for bad in (dict(kind="os.exit()"), dict(kind="dent", pos=["1", 0, 0], dir=[1, 0, 0], damage=3),
                    dict(kind="dent", pos=[0.0, 0.0, 0.0], dir=[0.0, 0.0, 0.0], damage=3)):
            c, e = self.cmd(**bad)
            self.assertIsNone(c)
            self.assertTrue(e)


FAKE_EXPORT_ENV = """
FS = { fileExists = function(self, p) return true end, getUserPath = function(self) return 'C:/user/' end,
       stat = function(self, p) return { filesize = 1234 } end, removeFile = function(self, p) end }
scenetree = { findObject = function(name) return nil end }
extensions = { load = function(name) end }
util_export = { exportFile = function(path) end }
written = {}
jsonWriteFile = function(path, data, pretty) written[path] = data end
-- a side file written by an earlier export: its pose, groups and materials, the first car's id and paint
jsonReadFile = function(path)
  if path == '/temp/mccross/exports.json' then return nil end
  return { id = 7, model = 'sdd_g80', n = 812, p = { 1, 2, 3 }, groups = { body = { 1, 2 } }, materials = { m = {} },
           paint = { { 1, 0, 0, 1 } }, paintData = { { 0, 1, 1, 0 } } }
end
getPlayerVehicle = function(slot) return nil end
be = { enterVehicle = function(self, slot, veh) end }
core_vehicle_manager = { getVehicleData = function(id) return nil end }
map = { objects = { [9] = { damage = 0 }, [10] = { damage = 2400 } } }   -- BeamNG's damage tally per car
local function car(id, model, n, pc, color)
  return { getID = function(self) return id end, getJBeamFilename = function(self) return model end,
           getNodeCount = function(self) return n end, getMaterialNames = function(self) return {} end,
           getNodePositionXYZ = function(self, i) return 0, 0, 0 end, getPositionXYZ = function(self) return 0, 0, 0 end,
           getMetallicPaintData = function(self) return { { metallic = 1, roughness = 0.65, clearcoat = 1, clearcoatRoughness = 0.03 } } end,
           partConfig = pc or ('/vehicles/' .. model .. '/base.pc'), color = color or { x = 0.1, y = 0.2, z = 0.3, w = 1 } }
end
return car
"""


class CarExportTest(unittest.TestCase):
    """The parts of carexport.lua that don't need BeamNG: which requests start an export."""

    def setUp(self):
        self.lua, self.X = load("carexport")
        self.car = self.lua.execute(FAKE_EXPORT_ENV)
        self.st = self.X.new()
        # a finished export of car 7 (model sdd_g80, 812 nodes) made for vmesh shape 3
        reply = self.lua.table_from({"id": 7, "model": "sdd_g80", "file": "C:/user/temp/mccross/sdd_g80_7.glb"})
        self.st.done[7] = self.lua.table_from({"model": "sdd_g80", "n": 812, "tv": 3, "path": "/temp/mccross/sdd_g80_7.glb",
                                               "side": "/temp/mccross/sdd_g80_7.json", "reply": reply})

    def start(self, veh, tv):
        r = self.lua.eval("function(X, st, veh, tv) local e, r = X.start(st, 'client', veh, 0, function() end, tv) "
                          "return {e = e, r = r} end")(self.X, self.st, veh, tv)
        return r.e, r.r

    def test_an_unchanged_car_is_answered_from_its_last_export(self):
        err, reply = self.start(self.car(7, "sdd_g80", 812), 3)
        self.assertIsNone(err)
        self.assertEqual(reply.file, "C:/user/temp/mccross/sdd_g80_7.glb")
        self.assertTrue(reply.cached)
        self.assertIsNone(self.st.job)   # no new export, so no freeze

    def test_a_replaced_or_rebuilt_car_is_exported_again(self):
        for veh, tv in ((self.car(7, "sdd_g80", 812), 4), (self.car(7, "pickup", 900), 3), (self.car(7, "sdd_g80", 812), None)):
            self.st.job = None
            err, reply = self.start(veh, tv)
            self.assertIsNone(reply)
            self.assertIsNotNone(self.st.job)

    def test_asking_again_for_the_car_being_exported_waits_for_that_export(self):
        self.start(self.car(9, "pickup", 900), 5)
        self.st.job.mark = "first"
        err, reply = self.start(self.car(9, "pickup", 900), 5)
        self.assertIsNone(err)
        self.assertIsNone(reply)
        self.assertEqual(self.st.job.mark, "first")   # still the same export, not a new one

    def test_a_layer_is_read_as_beamng_renders_it(self):
        # the M3's paint layer (RRpbrPaintPrimary, layer 1), fields as getField returns them
        mat = self.lua.execute("""
            local f = { colorPaletteMap = '/vehicles/common/RRpbrPaint/RR_Paint_PBR_skin_metallic_uv1color.dds',
              colorPaletteMapUseUV = '1', instanceDiffuse = '1', metallicFactor = '1', roughnessFactor = '0.5',
              clearCoatFactor = '1', clearCoatRoughnessFactor = '0', opacityFactor = '1', normalMapStrength = '1',
              opacityMap = '/vehicles/common/RRpbrPaint/RR_Paint_PBR_skin_odata.dds', paletteBaseColor = '1',
              paletteMetallic = '1', paletteRoughness = '0', baseColorFactor = '1 1 1 1' }
            return { getField = function(self, k, l) return f[k] or '' end }
        """)
        layer = self.X.layerOf(mat, 1)
        self.assertEqual(layer.maps.palette, "/vehicles/common/RRpbrPaint/RR_Paint_PBR_skin_metallic_uv1color.dds")
        self.assertEqual(layer.maps.base, "")
        self.assertTrue(layer.uv1.palette)
        self.assertFalse(layer.uv1.opacity)
        self.assertTrue(layer.instanceDiffuse)
        self.assertEqual((layer.metallic, layer.roughness, layer.clearCoat, layer.opacity), (1, 0.5, 1, 1))
        self.assertTrue(layer.palette.metallic)
        self.assertFalse(layer.palette.roughness)
        self.assertEqual(layer.baseColorFactor[1], 1)

    def test_another_car_waits_while_one_is_exported(self):
        self.start(self.car(9, "pickup", 900), 5)
        err, reply = self.start(self.car(11, "pickup", 900), 6)
        self.assertEqual(err.code, "busy")

    def shared_export(self):
        # the first G80 built from x.pc was exported before (this session or an earlier one)
        self.st.byParts["sdd_g80|/vehicles/sdd_g80/x.pc"] = self.lua.table_from(
            {"model": "sdd_g80", "n": 812, "path": "/temp/mccross/sdd_g80_7.glb", "side": "/temp/mccross/sdd_g80_7.json", "bytes": 85418424})

    def test_a_new_car_built_the_same_way_reuses_the_export_with_its_own_paint(self):
        self.shared_export()
        green = self.lua.table_from({"x": 0.0, "y": 0.8, "z": 0.1, "w": 1.0})
        err, reply = self.start(self.car(12, "sdd_g80", 812, "/vehicles/sdd_g80/x.pc", green), 1)
        self.assertIsNone(err)
        self.assertIsNone(self.st.job)                               # no export, so no freeze
        self.assertEqual(reply.file, "C:/user/temp/mccross/sdd_g80_7.glb")
        self.assertEqual(reply.side, "C:/user/temp/mccross/sdd_g80_12.json")
        self.assertTrue(reply.shared)
        side = self.lua.globals().written["/temp/mccross/sdd_g80_12.json"]
        self.assertEqual(side.id, 12)
        self.assertEqual((side.paint[1][1], side.paint[1][2]), (0.0, 0.8))   # this car's paint, not the first one's
        self.assertEqual(side.paintData[1][2], 0.65)
        self.assertEqual(side.p[3], 3)                               # the export's own pose, which its mesh was written in
        err, again = self.start(self.car(12, "sdd_g80", 812, "/vehicles/sdd_g80/x.pc", green), 1)
        self.assertTrue(again.cached)                                # asked again: answered from its own entry

    def test_a_shared_export_does_not_wait_for_a_running_one(self):
        self.shared_export()
        self.start(self.car(9, "pickup", 900), 5)
        err, reply = self.start(self.car(12, "sdd_g80", 812, "/vehicles/sdd_g80/x.pc"), 1)
        self.assertIsNone(err)
        self.assertEqual(reply.file, "C:/user/temp/mccross/sdd_g80_7.glb")

    def test_other_parts_or_another_node_count_mean_a_new_export(self):
        self.shared_export()
        for veh in (self.car(12, "sdd_g80", 812, "/vehicles/sdd_g80/y.pc"), self.car(13, "sdd_g80", 813, "/vehicles/sdd_g80/x.pc")):
            self.st.job = None
            err, reply = self.start(veh, 1)
            self.assertIsNone(reply)
            self.assertIsNotNone(self.st.job)

    def finish(self, vid, pc):
        self.start(self.car(vid, "pickup", 900, pc), 5)               # textures already converted: written at once
        job = self.st.job
        job.phase, job.t0, job.size = "write", 0, 1234                # the file stopped growing at 1234 bytes
        sent = []
        self.lua.eval("function(X, st, f) X.update(st, 1.0, f, function() end) end")(self.X, self.st, lambda c, t, b: sent.append(t))
        self.assertEqual(sent, ["vexported"])

    def test_a_finished_export_is_kept_for_cars_built_the_same_way(self):
        self.finish(9, "/vehicles/pickup/a.pc")
        e = self.st.byParts["pickup|/vehicles/pickup/a.pc"]
        self.assertEqual((e.model, e.n, e.path, e.bytes), ("pickup", 900, "/temp/mccross/pickup_9.glb", 1234))
        self.assertIsNotNone(self.lua.globals().written["/temp/mccross/exports.json"])

    def test_a_dented_car_is_not_exported_for_the_others(self):
        self.finish(10, "/vehicles/pickup/a.pc")                       # damage 2400 when it was exported
        self.assertIsNone(self.st.byParts["pickup|/vehicles/pickup/a.pc"])

    def test_an_export_overwriting_anothers_files_takes_its_place(self):
        # vehicle ids repeat after a restart: car 9 built from b.pc writes pickup_9.glb, which a.pc's entry pointed at
        self.st.byParts["pickup|/vehicles/pickup/a.pc"] = self.lua.table_from(
            {"model": "pickup", "n": 900, "path": "/temp/mccross/pickup_9.glb", "side": "/temp/mccross/pickup_9.json", "bytes": 1})
        self.finish(9, "/vehicles/pickup/b.pc")
        self.assertIsNone(self.st.byParts["pickup|/vehicles/pickup/a.pc"])
        self.assertIsNotNone(self.st.byParts["pickup|/vehicles/pickup/b.pc"])


LATCH_VDATA = """
-- a stock door's latch controller (etk800_doors_F.jbeam) and a mod's latch breakgroups (the M3's)
return {
  nodes = { [0] = { cid = 0, name = 'p5r' }, [1] = { cid = 1, name = 'd14rr' }, [2] = { cid = 2, name = 'dl1' },
            [3] = { cid = 3, name = 'dl2' }, [4] = { cid = 4, name = 'h1' }, [5] = { cid = 5, name = 'h2' } },
  controller = { { fileName = 'advancedCouplerControl', name = 'door_FR_coupler' }, { fileName = 'esc' } },
  door_FR_coupler = { couplerNodes = { { 'cid1', 'cid2', 'autoCouplingStrength', 'breakGroup' }, { 'p5r', 'd14rr', 35000, 'doorFR_latch' } } },
  beams = { { id1 = 0, id2 = 1, breakGroup = 'doorFR_latch' }, { id1 = 2, id2 = 3, breakGroup = 'doorlatch_FL' },
            { id1 = 4, id2 = 5, breakGroup = { 'hoodlatch', 'hood_body' } }, { id1 = 2, id2 = 5, breakGroup = 'doorhinge_a_FL' } },
}
"""


class LatchesTest(unittest.TestCase):
    def setUp(self):
        self.lua, self.L = load("latches")
        self.vdata = self.lua.execute(LATCH_VDATA)

    def test_couplers_and_latch_breakgroups_become_targets_once_each(self):
        ts = self.L.targets(self.vdata)
        ids = [t.id for t in ts.values()]
        self.assertEqual(ids, ["latch:coupler:door_FR_coupler", "latch:break:doorlatch_FL", "latch:break:hoodlatch"])
        self.assertEqual(list(ts[1].nodes.values()), [0, 1])          # the coupler's two nodes, by name
        self.assertEqual([t.label for t in ts.values()], ["door FR", "door FL", "bonnet"])

    def test_a_bonnets_latch_and_catch_are_one_target_toggling_both(self):
        vdata = self.lua.execute("""
          return { nodes = { [0] = { cid = 0, name = 'a' }, [1] = { cid = 1, name = 'b' }, [2] = { cid = 2, name = 'c' }, [3] = { cid = 3, name = 'd' } },
            controller = { { fileName = 'advancedCouplerControl', name = 'hoodLatchCoupler' }, { fileName = 'advancedCouplerControl', name = 'hoodCatchCoupler' } },
            hoodLatchCoupler = { couplerNodes = { { 'cid1', 'cid2' }, { 'a', 'b' } } },
            hoodCatchCoupler = { couplerNodes = { { 'cid1', 'cid2' }, { 'c', 'd' } } } }""")
        ts = self.L.targets(vdata)
        self.assertEqual(len(ts), 1)
        cmd = self.L.command(ts[1].id, ts)
        self.assertIn('getController("hoodLatchCoupler")', cmd)
        self.assertIn('getController("hoodCatchCoupler")', cmd)
        self.assertEqual(len(list(ts[1].nodes.values())), 4)

    def test_labels(self):
        for name, label in (("doorlatch_L", "door L"), ("trunklatch", "boot"), ("tailgatelatch", "tailgate"),
                            ("doorL0latch", "door L0"), ("hoodLatchCoupler", "bonnet"), ("doorlatch_RR", "door RR")):
            self.assertEqual(self.L.label(name), label)

    def test_a_target_box_sits_on_its_nodes_and_says_what_it_does(self):
        ts = self.L.targets(self.vdata)
        pos = self.lua.eval("function(cid) return cid * 1.0, 10.0, 2.0 end")
        box = self.L.box(ts[2], pos)
        self.assertEqual((box.c[1], box.c[2], box.c[3]), (2.5, 10.0, 2.0))
        self.assertEqual(box.actions.action0, "Open door FL")
        self.assertEqual(self.L.box(ts[1], pos).actions.action0, "Open / close door FR")

    def test_commands_are_beamngs_own_and_only_for_listed_latches(self):
        ts = self.L.targets(self.vdata)
        self.assertEqual(self.L.command("latch:break:hoodlatch", ts), 'beamstate.breakBreakGroup("hoodlatch")')
        self.assertIn('controller.getController("door_FR_coupler")', self.L.command("latch:coupler:door_FR_coupler", ts))
        self.assertIn("toggleGroup()", self.L.command("latch:coupler:door_FR_coupler", ts))
        self.assertIsNone(self.L.command("latch:break:os.exit()", ts))


FAKE_TERRAIN_ENV = """
local log = { created = nil, creates = 0, removed = 0, set = {}, grids = {}, moves = {}, placed = {}, water = nil, waterRemoved = 0, t = 0 }
local cars = { { id = 1, x = 10, y = 10, z = 0.2 }, { id = 2, x = 50, y = 50, z = 101.0 } }
local env = {
  create = function(file, square, height, x0, y0, z0)
    log.created = { file, square, height, x0, y0, z0 }
    log.creates = log.creates + 1
    return true
  end,
  remove = function() log.removed = log.removed + 1 end,
  now = function() return log.t end,
  setHeight = function(x, y, z) log.set[#log.set + 1] = { x, y, z } end,
  updateGrid = function(x0, y0, x1, y1) log.grids[#log.grids + 1] = { x0, y0, x1, y1 } end,
  heightAt = function(x, y) return log.ground and log.ground(x, y) or 100.0 end,
  cars = function() return cars end,
  moveCar = function(id, dz) log.moves[#log.moves + 1] = { id, dz } end,
  placeCar = function(id, x, y, z) log.placed[#log.placed + 1] = { id, x, y, z } return true end,
  box = function(x0, y0, z0, x1, y1, z1) log.boxes = (log.boxes or 0) + 1 log.box = { x0, y0, z0, x1, y1, z1 } return 100 + log.boxes end,
  deleteBox = function(id) log.deleted = (log.deleted or 0) + 1 end,
  water = function(z) log.water = z end,
  removeWater = function() log.waterRemoved = log.waterRemoved + 1 end,
}
return env, log
"""


class TerrainTest(unittest.TestCase):
    def setUp(self):
        self.lua, self.T = load("terrain")
        self.env, self.log = self.lua.execute(FAKE_TERRAIN_ENV)
        self.st = self.T.new()

    def msg(self, **kw):
        t = self.lua.table()
        for k, v in kw.items():
            t[k] = self.lua.table_from(v) if isinstance(v, list) else v
        return t

    def load_ok(self):
        return self.T.load(self.st, self.env, self.msg(file="/temp/mccross/terrain_h.png", size=1024, square=0.714,
                                                       x0=-365.0, y0=-365.0, z0=60.0, height=120.0))

    def test_a_heightmap_from_minecraft_becomes_a_terrain(self):
        self.assertIsNone(self.load_ok())
        c = self.log.created
        self.assertEqual((c[1], c[2], c[3], c[6]), ("/temp/mccross/terrain_h.png", 0.714, 120.0, 60.0))

    def test_the_sea_comes_with_the_terrain_and_goes_with_it(self):
        self.T.load(self.st, self.env, self.msg(file="/temp/mccross/terrain_h.png", size=1024, square=0.714,
                                                x0=0.0, y0=0.0, z0=60.0, height=120.0, sea=90.0))
        self.assertEqual(self.log.water, 90.0)
        self.T.remove(self.st, self.env)
        self.assertEqual(self.log.waterRemoved, 1)

    def test_a_load_sent_again_after_it_was_built_does_not_rebuild(self):
        load = self.lua.eval("function(T, st, env, m) local e, same = T.load(st, env, m) return {e = e, same = same} end")
        first = dict(file="/temp/mccross/terrain_h.png", size=1024, square=0.714, x0=0.0, y0=0.0, z0=60.0, height=120.0, id=7)
        self.assertIsNone(load(self.T, self.st, self.env, self.msg(**first)).e)
        self.T.cells(self.st, self.env, self.msg(x=10, y=20, w=1, h=1, z=[5.0]))   # a patch after the build
        again = load(self.T, self.st, self.env, self.msg(**first))
        self.assertIsNone(again.e)
        self.assertTrue(again.same)
        self.assertEqual(self.log.creates, 1)                                      # the patch stays
        self.assertIsNone(load(self.T, self.st, self.env, self.msg(**dict(first, id=8))).same)
        self.assertEqual(self.log.creates, 2)                                      # a new build does load

    def test_switching_to_real_blocks_without_water_rebuilds_finer_and_drains_the_sea(self):
        base = dict(file="/temp/mccross/terrain_h.png", x0=0.0, y0=0.0, z0=60.0, height=120.0)
        self.assertIsNone(self.T.load(self.st, self.env, self.msg(**base, size=1024, square=0.714, sea=90.0, id=1)))
        self.assertIsNone(self.T.load(self.st, self.env, self.msg(**base, size=2048, square=0.357, id=2)))
        self.assertEqual(self.log.creates, 2)
        self.assertEqual(self.log.created[2], 0.357)
        self.assertEqual(self.log.waterRemoved, 1)
        self.assertEqual(self.T.cells(self.st, self.env, self.msg(x=2046, y=2046, w=2, h=2, z=[1.0, 1.0, 1.0, 1.0])), 4)

    def test_only_files_minecraft_writes_are_loaded(self):
        for f in ("/levels/smallgrid/x.png", "/temp/mccross/../../x.png", "C:/x.png"):
            self.assertEqual(self.T.load(self.st, self.env, self.msg(file=f, size=1024, square=1, x0=0, y0=0, z0=0, height=10)), "bad file")
        self.assertIsNone(self.log.created)

    def test_cars_under_the_surface_go_up_onto_it_the_others_stay(self):
        self.load_ok()
        self.assertEqual(self.T.liftCars(self.st, self.env), 1)
        move = self.log.moves[1]
        self.assertEqual(move[1], 1)
        self.assertAlmostEqual(move[2], 100.0 + self.T.LIFT_CLEARANCE - 0.2)

    def add_car(self, **kw):
        self.lua.eval("function(t, c) t[#t + 1] = c end")(self.env.cars(), self.lua.table_from(kw))

    def test_a_new_car_is_never_asked_for_under_the_terrain(self):
        self.assertEqual(self.T.spawnZ(self.st, self.env, 10.0, 10.0, 59.0), 59.0)   # no terrain yet: as asked
        self.load_ok()
        self.assertAlmostEqual(self.T.spawnZ(self.st, self.env, 10.0, 10.0, 59.0), 100.0 + self.T.LIFT_CLEARANCE)   # garage under a lawn
        self.assertEqual(self.T.spawnZ(self.st, self.env, 10.0, 10.0, 104.0), 104.0)  # above the ground: as asked
        self.assertEqual(self.T.spawnZ(self.st, self.env, 900.0, 10.0, 5.0), 5.0)     # off the terrain: as asked

    def test_cars_inside_the_ground_or_fallen_through_the_floor_go_back_on_top(self):
        self.load_ok()
        self.add_car(id=3, x=5000.0, y=10.0, z=-19874.0)     # the G87 that fell for minutes
        done = self.T.rescue(self.st, self.env)
        whys = {r.id: r.why for r in done.values()}
        self.assertEqual(whys, {1: "was inside the ground", 3: "fell through the floor"})   # car 2 stands on it
        placed = {p[1]: (p[2], p[3], p[4]) for p in self.log.placed.values()}
        self.assertEqual(placed[1], (10, 10, 100.0 + self.T.LIFT_CLEARANCE))
        far = -365.0 + 1023 * 0.714
        self.assertAlmostEqual(placed[3][0], far - self.T.EDGE)   # brought in from beyond the edge
        self.assertEqual(placed[3][1], 10)

    def test_a_car_parked_where_the_new_terrain_ends_keeps_a_stand(self):
        self.load_ok()                                            # x -365..365: car 2 at (50, 50) stands on it (z 101, ground 100)
        moved = dict(file="/temp/mccross/terrain_h.png", size=1024, square=0.714, x0=200.0, y0=-365.0, z0=60.0, height=120.0, id=5)
        self.assertIsNone(self.T.load(self.st, self.env, self.msg(**moved)))   # the player went east: (50, 50) is off it now
        self.assertEqual(self.log.boxes, 1)                       # car 1 is inside the ground (z 0.2), not standing on it: none
        b = self.log.box
        self.assertEqual((b[1], b[2], b[3], b[6]), (50 - self.T.STAND_HALF, 50 - self.T.STAND_HALF, 100 - self.T.STAND_DEPTH, 100.0))
        back = dict(moved, x0=-365.0, id=6)
        self.T.load(self.st, self.env, self.msg(**back))          # the terrain comes back over it: the stand goes
        self.assertEqual(self.log.deleted, 1)

    def test_no_rescue_while_a_patch_waits_for_the_grid(self):
        self.load_ok()
        self.T.cells(self.st, self.env, self.msg(x=1000, y=1000, w=1, h=1, z=[5.0]))   # far from the cars: waits 3 s
        self.assertEqual(len(self.T.rescue(self.st, self.env)), 0)
        self.log.t = 10.0
        self.T.flush(self.st, self.env)
        self.assertEqual(len(self.T.rescue(self.st, self.env)), 1)

    def test_a_car_just_put_back_is_left_alone_while_it_settles(self):
        self.load_ok()
        self.assertEqual(len(self.T.rescue(self.st, self.env)), 1)   # car 1, inside the ground
        self.log.t = 1.0
        self.assertEqual(len(self.T.rescue(self.st, self.env)), 0)   # still there a second later: not again yet
        self.log.t = 1.0 + self.T.RESCUE_COOLDOWN
        self.assertEqual(len(self.T.rescue(self.st, self.env)), 1)

    def test_a_car_in_a_deep_valley_of_a_tall_world_is_on_the_ground_not_lost(self):
        self.load_ok()
        self.log.ground = self.lua.eval("function(x, y) if x < 0 then return -80.0 end return 100.0 end")
        self.add_car(id=4, x=-20.0, y=10.0, z=-79.6)              # on the valley floor, 80 m below z 0
        done = self.T.rescue(self.st, self.env)
        self.assertNotIn(4, [r.id for r in done.values()])

    def test_without_a_terrain_only_a_fall_through_the_floor_is_rescued(self):
        self.add_car(id=3, x=5.0, y=5.0, z=-60.0)
        done = self.T.rescue(self.st, self.env)
        self.assertEqual([r.id for r in done.values()], [3])
        self.assertEqual(tuple(self.log.placed[1].values()), (3, 5.0, 5.0, self.T.LIFT_CLEARANCE))

    def test_a_patch_sets_its_samples_clamped_and_updates_the_grid_once(self):
        self.load_ok()
        n = self.T.cells(self.st, self.env, self.msg(x=10, y=20, w=2, h=2, z=[1.0, 2.0, -5.0, 500.0]))
        self.assertEqual(n, 4)
        self.log.t = 10.0
        self.T.flush(self.st, self.env)
        s = self.log.set
        self.assertEqual((s[1][1], s[1][2], s[1][3]), (10, 20, 1.0))
        self.assertEqual((s[3][1], s[3][2], s[3][3]), (10, 21, 0))      # clamped to the terrain's range
        self.assertAlmostEqual(s[4][3], 120.0 * 65534 / 65535)       # just under the top: exactly the top wraps to 0 in BeamNG
        self.assertLess(s[4][3], 120.0)
        self.assertEqual(sum(1 for _ in self.log.grids.keys()), 1)

    def test_patches_wait_and_go_into_the_grid_together(self):
        self.load_ok()   # corner (-365, -365), 0.714 m squares; car 1 stands at (10, 10)
        far = dict(w=2, h=2, z=[1.0, 1.0, 1.0, 1.0])
        self.T.cells(self.st, self.env, self.msg(x=0, y=0, **far))          # 520 m from car 1
        self.T.cells(self.st, self.env, self.msg(x=4, y=6, **far))
        self.log.t = 1.0
        self.assertIsNone(self.T.flush(self.st, self.env))                  # far: waits
        self.assertEqual(sum(1 for _ in self.log.grids.keys()), 0)
        self.log.t = 3.5
        self.assertIsNotNone(self.T.flush(self.st, self.env))
        g = self.log.grids[1]
        self.assertEqual((g[1], g[2], g[3], g[4]), (0, 0, 5, 7))             # one call for both patches
        self.assertEqual(sum(1 for _ in self.log.grids.keys()), 1)

    def test_a_patch_near_a_car_goes_in_at_once(self):
        self.load_ok()
        self.log.t = 100.0
        self.T.cells(self.st, self.env, self.msg(x=525, y=525, w=2, h=2, z=[1.0, 1.0, 1.0, 1.0]))   # (10, 10): car 1
        self.log.t = 100.2
        self.assertIsNotNone(self.T.flush(self.st, self.env))
        self.assertIsNone(self.T.flush(self.st, self.env))                  # nothing left

    def test_a_patch_outside_the_terrain_or_before_one_is_refused(self):
        cells = self.lua.eval("function(T, st, env, m) local n, e = T.cells(st, env, m) return {n = n, e = e} end")
        self.assertEqual(cells(self.T, self.st, self.env, self.msg(x=0, y=0, w=1, h=1, z=[1.0])).e, "no terrain")
        self.load_ok()
        self.assertEqual(cells(self.T, self.st, self.env, self.msg(x=1023, y=0, w=2, h=1, z=[1.0, 1.0])).e, "patch out of range")


class TriggersTest(unittest.TestCase):
    """Door handles found from Minecraft's eye ray (triggers.lua)."""

    def setUp(self):
        self.lua, self.T = load("triggers")

    def t(self, *xs):
        return self.lua.table_from(list(xs))

    def box(self, c, h, axes=((1, 0, 0), (0, 1, 0), (0, 0, 1))):
        return self.t(*c), self.lua.table_from([self.t(*a) for a in axes]), self.t(*h)

    def ray(self, o, d, c, h, axes=((1, 0, 0), (0, 1, 0), (0, 0, 1))):
        bc, ba, bh = self.box(c, h, axes)
        return self.T.rayBox(self.t(*o), self.t(*d), bc, ba, bh)

    def test_ray_straight_at_a_box_enters_its_near_face(self):
        self.assertAlmostEqual(self.ray((0, 0, 0), (1, 0, 0), (2, 0, 0), (0.1, 0.1, 0.1)), 1.9)

    def test_ray_beside_a_box_misses(self):
        self.assertIsNone(self.ray((0, 0, 0), (1, 0, 0), (2, 0.3, 0), (0.1, 0.1, 0.1)))

    def test_box_behind_the_eye_is_not_hit(self):
        self.assertIsNone(self.ray((0, 0, 0), (1, 0, 0), (-2, 0, 0), (0.1, 0.1, 0.1)))

    def test_eye_inside_a_box_hits_at_zero(self):
        self.assertEqual(self.ray((0, 0, 0), (0, 0, 1), (0, 0, 0), (0.5, 0.5, 0.5)), 0)

    def test_turned_box_uses_its_own_axes(self):
        # a thin handle turned 45 degrees about z: its long axis lies along x = y
        r = 0.5 ** 0.5
        axes = ((r, r, 0), (-r, r, 0), (0, 0, 1))
        long_thin = (1.0, 0.05, 0.05)
        # a ray down the y axis at x = 0.5 meets the long axis at (0.5, 0.5): a hit
        self.assertIsNotNone(self.ray((0.5, -5, 0), (0, 1, 0), (0, 0, 0), long_thin, axes))
        # at x = 0.5, z = 0.2 it passes over the 0.05-thick handle
        self.assertIsNone(self.ray((0.5, -5, 0.2), (0, 1, 0), (0, 0, 0), long_thin, axes))

    def env(self, cars):
        lua = self.lua
        triggers = {}
        for vid, trgs in cars.items():
            lst = []
            for tid, c in trgs:
                bc, ba, bh = self.box(c, (0.1, 0.1, 0.1))
                lst.append(lua.table_from({"t": tid, "c": bc, "a": ba, "h": bh, "name": "door" + str(tid)}))
            triggers[vid] = lua.table_from(lst)
        vehicles = lua.table_from(list(cars.keys()))
        return lua.table_from({
            "vehicles": lambda o, near: vehicles,
            "triggers": lambda vid, *ray: triggers[vid],
        })

    def test_aim_picks_the_nearest_trigger_across_cars(self):
        env = self.env({7: [(1, (3, 0, 0))], 9: [(4, (1.5, 0, 0)), (5, (2.5, 0, 0))]})
        hit = self.T.aim(env, self.t(0, 0, 0), self.t(1, 0, 0), 3.0)
        self.assertEqual((hit.v, hit.t), (9, 4))
        self.assertAlmostEqual(hit.dist, 1.4)

    def test_aim_ignores_triggers_out_of_reach(self):
        env = self.env({7: [(1, (5, 0, 0))]})
        self.assertIsNone(self.T.aim(env, self.t(0, 0, 0), self.t(1, 0, 0), 3.0))

    def test_action_titles_come_from_the_links_input_actions(self):
        lua = self.lua
        vdata = lua.execute("""return {
          triggerEventLinksDict = { [3] = { action0 = { { inputAction = 'door_FL' } }, action2 = { { inputAction = 'lock' } } } },
          inputActions = { door_FL = { title = 'Open front left door' }, lock = { title = 'ui.lock' } } }""")
        titles = self.T.actionTitles(vdata, 3, lambda s: s.upper() if s.startswith("ui.") else s)
        self.assertEqual(titles.action0, "Open front left door")
        self.assertIsNone(titles.action1)
        self.assertEqual(titles.action2, "UI.LOCK")

    def test_near_ray_skips_balls_off_the_ray_or_out_of_reach(self):
        o, d = self.t(0, 0, 0), self.t(1, 0, 0)
        self.assertTrue(self.T.nearRay(o, d, self.t(2, 0.1, 0), 0.2, 3.0))
        self.assertFalse(self.T.nearRay(o, d, self.t(2, 0.5, 0), 0.2, 3.0))     # beside the ray
        self.assertFalse(self.T.nearRay(o, d, self.t(3.5, 0, 0), 0.2, 3.0))     # past reach
        self.assertFalse(self.T.nearRay(o, d, self.t(-1, 0, 0), 0.2, 3.0))      # behind the eye
        self.assertTrue(self.T.nearRay(o, d, self.t(-0.1, 0, 0), 0.2, 3.0))     # the eye inside the ball

    def test_radius_covers_box_and_sphere_sizes(self):
        self.assertAlmostEqual(self.T.radius(self.lua.table_from([0.2, 0.4, 0.4])), 0.3)
        self.assertAlmostEqual(self.T.radius(self.lua.eval("{x = 0.2, y = 0.4, z = 0.4}")), 0.3)
        self.assertAlmostEqual(self.T.radius(0.1), 0.175)
        self.assertAlmostEqual(self.T.radius(None), 0.5 * (3 * 0.04) ** 0.5)

    def test_trigger_without_links_has_no_titles(self):
        vdata = self.lua.execute("return { triggerEventLinksDict = {}, inputActions = {} }")
        self.assertIsNone(self.T.actionTitles(vdata, 3, None))


if __name__ == "__main__":
    unittest.main()
