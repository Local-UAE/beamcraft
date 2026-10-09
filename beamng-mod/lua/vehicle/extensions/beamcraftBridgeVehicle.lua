local M = {}

local httpJsonServer = require("utils/httpJsonServer")
local port = 23514
local initialized = false

local function currentVehicleStatus()
  local position = obj:getPosition()
  local velocity = obj:getVelocity()
  local vehicleName = (v.data and (v.data.vehicleDirectory or v.data.vehicleName)) or "BeamNG vehicle"

  return {
    connected = true,
    game = "BeamNG.drive",
    vehicle = {
      name = vehicleName,
      speedMps = velocity:length(),
      x = position.x,
      y = position.y,
      z = position.z
    }
  }
end

local function handleRequest(request)
  if not request.uri then
    return {connected = false, error = "invalid request"}
  end

  if request.uri.path == "v1/ping" then
    return {connected = true, game = "BeamNG.drive"}
  elseif request.uri.path == "v1/status" then
    return currentVehicleStatus()
  end

  return {connected = false, error = "not found"}
end

local function onExtensionLoaded()
  if initialized then
    return
  end
  httpJsonServer.init("127.0.0.1", port, handleRequest)
  initialized = true
  log("I", "beamcraftBridgeVehicle", "Local BeamCraft telemetry bridge listening on 127.0.0.1:" .. port)
end

local function updateGFX()
  if initialized then
    httpJsonServer.update()
  end
end

M.onExtensionLoaded = onExtensionLoaded
M.updateGFX = updateGFX

return M
