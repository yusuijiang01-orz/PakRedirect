_ENV["间隔允许执行"] = function(间隔时间毫秒, 执行代码)
  if _ENV["全局间隔执行变量"] == nil then
    _ENV["全局间隔执行变量"] = {}
  end
  if _ENV["全局间隔执行变量"][执行代码] == nil or 间隔时间毫秒 < tickCount() - _ENV["全局间隔执行变量"][执行代码] then
    _ENV["全局间隔执行变量"][执行代码] = tickCount()
    return load(_ENV["代码"])()
  end
  return false
end
_ENV["取开机秒数"] = function()
  local rtn = exec("cat /proc/uptime")
  local rtn_arr = splitStr(rtn, " ")
  if rtn_arr then
    return tonumber(rtn_arr[1]) or 0
  end
  return 0
end
_ENV["保留字母数字和常用汉字"] = function(str)
  local result = ""
  for char in string.gmatch(str, ".") do
    local byte = string.byte(char)
    if 48 <= byte and byte <= 57 or 65 <= byte and byte <= 90 or 97 <= byte and byte <= 122 then
      result = result .. char
    elseif 128 <= byte then
      result = result .. char
    end
  end
  return result
end
_ENV["保留字母数字和常用不常用汉字"] = function(str)
  local result = ""
  for i = 1, #str do
    local char = string.sub(str, i, i)
    local byte = string.byte(char)
    if 48 <= byte and byte <= 57 or 65 <= byte and byte <= 90 or 97 <= byte and byte <= 122 then
      result = result .. char
    elseif 128 <= byte then
      result = result .. char
    end
  end
  return result
end
_ENV["取当前时间戳秒"] = function()
  return os.time()
end
_ENV["时间戳秒转本地时间"] = function(时间戳秒)
  return os.date("%Y-%m-%d %H:%M:%S", 时间戳秒)
end
_ENV["找到带变化的区域_自定义区域"] = function(自定义区域数组, 判断间隔, 判断次数)
  hideToast()
  if 判断次数 == nil then
    判断次数 = 1
  end
  local arr = 自定义区域数组
  for i = 1, #自定义区域数组 do
    自定义区域数组[i]["变化"] = false
  end
  for ii = 1, 判断次数 do
    keepCapture()
    for i = 1, #自定义区域数组 do
      local pic_path = "/mnt/sdcard/" .. i .. ".png"
      snapShot(pic_path, 自定义区域数组[i][1], 自定义区域数组[i][2], 自定义区域数组[i][3], 自定义区域数组[i][4])
    end
    sleep(判断间隔)
    keepCapture()
    for i = 1, #自定义区域数组 do
      local pic_path = "/mnt/sdcard/" .. i .. ".png"
      local index, list = findPicFast(自定义区域数组[i][1], 自定义区域数组[i][2], 自定义区域数组[i][3] + 1, 自定义区域数组[i][4] + 1, pic_path, "101010", 0, 0.95)
      if index == -1 then
        print("找图返回", i)
        自定义区域数组[i]["变化"] = true
      end
      delfile(pic_path)
    end
    releaseCapture()
  end
  return arr
end
_ENV["找到带变化的区域"] = function(a, b, c, d, x步长, y步长, 判断间隔)
  local arr = {}
  keepCapture()
  local x_c, y_c = x步长, y步长
  for i = a, c, x_c do
    for ii = b, d, y_c do
      snapShot("/mnt/sdcard/" .. i .. "_" .. ii .. ".png", i, ii, i + x_c, ii + y_c)
    end
  end
  sleep(判断间隔)
  keepCapture()
  for i = a, c, x_c do
    for ii = b, d, y_c do
      local pic_path = "/mnt/sdcard/" .. i .. "_" .. ii .. ".png"
      local index, list = findPicFast(i, ii, i + x_c + 1, ii + y_c + 1, pic_path, "101010", 0, 0.95)
      if index == -1 then
        print("找图返回", index, i, ii)
        table.insert(arr, {i, ii})
      end
      delfile(pic_path)
    end
  end
  releaseCapture()
  return arr
end

function tonumberEx(...)
  local arg = (...)
  if type(arg) == "number" then
    return arg
  end
  if type(arg) == "string" then
    arg = arg:gsub("%D+", "")
  end
  local state, err = pcall(function()
    return tonumber(arg)
  end)
  if err == nil then
    return 0
  else
    return err
  end
end

_ENV["点击记录"] = {}
print("给tap增加参数")
swipe_ = swipe

function swipeex(a, b, c, d, time_)
  local times_ = math.floor(time_ / 10)
  local x_, y_ = math.floor((c - a) / times_), math.floor((d - b) / times_)
  local x_, y_ = (c - a) / times_, (d - b) / times_
  touchDown(1, a, b)
  for i = 1, times_ + 2 do
    touchMove(1, a + i * x_, b + i * y_)
    sleep(10)
  end
  sleep(100)
  print(1, c, d)
  touchMove(1, c, d)
  sleep(100)
  touchUp(1)
end

function swipe(...)
  local args = {
    ...
  }
  if #args == 6 then
    toast(args[1])
    table.remove(args, 1)
  end
  if #args == 5 then
    swipeex(table.unpack(args))
  end
  args.time = os.time()
  table.insert(_ENV["点击记录"], args)
end

tap_ = tap

