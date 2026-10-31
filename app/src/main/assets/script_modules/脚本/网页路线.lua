-- Browser route requests use the same localized search, OCR result, and title
-- verification path as the in-game workbench. This module never selects a
-- fixed map row. Arrival requires the matching HUD map and either observed
-- movement or the game's success toast at the requested coordinates.
local function selectedMapPanelVisible(mapName)
  local function exactTitle()
    local ok, rows = pcall(tomocrtexttable, 600, 0, 1000, 90)
    if not ok or type(rows) ~= "table" then return false end
    for _, row in pairs(rows) do
      if type(row) == "table" then
        local text = tostring(row.words or row.text or ""):gsub("%s", ""):gsub("　", "")
        if text == mapName and (tonumber(row.score) or 0) >= 0.82 then return true end
      end
    end
    return false
  end
  local ok, rows = pcall(tomocrtexttable, 0, 0, 150, 70)
  if not ok or type(rows) ~= "table" then return false end
  local npcTab = false
  for _, row in pairs(rows) do
    if type(row) == "table" then
      local x, y = tonumber(row.x), tonumber(row.y)
      local text = tostring(row.words or row.text or ""):gsub("%s", "")
      if x and y and x >= 20 and x <= 150 and y >= 15 and y <= 70
          and string.find(text, "NPC", 1, true) then
        npcTab = true
        break
      end
    end
  end
  return npcTab and exactTitle()
end

local function hudPosition()
  requireWebRouteDisplay()
  -- Lua OCR regions use the configured 1280x720 virtual reference space.
  -- The minimap digits appear around (1132..1196, 168..189) in that space.
  local ok, rows = pcall(tomocrtexttable, 1110, 160, 1210, 205)
  if not ok or type(rows) ~= "table" then return nil end
  for _, row in pairs(rows) do
    if type(row) == "table" and (tonumber(row.score) or 0) >= 0.7 then
      local label = tostring(row.words or row.text or ""):gsub("%s", "")
          :gsub("／", "/"):gsub("，", ",")
      local x, y = label:match("^(%d+)[/,](%d+)$")
      x, y = tonumber(x), tonumber(y)
      if x and y and x <= 99999 and y <= 99999 then return x, y end
    end
  end
  return nil
end

local function hudMapMatches(mapName)
  local expected = {[tostring(mapName):gsub("%s", ""):lower()] = true}
  for _, entry in pairs(_ENV["中越大地图数组"] or {}) do
    local chinese, vietnamese = tostring(entry):match("^([^,]+),(.+)$")
    if chinese == mapName then
      expected[vietnamese:gsub("%s", ""):lower()] = true
    end
  end
  -- The HUD map label is near the right edge, but still inside 1280x720.
  local ok, rows = pcall(tomocrtexttable, 1040, 0, 1260, 60)
  if not ok or type(rows) ~= "table" then return false end
  for _, row in pairs(rows) do
    if type(row) == "table" and (tonumber(row.score) or 0) >= 0.7 then
      local label = tostring(row.words or row.text or ""):gsub("%s", ""):lower()
      if expected[label] then return true end
    end
  end
  return false
end

local function gameRouteToast()
  -- The game briefly displays rejection or arrival after the checkmark.
  -- Read the shared toast region immediately, before either message fades.
  local ok, rows = pcall(tomocrtexttable, 350, 120, 930, 190)
  if not ok or type(rows) ~= "table" then return nil end
  local arrived = false
  for _, row in pairs(rows) do
    if type(row) == "table" and (tonumber(row.score) or 0) >= 0.7 then
      local label = tostring(row.words or row.text or ""):gsub("%s", "")
      if label:find("无法到达", 1, true) then return "rejected" end
      if label:find("已到目的地", 1, true) then arrived = true end
    end
  end
  if arrived then return "arrived" end
  return nil
end

