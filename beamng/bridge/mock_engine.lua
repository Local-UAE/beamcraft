-- MOCK of the BeamNG game-engine Lua environment, for offline tests only (see mock_beamng.py).
-- It implements just enough of BeamNG's globals for beamng-mod's Lua to run under plain LuaJIT.
-- Nothing here is BeamNG code; behaviour that matters is checked against the real game.

-- vec3 ----------------------------------------------------------------------------------------
local V = {}
V.__index = V
function vec3(x, y, z)
  if type(x) == 'table' then return setmetatable({ x = x.x, y = x.y, z = x.z }, V) end
  return setmetatable({ x = x or 0, y = y or 0, z = z or 0 }, V)
end
function V:set(x, y, z)
  if type(x) == 'table' then self.x, self.y, self.z = x.x, x.y, x.z else self.x, self.y, self.z = x, y, z end
end
function V.__add(a, b) return vec3(a.x + b.x, a.y + b.y, a.z + b.z) end
function V.__sub(a, b) return vec3(a.x - b.x, a.y - b.y, a.z - b.z) end
function V.__mul(a, b)
  if type(a) == 'number' then return vec3(b.x * a, b.y * a, b.z * a) end
  return vec3(a.x * b, a.y * b, a.z * b)
end
function V:length() return math.sqrt(self.x * self.x + self.y * self.y + self.z * self.z) end
function V:dot(o) return self.x * o.x + self.y * o.y + self.z * o.z end
function V:cross(o) return vec3(self.y * o.z - self.z * o.y, self.z * o.x - self.x * o.z, self.x * o.y - self.y * o.x) end
function V:normalized() local l = self:length(); return l > 0 and vec3(self.x / l, self.y / l, self.z / l) or vec3() end