function tap(...)
  local args = {
    ...
  }
  if type(args[1]) == "string" then
    local tips = table.remove(args, 1)
    toast(tips)
  end
  if #args == 2 then
    tap_(args[1], args[2])
  end
  if #args == 3 then
    tap_(args[1], args[2])
    sleep(args[3])
  end
  if #args == 4 then
    tap_(math.random(args[1], args[3]), math.random(args[2], args[4]))
  end
  if #args == 5 then
    tap_(math.random(args[1], args[3]), math.random(args[2], args[4]))
    sleep(args[5])
  end
  args.time = os.time()
  table.insert(_ENV["点击记录"], args)
end

toast_ = toast

function toast(...)
  toast_((...), 1, 620, 15)
end

sleep_ = sleep
sleep2 = sleep

function sleep(...)
  _ENV["子线程上次执休息时间"] = tickCount()
  sleep_(...)
end

_ENV["滑动并判断是否有变化"] = function(监控区域数组4个值, 滑动参数数组5个值, 滑动后等待时间, 判断相似度)
  if 判断相似度 == nil then
    判断相似度 = 0.8
  end
  snapShot("/sdcard/test.png", 监控区域数组4个值[1], 监控区域数组4个值[2], 监控区域数组4个值[3], 监控区域数组4个值[4])
  swipe(滑动参数数组5个值[1], 滑动参数数组5个值[2], 滑动参数数组5个值[3], 滑动参数数组5个值[4], 滑动参数数组5个值[5])
  sleep(滑动后等待时间)
  local rtn, x, y = findPic(监控区域数组4个值[1] - 1, 监控区域数组4个值[2] - 1, 监控区域数组4个值[3] + 1, 监控区域数组4个值[4] + 1, "/sdcard/test.png", "111111", 0, 判断相似度)
  delfile("/sdcard/test.png")
  if 0 < x then
    return false
  end
  return true
