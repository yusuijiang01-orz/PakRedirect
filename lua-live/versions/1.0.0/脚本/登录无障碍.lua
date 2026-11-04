-- Localized login state machine. The host may provide semantic nodes, or a
-- foreground-guarded adapter backed by the saved Chinese login button templates.

local function pauseBeforeAction()
  sleep(math.random(500, 1500))
end

_ENV["无图登录运行"] = function(maxDurationMs, maxRetries)
  maxDurationMs = tonumber(maxDurationMs) or 180000
  maxRetries = tonumber(maxRetries) or 3
  local startedAt = tickCount()
  local stateSince = startedAt
  local lastState = ""
  local lastActionState = ""
  local lastActionAt = startedAt
  local retryCount = 0

  while tickCount() - startedAt < maxDurationMs do
    local state = tostring(gameUiState() or "unknown")
    local now = tickCount()
    local acted = false

    if state ~= lastState then
      lastState = state
      stateSince = now
      lastActionState = ""
    end

    if state == "world" then return true, "world" end

    if state == "loading" then
      if now - stateSince >= 90000 then
        return false, "游戏资源加载超过 90 秒"
      end

    elseif state == "terms" or state == "server" or state == "login" or state == "role" then
      local action = state == "terms" and "agree"
        or (state == "server" and "server_confirm")
        or (state == "login" and "login" or "confirm")
      if lastActionState ~= state then
        pauseBeforeAction()
        acted = true
        if not gameUiClick(action) then
          return false, "流程控件不可用: " .. action
        end
        lastActionState = state
        lastActionAt = tickCount()
      elseif now - lastActionAt >= 10000 then
        return false, "点击后 10 秒页面未切换: " .. state
      end
    elseif state == "connecting" then
      if now - stateSince >= 10000 and lastActionState ~= "back" then
        pauseBeforeAction()
        acted = true
        if not gameUiClick("back") then
          return false, "登录卡住超过 10 秒，但无障碍返回按钮不可用"
        end
        lastActionState = "back"
        lastActionAt = tickCount()
        retryCount = retryCount + 1
        if retryCount > maxRetries then
          return false, "登录重试次数已达上限"
        end
      elseif lastActionState == "back" and now - lastActionAt >= 10000 then
        return false, "点击返回后 10 秒页面仍未切换"
      end
    elseif now - stateSince >= 15000 then
      return false, "无障碍状态未知 15 秒；拒绝盲目触控: " .. state
    end

    if not acted then sleep(250) end
  end
  return false, "登录流程超过时限"
end

return _ENV["无图登录运行"]