-- quat (Hamilton, v' = q v q*) -------------------------------------------------------------------
local Q = {}
Q.__index = Q
local function newq(x, y, z, w) return setmetatable({ x = x, y = y, z = z, w = w }, Q) end
function quat(x, y, z, w)
  if y == nil then
    if x == nil then return newq(1, 0, 0, 0) end   -- same quirk as BeamNG
    return newq(x.x, x.y, x.z, x.w)
  end
  return newq(x, y, z, w)
end
function Q:set(x, y, z, w) self.x, self.y, self.z, self.w = x, y, z, w end
function Q.__mul(a, b)
  if getmetatable(b) == V then
    local ux, uy, uz, w = a.x, a.y, a.z, a.w
    local tx, ty, tz = 2 * (uy * b.z - uz * b.y), 2 * (uz * b.x - ux * b.z), 2 * (ux * b.y - uy * b.x)
    return vec3(b.x + w * tx + (uy * tz - uz * ty), b.y + w * ty + (uz * tx - ux * tz), b.z + w * tz + (ux * ty - uy * tx))
  end
  return newq(a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y, a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x,
    a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w, a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z)
end
-- Rotation taking +Y to dir and +Z to up (camera convention).
function Q:setFromDir(dir, up)
  local f = vec3(dir):normalized()
  local r = f:cross(up):normalized()
  local u = r:cross(f)
  -- matrix columns r, f, u
  local m00, m10, m20, m01, m11, m21, m02, m12, m22 = r.x, r.y, r.z, f.x, f.y, f.z, u.x, u.y, u.z
  local tr = m00 + m11 + m22
  if tr > 0 then
    local s = math.sqrt(tr + 1) * 2
    self:set((m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s, 0.25 * s)
  elseif m00 > m11 and m00 > m22 then
    local s = math.sqrt(1 + m00 - m11 - m22) * 2
    self:set(0.25 * s, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s)
  elseif m11 > m22 then
    local s = math.sqrt(1 + m11 - m00 - m22) * 2
    self:set((m01 + m10) / s, 0.25 * s, (m12 + m21) / s, (m02 - m20) / s)
  else
    local s = math.sqrt(1 + m22 - m00 - m11) * 2
    self:set((m02 + m20) / s, (m12 + m21) / s, 0.25 * s, (m10 - m01) / s)
  end
end
function quatFromDir(dir, up) local q = newq(0, 0, 0, 1); q:setFromDir(dir, up); return q end
function quatFromAxisAngle(a, ang)
  local n = a:normalized(); local s = math.sin(ang / 2)
  return newq(n.x * s, n.y * s, n.z * s, math.cos(ang / 2))
end

-- misc globals ------------------------------------------------------------------------------------
beamng_version = '0.0.0.MOCK'
worldReadyState = 2   -- main.lua global: 2 = level loaded, loading screen gone
function tableFindKey(t, v) for k, x in pairs(t) do if x == v then return k end end end
Engine = { getStartingArgs = function() return mock.args end }
function ui_message(msg) mock.log('U', 'ui_message', tostring(msg)) end
function log(level, tag, msg) mock.log(level, tag, msg) end
function print(...) mock.log('A', 'print', table.concat({ ... }, ' ')) end
function getCurrentLevelIdentifier() return mock.level end
simTimeAuthority = { getPause = function() return mock.paused end }

-- One mock vehicle driving a 30 m circle at 10 m/s, plus a parked one.
local function makeVeh(id, model, fn)
  local v = { id = id, model = model }
  function v:getID() return self.id end
  function v:getActive() return true end
  function v:getJBeamFilename() return self.model end
  function v:getPositionXYZ() local p = fn(); return p.px, p.py, p.pz end
  function v:getDirectionVectorXYZ() local p = fn(); return p.dx, p.dy, 0 end
  function v:getDirectionVectorUpXYZ() return 0, 0, 1 end
  function v:getVelocityXYZ() local p = fn(); return p.vx, p.vy, 0 end
  return v
end
local function circle()
  local a = mock.t * (10 / 30)
  return { px = 30 * math.cos(a), py = 30 * math.sin(a), pz = 0.5, dx = -math.sin(a), dy = math.cos(a), vx = -10 * math.sin(a), vy = 10 * math.cos(a) }
end
local function parked() return { px = 5, py = 10, pz = 0.7, dx = 1, dy = 0, vx = 0, vy = 0 } end
mock.vehicles = { makeVeh(1001, 'pickup', circle), makeVeh(1002, 'etk800', parked) }
function getPlayerVehicle(p) return mock.vehicles[1] end
function activeVehiclesIterator()
  local i = 0
  return function()
    i = i + 1
    local v = mock.vehicles[i]
    if v then return v.id, v end
  end
end
be = {}
function be:getPlayerVehicleID() return 1001 end
local function byId(id) for _, v in ipairs(mock.vehicles) do if v.id == id then return v end end end
function be:getObjectOOBBCenterXYZ(id) local x, y, z = byId(id):getPositionXYZ(); return x, y, z + 0.4 end
function be:getObjectOOBBHalfAxisXYZ(id, i)
  local dx, dy = byId(id):getDirectionVectorXYZ()
  if i == 0 then return dy * 0.95, -dx * 0.95, 0 end
  if i == 1 then return dx * 2.6, dy * 2.6, 0 end
  return 0, 0, 0.9
end

-- Mock world: flat ground at z = 0, a ramp rising along +X between x = 10 and 20 (to 5 m), and a
-- 1 m thick wall at y in [-5, -4] for x in [-10, 10], 3 m tall. Vehicles are not hit.
local function groundZ(x, y)
  local z = 0
  if x >= 10 and x <= 20 and y >= -2 and y <= 2 then z = (x - 10) * 0.5 end
  if x > 20 and x <= 25 and y >= -2 and y <= 2 then z = 5 end
  if y >= -5 and y <= -4 and x >= -10 and x <= 10 then z = math.max(z, 3) end
  return z
end
mock.groundZ = groundZ
-- Marches the ray (fine for tests): returns the first point at or below the ground.
function castRayStatic(o, d, maxDist)
  local step = 0.02
  local t = 0
  while t <= maxDist do
    local x, y, z = o.x + d.x * t, o.y + d.y * t, o.z + d.z * t
    if z <= groundZ(x, y) then return t end
    t = t + step
  end
  return maxDist
end
Engine.castRay = function(a, b, includeTerrain, renderGeometry)
  local d = b - a
  local len = d:length()
  local t = castRayStatic(a, d:normalized(), len)
  if t >= len then return nil end
  local p = a + d:normalized() * t
  -- normal: wall faces are vertical, the ramp has a 0.5 slope, the rest is flat
  local nx, nz = 0, 1
  if p.y >= -5.05 and p.y <= -3.95 and p.x >= -10 and p.x <= 10 and p.z < 3 then nz = 0 end
  if p.x >= 10 and p.x <= 20 and p.y >= -2 and p.y <= 2 then nx, nz = -0.447, 0.894 end
  return { pt = p, norm = vec3(nx, 0, nz) }
end

-- Camera system stand-in: the last pose "rendered" by mock_beamng.py.
mock.cam = { pos = vec3(0, -10, 2), rot = quat(0, 0, 0, 1), fov = 60, mode = 'orbit' }
core_camera = {}
function core_camera.getPositionXYZ() return mock.cam.pos.x, mock.cam.pos.y, mock.cam.pos.z end
function core_camera.getForwardXYZ() local f = mock.cam.rot * vec3(0, 1, 0); return f.x, f.y, f.z end
function core_camera.getUp() return mock.cam.rot * vec3(0, 0, 1) end
function core_camera.getQuatXYZW() local q = mock.cam.rot; return q.x, q.y, q.z, q.w end
function core_camera.getFovDeg() return mock.cam.fov end
function core_camera.getActiveCamName() return mock.cam.mode end

os.clockhp = function() return mock.clock() end

-- settings (in memory) and extensions.reload
mock.settingValues = { fpsLimitBackgroundEnabled = true }
settings = {}
function settings.getValue(k, d) local v = mock.settingValues[k]; if v == nil then return d end; return v end
function settings.setValue(k, v) mock.settingValues[k] = v; return true end
extensions = {}
function extensions.reload(name)
  assert(name == 'mccross_bridge', 'mock only reloads mccross_bridge')
  mccross_bridge.onExtensionUnloaded()
  package.loaded['extensions/mccross/bridge'] = nil
  mccross_bridge = require('extensions/mccross/bridge')
  mccross_bridge.onExtensionLoaded()
end

-- debug drawing (records calls so tests can count them)
mock.drawn = 0
function ColorF(r, g, b, a) return { r = r, g = g, b = b, a = a } end
debugDrawer = {}
function debugDrawer:drawSphere(p, r, c) mock.drawn = mock.drawn + 1 end

-- vehicle methods used by vehicle_test
-- A mock car's skin is a 1.9 x 5.2 x 1.4 m box: 8 nodes, 12 triangles (vmesh.lua).
local BOX_NODES = { { -0.95, -2.6, 0 }, { 0.95, -2.6, 0 }, { 0.95, 2.6, 0 }, { -0.95, 2.6, 0 },
                    { -0.95, -2.6, 1.4 }, { 0.95, -2.6, 1.4 }, { 0.95, 2.6, 1.4 }, { -0.95, 2.6, 1.4 } }
local BOX_TRIS = { 0, 2, 1, 0, 3, 2, 4, 5, 6, 4, 6, 7, 0, 1, 5, 0, 5, 4, 1, 2, 6, 1, 6, 5, 2, 3, 7, 2, 7, 6, 3, 0, 4, 3, 4, 7 }
for _, v in ipairs(mock.vehicles) do
  function v:queueLuaCommand(s)
    mock.log('D', 'mock', 'queueLuaCommand ' .. s:sub(1, 80))
    if s:find('v.data.triangles', 1, true) and mccross_bridge then mccross_bridge.vmeshTris(self.id, 8, jsonEncode(BOX_TRIS)) end
  end
  function v:getNodeCount() return 8 end
  function v:getNodePositionXYZ(i) local n = BOX_NODES[i + 1]; return n[1], n[2], n[3] end
  function v:getRefNodeId() return 0 end
  function v:applyClusterVelocityScaleAdd(n, s, x, y, z) mock.log('D', 'mock', string.format('velocity add %.1f %.1f %.1f', x, y, z)) end
end

-- explosion / enter_vehicle support
function be:getObjectOOBBHalfExtentsXYZ(id) return 0.95, 2.6, 0.9 end
function getObjectByID(id) for _, v in ipairs(mock.vehicles) do if v.id == id then return v end end end
function be:enterVehicle(player, veh) mock.log('D', 'mock', 'enterVehicle ' .. tostring(veh and veh.id)) end

-- TSStatic / scenetree / collision reload (blocks -> cubes)
mock.objects = {}
local nextObj = 5000
function createObject(cls)
  nextObj = nextObj + 1
  local o = { id = nextObj, cls = cls, fields = {} }
  function o:setField(k, i, v) self.fields[k] = v end
  function o:setPosition(p) self.pos = p end
  function o:registerObject(name) self.name = name; mock.objects[self.id] = self end
  function o:getID() return self.id end
  function o:delete() mock.objects[self.id] = nil end
  o.obj = o
  return o
end
scenetree = { MissionGroup = { addObject = function() end }, findObjectById = function(id) return mock.objects[id] end }
mock.collisionReloads = 0
function be:reloadCollision() mock.collisionReloads = mock.collisionReloads + 1 end
