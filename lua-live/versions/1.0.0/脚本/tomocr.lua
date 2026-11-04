import("java.io.File")
import("java.lang.*")
import("java.util.Arrays")
import("android.content.Context")
import("android.hardware.Sensor")
import("android.hardware.SensorEvent")
import("android.hardware.SensorEventListener")
import("android.hardware.SensorManager")
import("com.nx.assist.lua.LuaEngine")
extractAssets("封神榜.rc", "/data/local/tmp/", "*.apk")
local loader = LuaEngine.loadApk("/data/local/tmp/【懒人精灵高级版】本地文字识别插件(中英文+繁、日、韩)-2.0.7_2.apk")
local OCR = loader.loadClass("com.tomato.ocr.lr.OCRApi")
local tmo_ocr = OCR.init(LuaEngine.getContext())
print("加载tomocr完成")
local license = "GJQM3QVWLD9ENYQ6RXTC0NOGAMSV0KWX|2PFPZCZ5HDKUQKW4DUL4RIG1"
local flag = tmo_ocr.setLicense(license)
printEx(flag)
tmo_ocr.setRecType("ch-3.0")
tmo_ocr.setDetBoxType("rect")
tmo_ocr.setDetUnclipRatio(1.9)
tmo_ocr.setRecScoreThreshold(0.3)
tmo_ocr.setReturnType("json")
tmo_ocr.setBinaryThresh(0)
tmo_ocr.setRunMode("slow")
print("开始执行")

function tomocrnumber(x1, y1, x2, y2)
  local type = 3
  local bitmap = LuaEngine.snapShot(x1, y1, x2, y2)
  local result = tmo_ocr.ocrBitmap(bitmap, type)
  bitmap.recycle()
  if result ~= "" then
    local json_results = jsonLib.decode(result)
    for i, json_result in ipairs(json_results) do
      local words = json_result.words
      if tonumberEx(words) >= 0 then
        return tonumberEx(words)
      end
    end
  end
  return 0
end

function tomocrtexttable(x1, y1, x2, y2, sim)
  if sim == nil then
    sim = 0.8
  end
  local type = 3
  local bitmap = LuaEngine.snapShot(x1, y1, x2, y2)
  local result = tmo_ocr.ocrBitmap(bitmap, type)
  bitmap.recycle()
  if result ~= "" then
    local json_results = jsonLib.decode(result)
    for i, json_result in ipairs(json_results) do
      local x, y = 0, 0
      for ii = 1, #json_result.location do
        x = x + json_result.location[ii][1]
        y = y + json_result.location[ii][2]
      end
      x = x / #json_result.location + x1
      y = y / #json_result.location + y1
      json_result.x = x
      json_result.y = y
    end
    return json_results
  end
  return {}
end

function tomocrtext(x1, y1, x2, y2, sim)
  if sim == nil then
    sim = 0.8
  end
  local type = 3
  local bitmap = LuaEngine.snapShot(x1, y1, x2, y2)
  local result = tmo_ocr.ocrBitmap(bitmap, type)
  bitmap.recycle()
  if result ~= "" then
    local json_results = jsonLib.decode(result)
    for i, json_result in ipairs(json_results) do
      local x, y = 0, 0
      for ii = 1, #json_result.location do
        x = x + json_result.location[ii][1]
        y = y + json_result.location[ii][2]
      end
      x = x / #json_result.location + x1
      y = y / #json_result.location + y1
      return json_result.words
    end
  end
  return ""
end

function tomocrtextall(x1, y1, x2, y2, sim)
  if sim == nil then
    sim = 0.8
  end
  local type = 3
  local bitmap = LuaEngine.snapShot(x1, y1, x2, y2)
  local result = tmo_ocr.ocrBitmap(bitmap, type)
  bitmap.recycle()
  local rtn = ""
  if result ~= "" then
    local json_results = jsonLib.decode(result)
    for i, json_result in ipairs(json_results) do
      local x, y = 0, 0
      for ii = 1, #json_result.location do
        x = x + json_result.location[ii][1]
        y = y + json_result.location[ii][2]
      end
      x = x / #json_result.location + x1
      y = y / #json_result.location + y1
      rtn = rtn .. json_result.words
    end
  end
  return rtn
end

function tomocr(x1, y1, x2, y2, zi, sim)
  if sim == nil then
    sim = 0.8
  end
  local type = 3
  local bitmap = LuaEngine.snapShot(x1, y1, x2, y2)
  local result = tmo_ocr.ocrBitmap(bitmap, type)
  bitmap.recycle()
  if result ~= "" then
    local json_results = jsonLib.decode(result)
    for i, json_result in ipairs(json_results) do
      local iszi = false
      for ii = 1, #zi do
        if json_result.words:find(zi[ii]) then
          iszi = true
          break
        end
      end
      if iszi and 1 <= #json_result.location and sim <= json_result.score then
        local x, y = 0, 0
        for ii = 1, #json_result.location do
          x = x + json_result.location[ii][1]
          y = y + json_result.location[ii][2]
        end
        x = x / #json_result.location + x1
        y = y / #json_result.location + y1
        return true, {
          x = x,
          y = y,
          words = json_result.words,
          score = json_result.score,
          success = true
        }
      end
    end
  end
  return false, {}
end

function tomocrclick(x1, y1, x2, y2, zi, sim)
  local a, b = tomocr(x1, y1, x2, y2, zi, sim)
  if a then
    tap(b.words, b.x, b.y, 1000)
  end
  return a
end