local function minimapCoordinateFingerprint()
  keepCapture()
  local ok, fingerprint = pcall(function()
    local colors = {}
    -- Fast pixel sampling of the calibrated minimap coordinate readout. OCR
    -- runs only at startup and after movement, rather than on every poll.
    for x = 1133, 1195, 5 do
      for y = 171, 187, 4 do
        colors[#colors + 1] = tostring(getPixelColor(x, y, 1))
      end
    end
    return table.concat(colors, ",")
  end)
  releaseCapture()
  if not ok then error(fingerprint, 0) end
  return fingerprint
end

local function confirmSuccessToast(mapName, targetX, targetY, requestId)
  local x, y = hudPosition()
  if x and math.abs(x - targetX) <= 2 and math.abs(y - targetY) <= 2
      and hudMapMatches(mapName) then
    updateWebRouteRequest(requestId, "arrived",
        "游戏提示“已到目的地”，且目标地图与坐标已核对 " .. x .. "/" .. y)
    return true
  end
  return false
end

local function monitorWebRoute(mapName, targetX, targetY, requestId, originX, originY)
  local startX, startY, moved = tonumber(originX), tonumber(originY), false
  local startedAt = tonumber(tickCount()) or 0
  local lastFingerprint = minimapCoordinateFingerprint()
  local lastCoordinateRead = startedAt
  local nearDestination = 0
  for attempt = 1, 300 do
    -- A toast can appear just after the first post-checkmark OCR read.
    if attempt <= 5 then
      local toast = gameRouteToast()
      if toast == "rejected" then
        return false, "游戏提示“此处无法到达”；路线未启动"
      elseif toast == "arrived" and confirmSuccessToast(mapName, targetX, targetY, requestId) then
        return true, "已确认抵达"
      end
    end
    local fingerprint = minimapCoordinateFingerprint()
    local elapsed = (tonumber(tickCount()) or startedAt) - startedAt
    local shouldReadPosition = attempt == 1
        or (fingerprint ~= lastFingerprint and elapsed - lastCoordinateRead >= 5000)
        or (moved and elapsed - lastCoordinateRead >= 5000)
    if shouldReadPosition then
      local x, y = hudPosition()
      lastCoordinateRead = elapsed
      if x then
        if not startX then
          startX, startY = x, y
        elseif x ~= startX or y ~= startY then
          moved = true
          updateWebRouteRequest(requestId, "moving", "已观察到坐标变化；当前位置 " .. x .. "/" .. y)
        end
        if moved and math.abs(x - targetX) <= 2 and math.abs(y - targetY) <= 2
            and hudMapMatches(mapName) then
          nearDestination = nearDestination + 1
          if nearDestination >= 2 then
            updateWebRouteRequest(requestId, "arrived", "已观察到移动并在目标地图抵达 " .. x .. "/" .. y)
            return true, "已确认抵达"
          end
        else
          nearDestination = 0
        end
      else
        nearDestination = 0
      end
    end
    if shouldReadPosition then lastFingerprint = fingerprint end
    if elapsed >= 30000 and not moved then
      local current = startX and (startX .. "/" .. startY) or "坐标不可读"
      return false, "提交后 30 秒未观察到小地图坐标移动（" .. current .. "）；未确认抵达"
    elseif elapsed >= 300000 then
      break
    end
    sleep(1000)
  end
  return false, "已观察到移动，但 300 秒内未确认目标地图与坐标；未确认抵达"
end
_ENV["监视网页路线"] = monitorWebRoute

local function executeWebRoute(mapName, coordX, coordY, requestId)
  local target = tostring(mapName or "")
  coordX, coordY = tonumber(coordX), tonumber(coordY)
  if target == "" or not coordX or not coordY
      or coordX ~= math.floor(coordX) or coordY ~= math.floor(coordY)
      or coordX < 0 or coordX > 99999 or coordY < 0 or coordY > 99999 then
    return false, "地图名或坐标无效"
  end

  local function fail(message)
    updateWebRouteRequest(requestId, "failed", message)
    return false, message
  end
  local ok, result, message = pcall(function()
    requireWebRouteDisplay()
    local originX, originY = hudPosition()
    if not originX or not originY then
      error("无法读取路线开始前的小地图坐标；未提交路线")
    end
    if not _ENV["大地图页面已打开"]() then
      if not _ENV["在主页面"]() then error("当前不在主界面，未操作路线") end
      if _ENV["找到点击"]("@@关掉自动挂机", tz["自动挂机按钮_已开启"]) then
        sleep(400)
      end
      if not _ENV["大地图页面已打开"]() then
        if not _ENV["找到"](tz["自动挂机按钮_未开启"]) then
          error("未检测到可打开大地图的主界面控件")
        end
        _ENV["点击"]("@@打开大地图", 1159, 110, 1000)
      end
      for _ = 1, 10 do
        requireWebRouteDisplay()
        if _ENV["大地图页面已打开"]() then break end
        sleep(250)
      end
    end
    if not _ENV["大地图页面已打开"]() then error("大地图未打开") end
    -- The panel can open on the NPC tab. The strict selector changes to the
    -- map tab and requires its Chinese search field before entering any text.
    if not _ENV["大地图_输入大地图"](target, false, true) then
      error("未能精确匹配地图搜索结果并确认标题：" .. target)
    end
    requireWebRouteDisplay()
    if not selectedMapPanelVisible(target) then
      error("地图选择后无法同时确认地图页签与目标标题")
    end
    updateWebRouteRequest(requestId, "selected", "已精确匹配地图搜索结果并确认顶部标题：" .. target)

    _ENV["点击"]("@@输入x", 664, 655, 1000)
    for _ = 1, 10 do keyPress("del"); sleep(80) end
    imeLib.lock()
    imeLib.setText(tostring(coordX))
    sleep(250)
    _ENV["点击"]("@@xong", 116, 227, 1000)
    requireWebRouteDisplay()

    _ENV["点击"]("@@输入y", 774, 657, 1000)
    for _ = 1, 10 do keyPress("del"); sleep(80) end
    imeLib.setText(tostring(coordY))
    sleep(250)
    _ENV["点击"]("@@xong", 116, 227, 1000)
    requireWebRouteDisplay()
    if not selectedMapPanelVisible(target) then
      error("填写坐标后无法确认地图页签与目标标题；未提交路线")
    end

    -- Submission is intermediate; verify a success toast or observe movement.
    _ENV["点击"]("@@去目的地", 857, 651, 1000)
    updateWebRouteRequest(requestId, "submitted", "地图标题已确认，已提交游戏内路线；尚未确认抵达")
    local toast = gameRouteToast()
    if toast == "rejected" then
      return false, "游戏提示“此处无法到达”；路线未启动"
    elseif toast == "arrived" and confirmSuccessToast(target, coordX, coordY, requestId) then
      return true, "已确认抵达"
    end
    return _ENV["监视网页路线"](target, coordX, coordY, requestId, originX, originY)
  end)
  imeLib.unlock()
  if not ok then return fail(tostring(result)) end
  if not result then return fail(tostring(message or "路线执行失败")) end
  return true, tostring(message or "已确认抵达")
end

_ENV["执行网页路线"] = executeWebRoute

local function processWebRouteQueue()
  if type(pollWebRouteRequest) ~= "function" then return false end
  local request = pollWebRouteRequest()
  if request == nil then return false end
  local ok, accepted, message = pcall(_ENV["执行网页路线"], request.mapName,
      request.x, request.y, request.requestId)
  if not ok then
    updateWebRouteRequest(request.requestId, "failed", tostring(accepted))
  elseif not accepted then
    updateWebRouteRequest(request.requestId, "failed", tostring(message or "路线执行失败"))
  end
  return true
end
_ENV["处理网页路线队列"] = processWebRouteQueue

local function processWebProfileQueue()
  if type(pollWebProfileRequest) ~= "function" then return false end
  local request = pollWebProfileRequest()
  if request == nil then return false end
  local requestId = request.requestId
  local ok, message = pcall(function()
    local data = _ENV["界面数据"]
    if type(data) ~= "table" or type(request.values) ~= "table" then
      error("当前 Lua 方案数据不可用")
    end
    local function mapName(indexKey)
      local index = tonumber(request.values[indexKey] or data[indexKey])
      local maps = _ENV["中越大地图数组"]
      if not index or index ~= math.floor(index) or index < 0 or index >= #maps then
        error(indexKey .. " 超出地图范围")
      end
      local names = splitStr(maps[index + 1], ",")
      if type(names) ~= "table" or not names[1] or names[1] == "" then
        error(indexKey .. " 对应的地图名无效")
      end
      return names[1]
    end
    local routeMap = mapName("挂机点地图")
    local tradeMap = mapName("卖货地图")
    local gearQualityKeys = {"出售装备品质_白色", "出售装备品质_蓝色", "出售装备品质_绿色",
        "出售装备品质_黄色", "出售装备品质_橙色"}
    local hasGearQualitySelection = false
    for _, key in ipairs(gearQualityKeys) do
      if request.values[key] ~= nil then
        hasGearQualitySelection = true
        break
      end
    end
    for key, value in pairs(request.values) do data[key] = tostring(value) end
    if not hasGearQualitySelection and request.values["出售装备品质上限"] ~= nil then
      local legacyLevel = math.max(0, math.min(2, tonumber(request.values["出售装备品质上限"]) or 0))
      data["出售装备品质_白色"] = tostring(legacyLevel >= 1)
      data["出售装备品质_蓝色"] = "false"
      data["出售装备品质_绿色"] = tostring(legacyLevel >= 2)
      data["出售装备品质_黄色"] = "false"
      data["出售装备品质_橙色"] = "false"
    end
    local blueFilter = tonumber(data["蓝条筛选模式"]) or 0
    data["回蓝>="] = tostring(blueFilter == 1)
    data["魔法出招>="] = tostring(blueFilter == 2)
    data["武器出招>="] = tostring(blueFilter == 3)
    _ENV["挂机点地图"] = routeMap
    _ENV["卖货地图"] = tradeMap
    _ENV["挂机点x"] = data["挂机点x"]
    _ENV["挂机点y"] = data["挂机点y"]
    _ENV["队长名称数组"] = splitStr(data["队长名"] or "", "\n")
    _ENV["队友名称数组"] = splitStr(data["队员名"] or "", "\n")
    _ENV["是队长"] = data["是否队长"] == "0"
    _ENV["开启vpn"] = data["开启vpn"] == "true"
    _ENV["检测修理装备"] = data["检测修理装备"] == "true"
    _ENV["背包保留重量"] = tonumber(data["背包保留重量"])
    _ENV["背包保留格子"] = tonumber(data["背包保留格子"])
    _ENV["背包检测间隔秒"] = tonumber(data["背包检测间隔秒"])
    _ENV["保留蓝条数量"] = tonumber(data["保留蓝条数量"])
    updateWebProfileRequest(requestId, "applied", "网页方案已写入当前 Lua 运行配置")
  end)
  if not ok then
    updateWebProfileRequest(requestId, "failed", tostring(message or "网页方案应用失败"))
  end
  return true
end
_ENV["处理网页方案队列"] = processWebProfileQueue