end
_ENV["点击记录_取多少秒没有点击"] = function()
  if #_ENV["点击记录"] == 0 then
    return 0
  end
  local lasttime = _ENV["点击记录"][#_ENV["点击记录"]].time
  return os.time() - lasttime
end
_ENV["上传人物数据"] = function(a, b, c, d, filename)
  local path = "/sdcard/" .. filename .. ".png"
  snapShot(path, a, b, c, d)
  local ret = uploadFile("http://1.94.49.83/upload2.php", path)
  print(ret)
  toast(ret)
  delfile(path)
end
_ENV["上传截图"] = function(是否停止)
  hideToast()
  ui.dismiss("layout1")
  sleep(2000)
  local path = "/sdcard/" .. os.time() .. ".png"
  snapShot(path)
  local ret = uploadFile("http://1.94.49.83/upload.php", path)
  print(ret)
  toast(ret)
  delfile(path)
  sleep(1000)
  if 是否停止 then
    exitScript()
  end
end
_ENV["执行任务"] = function(time_one, time_all, fun, ...)
  local args = {
    ...
  }
  local t1 = tickCount()
  while true do
    if time_all <= tickCount() - t1 then
      return false, "超时"
    end
    local rtn, rtn2 = fun(...)
    if rtn ~= nil then
      return rtn, rtn2
    end
    sleep(time_one)
  end
end

function getsobase_by_pid(pid)
  local input_data = exec(string.format("cat /proc/%d/maps", pid))
  local search_lib = "libil2cpp.so"
  local somsg = ""
  for line in input_data:gmatch([[
[^
]+]]) do
    if line:find(search_lib) then
      somsg = line
    end
  end
  local arr = splitStr(somsg, "-")
  return tonumber(arr[1], 16)
end

function getnewnamebypid(pid)
  local ps_output = exec("ps -ef |grep " .. pid)
  print("ps_output", ps_output)
  local process_name
  for line in ps_output:gmatch("[^\r\n]+") do
    if line:find(pid) and not line:find("grep " .. pid) then
      process_name = line:match("%S+%s+%S+%s+%S+%s+%S+%s+%S+%s+%S+%s+%S+%s+(%S+)")
      break
    end
  end
  if "[.f8games.royale]" == process_name then
    process_name = ""
  end
  print("Process name for PID " .. pid .. " is: " .. (process_name or "not found"))
  return process_name
end

function mygetpid()
  local rtn = exec("dumpsys activity top | grep 'ACTIVITY'")
  local input = [[
	ACTIVITY com.nx.nxprojit/com.main.app.MainActivity 8d8432d pid=2191
	ACTIVITY com.android.launcher3/.Launcher ad54ebc pid=2243
	ACTIVITY net.f8games.royale/.main.RoyaleActivity e5f9481 pid=4272
	]]
  input = rtn
  local target_package = "net.f8games.royale"
  local mpid = 0
  for line in input:gmatch("[^\r\n]+") do
    if line:find(target_package) then
      mpid = line:match("pid=(%d+)")
      break
    end
  end
  print("PID of " .. target_package .. " is: " .. (mpid or "not found"))
  return tonumber(mpid)
end

function extractPid(processOutput, packageName)
  local pid
  for line in processOutput:gmatch([[
[^
]+]]) do
    if line:find(packageName) then
      local fields = {}
      for field in line:gmatch("%S+") do
        table.insert(fields, field)
      end
      if 1 < #fields then
        pid = fields[2]
        break
      end
    end
  end
  return pid
end

_ENV["取前台应用的pid"] = function()
  local text = exec("dumpsys activity top | grep -E \"ACTIVITY|pid\" ")
  print(text)
  local last_pid = text:match([[
pid=(%d+)
?$]])
  print("最后一行的PID:", last_pid)
  local lines = {}
  for line in text:gmatch([[
[^
]+]]) do
    table.insert(lines, line)
  end
  local last_line = lines[#lines]
  local pid = last_line:match("pid=(%d+)")
  print("最后一行的PID:", pid)
  return pid
end
_ENV["查找占用端口的进程"] = function(端口)
  local str = exec("netstat -tulnp | grep 0:" .. 端口)
  print(str)
  local pid = string.match(str, "LISTEN%s+(%d+)")
  if pid == nil then
    pid = 0
  end
  return tonumber(pid)
end

function normalizeAngle(angle)
  return angle % 360
end

function calculateMinRotation(a, b)
  a = normalizeAngle(a)
  b = normalizeAngle(b)
  local clockwise = (b - a) % 360
  local counterClockwise = (a - b) % 360
  if clockwise <= counterClockwise then
    return clockwise, 1
  else
    return counterClockwise, -1
  end
end

_ENV["计算角度差"] = function(a, b)
  local minRotation, direction = calculateMinRotation(a, b)
  return minRotation
end
_ENV["计算距离"] = function(x_, y_)
  local x, y, z, jd = getxyz(libil2cppbase)
  return math.sqrt((x - x_) * (x - x_) + (y - y_) * (y - y_))
end

function getIntegerAndFirstDecimal(number)
  local integer, fractional = math.modf(number)
  local firstDecimal = math.floor(fractional * 1000)
  return integer + 0.001 * firstDecimal
end

function getCirclePoint(centerX, centerY, radius, angle)
  local radians = math.rad(angle)
  local x = centerX + radius * math.cos(radians)
  local y = centerY + radius * math.sin(radians)
  return {x, y}
end

_ENV["起点到终点的角度"] = function(x1, y1, x2, y2)
  local dx = x2 - x1
  local dy = y2 - y1
  local angleRadians = math.atan2(dy, dx)
  local angleDegrees = angleRadians * (180 / math.pi)
  if angleDegrees < 0 then
    angleDegrees = angleDegrees + 360
  end
  angleDegrees = (angleDegrees + 90) % 360
  return angleDegrees
end
_ENV["起点到终点的距离"] = function(x, y, x_, y_)
  return math.sqrt((x - x_) * (x - x_) + (y - y_) * (y - y_))
end
_ENV["起点到终点的距离3d"] = function(x, y, z, x_, y_, z_)
  return math.sqrt((x - x_) * (x - x_) + (y - y_) * (y - y_) + (z - z_) * (z - z_))
end
local w = {}
local show_x, show_y, default_show_time = nil, nil, 500
local tap_limits_x, tap_limits_y, tap_def_delay_time, tap_def_time_limits = 2, 2, 300, 50
local def_wait_time = 1000
local x, y
local tap_type = 0
local keepstate = false
local 上次x, 上次y, 重复点击次数 = 0, 0, 0
local 上次点击 = -99999
local 最快点击速度 = 300
local 识别异常用时 = 9999990
local 状态 = "空闲"
_ENV["运行记录_设置最大数量"] = function(最大数量)
  _ENV["运行记录"]["最大数量"] = 最大数量
end
_ENV["运行记录_初始化"] = function(最大数量)
  _ENV["运行记录"] = {}
  _ENV["运行记录"]["最大数量"] = 最大数量
  _ENV["运行记录"]["索引"] = 1
  _ENV["运行记录"]["点击记录"] = {}
  _ENV["运行记录"]["时间记录"] = {}
end
_ENV["运行记录_增加"] = function(value1, value2)
  local iRet, sRet = pcall(function()
    local yxjl = _ENV["运行记录"]["点击记录"]
    local sjjl = _ENV["运行记录"]["时间记录"]
    local xb = _ENV["运行记录"]["索引"]
    if yxjl == nil then
      return
    end
    yxjl[xb] = value1
    sjjl[xb] = value2
    xb = xb + 1
    if xb > _ENV["运行记录"]["最大数量"] then
      xb = 1
    end
    _ENV["运行记录"]["点击记录"] = yxjl
    _ENV["运行记录"]["时间记录"] = sjjl
    _ENV["运行记录"]["索引"] = xb
  end)
end
_ENV["取重复点击次数"] = function()
  return 重复点击次数
end
_ENV["取上次点击时间"] = function()
  return 上次点击
end
_ENV["取上次点击已过时间"] = function()
  return tickCount() - 上次点击
end

function delay(time)
  状态 = "空闲"
  sleep(time)
end

function w.getKeepState()
  return keepstate
end

w["截图"] = function()
  keepstate = true
  keepCapture()
end
_ENV["释放"] = function()
  keepstate = false
  releaseCapture()
end
w["释放"] = _ENV["释放"]

function w.setDefaultShowTime(args)
  default_show_time = args
end

function w.setShowXY(x, y)
  show_x, show_y = x, y
end

function w.setTapXYLimits(x, y)
  tap_limits_x, tap_limits_y = x, y
end

function w.setTapDefTimeLimits(args)
  tap_def_time_limits = args
end

function w.setTapDefDelayTime(args)
  tap_def_delay_time = args
end

function GetKeepState()
  return false
end

local function split(s, p)
  local rt = {}
  string.gsub(s, "[^" .. p .. "]+", function(w)
    table.insert(rt, w)
  end)
  return rt
end

function w.waitex(毫秒数, ...)
  local t = tickCount()
  local arg = {
    ...
  }
  while true do
    print("等待..")
    for i = 1, #arg do
      if arg[i]() then
        return true
      end
    end
    if 毫秒数 <= tickCount() - t then
      return false
    end
    sleep(100)
  end
end

function w.wait(毫秒数, 界面)
  local t = tickCount()
  while true do
    print("等待..")
    if 界面() then
      return true
    end
    if 毫秒数 <= tickCount() - t then
      return false
    end
    sleep(100)
  end
end

function tapex(...)
  local arg = {
    ...
  }
  local msg, intx1, inty1, intx2, inty2, delayTime, xx, yy
  local isStringXY = false
  if #arg == 0 then
    return false
  end
  if type(arg[1]) == "string" and (string.find(arg[1], ",") == nil or string.find(arg[1], "@") ~= nil) then
    msg = arg[1]
    table.remove(arg, 1)
  end
  if type(arg[1]) == "string" and string.find(arg[1], ",") ~= nil then
    isStringXY = true
    for key, value in pairs(split(arg[1], ",")) do
      if tonumber(key) == 1 then
        intx1 = tonumber(value)
      end
      if tonumber(key) == 2 then
        inty1 = tonumber(value)
      end
      if tonumber(key) == 3 then
        intx2 = tonumber(value)
      end
      if tonumber(key) == 4 then
        inty2 = tonumber(value)
      end
    end
  end
  if isStringXY then
    if intx2 ~= nil and inty2 ~= nil then
      xx, yy = math.random(intx1, intx2), math.random(inty1, inty2)
    elseif intx1 ~= nil and inty1 ~= nil then
      xx, yy = math.random(intx1 - tap_limits_x, intx1 + tap_limits_x), math.random(inty1 - tap_limits_y, inty1 + tap_limits_y)
    end
    if arg[2] ~= nil then
      delayTime = tonumber(arg[2])
    end
  elseif #arg == 2 then
    intx1 = arg[1]
    inty1 = arg[2]
    xx, yy = math.random(intx1 - tap_limits_x, intx1 + tap_limits_x), math.random(inty1 - tap_limits_y, inty1 + tap_limits_y)
  elseif #arg == 3 then
    intx1 = arg[1]
    inty1 = arg[2]
    delayTime = arg[3]
    xx, yy = math.random(intx1 - tap_limits_x, intx1 + tap_limits_x), math.random(inty1 - tap_limits_y, inty1 + tap_limits_y)
  elseif #arg == 4 then
    intx1 = arg[1]
    inty1 = arg[2]
    intx2 = arg[3]
    inty2 = arg[4]
    xx, yy = math.random(intx1, intx2), math.random(inty1, inty2)
  elseif #arg == 5 then
    intx1 = arg[1]
    inty1 = arg[2]
    intx2 = arg[3]
    inty2 = arg[4]
    delayTime = arg[5]
    xx, yy = math.random(intx1, intx2), math.random(inty1, inty2)
    delayTime = arg[5]
  end
  if xx == nil or yy == nil then
    return false
  end
  if msg ~= nil then
    scxx(msg, true, xx, yy)
  end
  if msg ~= nil and msg ~= "" then
    _ENV["运行记录_增加"](_ENV["取msg主要信息"](msg), os.time())
  end
  if tickCount() - 上次点击 >= 最快点击速度 then
    _ENV["释放"]()
    上次点击 = tickCount()
    if w.twoPointToDistance(上次x, 上次y, xx, yy) < 360 then
      重复点击次数 = 重复点击次数 + 1
    else
      重复点击次数 = 0
      上次x, 上次y = xx, yy
    end
    tap(xx, yy)
    tap_def_delay_time = math.random(500, 800)
    delayTime = delayTime or tap_def_delay_time
    delayTime = math.random(delayTime - tap_def_time_limits, delayTime + tap_def_time_limits)
    sleep(delayTime)
    return true
  else
    print("点击太快了")
    return false
  end
end

w.tap = tapex
_ENV["取msg主要信息"] = function(str)
  local index = utf8.inStr(1, str, "[")
  if 0 < index then
    return utf8.left(str, index - 1)
  else
    return str
  end
end
local 上次显示运行情况 = tickCount()

function find(...)
  local t = tickCount()
  if (...) == nil then
    return
  end
  local args = {
    ...
  }
  local msg, colorArgs
  local intx, inty = -1, -1
  if #args == 1 then
    msg, colorArgs = "", (...)
  elseif #args == 2 then
    msg, colorArgs = ...
  end
  if type(colorArgs[1]) ~= "table" then
    colorArgs = {colorArgs}
  end
  local n = 0
  for i in ipairs(colorArgs) do
    n = n + 1
    local colorArg = colorArgs[i]
    intx, inty = -1, -1
    if #colorArg == 1 then
      if cmpColorEx(colorArg[1], 0.9) == 1 then
        intx, inty = string.match(colorArg[1], "(%d+)|(%d+)")
      end
    elseif #colorArg == 2 and type(colorArg[2]) == "number" then
      if cmpColorEx(colorArg[1], colorArg[2]) == 1 then
        intx, inty = string.match(colorArg[1], "(%d+)|(%d+)")
      end
    elseif #colorArg == 5 and type(colorArg[1]) == "number" and type(colorArg[5]) == "table" then
      rtn, xyz_tb = tomocr(table.unpack(colorArg))
      if rtn then
        intx = math.floor(xyz_tb.x)
        inty = math.floor(xyz_tb.y)
      end
    elseif #colorArg == 8 and 0 < colorArg[7] and 1 > colorArg[7] and string.find(colorArg[5], "|", 1) == nil and string.find(TableToStr(colorArg, ""), ".png", 1) == nil and string.find(TableToStr(colorArg, ""), ".bmp", 1) == nil then
      useDict(colorArg[8])
      rtn, intx, inty = findStr(colorArg[1], colorArg[2], colorArg[3], colorArg[4], colorArg[5], colorArg[6], colorArg[7])
    elseif #colorArg == 8 and (string.find(TableToStr(colorArg, ""), ".png", 1) ~= nil or string.find(TableToStr(colorArg, ""), ".bmp", 1) ~= nil) then
      rtn, intx, inty = findPic(colorArg[1], colorArg[2], colorArg[3], colorArg[4], colorArg[5], colorArg[6], colorArg[7], colorArg[8])
    elseif #colorArg == 6 then
      rtn, intx, inty = findImage(colorArg[1], colorArg[2], colorArg[3], colorArg[4], colorArg[5], colorArg[6])
    elseif #colorArg == 8 then
      intx, inty = findMultiColor(colorArg[1], colorArg[2], colorArg[3], colorArg[4], colorArg[5], colorArg[6], colorArg[7], colorArg[8])
      rtn = 0 < intx
    end
    intx = tonumber(intx)
    inty = tonumber(inty)
    if -1 < intx then
      if msg ~= nil then
        scxx(msg, true, intx, inty, n)
      end
      break
    end
    sleep(1)
  end
  local 识别用时 = tickCount() - t
  if 识别用时 > 识别异常用时 then
    print("识别用时[" .. 识别用时 .. "]", utf8.left(TableToStr(args, ""), 80))
  end
  intx = tonumber(intx)
  inty = tonumber(inty)
  return 0 < intx, intx, inty, n, rtn
end

w.find = find

function findc(...)
  if (...) == nil then
    return
  end
  local args = {
    ...
  }
  local msg, colorArgs, pos
  msg = ""
  if #args == 1 then
    colorArgs = args[1]
  elseif #args == 2 then
    if type(args[1]) == "string" then
      msg, colorArgs = args[1], args[2]
    else
      colorArgs, pos = args[1], args[2]
    end
  elseif #args == 3 then
    msg, colorArgs, pos = ...
  end
  if type(colorArgs[1]) ~= "table" then
    colorArgs = {colorArgs}
  end
  local rtn, intx, inty, n = find(colorArgs)
  intx = tonumber(intx)
  inty = tonumber(inty)
  if -1 < intx then
    msg = msg .. "[" .. n .. "]"
    if pos == nil then
      tapex(msg, tonumber(intx), tonumber(inty), nil)
    elseif type(pos[1]) == "string" then
      if #pos == 3 then
        tapex(msg, tonumber(intx) + tonumber(pos[2]), tonumber(inty) + tonumber(pos[3]), nil)
      elseif #pos == 4 then
        tapex(msg, tonumber(intx) + tonumber(pos[2]), tonumber(inty) + tonumber(pos[3]), tonumber(pos[4]))
      end
    elseif #pos == 3 then
      tapex(msg, tonumber(pos[1]), tonumber(pos[2]), tonumber(pos[3]), nil)
    else
      tapex(msg, tonumber(pos[1]), tonumber(pos[2]), tonumber(pos[3]), tonumber(pos[4]), tonumber(pos[5]), nil)
    end
  end
  return 0 < intx, intx, inty
end

w.findc = findc

function findcws2(msg, colorArg1, colorArg2, maxTime, waitTime, pos)
  local startTime = os.time()
  local result = false
  _ENV["释放"]()
  while maxTime > os.time() - startTime and not result do
    if msg ~= nil then
      print("等待出现 " .. msg)
    end
    if type(colorArg2) == "function" then
      result = colorArg2()
      if result then
        if msg ~= nil then
          print("出现 " .. msg)
        end
        result = true
        break
      end
    else
      result, intx, inty = find(colorArg2)
      if result then
        if msg ~= nil then
          print("出现 " .. msg .. " " .. intx .. "," .. inty)
        end
        result = true
        break
      end
    end
    if type(colorArg1) == "function" then
      if colorArg1() and pos ~= nil then
        tap(pos)
      end
    elseif pos ~= nil then
      findc(colorArg1, pos)
    else
      findc(colorArg1)
    end
    sleep(waitTime)
  end
  if maxTime <= os.time() - startTime then
    print("超时")
  end
  return result
end

w.findcws2 = findcws2

function findcwh2(msg, colorArg1, colorArg2, maxTime, waitTime, pos)
  local startTime = os.time()
  local result = false
  _ENV["释放"]()
  while maxTime > os.time() - startTime and result == false do
    if msg ~= nil then
      print("等待消失 " .. msg)
    end
    if type(colorArg2) == "function" then
      result = colorArg2()
      if not result then
        if msg ~= nil then
          print("消失 " .. msg)
        end
        result = true
        break
      end
    else
      result, intx, inty = find(colorArg2)
      if not result then
        if msg ~= nil then
          print("消失 " .. msg .. " " .. intx .. "," .. inty)
        end
        result = true
        break
      end
    end
    if type(colorArg1) == "function" then
      colorArg1()
    elseif pos ~= nil then
      findc(colorArg1, pos)
    else
      findc(colorArg1)
    end
    sleep(waitTime)
  end
  if maxTime <= os.time() - startTime then
    print("超时")
  end
  return result
end

w.findcwh2 = findcwh2

function init()
  print("★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★")
  print("★[作者]   无名之辈QQ1002062607")
  print("★[版本]   v1.0")
  math.randomseed(os.time())
  local x, y = getDisplaySize()
  local dpi = getDisplayDpi()
  print("★[分辨率] " .. x .. "x" .. y)
  print("★[dpi]    " .. dpi)
  print("★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★")
  print("")
  print("")
  print("[启动时间] " .. os.date("%Y年%m月%d日 %H时%M分%S秒", os.time()))
end

w.init = init

function scxx(msg, isClick, intx, inty, n)
  pcall(function()
    local str = ""
    local str_msg = ""
    if msg ~= nil then
      if string.find(msg, "@@", 1) ~= nil then
        msg = string.sub(msg, 3, string.len(msg))
        str_msg = msg
        if n ~= nil then
          str_msg = "[" .. msg .. "][" .. n .. "]"
        else
          str_msg = "[" .. msg .. "]"
        end
        if isClick then
          str_msg = str_msg .. "■" .. intx .. "," .. inty
        else
          str_msg = str_msg .. "□" .. intx .. "," .. inty
        end
        str = msg
        if n ~= nil then
          str = str .. "[" .. n .. "]"
          str = str .. "[" .. intx .. "," .. inty .. "]"
        else
          str = str .. "[" .. intx .. "," .. inty .. "]"
        end
        toast(str_msg, show_x, show_y)
        print(str)
      end
      if string.find(msg, "@", 1) ~= nil then
        msg = string.sub(msg, 2, string.len(msg))
        str = msg
        if n ~= nil then
          str = str .. "[" .. n .. "]"
          str = str .. "[" .. intx .. "," .. inty .. "]"
        else
          str = str .. "[" .. intx .. "," .. inty .. "]"
        end
        print(str)
      end
    end
  end)
end

function w.Filter(arr, substr, tpe)
  local iRet, sRet = pcall(function()
    local tarr = {}
    for k, v in ipairs(arr) do
      if tpe then
        if string.find(v, substr) ~= nil then
          table.insert(tarr, v)
        end
      elseif string.find(v, substr) == nil then
        table.insert(tarr, v)
      end
    end
    return tarr
  end)
  if iRet == true then
    return sRet
  else
    print(sRet)
    return arr
  end
end

function w.Sort(arr, comp)
  local iRet, sRet = pcall(function()
    local t = {}
    local j = 1
    tarr = CopyTable(arr)
    table.sort(tarr)
    if comp then
      for i = #tarr, 1, -1 do
        t[j] = tarr[i]
        j = j + 1
      end
      return t
    else
      return tarr
    end
  end)
  if iRet == true then
    return sRet
  else
    print(sRet)
    return arr
  end
end

function w.Remove(arr, ipos)
  local iRet, sRet = pcall(function()
    tarr = CopyTable(arr)
    table.remove(tarr, ipos + 1)
    return tarr
  end)
  if iRet == true then
    return sRet
  else
    print(sRet)
    return arr
  end
end

function w.insert(arr, ipos, value)
  local iRet, sRet = pcall(function()
    tarr = CopyTable(arr)
    table.insert(tarr, ipos + 1, value)
    return tarr
  end)
  if iRet == true then
    return sRet
  else
    print(sRet)
    return arr
  end
end

function w.RemoveDup(tbl)
  local iRet, sRet = pcall(function()
    local a = {}
    local b = {}
    for _, v in ipairs(tbl) do
      a[v] = 0
    end
    for k, v in pairs(a) do
      table.insert(b, k)
    end
    return b
  end)
  if iRet == true then
    return sRet
  else
    print(sRet)
    return ""
  end
end

function w.ChangePos(arr, a, b)
  local iRet, sRet = pcall(function()
    local tmptbl = arr
    local tmpval = arr[a + 1]
    table.remove(tmptbl, a + 1)
    table.insert(tmptbl, b + 1, tmpval)
    return tmptbl
  end)
  if iRet == true then
    return sRet
  else
    print(sRet)
    return ""
  end
end

function filter_spec_chars(s)
  local ss = {}
  local k = 1
  while not (k > #s) do
    local c = string.byte(s, k)
    if not c then
      break
    end
    if c < 192 then
      if not ((not (48 <= c) or not (c <= 57)) and (not (65 <= c) or not (c <= 90)) and 97 <= c) or c <= 122 then
      end
      k = k + 1
    elseif c < 224 then
      k = k + 2
    elseif c < 240 then
      if 228 <= c and c <= 233 then
        local c1 = string.byte(s, k + 1)
        local c2 = string.byte(s, k + 2)
        if c1 and c2 then
          local a1, a2, a3, a4 = 128, 191, 128, 191
          if c == 228 then
            a1 = 184
          elseif c == 233 then
            a2, a4 = 190, c1 ~= 190 and 191 or 165
          end
          if c1 >= a1 and c1 <= a2 and c2 >= a3 and c2 <= a4 then
            table.insert(ss, string.char(c, c1, c2))
          end
        end
      end
      k = k + 3
    elseif c < 248 then
      k = k + 4
    elseif c < 252 then
      k = k + 5
    elseif c < 254 then
      k = k + 6
    end
  end
  return table.concat(ss)
end

w.filter_spec_chars = filter_spec_chars

function TableToStr(t, chr)
  if t == nil then
    return ""
  end
  local retstr = ""
  local i = 1
  for key, value in pairs(t) do
    local signal = chr
    if i == 1 then
      signal = ""
    end
    if signal == nil then
      signal = ","
    end
    if key == i then
      retstr = retstr .. signal .. ToStringEx(value)
    elseif type(key) == "number" or type(key) == "string" then
      retstr = retstr .. signal .. "[" .. ToStringEx(key) .. "]=" .. ToStringEx(value)
    elseif type(key) == "userdata" then
      retstr = retstr .. signal .. "*s" .. TableToStr(getmetatable(key)) .. "*e" .. "=" .. ToStringEx(value)
    else
      retstr = retstr .. signal .. key .. "=" .. ToStringEx(value)
    end
    i = i + 1
  end
  return retstr
end

function ToStringEx(value)
  if type(value) == "table" then
    return TableToStr(value)
  elseif type(value) == "string" then
    return "'" .. value .. "'"
  else
    return tostring(value)
  end
end

function StrToTable(str)
  if str == nil or type(str) ~= "string" then
    return
  end
  return loadstring("return " .. str)()
end

function replace(str, rgx, str2)
  if str == nil then
    return ""
  end
  local temp = str
  return temp:gsub(rgx, str2)
end

w.replace = replace

function w.timeStampToData(tempTimeStamp)
  local result = ""
  local temp = -1
  tempTimeStamp = tonumber(tempTimeStamp)
  if tempTimeStamp == nil then
    error("传递进来的时间戳不合法")
  end
  temp = string.len(tostring(tempTimeStamp))
  if temp == 10 then
    result = os.date("%Y-%m-%d %H:%M:%S", tempTimeStamp)
  elseif temp == 13 then
    result = os.date("%Y-%m-%d %H:%M:%S", tempTimeStamp / 1000)
  else
    error("传进的时间戳长度不合法")
  end
  return result
end

function w.dataToTimeStamp(dataStr)
  local result = -1
  local tempTable = {}
  if dataStr == nil then
    error("传递进来的日期时间参数不合法")
  elseif type(dataStr) == "string" then
    for v in string.gmatch(dataStr, "%d+") do
      tempTable[#tempTable + 1] = v
    end
  elseif type(dataStr) == "table" then
    tempTable = dataStr
  else
    error("传递进来的日期时间参数不合法")
  end
  result = os.time({
    day = tonumber(tempTable[3]),
    month = tonumber(tempTable[2]),
    year = tonumber(tempTable[1]),
    hour = tonumber(tempTable[4]),
    min = tonumber(tempTable[5]),
    sec = tonumber(tempTable[6])
  })
  return result
end

function w.buqi(str, len, filledChar)
  local function toleft(str, len, filledChar)
    str = tostring(str)
    
    filledChar = filledChar or " "
    local nRestLen = len - #str
    local nNeedCharNum = math.floor(nRestLen / #filledChar)
    str = str .. string.rep(filledChar, nNeedCharNum)
    return str
  end
  
  if type(str) == "number" or type(str) == "string" then
    if not string.find(tostring(str), "\n") then
      return toleft(str, len, filledChar)
    else
      str = string.split(str, "\n")
    end
  end
  if type(str) == "table" then
    local tmpStr = toleft(str[1], len, filledChar)
    for i = 2, #str do
      tmpStr = tmpStr .. "\n" .. toleft(str[i], len, filledChar)
    end
    return tmpStr
  end
  return ""
end

function w.twoPointToDistance(x1, y1, x2, y2)
  return math.sqrt((y2 - y1) * (y2 - y1) + (x2 - x1) * (x2 - x1))
end

function execute(cmd)
  local iRet, sRet = pcall(function()
    local tFile = TempFile()
    if tFile == "" then
      return ""
    else
      cmd = cmd .. " > " .. tFile
      local ret = os.execute(cmd)
      return TrimEx(ReadFile(tFile, true), "\r\n ")
    end
  end)
  if iRet == true then
    return sRet
  else
    print(sRet)
    return ""
  end
end

w.execute = execute

function w.GetTopActivity(tpe)
  local ret = execute("dumpsys activity top ")
  if tpe and tpe == 1 then
    return string.match(ret, "ACTIVITY ([^/]+)")
  elseif tpe and tpe == 2 then
    return string.match(ret, "ACTIVITY .-/([^ ]+)")
  else
    return string.match(ret, "ACTIVITY ([^ ]+)")
  end
end

function GetAppList(tpe)
  local iRet, sRet = pcall(function()
    local sRet
    local appList = {}
    if tpe == 0 then
      sRet = execute("pm list packages -3")
    elseif tpe == 1 then
      sRet = execute("pm list packages -s")
    end
    for k in sRet:gmatch("package:([^\r\n]+)") do
      table.insert(appList, TrimEx(k, " "))
    end
    return appList
  end)
  if iRet == true then
    return sRet
  else
    print(sRet)
    return ""
  end
end

w.GetAppList = GetAppList

local function split(s, p)
  local rt = {}
  string.gsub(s, "[^" .. p .. "]+", function(w)
    table.insert(rt, w)
  end)
  return rt
end

w["ftp下载"] = function(url, filepath)
  print("下载", url, filepath)
  return exec(string.format("curl -o %s %s", filepath, url))
end
w["ftp上传"] = function(url, filepath)
  print("上传", url, filepath)
  return exec(string.format("curl -T %s %s", filepath, url))
end
_ENV["修改文件权限"] = function(文件路径, 权限)
  print("修改文件权限", exec("chmod " .. 权限 .. " " .. 文件路径))
end
_ENV["修改文件日期"] = function(文件路径, 年, 月, 日, 时, 分)
  print("修改文件日期", exec("touch -m -t " .. 年 .. 月 .. 日 .. 时 .. 分 .. " " .. 文件路径))
end
w["tar打包"] = function(压缩到的路径, 目录或文件路径)
  exec("tar -cvf " .. 压缩到的路径 .. " " .. 目录或文件路径)
end
w["tar压缩"] = function(压缩到的路径, 目录或文件路径)
  exec("tar -zcvf " .. 压缩到的路径 .. " " .. 目录或文件路径)
end
w["tar解压"] = function(压缩包路径, 解压到的文件夹)
  exec("tar -xvf " .. 压缩包路径 .. " -C " .. 解压到的文件夹)
end

function w.init_phone(dpi, left, top, right, bottom)
  exec("wm density " .. dpi)
  exec("wm overscan " .. string.format("%d,%d,%d,%d", left, top, right, bottom))
end

_ENV["取所有输入法"] = function()
  local retstr = exec("ime list -s")
  local retIME = {}
  for v in string.gmatch(retstr, [[
([^
]+)]]) do
    table.insert(retIME, v)
  end
  return retIME
end
w["匹配所有输入法"] = function()
  local localime = {
    "baidu.input_mi",
    "FlyIME",
    "google",
    "xinshuru",
    "gokeyboard",
    "smartinputv5",
    "qqpinyin",
    "baidu",
    "komoxo",
    "mobileanjian",
    "sogou"
  }
  local sysime = _ENV["取所有输入法"]()
  for i = 1, #sysime do
    print("i:", i, sysime[i])
  end
end
w["切换输入法"] = function(输入法)
  local 输入法类型 = {
    "百度",
    "com.baidu.input_miv6/.ImeService",
    "搜狗",
    "com.sohu.inputmethod.sogou.xiaomi/.SogouIM"
  }
  exec("ime set " .. 输入法类型[输入法])
end
w["保证app在前台"] = function(包名)
  if w.GetTopActivity(1) ~= 包名 then
    runApp(包名)
    sleep(2000)
  end
end
_ENV["找到点击"] = w.findc
_ENV["找到"] = w.find
_ENV["点击等待找到"] = w.findcws2
_ENV["点击等待消失"] = w.findcwh2
_ENV["点击"] = w.tap
_ENV["截图"] = w["截图"]
_ENV["释放"] = w["释放"]
local stringSimilarity = {}

function stringSimilarity.levenshtein(str1, str2)
  local len1, len2 = #str1, #str2
  local matrix = {}
  for i = 0, len1 do
    matrix[i] = {
      [0] = i
    }
  end
  for j = 0, len2 do
    matrix[0][j] = j
  end
  for i = 1, len1 do
    for j = 1, len2 do
      local cost = str1:sub(i, i) == str2:sub(j, j) and 0 or 1
      matrix[i][j] = math.min(matrix[i - 1][j] + 1, matrix[i][j - 1] + 1, matrix[i - 1][j - 1] + cost)
    end
  end
  return matrix[len1][len2]
end

function stringSimilarity.levenshteinNormalized(str1, str2)
  str1 = str1:gsub(" ", "")
  str2 = str2:gsub(" ", "")
  local distance = stringSimilarity.levenshtein(str1, str2)
  local maxLen = math.max(#str1, #str2)
  return maxLen == 0 and 1 or 1 - distance / maxLen
end

function stringSimilarity.jaroWinkler(str1, str2, p)
  p = p or 0.1
  local len1, len2 = #str1, #str2
  if len1 == 0 or len2 == 0 then
    return 0
  end
  local matchDistance = math.floor(math.max(len1, len2) / 2) - 1
  local str1Matches = {}
  local str2Matches = {}
  local matches = 0
  for i = 1, len1 do
    local start = math.max(1, i - matchDistance)
    local finish = math.min(len2, i + matchDistance)
    for j = start, finish do
      if not str2Matches[j] and str1:sub(i, i) == str2:sub(j, j) then
        str1Matches[i] = true
        str2Matches[j] = true
        matches = matches + 1
        break
      end
    end
  end
  if matches == 0 then
    return 0
  end
  local k = 1
  local transpositions = 0
  for i = 1, len1 do
    if str1Matches[i] then
      while not str2Matches[k] do
        k = k + 1
      end
      if str1:sub(i, i) ~= str2:sub(k, k) then
        transpositions = transpositions + 1
      end
      k = k + 1
    end
  end
  local jaro = (matches / len1 + matches / len2 + (matches - transpositions / 2) / matches) / 3
  local prefix = 0
  local prefixLength = math.min(4, len1, len2)
  for i = 1, prefixLength do
    if str1:sub(i, i) == str2:sub(i, i) then
      prefix = prefix + 1
    else
      break
    end
  end
  return jaro + prefix * p * (1 - jaro)
end

function stringSimilarity.cosine(str1, str2, n)
  n = n or 2
  
  local function getNGrams(s)
    local ngrams = {}
    for i = 1, #s - n + 1 do
      local gram = s:sub(i, i + n - 1)
      ngrams[gram] = (ngrams[gram] or 0) + 1
    end
    return ngrams
  end
  
  local ngrams1 = getNGrams(str1)
  local ngrams2 = getNGrams(str2)
  local dotProduct = 0
  for gram, count1 in pairs(ngrams1) do
    local count2 = ngrams2[gram] or 0
    dotProduct = dotProduct + count1 * count2
  end
  
  local function magnitude(ngrams)
    local sum = 0
    for _, count in pairs(ngrams) do
      sum = sum + count * count
    end
    return math.sqrt(sum)
  end
  
  local mag1 = magnitude(ngrams1)
  local mag2 = magnitude(ngrams2)
  if mag1 == 0 or mag2 == 0 then
    return 0
  end
  return dotProduct / (mag1 * mag2)
end

function stringSimilarity.compare(str1, str2)
  return {
    levenshtein = stringSimilarity.levenshtein(str1, str2),
    levenshteinNormalized = stringSimilarity.levenshteinNormalized(str1, str2),
    jaroWinkler = stringSimilarity.jaroWinkler(str1, str2),
    cosine = stringSimilarity.cosine(str1, str2)
  }
end

w.stringSimilarity = stringSimilarity
return w
