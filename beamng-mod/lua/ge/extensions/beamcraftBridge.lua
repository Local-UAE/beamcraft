local M = {}

local loadedVehicleId = nil

local function onUpdate()
  local vehicle = be:getPlayerVehicle(0)
  if not vehicle then
    if loadedVehicleId then
      local previous = getObjectByID(loadedVehicleId)
      if previous then
        previous:queueLuaCommand("extensions.unload('beamcraftBridgeVehicle')")
      end
      loadedVehicleId = nil
    end
    return
  end

  local vehicleId = vehicle:getId()
  if vehicleId ~= loadedVehicleId then
    if loadedVehicleId then
      local previous = getObjectByID(loadedVehicleId)
      if previous then
        previous:queueLuaCommand("extensions.unload('beamcraftBridgeVehicle')")
      end
    end
    loadedVehicleId = vehicleId
    vehicle:queueLuaCommand("extensions.load('beamcraftBridgeVehicle')")
  end
end

M.onUpdate = onUpdate

return M
