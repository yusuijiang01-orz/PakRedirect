local TURING = {}
local FISR_Ver = "1.2.8.20230103"
local ImgData = {}
local IncData = {}
local LibData = {}
local PicData = {}
local BakData0 = {}
local BakData1 = {}
local BakData2 = {}
local BakData3 = {}
local BakData4 = {}
local BakData5 = {}
local BakData6 = {}
local BakData7 = {}
local BakData8 = {}
local BakData9 = {}
local Color_B = 255
local Color_G = 255
local Color_R = 255
local R_Left = 0
local R_Top = 0
local R_Right = 0
local R_Bottom = 0
local R_Model = 0
local StartIn = 1
local AgeLimit = 2023
local sAuth_code = ""
local sAdd_code = ""
local token = ""
local Countdown = 0
local rndNum = 0

local function RandomNow(FixedYear, StartMonth)
  local NowDay, EndDay, RndMonth, RndDay, MoreMonth
  math.randomseed(os.time())
  RndMonth = math.random(6, 9)
  math.randomseed(os.time())
  RndDay = math.random(4, 17)
  EndDay = RndMonth * 30 + RndDay
  local Year = os.date("%Y")
  local Month = os.date("%m")
  local Flag = false
  if FixedYear <= tonumber(Year) then
    MoreMonth = (tonumber(Year) - FixedYear) * 12
    if MoreMonth + tonumber(Month) - StartMonth >= 0 then
      NowDay = (MoreMonth + tonumber(Month) - StartMonth) * 30 + RndDay
      if EndDay >= NowDay then
        Flag = true
      end
    end
  end
  return Flag
end

local function GUID()
  local seed = {
    "0",
    "1",
    "2",
    "3",
    "4",
    "5",
    "6",
    "7",
    "8",
    "9",
    "a",
    "b",
    "c",
    "d",
    "e",
    "f"
  }
  local tb = {}
  for i = 1, 32 do
    table.insert(tb, seed[math.random(1, 16)])
  end
  local sid = table.concat(tb)
  return string.format("%s-%s-%s-%s-%s", string.sub(sid, 1, 8), string.sub(sid, 9, 12), string.sub(sid, 13, 16), string.sub(sid, 17, 20), string.sub(sid, 21, 32))
end

local function ReadGUID(Text, FileName)
  local f = io.open(FileName, "r")
  if f == null then
    f = io.open(FileName, "w")
    f:write(Text)
    f:close()
    return Text
  end
  local ret = f:read("*all")
  f:close()
  return ret
end

local function Post_Html(url, dataStr)
  local curl = require("luacurl")
  local result = {}
  local sData = ""
  local c = curl.new()
  c:setopt(curl.OPT_SSL_VERIFYHOST, 0)
  c:setopt(curl.OPT_SSL_VERIFYPEER, false)
  c:setopt(curl.OPT_TIMEOUT, 1)
  c:setopt(curl.OPT_URL, url)
  c:setopt(curl.OPT_WRITEDATA, result)
  c:setopt(curl.OPT_POSTFIELDS, dataStr)
  c:setopt(curl.OPT_WRITEFUNCTION, function(tab, buffer)
    table.insert(tab, buffer)
    return #buffer
  end)
  c:perform()
  c:close()
  c = null
  sData = table.concat(result)
  return sData
end

local function Get_Html(url)
  local curl = require("luacurl")
  local result = {}
  local sData = ""
  local c = curl.new()
  c:setopt(curl.OPT_SSL_VERIFYHOST, 0)
  c:setopt(curl.OPT_SSL_VERIFYPEER, false)
  c:setopt(curl.OPT_TIMEOUT, 1)
  c:setopt(curl.OPT_URL, url)
  c:setopt(curl.OPT_WRITEDATA, result)
  c:setopt(curl.OPT_WRITEFUNCTION, function(tab, buffer)
    table.insert(tab, buffer)
    return #buffer
  end)
  c:perform()
  c:close()
  c = null
  sData = table.concat(result)
  return sData
end

local function GetTrueRandom(min, max)
  local strTime = tostring(os.time())
  local strRev = string.reverse(strTime)
  local strRandomTime = string.sub(strRev, 1, 6)
  math.randomseed(strRandomTime)
  return math.random(min, max)
end

local function TONGJI()
  local FileName = getSdPath() .. "/TUR.ING"
  local Year = os.date("%Y")
  local Month = os.date("%m")
  local Day = os.date("%d")
  local Text = Year .. "-" .. Month .. "-" .. Day
  local ret
  local f = io.open(FileName, "r")
  if f == nil then
    f = io.open(FileName, "w")
    f:write(Text)
  else
    ret = f:read("*all")
  end
  f:close()
  if Text ~= ret then
    f = io.open(FileName, "w")
    f:write(Text)
    f:close()
    local SID = "92bd5ab0a88fa1312efd48ecdc38edde"
    local FanHuiZhi = httpGet("https://hm.baidu.com/hm.js?" .. SID)
    local ep1 = GetTrueRandom(7000000, 9999999)
    local ep2 = GetTrueRandom(10000, 400000)
    local SJS = GetTrueRandom(1000000000, 7000000000)
    local sn1 = GetTrueRandom(10000, 99999)
    FanHuiZhi = httpGet("https://hm.baidu.com/hm.gif?hca=DF9FD97EEDB3B238&cc=1&ck=1&cl=24-bit&ds=1080x1920&vl=909&ep=" .. ep1 .. "%2C" .. ep2 .. "&et=3&ja=0&ln=zh-cn&lo=0&lt=" .. os.time() .. "&rnd=" .. SJS .. "&si=" .. SID .. "&v=1.2.97&lv=2&sn=" .. sn1 .. "&r=0&ww=1880&u=http://sdk.turing.video/index.html?flag=mobile_LR")
    SJS = GetTrueRandom(1000000000, 7000000000)
    sn1 = GetTrueRandom(10000, 99999)
    FanHuiZhi = httpGet("https://hm.baidu.com/hm.gif?cc=1&ck=1&cl=24-bit&ds=1080x1920&vl=467&et=0&ja=0&ln=zh-cn&lo=0&lt=" .. os.time() .. "&rnd=" .. SJS .. "&si=" .. SID .. "&v=1.2.97&lv=2&sn=" .. sn1 .. "&r=0&ww=1880&ct=!!&u=http://sdk.turing.video/index.html?flag=mobile_LR")
  end
end

TURING["关于"] = function()
  return TURING.About()
end

function TURING.About()
  local TmpText = {}
  TmpText[1] = "AI（人工智能）图像识别《我的眼睛--图灵识别》全彩图书简介："
  TmpText[2] = "揭开面纱，不再神秘；"
  TmpText[3] = "图文并茂，深入原理；"
  TmpText[4] = "需要有一点的计算机编程基础，至少熟悉任意一门编程语言，适用于AI（人工智能）机器人、图形图像、颜色和文字识别领域的相关行业作为学习参考。"
  TmpText[5] = ""
  TmpText[6] = "●专注图色文字识别"
  TmpText[7] = "●全彩共有十三章节"
  TmpText[8] = "●图文并茂原理易懂"
  TmpText[9] = "●多门编程语言源码"
  TmpText[10] = "VB6（含源码）、按键精灵（含手机版）、懒人精灵、C/C++、Python3、UiBot、易语言、TC等"
  TmpText[11] = "●三步识别入门基础"
  TmpText[12] = "颜色识别、图片识别、形状识别"
  TmpText[13] = "●带十三套图像滤镜"
  TmpText[14] = "彩色处理滤镜：二值化、灰度、色调分离"
  TmpText[15] = "黑白处理滤镜：清除杂点、去掉直线、获取轮廓、提取色块、倾斜矫正、旋转纠正、颠倒颜色、膨胀腐蚀"
  TmpText[16] = "通用处理滤镜：等比缩放、缩放归一化"
  TmpText[17] = "●五种字符切割技术"
  TmpText[18] = "固定位置、随机方位、连通区域、范围投影、颜色分层"
  TmpText[19] = "●十个实战演练范例"
  TmpText[20] = "文字类识别：标准数字、重叠数字、波浪数字、错位文字、中文选题"
  TmpText[21] = "图像类识别：与众不同、面朝方向、清晰图像"
  TmpText[22] = "图形类识别：箭头朝向、寻找圆心"
  TmpText[23] = "●三篇识别分析理论"
  TmpText[24] = "扭曲变形、旋转方向、滑块拼图"
  TONGJI()
  return table.concat(TmpText, "\r\n")
end

local function TURING_LICENSE(auth_code, add_code, jqm_token)
  local url = "http://license.turing.video/api/user/authActivate"
  local dataStr = "auth_code=" .. auth_code .. "&add_code=" .. add_code .. "&machine_code=" .. jqm_token
  local jsonData, code = httpPost(url, dataStr)
  if jsonData ~= nil and jsonData ~= "" then
    local tableData = jsonLib.decode(jsonData)
    return tonumber(tableData.code)
  else
    return 0
  end
end

TURING["授权"] = function(auth_code, add_code)
  return TURING.LICENSE(auth_code, add_code)
end

function TURING.LICENSE(auth_code, add_code)
  if auth_code == nil then
    auth_code = ""
  end
  if add_code == nil then
    add_code = ""
  end
  local result = 0
  token = ReadGUID(GUID(), getSdPath() .. "/ocr.tr")
  rndNum = GetTrueRandom(1000, 10000)
  if RandomNow(AgeLimit, StartIn) == false then
    local flag = 0
    if auth_code ~= "" then
      sleep(GetTrueRandom(250, 500))
      flag = TURING_LICENSE(auth_code, add_code, token)
      Countdown = os.time()
    end
    if flag == 1 then
      sAuth_code = auth_code
      sAdd_code = add_code
      result = 1
    else
      token = ""
      result = 0
    end
  else
    result = 2
  end
  TONGJI()
  return result
end

TURING["保质期"] = function()
  return TURING.GameOver()
end

function TURING.GameOver()
  local Year = os.date("%Y")
  local Month = os.date("%m") + 6
  local Day = os.date("%d") + 4
  if 30 < Day then
    Day = Day - 30
    Month = Month + 1
    if 12 < Month then
      Month = Month - 12
      if Month == 2 then
        Day = 4
      end
      Year = Year + 1
    end
  elseif 12 < Month then
    Month = Month - 12
    if Month == 2 then
      Day = 4
    end
    Year = Year + 1
  end
  local getTime = math.floor(Year) .. "/" .. math.floor(Month) .. "/" .. math.floor(Day)
  TONGJI()
  return getTime
end

TURING["版本号"] = function()
  return TURING.Version()
end

function TURING.Version()
  TONGJI()
  return FISR_Ver
end

TURING["联系开发者"] = function()
  return TURING.Developer()
end

function TURING.Developer()
  local TmpText = {}
  TmpText[1] = "联系QQ号：1204400520  "
  TmpText[2] = "交流QQ群：314452472(群1)、460472698(群2)、651646550(群3)、347785380（群4）"
  return table.concat(TmpText, "\r\n")
end

local assert, pairs, type, tostring, baseMt, _instances, _classes, class = assert, pairs, type, tostring, {}, {}, {}

local function deep_copy(t, dest, aType)
  local t, r = t or {}, dest or {}
  for k, v in pairs(t) do
    if aType and type(v) == aType then
      r[k] = v
    elseif not aType then
      if type(v) == "table" and k ~= "__index" then
        r[k] = deep_copy(v)
      else
        r[k] = v
      end
    end
  end
  return r
end

local function instantiate(self, ...)
  local instance = deep_copy(self)
  _instances[instance] = tostring(instance)
  setmetatable(instance, self)
  if self.__init then
    if type(self.__init) == "table" then
      deep_copy(self.__init, instance)
    else
      self.__init(instance, ...)
    end
  end
  return instance
end

local function extends(self, extra_params)
  local heirClass = deep_copy(self, class(extra_params))
  heirClass.__index, heirClass.super = heirClass, self
  return setmetatable(heirClass, self)
end

baseMt = {
  __call = function(self, ...)
    return self:new(...)
  end,
  __tostring = function(self, ...)
    if _instances[self] then
      return ("object (of %s): <%s>"):format(rawget(getmetatable(self), "__name") or "Unnamed", _instances[self])
    end
    return _classes[self] and ("class (%s): <%s>"):format(rawget(self, "__name") or "Unnamed", _classes[self]) or self
  end
}

function class(attr)
  local c = deep_copy(attr)
  _classes[c] = tostring(c)
  
  function c:with(include)
    assert(_classes[self], "Mixins can only be used on classes")
    return deep_copy(include, self, "function")
  end
  
  c.new, c.extends, c.__index, c.__call, c.__tostring = instantiate, extends, c, baseMt.__call, baseMt.__tostring
  return setmetatable(c, baseMt)
end

local deflate = {
  _TYPE = "module",
  _NAME = "compress.deflatelua",
  _VERSION = "0.3.20111128"
}

local function requireany(...)
  local errs = {}
  for i = 1, select("#", ...) do
    local name = select(i, ...)
    if type(name) ~= "string" then
      return name, ""
    end
    local ok, mod = pcall(require, name)
    if ok then
      return mod, name
    end
    errs[#errs + 1] = mod
  end
  error(table.concat(errs, "\n"), 2)
end

local bit, name_ = requireany("bit", "bit32", "bit.numberlua", nil)
local NATIVE_BITOPS = bit ~= nil
local band, lshift, rshift
if NATIVE_BITOPS then
  band = bit.band
  lshift = bit.lshift
  rshift = bit.rshift
end

local function make_outstate(outbs)
  local outstate = {}
  outstate.outbs = outbs
  outstate.window = {}
  outstate.window_pos = 1
  return outstate
end

local function output(outstate, byte)
  local window_pos = outstate.window_pos
  outstate.outbs(byte)
  outstate.window[window_pos] = byte
  outstate.window_pos = window_pos % 32768 + 1
end

local function noeof(val)
  return assert(val, "unexpected end of file")
end

local function hasbit(bits, bit)
  return bit <= bits % (bit + bit)
end

local function memoize(f)
  local t = setmetatable({}, {
    __index = function(t, k)
      local v = f(k)
      t[k] = v
      return v
    end
  })
  return t
end

local pow2 = memoize(function(n)
  return 2 ^ n
end)
local is_bitstream = setmetatable({}, {__mode = "k"})

local function bytestream_from_file(fh)
  local o = {}
  
  function o:read()
    local sb = fh:read(1)
    if sb then
      return sb:byte()
    end
  end
  
  return o
end

local function bytestream_from_string(s)
  local i = 1
  local o = {}
  
  function o:read()
    local by
    if i <= #s then
      by = s:byte(i)
      i = i + 1
    end
    return by
  end
  
  return o
end

local function bytestream_from_function(f)
  local i = 0
  local buffer = ""
  local o = {}
  
  function o:read()
    i = i + 1
    if i > #buffer then
      buffer = f()
      if not buffer then
        return
      end
      i = 1
    end
    return buffer:byte(i, i)
  end
  
  return o
end

local function bitstream_from_bytestream(bys)
  local buf_byte = 0
  local buf_nbit = 0
  local o = {}
  
  function o:nbits_left_in_byte()
    return buf_nbit
  end
  
  if NATIVE_BITOPS then
    function o:read(nbits)
      nbits = nbits or 1
      
      while nbits > buf_nbit do
        local byte = bys:read()
        if not byte then
          return
        end
        buf_byte = buf_byte + lshift(byte, buf_nbit)
        buf_nbit = buf_nbit + 8
      end
      local bits
      if nbits == 0 then
        bits = 0
      elseif nbits == 32 then
        bits = buf_byte
        buf_byte = 0
      else
        bits = band(buf_byte, rshift(4294967295, 32 - nbits))
        buf_byte = rshift(buf_byte, nbits)
      end
      buf_nbit = buf_nbit - nbits
      return bits
    end
  else
    function o:read(nbits)
      nbits = nbits or 1
      
      while nbits > buf_nbit do
        local byte = bys:read()
        if not byte then
          return
        end
        buf_byte = buf_byte + pow2[buf_nbit] * byte
        buf_nbit = buf_nbit + 8
      end
      local m = pow2[nbits]
      local bits = buf_byte % m
      buf_byte = (buf_byte - bits) / m
      buf_nbit = buf_nbit - nbits
      return bits
    end
  end
  is_bitstream[o] = true
  return o
end

local function get_bitstream(o)
  local bs
  if is_bitstream[o] then
    return o
  elseif io.type(o) == "file" then
    bs = bitstream_from_bytestream(bytestream_from_file(o))
  elseif type(o) == "string" then
    bs = bitstream_from_bytestream(bytestream_from_string(o))
  elseif type(o) == "function" then
    bs = bitstream_from_bytestream(bytestream_from_function(o))
  else
    runtime_error("unrecognized type")
  end
  return bs
end

local function get_obytestream(o)
  local bs
  if io.type(o) == "file" then
    function bs(sbyte)
      o:write(string.char(sbyte))
    end
  elseif type(o) == "function" then
    bs = o
  else
    runtime_error("unrecognized type: " .. tostring(o))
  end
  return bs
end

local function HuffmanTable(init, is_full)
  local t = {}
  if is_full then
    for val, nbits in pairs(init) do
      if nbits ~= 0 then
        t[#t + 1] = {val = val, nbits = nbits}
      end
    end
  else
    for i = 1, #init - 2, 2 do
      local firstval, nbits, nextval = init[i], init[i + 1], init[i + 2]
      if nbits ~= 0 then
        for val = firstval, nextval - 1 do
          t[#t + 1] = {val = val, nbits = nbits}
        end
      end
    end
  end
  table.sort(t, function(a, b)
    return a.nbits == b.nbits and a.val < b.val or a.nbits < b.nbits
  end)
  local code = 1
  local nbits = 0
  for i, s in ipairs(t) do
    if s.nbits ~= nbits then
      code = code * pow2[s.nbits - nbits]
      nbits = s.nbits
    end
    s.code = code
    code = code + 1
  end
  local minbits = math.huge
  local look = {}
  for i, s in ipairs(t) do
    minbits = math.min(minbits, s.nbits)
    look[s.code] = s.val
  end
  local msb = NATIVE_BITOPS and function(bits, nbits)
    local res = 0
    for i = 1, nbits do
      res = lshift(res, 1) + band(bits, 1)
      bits = rshift(bits, 1)
    end
    return res
  end or function(bits, nbits)
    local res = 0
    for i = 1, nbits do
      local b = bits % 2
      bits = (bits - b) / 2
      res = res * 2 + b
    end
    return res
  end
  local tfirstcode = memoize(function(bits)
    return pow2[minbits] + msb(bits, minbits)
  end)
  
  function t:read(bs)
    local code = 1
    local nbits = 0
    while true do
      if nbits == 0 then
        code = tfirstcode[noeof(bs:read(minbits))]
        nbits = nbits + minbits
      else
        local b = noeof(bs:read())
        nbits = nbits + 1
        code = code * 2 + b
      end
      local val = look[code]
      if val then
        return val
      end
    end
  end
  
  return t
end

local function parse_gzip_header(bs)
  local FLG_FHCRC = 2.0
  local FLG_FEXTRA = 4.0
  local FLG_FNAME = 8.0
  local FLG_FCOMMENT = 16.0
  local id1 = bs:read(8)
  local id2 = bs:read(8)
  if id1 ~= 31 or id2 ~= 139 then
    runtime_error("not in gzip format")
  end
  local cm = bs:read(8)
  local flg = bs:read(8)
  local mtime = bs:read(32)
  local xfl = bs:read(8)
  local os = bs:read(8)
  if not os then
    runtime_error("invalid header")
  end
  if hasbit(flg, FLG_FEXTRA) then
    local xlen = bs:read(16)
    local extra = 0
    for i = 1, xlen do
      extra = bs:read(8)
    end
    if not extra then
      runtime_error("invalid header")
    end
  end
  
  local function parse_zstring(bs)
    repeat
      local by = bs:read(8)
      if not by then
        runtime_error("invalid header")
      end
    until by == 0
  end
  
  if hasbit(flg, FLG_FNAME) then
    parse_zstring(bs)
  end
  if hasbit(flg, FLG_FCOMMENT) then
    parse_zstring(bs)
  end
  if hasbit(flg, FLG_FHCRC) then
    local crc16 = bs:read(16)
    if not crc16 then
      runtime_error("invalid header")
    end
  end
end

local function parse_zlib_header(bs)
  local cm = bs:read(4)
  local cinfo = bs:read(4)
  local fcheck = bs:read(5)
  local fdict = bs:read(1)
  local flevel = bs:read(2)
  local cmf = cinfo * 16 + cm
  local flg = fcheck + fdict * 32 + flevel * 64
  if cm ~= 8 then
    runtime_error("unrecognized zlib compression method: " .. cm)
  end
  if 7 < cinfo then
    runtime_error("invalid zlib window size: cinfo=" .. cinfo)
  end
  local window_size = 2 ^ (cinfo + 8)
  if (cmf * 256 + flg) % 31 ~= 0 then
    runtime_error("invalid zlib header (bad fcheck sum)")
  end
  if fdict == 1 then
    runtime_error("FIX:TODO - FDICT not currently implemented")
    local dictid_ = bs:read(32)
  end
  return window_size
end

local function parse_huffmantables(bs)
  local hlit = bs:read(5)
  local hdist = bs:read(5)
  local hclen = noeof(bs:read(4))
  local ncodelen_codes = hclen + 4
  local codelen_init = {}
  local codelen_vals = {
    16,
    17,
    18,
    0,
    8,
    7,
    9,
    6,
    10,
    5,
    11,
    4,
    12,
    3,
    13,
    2,
    14,
    1,
    15
  }
  for i = 1, ncodelen_codes do
    local nbits = bs:read(3)
    local val = codelen_vals[i]
    codelen_init[val] = nbits
  end
  local codelentable = HuffmanTable(codelen_init, true)
  
  local function decode(ncodes)
    local init = {}
    local nbits
    local val = 0
    while ncodes > val do
      local codelen = codelentable:read(bs)
      local nrepeat
      if codelen <= 15 then
        nrepeat = 1
        nbits = codelen
      elseif codelen == 16 then
        nrepeat = 3 + noeof(bs:read(2))
      elseif codelen == 17 then
        nrepeat = 3 + noeof(bs:read(3))
        nbits = 0
      elseif codelen == 18 then
        nrepeat = 11 + noeof(bs:read(7))
        nbits = 0
      else
        error("ASSERT")
      end
      for i = 1, nrepeat do
        init[val] = nbits
        val = val + 1
      end
    end
    local huffmantable = HuffmanTable(init, true)
    return huffmantable
  end
  
  local nlit_codes = hlit + 257
  local ndist_codes = hdist + 1
  local littable = decode(nlit_codes)
  local disttable = decode(ndist_codes)
  return littable, disttable
end

local tdecode_len_base, tdecode_len_nextrabits, tdecode_dist_base, tdecode_dist_nextrabits

local function parse_compressed_item(bs, outstate, littable, disttable)
  local val = littable:read(bs)
  if val < 256 then
    output(outstate, val)
  elseif val == 256 then
    return true
  else
    if not tdecode_len_base then
      local t = {
        [257] = 3
      }
      local skip = 1
      for i = 258, 285, 4 do
        for j = i, i + 3 do
          t[j] = t[j - 1] + skip
        end
        if i ~= 258 then
          skip = skip * 2
        end
      end
      t[285] = 258
      tdecode_len_base = t
    end
    if not tdecode_len_nextrabits then
      local t = {}
      if NATIVE_BITOPS then
        for i = 257, 285 do
          local j = math.max(i - 261, 0)
          t[i] = rshift(j, 2)
        end
      else
        for i = 257, 285 do
          local j = math.max(i - 261, 0)
          t[i] = (j - j % 4) / 4
        end
      end
      t[285] = 0
      tdecode_len_nextrabits = t
    end
    local len_base = tdecode_len_base[val]
    local nextrabits = tdecode_len_nextrabits[val]
    local extrabits = bs:read(nextrabits)
    local len = len_base + extrabits
    if not tdecode_dist_base then
      local t = {
        [0] = 1
      }
      local skip = 1
      for i = 1, 29, 2 do
        for j = i, i + 1 do
          t[j] = t[j - 1] + skip
        end
        if i ~= 1 then
          skip = skip * 2
        end
      end
      tdecode_dist_base = t
    end
    if not tdecode_dist_nextrabits then
      local t = {}
      if NATIVE_BITOPS then
        for i = 0, 29 do
          local j = math.max(i - 2, 0)
          t[i] = rshift(j, 1)
        end
      else
        for i = 0, 29 do
          local j = math.max(i - 2, 0)
          t[i] = (j - j % 2) / 2
        end
      end
      tdecode_dist_nextrabits = t
    end
    local dist_val = disttable:read(bs)
    local dist_base = tdecode_dist_base[dist_val]
    local dist_nextrabits = tdecode_dist_nextrabits[dist_val]
    local dist_extrabits = bs:read(dist_nextrabits)
    local dist = dist_base + dist_extrabits
    for i = 1, len do
      local pos = (outstate.window_pos - 1 - dist) % 32768 + 1
      output(outstate, assert(outstate.window[pos], "invalid distance"))
    end
  end
  return false
end

local function parse_block(bs, outstate)
  local bfinal = bs:read(1)
  local btype = bs:read(2)
  local BTYPE_NO_COMPRESSION = 0
  local BTYPE_FIXED_HUFFMAN = 1
  local BTYPE_DYNAMIC_HUFFMAN = 2
  local BTYPE_RESERVED_ = 3
  if btype == BTYPE_NO_COMPRESSION then
    bs:read(bs:nbits_left_in_byte())
    local len = bs:read(16)
    local nlen_ = noeof(bs:read(16))
    for i = 1, len do
      local by = noeof(bs:read(8))
      output(outstate, by)
    end
  elseif btype == BTYPE_FIXED_HUFFMAN or btype == BTYPE_DYNAMIC_HUFFMAN then
    local littable, disttable
    if btype == BTYPE_DYNAMIC_HUFFMAN then
      littable, disttable = parse_huffmantables(bs)
    else
      littable = HuffmanTable({
        0,
        8,
        144,
        9,
        256,
        7,
        280,
        8,
        288,
        nil
      })
      disttable = HuffmanTable({
        0,
        5,
        32,
        nil
      })
    end
    repeat
      local is_done = parse_compressed_item(bs, outstate, littable, disttable)
    until is_done
  else
    runtime_error("unrecognized compression type")
  end
  return bfinal ~= 0
end

function deflate.inflate(t)
  local bs = get_bitstream(t.input)
  local outbs = get_obytestream(t.output)
  local outstate = make_outstate(outbs)
  repeat
    local is_final = parse_block(bs, outstate)
  until is_final
end

local inflate = deflate.inflate

function deflate.gunzip(t)
  local bs = get_bitstream(t.input)
  local outbs = get_obytestream(t.output)
  local disable_crc = t.disable_crc
  if disable_crc == nil then
    disable_crc = false
  end
  parse_gzip_header(bs)
  local data_crc32 = 0
  inflate({
    input = bs,
    output = disable_crc and outbs or function(byte)
      data_crc32 = crc32(byte, data_crc32)
      outbs(byte)
    end
  })
  bs:read(bs:nbits_left_in_byte())
  local expected_crc32 = bs:read(32)
  local isize = bs:read(32)
  if not disable_crc and data_crc32 and data_crc32 ~= expected_crc32 then
    runtime_error("invalid compressed data--crc error")
  end
end

function deflate.adler32(byte, crc)
  local s1 = crc % 65536
  local s2 = (crc - s1) / 65536
  s1 = (s1 + byte) % 65521
  s2 = (s2 + s1) % 65521
  return s2 * 65536 + s1
end

function deflate.inflate_zlib(t)
  local bs = get_bitstream(t.input)
  local outbs = get_obytestream(t.output)
  local disable_crc = t.disable_crc
  if disable_crc == nil then
    disable_crc = false
  end
  local window_size_ = parse_zlib_header(bs)
  local data_adler32 = 1
  inflate({
    input = bs,
    output = disable_crc and outbs or function(byte)
      data_adler32 = numberlua.adler32(byte, data_adler32)
      outbs(byte)
    end
  })
  bs:read(bs:nbits_left_in_byte())
  local b3 = bs:read(8)
  local b2 = bs:read(8)
  local b1 = bs:read(8)
  local b0 = bs:read(8)
  local expected_adler32 = ((b3 * 256 + b2) * 256 + b1) * 256 + b0
  if not disable_crc and data_adler32 ~= expected_adler32 then
    runtime_error("invalid compressed data--crc error")
  end
end

local numberlua = {
  _TYPE = "module",
  _NAME = "bit.numberlua",
  _VERSION = "0.3.1.20120131"
}
local MOD = 4.294967296E9
local MODM = MOD - 1

local function make_bitop_uncached(t, m)
  local function bitop(a, b)
    local res, p = 0, 1
    
    while a ~= 0 and b ~= 0 do
      local am, bm = a % m, b % m
      res = res + t[am][bm] * p
      a = (a - am) / m
      b = (b - bm) / m
      p = p * m
    end
    res = res + (a + b) * p
    return res
  end
  
  return bitop
end

local function make_bitop(t)
  local op1 = make_bitop_uncached(t, 2.0)
  local op2 = memoize(function(a)
    return memoize(function(b)
      return op1(a, b)
    end)
  end)
  return make_bitop_uncached(op2, 2 ^ (t.n or 1))
end

function numberlua.tobit(x)
  return x % 4.294967296E9
end

numberlua.bxor = make_bitop({
  [0] = {
    [0] = 0,
    [1] = 1
  },
  [1] = {
    [0] = 1,
    [1] = 0
  },
  n = 4
})
local bxor = numberlua.bxor

function numberlua.bnot(a)
  return MODM - a
end

local bnot = numberlua.bnot

function numberlua.band(a, b)
  return (a + b - bxor(a, b)) / 2
end

local band = numberlua.band

function numberlua.bor(a, b)
  return MODM - band(MODM - a, MODM - b)
end

local bor = numberlua.bor
local lshift, rshift

function numberlua.rshift(a, disp)
  if disp < 0 then
    return lshift(a, -disp)
  end
  return math.floor(a % 4.294967296E9 / 2 ^ disp)
end

rshift = numberlua.rshift

function numberlua.lshift(a, disp)
  if disp < 0 then
    return rshift(a, -disp)
  end
  return a * 2 ^ disp % 4.294967296E9
end

lshift = numberlua.lshift

function numberlua.tohex(x, n)
  n = n or 8
  local up
  if n <= 0 then
    if n == 0 then
      return ""
    end
    up = true
    n = -n
  end
  x = band(x, 16 ^ n - 1)
  return ("%0" .. n .. (up and "X" or "x")):format(x)
end

local tohex = numberlua.tohex

function numberlua.extract(n, field, width)
  width = width or 1
  return band(rshift(n, field), 2 ^ width - 1)
end

local extract = numberlua.extract

function numberlua.replace(n, v, field, width)
  width = width or 1
  local mask1 = 2 ^ width - 1
  v = band(v, mask1)
  local mask = bnot(lshift(mask1, field))
  return band(n, mask) + lshift(v, field)
end

local replace = numberlua.replace

function numberlua.bswap(x)
  local a = band(x, 255)
  x = rshift(x, 8)
  local b = band(x, 255)
  x = rshift(x, 8)
  local c = band(x, 255)
  x = rshift(x, 8)
  local d = band(x, 255)
  return lshift(lshift(lshift(a, 8) + b, 8) + c, 8) + d
end

local bswap = numberlua.bswap

function numberlua.rrotate(x, disp)
  disp = disp % 32
  local low = band(x, 2 ^ disp - 1)
  return rshift(x, disp) + lshift(low, 32 - disp)
end

local rrotate = numberlua.rrotate

function numberlua.lrotate(x, disp)
  return rrotate(x, -disp)
end

local lrotate = numberlua.lrotate
numberlua.rol = numberlua.lrotate
numberlua.ror = numberlua.rrotate

function numberlua.arshift(x, disp)
  local z = rshift(x, disp)
  if 2147483648 <= x then
    z = z + lshift(2 ^ disp - 1, 32 - disp)
  end
  return z
end

local arshift = numberlua.arshift

function numberlua.btest(x, y)
  return band(x, y) ~= 0
end

numberlua.bit32 = {}

local function bit32_bnot(x)
  return (-1 - x) % MOD
end

numberlua.bit32.bnot = bit32_bnot

local function bit32_bxor(a, b, c, ...)
  local z
  if b then
    a = a % MOD
    b = b % MOD
    z = bxor(a, b)
    if c then
      z = bit32_bxor(z, c, ...)
    end
    return z
  elseif a then
    return a % MOD
  else
    return 0
  end
end

numberlua.bit32.bxor = bit32_bxor

local function bit32_band(a, b, c, ...)
  local z
  if b then
    a = a % MOD
    b = b % MOD
    z = (a + b - bxor(a, b)) / 2
    if c then
      z = bit32_band(z, c, ...)
    end
    return z
  elseif a then
    return a % MOD
  else
    return MODM
  end
end

numberlua.bit32.band = bit32_band

local function bit32_bor(a, b, c, ...)
  local z
  if b then
    a = a % MOD
    b = b % MOD
    z = MODM - band(MODM - a, MODM - b)
    if c then
      z = bit32_bor(z, c, ...)
    end
    return z
  elseif a then
    return a % MOD
  else
    return 0
  end
end

numberlua.bit32.bor = bit32_bor

function numberlua.bit32.btest(...)
  return bit32_band(...) ~= 0
end

function numberlua.bit32.lrotate(x, disp)
  return lrotate(x % MOD, disp)
end

function numberlua.bit32.rrotate(x, disp)
  return rrotate(x % MOD, disp)
end

function numberlua.bit32.lshift(x, disp)
  if 31 < disp or disp < -31 then
    return 0
  end
  return lshift(x % MOD, disp)
end

function numberlua.bit32.rshift(x, disp)
  if 31 < disp or disp < -31 then
    return 0
  end
  return rshift(x % MOD, disp)
end

function numberlua.bit32.arshift(x, disp)
  x = x % MOD
  if 0 <= disp then
    if 31 < disp then
      return 2147483648 <= x and MODM or 0
    else
      local z = rshift(x, disp)
      if 2147483648 <= x then
        z = z + lshift(2 ^ disp - 1, 32 - disp)
      end
      return z
    end
  else
    return lshift(x, -disp)
  end
end

function numberlua.bit32.extract(x, field, ...)
  local width = (...) or 1
  if field < 0 or 31 < field or width < 0 or 32 < field + width then
    error("out of range")
  end
  x = x % MOD
  return extract(x, field, ...)
end

function numberlua.bit32.replace(x, v, field, ...)
  local width = (...) or 1
  if field < 0 or 31 < field or width < 0 or 32 < field + width then
    error("out of range")
  end
  x = x % MOD
  v = v % MOD
  return replace(x, v, field, ...)
end

numberlua.bit = {}

function numberlua.bit.tobit(x)
  x = x % MOD
  if 2147483648 <= x then
    x = x - MOD
  end
  return x
end

local bit_tobit = numberlua.bit.tobit

function numberlua.bit.tohex(x, ...)
  return tohex(x % MOD, ...)
end

function numberlua.bit.bnot(x)
  return bit_tobit(bnot(x % MOD))
end

local function bit_bor(a, b, c, ...)
  if c then
    return bit_bor(bit_bor(a, b), c, ...)
  elseif b then
    return bit_tobit(bor(a % MOD, b % MOD))
  else
    return bit_tobit(a)
  end
end

numberlua.bit.bor = bit_bor

local function bit_band(a, b, c, ...)
  if c then
    return bit_band(bit_band(a, b), c, ...)
  elseif b then
    return bit_tobit(band(a % MOD, b % MOD))
  else
    return bit_tobit(a)
  end
end

numberlua.bit.band = bit_band

local function bit_bxor(a, b, c, ...)
  if c then
    return bit_bxor(bit_bxor(a, b), c, ...)
  elseif b then
    return bit_tobit(bxor(a % MOD, b % MOD))
  else
    return bit_tobit(a)
  end
end

numberlua.bit.bxor = bit_bxor

function numberlua.bit.lshift(x, n)
  return bit_tobit(lshift(x % MOD, n % 32))
end

function numberlua.bit.rshift(x, n)
  return bit_tobit(rshift(x % MOD, n % 32))
end

function numberlua.bit.arshift(x, n)
  return bit_tobit(arshift(x % MOD, n % 32))
end

function numberlua.bit.rol(x, n)
  return bit_tobit(lrotate(x % MOD, n % 32))
end

function numberlua.bit.ror(x, n)
  return bit_tobit(rrotate(x % MOD, n % 32))
end

function numberlua.bit.bswap(x)
  return bit_tobit(bswap(x % MOD))
end

Chunk = class()
Chunk.__name = "Chunk"
Chunk.length = 0
Chunk.name = ""
Chunk.data = ""
Chunk.crc = ""

function Chunk:__init(stream)
  if stream.__name == "Chunk" then
    self.length = stream.length
    self.name = stream.name
    self.data = stream.data
    self.crc = stream.crc
  else
    self.length = stream:readInt()
    self.name = stream:readChars(4)
    self.data = stream:readChars(self.length)
    self.crc = stream:readChars(4)
  end
end

function Chunk:getDataStream()
  return Stream({
    input = self.data
  })
end

IHDR = Chunk:extends()
IHDR.__name = "IHDR"
IHDR.width = 0
IHDR.height = 0
IHDR.bitDepth = 0
IHDR.colorType = 0
IHDR.compression = 0
IHDR.filter = 0
IHDR.interlace = 0

function IHDR:__init(chunk)
  self.super.__init(self, chunk)
  local stream = chunk:getDataStream()
  self.width = stream:readInt()
  self.height = stream:readInt()
  self.bitDepth = stream:readByte()
  self.colorType = stream:readByte()
  self.compression = stream:readByte()
  self.filter = stream:readByte()
  self.interlace = stream:readByte()
end

IDAT = Chunk:extends()
IDAT.__name = "IDAT"

function IDAT:__init(chunk)
  self.super.__init(self, chunk)
end

PLTE = Chunk:extends()
PLTE.__name = "PLTE"
PLTE.numColors = 0
PLTE.colors = {}

function PLTE:__init(chunk)
  self.super.__init(self, chunk)
  self.numColors = math.floor(chunk.length / 3)
  local stream = chunk:getDataStream()
  for i = 1, self.numColors do
    self.colors[i] = {
      R = stream:readByte(),
      G = stream:readByte(),
      B = stream:readByte()
    }
  end
end

function PLTE:getColor(index)
  return self.colors[index]
end

Pixel = class()
Pixel.__name = "Pixel"
Pixel.R = 0
Pixel.G = 0
Pixel.B = 0
Pixel.A = 0

function Pixel:__init(stream, depth, colorType, palette)
  local bps = math.floor(depth / 8)
  if colorType == 6 then
    self.R = stream:readInt(bps)
    self.G = stream:readInt(bps)
    self.B = stream:readInt(bps)
    self.A = stream:readInt(bps)
  end
  if colorType == 0 then
    local grey = stream:readInt(bps)
    self.R = grey
    self.G = grey
    self.B = grey
    self.A = 255
  end
  if colorType == 2 then
    self.R = stream:readInt(bps)
    self.G = stream:readInt(bps)
    self.B = stream:readInt(bps)
    self.A = 255
  end
  if colorType == 3 then
    local index = stream:readInt(bps) + 1
    local color = palette:getColor(index)
    self.R = color.R
    self.G = color.G
    self.B = color.B
    self.A = 255
  end
  if colorType == 4 then
    local grey = stream:readInt(bps)
    self.R = grey
    self.G = grey
    self.B = grey
    self.A = stream:readInt(bps)
  end
end

function Pixel:format()
  return string.format("%02X%02X%02X", self.R, self.G, self.B, self.A)
end

ScanLine = class()
ScanLine.__name = "ScanLine"
ScanLine.pixels = {}
ScanLine.filterType = 0

function ScanLine:__init(stream, depth, colorType, palette, length)
  bpp = math.floor(depth / 8) * self:bitFromColorType(colorType)
  bpl = bpp * length
  self.filterType = stream:readByte()
  stream:seek(-1)
  stream:writeByte(0)
  local startLoc = stream.position
  if self.filterType == 0 then
    for i = 1, length do
      self.pixels[i] = Pixel(stream, depth, colorType, palette)
    end
  end
  if self.filterType == 1 then
    for i = 1, length do
      for j = 1, bpp do
        local curByte = stream:readByte()
        stream:seek(-(bpp + 1))
        local lastByte = 0
        if startLoc <= stream.position then
          lastByte = stream:readByte() or 0
        else
          stream:readByte()
        end
        stream:seek(bpp - 1)
        stream:writeByte((curByte + lastByte) % 256)
      end
      stream:seek(-bpp)
      self.pixels[i] = Pixel(stream, depth, colorType, palette)
    end
  end
  if self.filterType == 2 then
    for i = 1, length do
      for j = 1, bpp do
        local curByte = stream:readByte()
        stream:seek(-(bpl + 2))
        local lastByte = stream:readByte() or 0
        stream:seek(bpl)
        stream:writeByte((curByte + lastByte) % 256)
      end
      stream:seek(-bpp)
      self.pixels[i] = Pixel(stream, depth, colorType, palette)
    end
  end
  if self.filterType == 3 then
    for i = 1, length do
      for j = 1, bpp do
        local curByte = stream:readByte()
        stream:seek(-(bpp + 1))
        local lastByte = 0
        if startLoc <= stream.position then
          lastByte = stream:readByte() or 0
        else
          stream:readByte()
        end
        stream:seek(-bpl + bpp - 2)
        local priByte = stream:readByte() or 0
        stream:seek(bpl)
        stream:writeByte((curByte + math.floor((lastByte + priByte) / 2)) % 256)
      end
      stream:seek(-bpp)
      self.pixels[i] = Pixel(stream, depth, colorType, palette)
    end
  end
  if self.filterType == 4 then
    for i = 1, length do
      for j = 1, bpp do
        local curByte = stream:readByte()
        stream:seek(-(bpp + 1))
        local lastByte = 0
        if startLoc <= stream.position then
          lastByte = stream:readByte() or 0
        else
          stream:readByte()
        end
        stream:seek(-(bpl + 2 - bpp))
        local priByte = stream:readByte() or 0
        stream:seek(-(bpp + 1))
        local lastPriByte = 0
        if stream.position >= startLoc - (length * bpp + 1) then
          lastPriByte = stream:readByte() or 0
        else
          stream:readByte()
        end
        stream:seek(bpl + bpp)
        stream:writeByte((curByte + self:_PaethPredict(lastByte, priByte, lastPriByte)) % 256)
      end
      stream:seek(-bpp)
      self.pixels[i] = Pixel(stream, depth, colorType, palette)
    end
  end
end

function ScanLine:bitFromColorType(colorType)
  if colorType == 0 then
    return 1
  end
  if colorType == 2 then
    return 3
  end
  if colorType == 3 then
    return 1
  end
  if colorType == 4 then
    return 2
  end
  if colorType == 6 then
    return 4
  end
  error("Invalid colortype")
end

function ScanLine:getPixel(pixel)
  return self.pixels[pixel]
end

function ScanLine:_PaethPredict(a, b, c)
  local p = a + b - c
  local varA = math.abs(p - a)
  local varB = math.abs(p - b)
  local varC = math.abs(p - c)
  if varA <= varB and varA <= varC then
    return a
  end
  if varB <= varC then
    return b
  end
  return c
end

pngImage = class()
pngImage.__name = "PNG"
pngImage.width = 0
pngImage.height = 0
pngImage.depth = 0
pngImage.colorType = 0
pngImage.scanLines = {}

function pngImage:__init(path, progCallback)
  local str = Stream({inputF = path})
  if str:readChars(8) ~= "�PNG\r\n\026\n" then
    error("Not a PNG")
  end
  local ihdr = {}
  local plte = {}
  local idat = {}
  local num = 1
  while true do
    ch = Chunk(str)
    if ch.name == "IHDR" then
      ihdr = IHDR(ch)
    end
    if ch.name == "PLTE" then
      plte = PLTE(ch)
    end
    if ch.name == "IDAT" then
      idat[num] = IDAT(ch)
      num = num + 1
    end
    if ch.name == "IEND" then
      break
    end
  end
  self.width = ihdr.width
  self.height = ihdr.height
  self.depth = ihdr.bitDepth
  self.colorType = ihdr.colorType
  local dataStr = ""
  for k, v in pairs(idat) do
    dataStr = dataStr .. v.data
  end
  local output = {}
  deflate.inflate_zlib({
    input = dataStr,
    output = function(byte)
      output[#output + 1] = string.char(byte)
    end,
    disable_crc = true
  })
  imStr = Stream({
    input = table.concat(output)
  })
  for i = 1, self.height do
    self.scanLines[i] = ScanLine(imStr, self.depth, self.colorType, plte, self.width)
  end
end

function pngImage:getPixel(x, y)
  local pixel = self.scanLines[y].pixels[x]
  return pixel
end

Stream = class()
Stream.data = {}
Stream.position = 1
Stream.__name = "Stream"

function Stream:bsRight(num, pow)
  return math.floor(num / 2 ^ pow)
end

function Stream:bsLeft(num, pow)
  return math.floor(num * 2 ^ pow)
end

function Stream:bytesToNum(bytes)
  local n = 0
  for k, v in ipairs(bytes) do
    n = self:bsLeft(n, 8) + v
  end
  n = 2147483647 < n and n - 4294967296 or n
  return n
end

function Stream:__init(param)
  local str = ""
  if param.inputF ~= nil then
    str = io.open(param.inputF, "rb"):read("*all")
  end
  if param.input ~= nil then
    str = param.input
  end
  for i = 1, #str do
    self.data[i] = str:byte(i, i)
  end
end

function Stream:seek(amount)
  self.position = self.position + amount
end

function Stream:readByte()
  if self.position <= 0 then
    self:seek(1)
    return nil
  end
  local byte = self.data[self.position]
  self:seek(1)
  return byte
end

function Stream:readChars(num)
  if self.position <= 0 then
    self:seek(1)
    return nil
  end
  local str = {}
  local i = 1
  while num >= i do
    table.insert(str, self:readChar())
    i = i + 1
  end
  return table.concat(str), i - 1
end

function Stream:readChar()
  if self.position <= 0 then
    self:seek(1)
    return nil
  end
  return string.char(self:readByte())
end

function Stream:readBytes(num)
  if self.position <= 0 then
    self:seek(1)
    return nil
  end
  local tabl = {}
  local i = 1
  while num >= i do
    local curByte = self:readByte()
    if curByte == nil then
      break
    end
    tabl[i] = curByte
    i = i + 1
  end
  return tabl, i - 1
end

function Stream:readInt(num)
  if self.position <= 0 then
    self:seek(1)
    return nil
  end
  num = num or 4
  local bytes, count = self:readBytes(num)
  return self:bytesToNum(bytes), count
end

function Stream:writeByte(byte)
  if self.position <= 0 then
    self:seek(1)
    return
  end
  self.data[self.position] = byte
  self:seek(1)
end

function Stream:writeChar(char)
  if self.position <= 0 then
    self:seek(1)
    return
  end
  self:writeByte(string.byte(char))
end

function Stream:writeBytes(buffer)
  if self.position <= 0 then
    self:seek(1)
    return
  end
  local str = {}
  for k, v in pairs(buffer) do
    table.insert(str, string.char(v))
  end
  writeChars(table.concat(str))
end

local function getRgb(fileName)
  local iRtn, sRtn = pcall(function()
    img = pngImage(fileName)
    local arr = {}
    for i = 1, img.width do
      arr[i] = {}
      for j = 1, img.height do
        arr[i][j] = {
          img.scanLines[j].pixels[i].B,
          img.scanLines[j].pixels[i].G,
          img.scanLines[j].pixels[i].R
        }
      end
    end
    return arr
  end)
  if iRtn then
    return sRtn
  else
    return nil
  end
end

local Png = {}
Png.__index = Png
DEFLATE_MAX_BLOCK_SIZE = 65535

local function putBigUint32(val, tbl, index)
  for i = 0, 3 do
    tbl[index + i] = bit.band(bit.rshift(val, (3 - i) * 8), 255)
  end
end

function Png:writeBytes(data, index, len)
  index = index or 1
  len = len or #data
  for i = index, index + len - 1 do
    table.insert(self.output, string.char(data[i]))
  end
end

function Png:write(pixels)
  local count = #pixels
  local pixelPointer = 1
  while 0 < count do
    if self.positionY >= self.height then
      error("All image pixels already written")
    end
    if self.deflateFilled == 0 then
      local size = DEFLATE_MAX_BLOCK_SIZE
      if size > self.uncompRemain then
        size = self.uncompRemain
      end
      local header = {
        bit.band(self.uncompRemain <= DEFLATE_MAX_BLOCK_SIZE and 1 or 0, 255),
        bit.band(bit.rshift(size, 0), 255),
        bit.band(bit.rshift(size, 8), 255),
        bit.band(bit.bxor(bit.rshift(size, 0), 255), 255),
        bit.band(bit.bxor(bit.rshift(size, 8), 255), 255)
      }
      self:writeBytes(header)
      self:crc32(header, 1, #header)
    end
    assert(self.positionX < self.lineSize and self.deflateFilled < DEFLATE_MAX_BLOCK_SIZE)
    if self.positionX == 0 then
      local b = {0}
      self:writeBytes(b)
      self:crc32(b, 1, 1)
      self:adler32(b, 1, 1)
      self.positionX = self.positionX + 1
      self.uncompRemain = self.uncompRemain - 1
      self.deflateFilled = self.deflateFilled + 1
    else
      local n = DEFLATE_MAX_BLOCK_SIZE - self.deflateFilled
      if n > self.lineSize - self.positionX then
        n = self.lineSize - self.positionX
      end
      if count < n then
        n = count
      end
      assert(0 < n)
      self:writeBytes(pixels, pixelPointer, n)
      self:crc32(pixels, pixelPointer, n)
      self:adler32(pixels, pixelPointer, n)
      count = count - n
      pixelPointer = pixelPointer + n
      self.positionX = self.positionX + n
      self.uncompRemain = self.uncompRemain - n
      self.deflateFilled = self.deflateFilled + n
    end
    if self.deflateFilled >= DEFLATE_MAX_BLOCK_SIZE then
      self.deflateFilled = 0
    end
    if self.positionX == self.lineSize then
      self.positionX = 0
      self.positionY = self.positionY + 1
      if self.positionY == self.height then
        local footer = {
          0,
          0,
          0,
          0,
          0,
          0,
          0,
          0,
          0,
          0,
          0,
          0,
          73,
          69,
          78,
          68,
          174,
          66,
          96,
          130
        }
        putBigUint32(self.adler, footer, 1)
        self:crc32(footer, 1, 4)
        putBigUint32(self.crc, footer, 5)
        self:writeBytes(footer)
        self.done = true
      end
    end
  end
end

function Png:crc32(data, index, len)
  self.crc = bit.bnot(self.crc)
  for i = index, index + len - 1 do
    local byte = data[i]
    for j = 0, 7 do
      local nbit = bit.band(bit.bxor(self.crc, bit.rshift(byte, j)), 1)
      self.crc = bit.bxor(bit.rshift(self.crc, 1), bit.band(-nbit, 3988292384))
    end
  end
  self.crc = bit.bnot(self.crc)
end

function Png:adler32(data, index, len)
  local s1 = bit.band(self.adler, 65535)
  local s2 = bit.rshift(self.adler, 16)
  for i = index, index + len - 1 do
    s1 = (s1 + data[i]) % 65521
    s2 = (s2 + s1) % 65521
  end
  self.adler = bit.bor(bit.lshift(s2, 16), s1)
end

local function begin(width, height, colorMode)
  colorMode = colorMode or "rgb"
  local bytesPerPixel, colorType
  if colorMode == "rgb" then
    bytesPerPixel, colorType = 3, 2
  elseif colorMode == "rgba" then
    bytesPerPixel, colorType = 4, 6
  else
    error("Invalid colorMode")
  end
  local state = setmetatable({
    width = width,
    height = height,
    done = false,
    output = {}
  }, Png)
  state.lineSize = width * bytesPerPixel + 1
  state.uncompRemain = state.lineSize * height
  local numBlocks = math.ceil(state.uncompRemain / DEFLATE_MAX_BLOCK_SIZE)
  local idatSize = numBlocks * 5 + 6
  idatSize = idatSize + state.uncompRemain
  local header = {
    137,
    80,
    78,
    71,
    13,
    10,
    26,
    10,
    0,
    0,
    0,
    13,
    73,
    72,
    68,
    82,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    8,
    colorType,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    73,
    68,
    65,
    84,
    8,
    29
  }
  putBigUint32(width, header, 17)
  putBigUint32(height, header, 21)
  putBigUint32(idatSize, header, 34)
  state.crc = 0
  state:crc32(header, 13, 17)
  putBigUint32(state.crc, header, 30)
  state:writeBytes(header)
  state.crc = 0
  state:crc32(header, 38, 6)
  state.adler = 1
  state.positionX = 0
  state.positionY = 0
  state.deflateFilled = 0
  return state
end

local function wrtRgb(img_data, file_name)
  local encode = begin
  local width, height
  width = #img_data
  height = #img_data[1]
  local png = encode(width, height)
  for col = 1, height do
    for row = 1, width do
      png:write({
        img_data[row][col][3],
        img_data[row][col][2],
        img_data[row][col][1]
      })
    end
  end
  local data = png.output
  f = io.open(file_name, "w")
  for k, v in ipairs(data) do
    f:write(v)
  end
  f:close()
end

TURING["来源_获取屏幕像素"] = function(iLeft, iTop, iRight, iBottom)
  return TURING.Pixel_FromScreen(iLeft, iTop, iRight, iBottom)
end

function TURING.Pixel_FromScreen(iLeft, iTop, iRight, iBottom)
  local width, height, arr = getScreenPixel(iLeft, iTop, iRight, iBottom)
  R_Left = iLeft
  R_Top = iTop
  R_Right = iRight
  R_Bottom = iBottom
  R_Model = 0
  ImgData = {}
  if arr == nil then
    return ImgData
  end
  for x = 1, width do
    ImgData[x] = {}
    for y = 1, height do
      local color = arr[(y - 1) * width + x]
      local r, g, b = colorToRGB(color)
      ImgData[x][y] = {
        r,
        g,
        b
      }
    end
  end
  arr = nil
  return ImgData
end

local function bufToInt32(num1, num2, num3, num4)
  local num = 0
  num = num + leftShift(num1, 24)
  num = num + leftShift(num2, 16)
  num = num + leftShift(num3, 8)
  num = num + num4
  return num
end

local function int32ToBufStr(num)
  local str = ""
  str = str .. numToAscii(rightShift(num, 24))
  str = str .. numToAscii(rightShift(num, 16))
  str = str .. numToAscii(rightShift(num, 8))
  str = str .. numToAscii(num)
  return str
end

local function bufToInt16(num1, num2)
  local num = 0
  num = num + leftShift(num1, 8)
  num = num + num2
  return num
end

local function int16ToBufStr(num)
  local str = ""
  str = str .. numToAscii(rightShift(num, 8))
  str = str .. numToAscii(num)
  return str
end

local function leftShift(num, shift)
  return math.floor(num * 2 ^ shift)
end

local function rightShift(num, shift)
  return math.floor(num / 2 ^ shift)
end

local function numToAscii(num)
  num = num % 256
  return string.char(num)
end

TURING["来源_获取图片像素"] = function(FileName)
  return TURING.Pixel_FromPicture(FileName)
end

function TURING.Pixel_FromPicture(FileName)
  if string.lower(string.sub(FileName, string.len(FileName) - 3, string.len(FileName))) == ".png" then
    ImgData = getRgb(FileName)
    return ImgData
  end
  local file = io.open(FileName, "rb")
  local data = file:read("*all")
  file:close()
  local bb = tonumber(string.byte(data, 1, 1))
  local mm = tonumber(string.byte(data, 2, 2))
  if bb ~= 66 or mm ~= 77 then
    return
  end
  local bit = tonumber(string.byte(data, 29, 29))
  if bit ~= 24 then
    return
  end
  local w1 = tonumber(string.byte(data, 19, 19))
  local w2 = tonumber(string.byte(data, 20, 20))
  local w3 = tonumber(string.byte(data, 21, 21))
  local w4 = tonumber(string.byte(data, 22, 22))
  local width = bufToInt32(w4, w3, w2, w1)
  local h1 = tonumber(string.byte(data, 23, 23))
  local h2 = tonumber(string.byte(data, 24, 24))
  local h3 = tonumber(string.byte(data, 25, 25))
  local h4 = tonumber(string.byte(data, 26, 26))
  local height = bufToInt32(h4, h3, h2, h1)
  local x, y
  local num = 4 - width * 3 % 4
  local n = width * 3 + num
  local m = 0
  local point = 55
  ImgData = {}
  for x = 1, width do
    ImgData[x] = {}
    local h = height - 1
    for y = 1, height do
      local k = point + m + n * h
      ImgData[x][y] = {
        tonumber(string.byte(data, k, k)),
        tonumber(string.byte(data, k + 1, k + 1)),
        tonumber(string.byte(data, k + 2, k + 2))
      }
      h = h - 1
    end
    m = m + 3
  end
  return ImgData
end

TURING["来源_配置切割图像数据"] = function(Num)
  return TURING.Pixel_SetImageDataCut(Num)
end

function TURING.Pixel_SetImageDataCut(Num)
  local n = #IncData
  if n <= 0 then
    return
  end
  if Num == nil or Num < 1 then
    Num = 1
  end
  local k = Num
  if k < 1 then
    k = 1
  end
  if n < k then
    k = n
  end
  local IncW = IncData[k][3]
  local IncH = IncData[k][4]
  ImgData = {}
  local p
  for x = 1, IncW do
    ImgData[x] = {}
    for y = 1, IncH do
      p = (x - 1) * IncH + y
      if string.sub(IncData[k][5], p, p) == "1" then
        ImgData[x][y] = {
          255,
          255,
          255
        }
      else
        ImgData[x][y] = {
          0,
          0,
          0
        }
      end
    end
  end
  return ImgData
end

local function SaveImageData(TempData, FileName)
  if FileName == nil or FileName == "" then
    return
  end
  local width = #TempData
  if width <= 0 then
    return
  end
  local height = #TempData[1]
  if height <= 0 then
    return
  end
  if string.lower(string.sub(FileName, string.len(FileName) - 3, string.len(FileName))) == ".png" then
    wrtRgb(TempData, FileName)
    return
  end
  local n = 4 - width * 3 % 4
  local pixels = {}
  table.insert(pixels, string.char(66) .. string.char(77))
  table.insert(pixels, string.char(70) .. string.char(0) .. string.char(0) .. string.char(0))
  table.insert(pixels, string.char(0) .. string.char(0) .. string.char(0) .. string.char(0))
  table.insert(pixels, string.char(54) .. string.char(0) .. string.char(0) .. string.char(0))
  table.insert(pixels, string.char(40) .. string.char(0) .. string.char(0) .. string.char(0))
  table.insert(pixels, string.char(width % 256) .. string.char(rightShift(width, 8)) .. string.char(rightShift(width, 16)) .. string.char(rightShift(width, 24)))
  table.insert(pixels, string.char(height % 256) .. string.char(rightShift(height, 8)) .. string.char(rightShift(height, 16)) .. string.char(rightShift(height, 24)))
  table.insert(pixels, string.char(1) .. string.char(0) .. string.char(24) .. string.char(0))
  table.insert(pixels, string.char(0) .. string.char(0) .. string.char(0) .. string.char(0))
  table.insert(pixels, string.char(16) .. string.char(0) .. string.char(0) .. string.char(0))
  table.insert(pixels, string.char(0) .. string.char(0) .. string.char(0) .. string.char(0))
  table.insert(pixels, string.char(0) .. string.char(0) .. string.char(0) .. string.char(0))
  table.insert(pixels, string.char(0) .. string.char(0) .. string.char(0) .. string.char(0))
  table.insert(pixels, string.char(0) .. string.char(0) .. string.char(0) .. string.char(0))
  local W, H
  for H = height, 1, -1 do
    for W = 1, width do
      table.insert(pixels, string.char(TempData[W][H][1]) .. string.char(TempData[W][H][2]) .. string.char(TempData[W][H][3]))
    end
    if 0 < n and n < 4 then
      for i = 1, n do
        table.insert(pixels, string.char(0))
      end
    end
  end
  local file = io.open(FileName, "wb")
  file:write(table.concat(pixels))
  file:close()
  if string.sub(FileName, 1, 5) ~= "/var/" then
    os.execute("am broadcast -a android.intent.action.MEDIA_MOUNTED -d file://" .. FileName)
  end
end

TURING["来源_图像数据保存为图片"] = function(FileName)
  TURING.SaveImageData(FileName)
end

function TURING.SaveImageData(FileName)
  SaveImageData(ImgData, FileName)
end

TURING["来源_获取像素数据大小"] = function()
  TURING.Pixel_ImageSize()
end

function TURING.Pixel_ImageSize()
  local width = #ImgData
  if width <= 0 then
    return {-1, -1}
  end
  local height = #ImgData[1]
  if height <= 0 then
    return {-1, -1}
  else
    return {width, height}
  end
end

TURING["来源_像素数据预览"] = function()
  TURING.Pixel_Preview()
end

function TURING.Pixel_Preview()
  local W, H
  local width = #ImgData
  if width <= 0 then
    return
  end
  local height = #ImgData[1]
  if height <= 0 then
    return
  end
  local imgPath = getSdPath() .. "/temp.bmp"
  SaveImageData(ImgData, imgPath)
  local id = createHUD()
  showHUD(id, "", 0, "", imgPath, 1, 0, 0, 0, 0)
  sleep(5000)
  hideHUD(id)
end

local function NewRound(decimal)
  return math.floor(decimal + 0.444444)
end

local function Split(szFullString, szSeparator)
  local nFindStartIndex = 1
  local nSplitIndex = 1
  local nSplitArray = {}
  while true do
    local nFindLastIndex = string.find(szFullString, szSeparator, nFindStartIndex)
    if not nFindLastIndex then
      nSplitArray[nSplitIndex] = string.sub(szFullString, nFindStartIndex, string.len(szFullString))
      break
    end
    nSplitArray[nSplitIndex] = string.sub(szFullString, nFindStartIndex, nFindLastIndex - 1)
    nFindStartIndex = nFindLastIndex + string.len(szSeparator)
    nSplitIndex = nSplitIndex + 1
  end
  return nSplitArray
end

local function Filter_BinaryColor(value)
  local width = #ImgData
  if width <= 0 then
    return
  end
  local height = #ImgData[1]
  if height <= 0 then
    return
  end
  local flag, imin, imax
  local range = {}
  if string.find(value, "-") ~= nil then
    flag = true
  else
    flag = false
  end
  if flag == true then
    range = Split(value, "-")
    imin = tonumber(range[1])
    imax = tonumber(range[2])
  else
    imax = tonumber(value)
  end
  local w, h, average
  for w = 1, width do
    for h = 1, height do
      average = NewRound((ImgData[w][h][1] + ImgData[w][h][2] + ImgData[w][h][3]) / 3)
      if flag == false then
        if imax > average then
          ImgData[w][h] = {
            0,
            0,
            0
          }
        else
          ImgData[w][h] = {
            255,
            255,
            255
          }
        end
      elseif flag == true then
        if imin <= average and imax >= average then
          ImgData[w][h] = {
            0,
            0,
            0
          }
        else
          ImgData[w][h] = {
            255,
            255,
            255
          }
        end
      end
    end
  end
  return ImgData
end

local function Filter_PickColor(feature)
  local width = #ImgData
  if width <= 0 then
    return
  end
  local height = #ImgData[1]
  if height <= 0 then
    return
  end
  local W, H, fsColor, flag
  if string.sub(feature, 1, 1) == "@" then
    fsColor = 255
    feature = string.sub(feature, 2, #feature)
  else
    fsColor = 0
  end
  local R, G, B, dR, dG, dB
  local range = Split(feature, "|")
  local num = #range
  local ColorData = {}
  for i = 1, num do
    local tempColor = Split(range[i], "-")
    local sColor = tonumber(tempColor[1], 16)
    R = math.floor(sColor % 256)
    G = math.floor(sColor % 65536 / 256)
    B = math.floor(sColor / 65536)
    if 1 < #tempColor then
      sColor = tonumber(tempColor[2], 16)
      dR = math.floor(sColor % 256)
      dG = math.floor(sColor % 65536 / 256)
      dB = math.floor(sColor / 65536)
    else
      dR = 0
      dG = 0
      dB = 0
    end
    ColorData[i] = {
      R + dR,
      G + dG,
      B + dB,
      R - dR,
      G - dG,
      B - dB
    }
  end
  for W = 1, width do
    for H = 1, height do
      flag = false
      for i = 1, num do
        if ImgData[W][H][3] >= ColorData[i][4] and ImgData[W][H][3] <= ColorData[i][1] and ImgData[W][H][2] >= ColorData[i][5] and ImgData[W][H][2] <= ColorData[i][2] and ImgData[W][H][1] >= ColorData[i][6] and ImgData[W][H][1] <= ColorData[i][3] then
          ImgData[W][H] = {
            255 - fsColor,
            255 - fsColor,
            255 - fsColor
          }
          flag = true
          break
        end
      end
      if flag == false then
        ImgData[W][H] = {
          fsColor,
          fsColor,
          fsColor
        }
      end
    end
  end
  return ImgData
end

local function mao_pao(tb, key)
  local Num = #tb
  for i = 1, Num do
    for j = 1, Num - i do
      if tb[j + 1][key] < tb[j][key] then
        local temp = tb[j + 1]
        tb[j + 1] = tb[j]
        tb[j] = temp
      end
    end
  end
  return tb
end

local function Filter_AI()
  local W, H, width, height, Gray, n, Num, Nums, R, G, B, i, ii, k, m, Flag
  local width = #ImgData
  if width <= 0 then
    return
  end
  local height = #ImgData[1]
  Nums = math.floor(width * height * 0.5)
  for W = 1, width do
    for H = 1, height do
      R = ImgData[W][H][3]
      G = ImgData[W][H][2]
      B = ImgData[W][H][1]
      Gray = (R ^ 2.2 * 0.2973 + G ^ 2.2 * 0.6274 + B ^ 2.2 * 0.0753) ^ 0.45454545454545453
      ImgData[W][H][1] = Gray
    end
  end
  local Colors = {}
  k = 1
  for W = 1, width do
    for H = 1, height do
      Flag = false
      if 1 < k then
        for m = 1, k - 1 do
          if ImgData[W][H][1] == Colors[m][1] then
            Colors[m][2] = Colors[m][2] + 1
            Flag = true
            break
          end
        end
      end
      if Flag == false then
        Colors[k] = {}
        Colors[k][1] = ImgData[W][H][1]
        Colors[k][2] = 1
        k = k + 1
      end
    end
  end
  Num = #Colors
  Colors = mao_pao(Colors, 1)
  local MaxN, MinN, Max, Min
  if 3 < Num then
    MaxN = 0
    MinN = 0
    Max = 1
    Min = 1
    for i = 1, Num do
      MinN = MinN + Colors[i][2]
      if Nums < MinN then
        Min = i
        break
      end
    end
    for ii = Num, 2, -1 do
      MaxN = MaxN + Colors[ii][2]
      if Nums < MaxN then
        Max = ii
        break
      end
    end
    if Min == 1 then
      MinN = Colors[Min][2]
    else
      MinN = MinN - Colors[Min - 1][2]
    end
    if Max == Num then
      MaxN = Colors[Max][2]
    else
      MaxN = MaxN - Colors[Max + 1][2]
    end
    if Colors[Max][2] > Colors[Min][2] then
      if MinN < MaxN then
        Gray = Colors[Max + 1][1]
      else
        Gray = Colors[Min][1]
      end
    elseif MinN > MaxN then
      if Min <= 1 then
        Min = 2
      end
      Gray = Colors[Min - 1][1]
    else
      Gray = Colors[Max][1]
    end
  elseif 2 < Num then
    if Colors[1][2] > Colors[3][2] then
      Gray = Colors[1][1]
    else
      Gray = Colors[2][1]
    end
  else
    Gray = Colors[1][1]
  end
  for W = 1, width do
    for H = 1, height do
      if Gray >= ImgData[W][H][1] then
        ImgData[W][H] = {
          0,
          0,
          0
        }
      else
        ImgData[W][H] = {
          255,
          255,
          255
        }
      end
    end
  end
  return ImgData
end

TURING["滤镜_彩色_二值化"] = function(threshold)
  return TURING.Filter_Binaryzation(threshold)
end

function TURING.Filter_Binaryzation(threshold)
  local width = #ImgData
  if width <= 0 then
    return
  end
  local height = #ImgData[1]
  if height <= 0 then
    return
  end
  local flag, value
  if type(threshold) == "number" then
    value = tostring(threshold)
  else
    value = threshold
  end
  if string.sub(value, #value, #value) == "-" then
    return
  end
  if 6 <= #value then
    if 7 < #value then
      flag = 1
    elseif string.find(value, "-") ~= nil then
      flag = 0
    else
      flag = 1
    end
  elseif string.lower(value) == "auto" then
    flag = 2
  elseif string.lower(value) == "ai" then
    flag = 3
  else
    flag = 0
  end
  if flag == 1 then
    return Filter_PickColor(value)
  elseif flag == 0 then
    return Filter_BinaryColor(value)
  elseif flag == 3 then
    return Filter_AI()
  else
    return
  end
end

local function ToMax(r, g, b)
  local Max
  if g <= r and b <= r then
    Max = r
  elseif r <= g and b <= g then
    Max = g
  elseif r <= b and g <= b then
    Max = b
  end
  return Max
end

local function ToMin(r, g, b)
  local Min
  if r <= g and r <= b then
    Min = r
  elseif g <= r and g <= b then
    Min = g
  elseif b <= r and b <= g then
    Min = b
  end
  return Min
end

TURING["滤镜_彩色_灰度"] = function(Model)
  return TURING.Filter_Gray(Model)
end

function TURING.Filter_Gray(Model)
  local width = #ImgData
  if width <= 0 then
    return
  end
  local height = #ImgData[1]
  if height <= 0 then
    return
  end
  local Gray, W, H, r, g, b, Num, Max, Min
  if Model == nil then
    Model = 0
  end
  Num = 0
  for W = 1, width do
    for H = 1, height do
      r = ImgData[W][H][3]
      g = ImgData[W][H][2]
      b = ImgData[W][H][1]
      if Model == 1 then
        Gray = (r ^ 2.2 * 0.2973 + g ^ 2.2 * 0.6274 + b ^ 2.2 * 0.0753) ^ 0.45454545454545453
      elseif Model == 2 then
        Max = ToMax(r, g, b)
        Min = ToMin(r, g, b)
        Gray = (Max + Min) / 2
      else
        Gray = (r * 299 + g * 587 + b * 114) / 1000
      end
      ImgData[W][H] = {
        Gray,
        Gray,
        Gray
      }
      Num = Num + Gray
    end
  end
  Gray = Num / (width * height)
  return Gray
end

TURING["滤镜_彩色_色调分离"] = function(value)
  return TURING.Filter_Posterization(value)
end

function TURING.Filter_Posterization(value)
  local width = #ImgData
  if width <= 0 then
    return
  end
  local height = #ImgData[1]
  if height <= 0 then
    return
  end
  local W, H, n
  if value == nil or value < 2 then
    value = 2
  end
  if 255 < value then
    value = 255
  end
  local feature = 255 / value
  local fsColor = 255 / (value - 1)
  for H = 1, height do
    for W = 1, width do
      local bR = true
      local bG = true
      local bB = true
      for n = 1, value do
        if bB == true and ImgData[W][H][1] >= feature * (n - 1) and ImgData[W][H][1] < feature * n then
          bB = false
          ImgData[W][H][1] = math.floor(fsColor * (n - 1))
        end
        if bG == true and ImgData[W][H][2] >= feature * (n - 1) and ImgData[W][H][2] < feature * n then
          bG = false
          ImgData[W][H][2] = math.floor(fsColor * (n - 1))
        end
        if bR == true and ImgData[W][H][3] >= feature * (n - 1) and ImgData[W][H][3] < feature * n then
          bR = false
          ImgData[W][H][3] = math.floor(fsColor * (n - 1))
        end
      end
    end
  end
  return ImgData
end

TURING["滤镜_彩色_通道图层"] = function(Model)
  return TURING.Filter_ChannelLayer(Model)
end

function TURING.Filter_ChannelLayer(Model)
  local width = #ImgData
  if width <= 0 then
    return
  end
  local height = #ImgData[1]
  if height <= 0 then
    return
  end
  if Model == nil then
    Model = 0
  end
  for W = 1, width do
    for H = 1, height do
      if Model == 0 then
        ImgData[W][H][2] = 0
        ImgData[W][H][1] = 0
      elseif Model == 1 then
        ImgData[W][H][3] = 0
        ImgData[W][H][1] = 0
      elseif Model == 2 then
        ImgData[W][H][3] = 0
        ImgData[W][H][2] = 0
      elseif Model == 3 then
        ImgData[W][H][1] = 0
      elseif Model == 4 then
        ImgData[W][H][3] = 0
      else
        ImgData[W][H][2] = 0
      end
    end
  end
  return ImgData
end

TURING["滤镜_黑白_清除杂点"] = function(value, interval, Model)
  return TURING.Filter_Despeckle(value, interval, Model)
end

function TURING.Filter_Despeckle(value, interval, Model)
  local width = #ImgData
  if width <= 0 then
    return
  end
  local height = #ImgData[1]
  if height <= 0 then
    return
  end
  if value == nil or 8 < value then
    value = 8
  end
  if value < 1 then
    value = 1
  end
  if interval == nil or interval < 0 then
    interval = 0
  end
  if 16 < interval then
    interval = 16
  end
  local fsColor
  if Model == nil or Model == 1 then
    fsColor = 0
  else
    fsColor = 255
  end
  local W, H, X, Y, num, flag, n
  for W = 1, width do
    for H = 1, height do
      num = 0
      if ImgData[W][H][1] == 255 - fsColor then
        flag = false
        if 0 < interval then
          n = 0
          for X = -1 - interval, 1 + interval do
            for Y = -1 - interval, 1 + interval do
              if -1 <= X and X <= 1 and -1 <= Y and Y <= 1 then
              elseif W + X < 1 or H + Y < 1 or (width < W + X or height < H + Y) and fsColor == 255 then
                n = n + 1
              elseif ImgData[W + X][H + Y][1] == fsColor then
                n = n + 1
              end
            end
          end
          if n >= (interval * 2 + 3) ^ 2 - 9 then
            flag = true
          end
        else
          flag = true
        end
        if flag == true then
          for X = -1, 1 do
            for Y = -1, 1 do
              if W + X < 1 or H + Y < 1 or width < W + X or height < H + Y then
                num = num + 1
              elseif ImgData[W + X][H + Y][1] ~= 255 - fsColor then
                num = num + 1
              end
            end
          end
          if value <= num then
            ImgData[W][H] = {
              fsColor,
              fsColor,
              fsColor
            }
          else
            ImgData[W][H] = {
              255 - fsColor,
              255 - fsColor,
              255 - fsColor
            }
          end
        end
      end
    end
  end
  return ImgData
end

local function DeepCopy(object)
  local SearchTable = {}
  
  local function Func(object)
    if type(object) ~= "table" then
      return object
    end
    local NewTable = {}
    SearchTable[object] = NewTable
    for k, v in pairs(object) do
      NewTable[Func(k)] = Func(v)
    end
    return setmetatable(NewTable, getmetatable(object))
  end
  
  return Func(object)
end

TURING["滤镜_黑白_提取色块"] = function(wNum, hNum, Num)
  return TURING.Filter_ExtractBlock(wNum, hNum, Num)
end

function TURING.Filter_ExtractBlock(wNum, hNum, Num)
  local width = #ImgData
  if width <= 0 then
    return
  end
  local height = #ImgData[1]
  if height <= 0 then
    return
  end
  local W, H, X, Y, n
  local TmpData = {}
  for W = 1, width do
    TmpData[W] = {}
    for H = 1, height do
      TmpData[W][H] = {
        0,
        0,
        0
      }
    end
  end
  for W = 1, width do
    for H = 1, height do
      n = 0
      if ImgData[W][H][1] == 255 then
        for X = 0, wNum - 1 do
          for Y = 0, hNum - 1 do
            if width < W + X or height < H + Y then
              break
            end
            if ImgData[W + X][H + Y][1] == 255 then
              n = n + 1
            end
          end
        end
        if Num <= n then
          for X = 0, wNum - 1 do
            for Y = 0, hNum - 1 do
              if width < W + X or height < H + Y then
                break
              end
              TmpData[W + X][H + Y] = {
                ImgData[W + X][H + Y][1],
                ImgData[W + X][H + Y][2],
                ImgData[W + X][H + Y][3]
              }
            end
          end
        end
      end
    end
  end
  ImgData = {}
  ImgData = DeepCopy(TmpData)
  return ImgData
end

local function Filter_Outline(value, Interval, r, g, b)
  local W, H, width, height, Flag, n, yuzhi, jianju, R1, G1, B1, R2, G2, B2, R3, G3, B3, R4, G4, B4
  yuzhi = value
  jianju = Interval
  if yuzhi < 0 then
    yuzhi = 0
  end
  if 255 < yuzhi then
    yuzhi = 255
  end
  if 255 < jianju then
    jianju = 255
  end
  if jianju <= 0 then
    jianju = 1
    n = false
  else
    n = true
  end
  local width = #ImgData
  if width <= 0 then
    return
  end
  local height = #ImgData[1]
  if height <= 0 then
    return
  end
  local TmpData = {}
  for W = 1, width do
    TmpData[W] = {}
    for H = 1, height do
      TmpData[W][H] = {
        b,
        g,
        r
      }
    end
  end
  width = width - jianju
  height = height - jianju
  for W = 1, width do
    for H = 1, height do
      R1 = ImgData[W][H][3]
      G1 = ImgData[W][H][2]
      B1 = ImgData[W][H][1]
      R2 = ImgData[W + jianju][H][3]
      G2 = ImgData[W + jianju][H][2]
      B2 = ImgData[W + jianju][H][1]
      R3 = ImgData[W][H + jianju][3]
      G3 = ImgData[W][H + jianju][2]
      B3 = ImgData[W][H + jianju][1]
      Flag = false
      if yuzhi <= (math.abs(R1 - R2) + math.abs(G1 - G2) + math.abs(B1 - B2)) / 3 then
        Flag = true
        TmpData[W + jianju][H] = {
          ImgData[W + jianju][H][1],
          ImgData[W + jianju][H][2],
          ImgData[W + jianju][H][3]
        }
      end
      if yuzhi <= (math.abs(R1 - R3) + math.abs(G1 - G3) + math.abs(B1 - B3)) / 3 then
        Flag = true
        TmpData[W][H + jianju] = {
          ImgData[W][H + jianju][1],
          ImgData[W][H + jianju][2],
          ImgData[W][H + jianju][3]
        }
      end
      if n == true then
        R4 = ImgData[W + jianju][H + jianju][3]
        G4 = ImgData[W + jianju][H + jianju][2]
        B4 = ImgData[W + jianju][H + jianju][1]
        if yuzhi <= (math.abs(R1 - R4) + math.abs(G1 - G4) + math.abs(B1 - B4)) / 3 then
          Flag = true
          TmpData[W + jianju][H + jianju] = {
            ImgData[W + jianju][H + jianju][1],
            ImgData[W + jianju][H + jianju][2],
            ImgData[W + jianju][H + jianju][3]
          }
        end
      end
      if Flag == true then
        TmpData[W][H] = {
          ImgData[W][H][1],
          ImgData[W][H][2],
          ImgData[W][H][3]
        }
      end
    end
  end
  ImgData = {}
  ImgData = DeepCopy(TmpData)
  return ImgData
end

TURING["滤镜_黑白_获取轮廓"] = function(value, Interval, r, g, b)
  return TURING.Filter_Outline(value, Interval, r, g, b)
end

function TURING.Filter_Outline(value, Interval, r, g, b)
  local width = #ImgData
  if width <= 0 then
    return
  end
  local height = #ImgData[1]
  if height <= 0 then
    return
  end
  if value ~= nil and 0 < value then
    if Interval == nil then
      Interval = 1
    end
    if r == nil then
      r = 255
    end
    if g == nil then
      g = 0
    end
    if b == nil then
      b = 0
    end
    return Filter_Outline(value, Interval, r, g, b)
  end
  local W, H
  local TmpData = {}
  for W = 1, width do
    TmpData[W] = {}
    for H = 1, height do
      if 1 < W and 1 < H and W < width and H < height then
        local c = ImgData[W][H][1]
        if c == 255 then
          if ImgData[W - 1][H][1] == c and ImgData[W][H - 1][1] == c and ImgData[W + 1][H][1] == c and ImgData[W][H + 1][1] == c then
            TmpData[W][H] = {
              0,
              0,
              0
            }
          else
            TmpData[W][H] = {
              255,
              255,
              255
            }
          end
        else
          TmpData[W][H] = {
            0,
            0,
            0
          }
        end
      else
        TmpData[W][H] = {
          0,
          0,
          0
        }
      end
    end
  end
  ImgData = {}
  ImgData = DeepCopy(TmpData)
  return ImgData
end

TURING["滤镜_黑白_颠倒颜色"] = function(Inverse)
  return TURING.Filter_InverseColor(Inverse)
end

function TURING.Filter_InverseColor(Inverse)
  local width = #ImgData
  if width <= 0 then
    return
  end
  local height = #ImgData[1]
  if height <= 0 then
    return
  end
  local W, H, HEI, BAI
  if Inverse == 1 or Inverse == nil then
    value = 0
  elseif Inverse == 0 then
    value = 255
  else
    value = 178
  end
  if value == 0 or value == 255 then
    HEI = 0
    BAI = 0
    for H = 1, height do
      for W = 1, width do
        if ImgData[W][H][1] == value then
          HEI = HEI + 1
        else
          BAI = BAI + 1
        end
      end
    end
    if (HEI ~= 0 or BAI ~= 0) and HEI < BAI then
      for H = 1, height do
        for W = 1, width do
          if ImgData[W][H][1] == value then
            ImgData[W][H] = {
              255 - value,
              255 - value,
              255 - value
            }
          else
            ImgData[W][H] = {
              value,
              value,
              value
            }
          end
        end
      end
    end
  else
    for H = 1, height do
      for W = 1, width do
        if ImgData[W][H][1] == 0 then
          ImgData[W][H] = {
            255,
            255,
            255
          }
        else
          ImgData[W][H] = {
            0,
            0,
            0
          }
        end
      end
    end
  end
  return ImgData
end

TURING["滤镜_黑白_腐蚀膨胀"] = function(Inverse)
  return TURING.Filter_DilationErosion(Inverse)
end

function TURING.Filter_DilationErosion(Inverse)
  local width = #ImgData
  if width <= 0 then
    return
  end
  local height = #ImgData[1]
  if height <= 0 then
    return
  end
  local value, m
  if Inverse == 1 or Inverse == nil then
    value = 0
  else
    value = 255
  end
  local TempData = {}
  for W = 1, width do
    TempData[W] = {}
    for H = 1, height do
      m = 0
      TempData[W][H] = {
        ImgData[W][H][1],
        ImgData[W][H][2],
        ImgData[W][H][3]
      }
      if ImgData[W][H][1] == value then
        for Y = 0, 1 do
          for X = 0, 1 do
            if width < W + X or height < H + Y then
              break
            end
            if ImgData[W + X][H + Y][1] == 255 - value then
              m = 1
            end
          end
        end
        if m == 1 then
          TempData[W][H] = {
            255 - value,
            255 - value,
            255 - value
          }
        end
      end
    end
  end
  ImgData = TempData
  return ImgData
end

TURING["滤镜_通用_等比缩放"] = function(ZoomX, ZoomY)
  return TURING.Filter_Zoom(ZoomX, ZoomY)
end

function TURING.Filter_Zoom(ZoomX, ZoomY)
  local width = #ImgData
  if width <= 0 then
    return
  end
  local height = #ImgData[1]
  if height <= 0 then
    return
  end
  local W, H, X, Y, ZoomW, ZoomH
  if ZoomX == null then
    ZoomX = 1
  end
  if ZoomY == null then
    ZoomY = 1
  end
  if 10 < ZoomX or 10 < ZoomY or ZoomX <= 0 or ZoomY <= 0 then
    return ImgData
  end
  if ZoomX < 1 then
    if ZoomX == 0.5 then
      ZoomW = 2
    elseif ZoomX == 0.25 then
      ZoomW = 4
    elseif ZoomX == 0.125 then
      ZoomW = 8
    else
      ZoomW = 1
    end
  else
    ZoomW = ZoomX
  end
  if ZoomY < 1 then
    if ZoomY == 0.5 then
      ZoomH = 2
    elseif ZoomY == 0.25 then
      ZoomH = 4
    elseif ZoomY == 0.125 then
      ZoomH = 8
    else
      ZoomH = 1
    end
  else
    ZoomH = ZoomY
  end
  local TmpData = {}
  if ZoomX == 1 and ZoomY == 1 then
    return ImgData
  elseif ZoomX < 1 and ZoomY < 1 then
    X = 1
    for W = 1, width, ZoomW do
      TmpData[X] = {}
      Y = 1
      for H = 1, height, ZoomH do
        TmpData[X][Y] = {
          ImgData[W][H][1],
          ImgData[W][H][2],
          ImgData[W][H][3]
        }
        Y = Y + 1
      end
      X = X + 1
    end
  elseif 1 <= ZoomX and 1 <= ZoomY then
    for W = 1, width do
      for X = 0, ZoomW - 1 do
        TmpData[(W - 1) * ZoomX + X + 1] = {}
        for H = 1, height do
          for Y = 0, ZoomH - 1 do
            TmpData[(W - 1) * ZoomX + X + 1][(H - 1) * ZoomY + Y + 1] = {
              ImgData[W][H][1],
              ImgData[W][H][2],
              ImgData[W][H][3]
            }
          end
        end
      end
    end
  elseif 1 <= ZoomX and ZoomY < 1 then
    for W = 1, width do
      for X = 0, ZoomW - 1 do
        TmpData[(W - 1) * ZoomX + X + 1] = {}
        Y = 0
        for H = 1, height, ZoomH do
          TmpData[(W - 1) * ZoomX + X + 1][Y] = {
            ImgData[W][H][1],
            ImgData[W][H][2],
            ImgData[W][H][3]
          }
          Y = Y + 1
        end
      end
    end
  elseif ZoomX < 1 and 1 <= ZoomY then
    X = 0
    for W = 1, width, ZoomW do
      TmpData[X] = {}
      for H = 1, height do
        for Y = 0, ZoomH - 1 do
          TmpData[X][(H - 1) * ZoomY + Y + 1] = {
            ImgData[W][H][1],
            ImgData[W][H][2],
            ImgData[W][H][3]
          }
        end
      end
      X = X + 1
    end
  end
  ImgData = {}
  ImgData = DeepCopy(TmpData)
  return ImgData
end

TURING["滤镜_通用_等比缩小"] = function(ZoomX, ZoomY)
  return TURING.Filter_ZoomOut(ZoomX, ZoomY)
end

function TURING.Filter_ZoomOut(ZoomX, ZoomY)
  local width = #ImgData
  if width <= 0 then
    return
  end
  local height = #ImgData[1]
  if height <= 0 then
    return
  end
  local W, H, X, Y, ZX, ZY
  if ZoomX == nil then
    ZX = 1
  else
    ZX = ZoomX
  end
  if ZoomY == nil then
    ZY = 1
  else
    ZY = ZoomY
  end
  if 8 < ZX then
    ZX = 8
  end
  if 8 < ZY then
    ZY = 8
  end
  local TmpData = {}
  X = 1
  for W = 1, width, ZX do
    TmpData[X] = {}
    Y = 1
    for H = 1, height, ZY do
      TmpData[X][Y] = {
        ImgData[W][H][1],
        ImgData[W][H][2],
        ImgData[W][H][3]
      }
      if TmpData[X][Y][1] == nil then
        TmpData[X][Y][1] = 0
      end
      if TmpData[X][Y][2] == nil then
        TmpData[X][Y][2] = 0
      end
      if TmpData[X][Y][3] == nil then
        TmpData[X][Y][3] = 0
      end
      Y = Y + 1
    end
    X = X + 1
  end
  ImgData = {}
  ImgData = DeepCopy(TmpData)
  return ImgData
end

TURING["滤镜_通用_等比放大"] = function(ZoomX, ZoomY)
  return TURING.Filter_ZoomIn(ZoomX, ZoomY)
end

function TURING.Filter_ZoomIn(ZoomX, ZoomY)
  local width = #ImgData
  if width <= 0 then
    return
  end
  local height = #ImgData[1]
  if height <= 0 then
    return
  end
  local W, H, X, Y, ZX, ZY
  if ZoomX == 2 then
    ZX = 2
  else
    ZX = 1
  end
  if ZoomY == 2 then
    ZY = 2
  else
    ZY = 1
  end
  local TmpData = {}
  X = 1
  for W = 1, width * ZX, ZX do
    TmpData[W] = {}
    if ZX == 2 then
      TmpData[W + 1] = {}
    end
    Y = 1
    for H = 1, height * ZY, ZY do
      TmpData[W][H] = {
        ImgData[X][Y][1],
        ImgData[X][Y][2],
        ImgData[X][Y][3]
      }
      if ZX == 2 and ZY == 2 then
        TmpData[W][H + 1] = {
          ImgData[X][Y][1],
          ImgData[X][Y][2],
          ImgData[X][Y][3]
        }
        TmpData[W + 1][H] = {
          ImgData[X][Y][1],
          ImgData[X][Y][2],
          ImgData[X][Y][3]
        }
        TmpData[W + 1][H + 1] = {
          ImgData[X][Y][1],
          ImgData[X][Y][2],
          ImgData[X][Y][3]
        }
      elseif ZX == 2 then
        TmpData[W + 1][H] = {
          ImgData[X][Y][1],
          ImgData[X][Y][2],
          ImgData[X][Y][3]
        }
      elseif ZY == 2 then
        TmpData[W][H + 1] = {
          ImgData[X][Y][1],
          ImgData[X][Y][2],
          ImgData[X][Y][3]
        }
      end
      Y = Y + 1
    end
    X = X + 1
  end
  ImgData = {}
  ImgData = DeepCopy(TmpData)
  return ImgData
end

TURING["滤镜_通用_缩放归一化"] = function(ZoomW, ZoomH, Model)
  return TURING.Filter_ZoomOne(ZoomW, ZoomH, Model)
end

function TURING.Filter_ZoomOne(ZoomW, ZoomH, Model)
  local width = #ImgData
  if width <= 0 then
    return
  end
  local height = #ImgData[1]
  if height <= 0 then
    return
  end
  local W, H, Temp, m, n, x, y, BeginX, BeginY, EndX, EndY, CurStep, NxtStep, NegN, CurC0, NxtC0, DCt0, DC0, CurC1, NxtC1, DCt1, DC1, CurC2, NxtC2, DCt2, DC2
  if ZoomW == null or ZoomW <= 0 then
    return
  end
  if ZoomH == null or ZoomH <= 0 then
    return
  end
  if Model == null or Model <= 0 then
    Model = 0
  else
    Model = 1
  end
  local iWidth, iHeight
  if Model == 1 then
    iWidth = NewRound(width * (ZoomW / 100))
    iHeight = NewRound(height * (ZoomH / 100))
  else
    iWidth = ZoomW
    iHeight = ZoomH
  end
  local TempData = {}
  local OverData = {}
  local RatioX, RatioY
  if width == 1 then
    RatioX = 0
  else
    RatioX = iWidth / width
  end
  if height == 1 then
    RatioY = 0
  else
    RatioY = iHeight / height
  end
  NegN = math.floor(1 / (RatioY + 1))
  for W = 1, width do
    TempData[W] = {}
    if height == 1 then
      for H = 1, iHeight do
        TempData[W][H] = {
          ImgData[W][1][1],
          ImgData[W][1][2],
          ImgData[W][1][3]
        }
      end
    else
      CurC0 = ImgData[W][1][1]
      CurC1 = ImgData[W][1][2]
      CurC2 = ImgData[W][1][3]
      CurStep = 1
      NxtStep = 1
      for H = 1, height - 1 do
        NxtStep = CurStep + RatioY
        BeginY = math.floor(CurStep)
        EndY = math.floor(NxtStep)
        n = NxtStep - CurStep
        TempData[W][BeginY] = {
          CurC0,
          CurC1,
          CurC2
        }
        m = H + 1
        NxtC0 = ImgData[W][m][1]
        NxtC1 = ImgData[W][m][2]
        NxtC2 = ImgData[W][m][3]
        if 1 < n then
          DCt0 = (NxtC0 - CurC0) * NegN
          DCt1 = (NxtC1 - CurC1) * NegN
          DCt2 = (NxtC2 - CurC2) * NegN
          DC0 = 0
          DC1 = 0
          DC2 = 0
          for Temp = BeginY + 1, EndY - 1 do
            DC0 = DC0 + DCt0
            DC1 = DC1 + DCt1
            DC2 = DC2 + DCt2
            TempData[W][Temp] = {
              CurC0 + DC0,
              CurC1 + DC1,
              CurC2 + DC2
            }
          end
        end
        CurStep = NxtStep
        CurC0 = NxtC0
        CurC1 = NxtC1
        CurC2 = NxtC2
      end
      for xx = EndY, iHeight do
        TempData[W][xx] = {
          NxtC0,
          NxtC1,
          NxtC2
        }
      end
    end
  end
  NegN = math.floor(1 / (RatioX + 1))
  for H = 1, iHeight do
    OverData[H] = {}
    if width == 1 then
      for W = 1, iWidth do
        OverData[H][W] = {
          TempData[1][H][1],
          TempData[1][H][2],
          TempData[1][H][3]
        }
      end
    else
      CurC0 = TempData[1][H][1]
      CurC1 = TempData[1][H][2]
      CurC2 = TempData[1][H][3]
      CurStep = 1
      NxtStep = 1
      for W = 1, width - 1 do
        NxtStep = CurStep + RatioX
        BeginX = math.floor(CurStep)
        EndX = math.floor(NxtStep)
        n = NxtStep - CurStep
        OverData[H][BeginX] = {
          CurC0,
          CurC1,
          CurC2
        }
        m = W + 1
        NxtC0 = TempData[m][H][1]
        NxtC1 = TempData[m][H][2]
        NxtC2 = TempData[m][H][3]
        if 1 < n then
          DCt0 = (NxtC0 - CurC0) * NegN
          DCt1 = (NxtC1 - CurC1) * NegN
          DCt2 = (NxtC2 - CurC2) * NegN
          DC0 = 0
          DC1 = 0
          DC2 = 0
          for Temp = BeginX + 1, EndX - 1 do
            DC0 = DC0 + DCt0
            DC1 = DC1 + DCt1
            DC2 = DC2 + DCt2
            OverData[H][Temp] = {
              CurC0 + DC0,
              CurC1 + DC1,
              CurC2 + DC2
            }
          end
        end
        CurStep = NxtStep
        CurC0 = NxtC0
        CurC1 = NxtC1
        CurC2 = NxtC2
      end
      for xx = EndX, iWidth do
        OverData[H][xx] = {
          NxtC0,
          NxtC1,
          NxtC2
        }
      end
    end
  end
  ImgData = {}
  width = iHeight
  height = iWidth
  x = 1
  for W = 1, height do
    ImgData[x] = {}
    y = 1
    for H = 1, width do
      ImgData[x][y] = {
        OverData[H][W][1],
        OverData[H][W][2],
        OverData[H][W][3]
      }
      y = y + 1
    end
    x = x + 1
  end
  return ImgData
end

TURING["滤镜_通用_缩放自适应"] = function(ZoomW, ZoomH)
  return TURING.Filter_ZoomAdaptive(ZoomW, ZoomH)
end

function TURING.Filter_ZoomAdaptive(ZoomW, ZoomH)
  local width = #ImgData
  if width <= 0 then
    return
  end
  local height = #ImgData[1]
  if height <= 0 then
    return
  end
  local W, H
  W = width
  if ZoomW < W then
    W = width * 0.5
    if ZoomW > W then
      W = ZoomW
    end
  elseif ZoomW > W then
    W = width * 2
    if ZoomW < W then
      W = ZoomW
    end
  end
  H = height
  if ZoomH < H then
    H = height * 0.5
    if ZoomH > H then
      H = ZoomH
    end
  elseif ZoomH > H then
    H = height * 2
    if ZoomH < H then
      H = ZoomH
    end
  end
  return TURING.Filter_ZoomOne(W, H, 0)
end

TURING["滤镜_通用_延伸裁剪"] = function(Left, Top, Right, Bottom)
  return TURING.Filter_Tailor(Left, Top, Right, Bottom)
end

function TURING.Filter_Tailor(Left, Top, Right, Bottom)
  local width = #ImgData
  if width <= 0 then
    return
  end
  local height = #ImgData[1]
  if height <= 0 then
    return
  end
  local iLeft, iTop, iRight, iBottom, W, H, WW, HH, X, Y, xx, yy
  iLeft = 0
  iTop = 0
  iRight = Right - Left
  iBottom = Bottom - Top
  if Left < 0 then
    X = 1
    xx = 1 - Left
  else
    X = Left
    xx = 1
  end
  if Top < 0 then
    Y = 1
    yy = 1 - Top
  else
    Y = Top
    yy = 1
  end
  if Right > width then
    WW = width
  else
    WW = Right
  end
  if Bottom > height then
    HH = height
  else
    HH = Bottom
  end
  local TempData = {}
  for W = 1, iRight + 1 do
    TempData[W] = {}
    for H = 1, iBottom + 1 do
      TempData[W][H] = {
        0,
        0,
        0
      }
    end
  end
  local X1, Y1
  X1 = xx
  for W = X, WW do
    Y1 = yy
    for H = Y, HH do
      TempData[X1][Y1] = {
        ImgData[W][H][1],
        ImgData[W][H][2],
        ImgData[W][H][3]
      }
      Y1 = Y1 + 1
    end
    X1 = X1 + 1
  end
  ImgData = {}
  for W = 1, iRight do
    ImgData[W] = {}
    for H = 1, iBottom do
      ImgData[W][H] = {
        TempData[W][H][1],
        TempData[W][H][2],
        TempData[W][H][3]
      }
    end
  end
  R_Right = R_Left + Right
  R_Bottom = R_Top + Bottom
  R_Left = R_Left + Left
  R_Top = R_Top + Top
  return ImgData
end

TURING["滤镜_通用_颜色选留"] = function(feature, bColor)
  return TURING.Filter_ColorChoose(feature, bColor)
end

function TURING.Filter_ColorChoose(feature, bColor)
  local width = #ImgData
  if width <= 0 then
    return
  end
  local height = #ImgData[1]
  if height <= 0 then
    return
  end
  if feature == nil then
    feature = "FFFFFF"
  end
  if bColor == nil then
    bColor = "000000"
  end
  local W, H, fsColor, sColor, i, flag
  if string.sub(feature, 1, 1) == "@" then
    fsColor = 255
    feature = string.sub(feature, 2, #feature)
  else
    fsColor = 0
  end
  local R, G, B, RR, GG, BB, dR, dG, dB
  local range = Split(feature, "|")
  local num = #range
  local ColorData = {}
  for i = 1, num do
    local tempColor = Split(range[i], "-")
    sColor = tonumber(tempColor[1], 16)
    R = math.floor(sColor % 256)
    G = math.floor(sColor % 65536 / 256)
    B = math.floor(sColor / 65536)
    if 1 < #tempColor then
      sColor = tonumber(tempColor[2], 16)
      dR = math.floor(sColor % 256)
      dG = math.floor(sColor % 65536 / 256)
      dB = math.floor(sColor / 65536)
    else
      dR = 0
      dG = 0
      dB = 0
    end
    ColorData[i] = {
      R + dR,
      G + dG,
      B + dB,
      R - dR,
      G - dG,
      B - dB
    }
  end
  sColor = tonumber(bColor, 16)
  RR = math.floor(sColor % 256)
  GG = math.floor(sColor % 65536 / 256)
  BB = math.floor(sColor / 65536)
  for W = 1, width do
    for H = 1, height do
      if fsColor == 0 then
        flag = false
        for i = 1, num do
          if ImgData[W][H][3] >= ColorData[i][4] and ImgData[W][H][3] <= ColorData[i][1] and ImgData[W][H][2] >= ColorData[i][5] and ImgData[W][H][2] <= ColorData[i][2] and ImgData[W][H][1] >= ColorData[i][6] and ImgData[W][H][1] <= ColorData[i][3] then
            flag = true
            break
          end
        end
        if flag == false then
          ImgData[W][H] = {
            BB,
            GG,
            RR
          }
        end
      else
        for i = 1, num do
          if ImgData[W][H][3] >= ColorData[i][4] and ImgData[W][H][3] <= ColorData[i][1] and ImgData[W][H][2] >= ColorData[i][5] and ImgData[W][H][2] <= ColorData[i][2] and ImgData[W][H][1] >= ColorData[i][6] and ImgData[W][H][1] <= ColorData[i][3] then
            ImgData[W][H] = {
              BB,
              GG,
              RR
            }
            break
          end
        end
      end
    end
  end
  return ImgData
end

TURING["滤镜_通用_固定旋转"] = function(angle)
  return TURING.Filter_Rotate(angle)
end

function TURING.Filter_Rotate(angle)
  local width = #ImgData
  if width <= 0 then
    return
  end
  local height = #ImgData[1]
  if height <= 0 then
    return
  end
  local W, H, X, Y, nX, nY, dx, dy, ddX, ddY
  local size = width + height + 2
  local iLeft = size
  local iRight = 1
  local iTop = size
  local iBottom = 1
  local PI = 3.14159265358979
  local dThetaRad = -angle * PI / 180
  R_Model = 0
  local TempData = {}
  if angle == nil or angle == 0 then
    return
  end
  if angle == 90 or angle == -270 then
    X = 1
    for H = height, 1, -1 do
      TempData[X] = {}
      Y = 1
      for W = 1, width do
        TempData[X][Y] = {
          ImgData[W][H][1],
          ImgData[W][H][2],
          ImgData[W][H][3]
        }
        Y = Y + 1
      end
      X = X + 1
    end
    iLeft = 1
    iRight = height
    iTop = 1
    iBottom = width
  elseif angle == -90 or angle == 270 then
    X = 1
    for H = 1, height do
      TempData[X] = {}
      Y = 1
      for W = width, 1, -1 do
        TempData[X][Y] = {
          ImgData[W][H][1],
          ImgData[W][H][2],
          ImgData[W][H][3]
        }
        Y = Y + 1
      end
      X = X + 1
    end
    iLeft = 1
    iRight = height
    iTop = 1
    iBottom = width
    R_Model = 1
  elseif angle == 180 or angle == -180 then
    X = 1
    for W = width, 1, -1 do
      TempData[X] = {}
      Y = 1
      for H = height, 1, -1 do
        TempData[X][Y] = {
          ImgData[W][H][1],
          ImgData[W][H][2],
          ImgData[W][H][3]
        }
        Y = Y + 1
      end
      X = X + 1
    end
    iLeft = 1
    iRight = width
    iTop = 1
    iBottom = height
  else
    for X = 1, size do
      TempData[X] = {}
      nX = math.floor(X - size / 2)
      for Y = 1, size do
        nY = math.floor(Y - size / 2)
        dx = nX * math.cos(-dThetaRad) + nY * math.sin(-dThetaRad) + 0.4
        dy = nY * math.cos(-dThetaRad) - nX * math.sin(-dThetaRad) + 0.4
        ddX = math.floor(dx + (width + 1) / 2)
        ddY = math.floor(dy + (height + 1) / 2)
        if 0 <= ddX and width > ddX and 0 <= ddY and height > ddY then
          if X <= iLeft then
            iLeft = X
          end
          if X >= iRight then
            iRight = X
          end
          if Y <= iTop then
            iTop = Y
          end
          if Y >= iBottom then
            iBottom = Y
          end
          ddX = ddX + 1
          ddY = ddY + 1
          TempData[X][Y] = {
            ImgData[ddX][ddY][1],
            ImgData[ddX][ddY][2],
            ImgData[ddX][ddY][3]
          }
        else
          TempData[X][Y] = {
            0,
            0,
            0
          }
        end
      end
    end
  end
  ImgData = {}
  X = 1
  for W = iLeft, iRight do
    ImgData[X] = {}
    Y = 1
    for H = iTop, iBottom do
      ImgData[X][Y] = {
        TempData[W][H][1],
        TempData[W][H][2],
        TempData[W][H][3]
      }
      Y = Y + 1
    end
    X = X + 1
  end
  return ImgData
end

TURING["滤镜_通用_电影底片"] = function()
  return TURING.Filter_Negative()
end

function TURING.Filter_Negative()
  local width = #ImgData
  if width <= 0 then
    return
  end
  local height = #ImgData[1]
  if height <= 0 then
    return
  end
  for W = 1, width do
    for H = 1, height do
      ImgData[W][H] = {
        255 - ImgData[W][H][1],
        255 - ImgData[W][H][2],
        255 - ImgData[W][H][3]
      }
    end
  end
  return ImgData
end

TURING["滤镜_通用_固定镜像"] = function(value)
  return TURING.Filter_Mirror(value)
end

function TURING.Filter_Mirror(value)
  local width = #ImgData
  if width <= 0 then
    return
  end
  local height = #ImgData[1]
  if width <= 0 or height <= 0 then
    return
  end
  if value == nil then
    value = 0
  end
  local x, y
  local TempData = {}
  if value == 0 then
    x = 1
    for W = width, 1, -1 do
      TempData[x] = {}
      y = 1
      for H = 1, height do
        TempData[x][y] = {
          ImgData[W][H][1],
          ImgData[W][H][2],
          ImgData[W][H][3]
        }
        y = y + 1
      end
      x = x + 1
    end
  elseif value == 1 then
    x = 1
    for W = 1, width do
      TempData[x] = {}
      y = 1
      for H = height, 1, -1 do
        TempData[x][y] = {
          ImgData[W][H][1],
          ImgData[W][H][2],
          ImgData[W][H][3]
        }
        y = y + 1
      end
      x = x + 1
    end
  else
    x = 1
    for W = width, 1, -1 do
      TempData[x] = {}
      y = 1
      for H = height, 1, -1 do
        TempData[x][y] = {
          ImgData[W][H][1],
          ImgData[W][H][2],
          ImgData[W][H][3]
        }
        y = y + 1
      end
      x = x + 1
    end
  end
  ImgData = TempData
  return ImgData
end

TURING["切割_选取指定字符颜色"] = function(ColorValue)
  TURING.Incise_PickColor(ColorValue)
end

function TURING.Incise_PickColor(ColorValue)
  if ColorValue == nil or string.len(ColorValue) < 6 then
    Color_B = 255
    Color_G = 255
    Color_R = 255
  else
    local sColor = tonumber(ColorValue, 16)
    Color_B = math.floor(sColor / 65536)
    Color_G = math.floor(sColor % 65536 / 256)
    Color_R = math.floor(sColor % 256)
  end
end

TURING["切割_固定位置切割"] = function(Qx, Qy, ZW, ZH, Column, cNum, Row, rNum)
  return TURING.Incise_FixedLocation(Qx, Qy, ZW, ZH, Column, cNum, Row, rNum)
end

function TURING.Incise_FixedLocation(Qx, Qy, ZW, ZH, Column, cNum, Row, rNum)
  local width = #ImgData
  if width <= 0 then
    return 0
  end
  local height = #ImgData[1]
  if height <= 0 then
    return 0
  end
  if Column == nil then
    Column = 0
  end
  if cNum == nil then
    cNum = 1
  end
  if Row == nil then
    Row = 0
  end
  if rNum == nil then
    rNum = 1
  end
  local X, Y, W, H, WW, HH, i
  IncData = {}
  i = 1
  Y = Qy
  for HH = 1, rNum do
    X = Qx
    for WW = 1, cNum do
      local Two = {}
      for W = 1, ZW do
        if width < W + X then
          break
        end
        local One = {}
        for H = 1, ZH do
          if height < H + Qy then
            break
          end
          if ImgData[W + X][H + Y][1] == Color_B and ImgData[W + X][H + Y][2] == Color_G and ImgData[W + X][H + Y][3] == Color_R then
            One[H] = "1"
          else
            One[H] = "0"
          end
        end
        Two[W] = string.sub(table.concat(One, "") .. string.rep("0", ZH), 1, ZH)
      end
      local n = ZW * ZH
      local iLeft = X
      local iTop = Y
      local iWidth = ZW
      local iHeight = ZH
      local sLattice = string.sub(table.concat(Two, "") .. string.rep("0", n), 1, n)
      IncData[i] = {
        iLeft,
        iTop,
        iWidth,
        iHeight,
        sLattice
      }
      i = i + 1
      X = X + Column
    end
    Y = Y + Row
  end
  return cNum * rNum
end

TURING["切割_随机方位切割"] = function(Vlaue, sWidth, sHeight)
  return TURING.Incise_RandomOrientation(Vlaue, sWidth, sHeight)
end

function TURING.Incise_RandomOrientation(Vlaue, sWidth, sHeight)
  local width = #ImgData
  if width <= 0 then
    return 0
  end
  local height = #ImgData[1]
  if height <= 0 then
    return 0
  end
  if Vlaue == nil then
    Vlaue = 0
  end
  if sWidth == nil then
    sWidth = "0"
  end
  if sHeight == nil then
    sHeight = "0"
  end
  local W, H, Zw, Zh, QX, QY, bo, j, Point, n, m
  local ZYBJ = {}
  local SXBJ = {}
  IncData = {}
  bo = false
  n = 1
  for W = 1, width do
    Point = 0
    for H = 1, height do
      if ImgData[W][H][1] == Color_B and ImgData[W][H][2] == Color_G and ImgData[W][H][3] == Color_R and bo == false then
        Point = Point + 1
        if Vlaue < Point then
          bo = true
          ZYBJ[n] = W
          n = n + 1
          break
        end
      elseif ImgData[W][H][1] == Color_B and ImgData[W][H][2] == Color_G and ImgData[W][H][3] == Color_R and bo == true then
        Point = Point + 1
      end
      if W < width and H >= height and bo == true and Vlaue >= Point then
        bo = false
        ZYBJ[n] = W - 1
        n = n + 1
        break
      end
    end
    if W >= width and bo == true then
      ZYBJ[n] = W
      n = n + 1
      break
    end
  end
  if n < 3 then
    return 0
  end
  m = n - 1
  local i = 0
  n = 1
  for j = 1, m, 2 do
    if j >= m then
      j = m
    end
    Zw = ZYBJ[j + 1] - ZYBJ[j]
    for H = 1, height do
      for W = ZYBJ[j], ZYBJ[j + 1] do
        if ImgData[W][H][1] == Color_B and ImgData[W][H][2] == Color_G and ImgData[W][H][3] == Color_R then
          SXBJ[n] = H
          n = n + 1
          goto lbl_159
        end
      end
    end
    ::lbl_159::
    for H = height, 1, -1 do
      for W = ZYBJ[j], ZYBJ[j + 1] do
        if ImgData[W][H][1] == Color_B and ImgData[W][H][2] == Color_G and ImgData[W][H][3] == Color_R then
          SXBJ[n] = H
          n = n + 1
          goto lbl_191
        end
      end
    end
    ::lbl_191::
    Zh = SXBJ[j + 1] - SXBJ[j]
    local Two = {}
    for QX = 0, Zw do
      local One = {}
      for QY = 0, Zh do
        if ImgData[QX + ZYBJ[j]][QY + SXBJ[j]][1] == Color_B and ImgData[QX + ZYBJ[j]][QY + SXBJ[j]][2] == Color_G and ImgData[QX + ZYBJ[j]][QY + SXBJ[j]][3] == Color_R then
          table.insert(One, "1")
        else
          table.insert(One, "0")
        end
      end
      table.insert(Two, table.concat(One, ""))
    end
    if sWidth == "0" and sHeight == "0" then
    else
      local q, iLfet, iRight, iTop, iBottom
      local FW = {}
      _, q = string.find(sWidth, "-")
      if q == nil then
        q = 0
      end
      if 0 < q then
        FW = Split(sWidth, "-")
        iLfet = tonumber(FW[1])
        iRight = tonumber(FW[2])
      else
        iLfet = 1
        iRight = tonumber(sWidth)
      end
      _, q = string.find(sHeight, "-")
      if q == nil then
        q = 0
      end
      if 0 < q then
        FW = Split(sHeight, "-")
        iTop = tonumber(FW[1])
        iBottom = tonumber(FW[2])
      else
        iTop = 1
        iBottom = tonumber(sHeight)
      end
      if not (iLfet <= Zw + 1 and iRight >= Zw + 1 and iTop <= Zh + 1) or not (iBottom >= Zh + 1) then
        goto lbl_359
      else
        local iLeft = ZYBJ[j]
        local iTop = SXBJ[j]
        local iWidth = Zw + 1
        local iHeight = Zh + 1
        local sLattice = table.concat(Two, "")
        IncData[i + 1] = {
          iLeft,
          iTop,
          iWidth,
          iHeight,
          sLattice
        }
        i = i + 1
      end
    end
    ::lbl_359::
  end
  return i
end

local function imgZhongZiTianChong(Qx, Qy, sColor, isBaxiang)
  local i
  local JiLu = {
    0,
    0,
    0
  }
  local xxTmp = {}
  local yyTmp = {}
  local width = #ImgData
  local height = #ImgData[1]
  local nLeft = width
  local nTop = height
  local nGight = 1
  local nBottom = 1
  local R = math.floor(sColor % 256)
  local G = math.floor(sColor % 65536 / 256)
  local B = math.floor(sColor / 65536)
  if isBaxiang == true then
    xxTmp = {
      1,
      1,
      0,
      -1,
      -1,
      -1,
      0,
      1
    }
    yyTmp = {
      0,
      -1,
      -1,
      -1,
      0,
      1,
      1,
      1
    }
  else
    xxTmp = {
      1,
      0,
      -1,
      0
    }
    yyTmp = {
      0,
      -1,
      0,
      1
    }
  end
  local Num = #xxTmp
  local n = 2
  local t = 0
  local xx = Qx
  local yy = Qy
  t = t + 1
  JiLu[t] = {xx, yy}
  while t ~= 0 do
    xx = JiLu[t][1]
    yy = JiLu[t][2]
    t = t - 1
    if width < xx then
      xx = width
    end
    if height < yy then
      yy = height
    end
    if xx < 1 then
      xx = 1
    end
    if yy < 1 then
      yy = 1
    end
    local BB = ImgData[xx][yy][1]
    local GG = ImgData[xx][yy][2]
    local RR = ImgData[xx][yy][3]
    if RR == Color_B and GG == Color_G and BB == Color_R then
      ImgData[xx][yy] = {
        B,
        G,
        R
      }
      if nLeft > xx then
        nLeft = xx
      end
      if nTop > yy then
        nTop = yy
      end
      if nGight < xx then
        nGight = xx
      end
      if nBottom < yy then
        nBottom = yy
      end
      for i = 1, Num do
        if t == n then
          n = n * 2
          for m = 0, n - 1 do
            JiLu[n + m] = {}
          end
        end
        t = t + 1
        JiLu[t] = {
          xx + xxTmp[i],
          yy + yyTmp[i]
        }
      end
    end
  end
  local QuYU = {}
  QuYU[1] = nLeft
  QuYU[2] = nTop
  QuYU[3] = nGight
  QuYU[4] = nBottom
  return QuYU
end

local function H_TableSort(Min, Max)
  for i = Min, Max - 1 do
    for j = i + 1, Max do
      if IncData[j][2] < IncData[i][2] then
        local temp = {}
        for n = 1, 5 do
          temp[n] = IncData[i][n]
          IncData[i][n] = IncData[j][n]
          IncData[j][n] = temp[n]
        end
      end
    end
  end
  return
end

local function W_TableSort(Min, Max)
  for i = Min, Max - 1 do
    for j = i + 1, Max do
      if IncData[j][1] < IncData[i][1] then
        local temp = {}
        for n = 1, 5 do
          temp[n] = IncData[i][n]
          IncData[i][n] = IncData[j][n]
          IncData[j][n] = temp[n]
        end
      end
    end
  end
  return
end

TURING["切割_连通区域切割"] = function(Through, sWidth, sHeight)
  return TURING.Incise_ConnectedArea(Through, sWidth, sHeight)
end

function TURING.Incise_ConnectedArea(Through, sWidth, sHeight)
  if RandomNow(AgeLimit, StartIn) == false then
    if token == "" then
      return 0
    elseif sAuth_code == "" then
      return 0
    else
      local TMP_Countdown = 600000 + rndNum
      if TMP_Countdown <= os.time() - Countdown then
        local flag = TURING_LICENSE(sAuth_code, sAdd_code)
        Countdown = os.time()
        if flag ~= 1 then
          sAuth_code = ""
          return 0
        end
      end
    end
  end
  local width = #ImgData
  if width <= 0 then
    return 0
  end
  local height = #ImgData[1]
  if height <= 0 then
    return 0
  end
  if Through == nil then
    Through = false
  end
  if sWidth == nil then
    sWidth = "0"
  end
  if sHeight == nil then
    sHeight = "0"
  end
  IncData = {}
  local nLeft = {}
  local nTop = {}
  local nWidth = {}
  local nHeight = {}
  local sColor = {}
  local iColor = 1
  local i = 1
  for W = 1, width do
    for H = 1, height do
      if ImgData[W][H][1] == Color_B and ImgData[W][H][2] == Color_G and ImgData[W][H][3] == Color_R then
        local QuYU = {}
        QuYU = imgZhongZiTianChong(W, H, iColor, Through)
        if 0 <= QuYU[3] - QuYU[1] and 0 <= QuYU[4] - QuYU[2] and width >= QuYU[3] - QuYU[1] and height >= QuYU[4] - QuYU[2] then
          sColor[i] = iColor
          nLeft[i] = QuYU[1]
          nTop[i] = QuYU[2]
          nWidth[i] = QuYU[3] - QuYU[1] + 1
          nHeight[i] = QuYU[4] - QuYU[2] + 1
          i = i + 1
          iColor = iColor + 1
        end
      end
    end
  end
  if i == 1 then
    return 0
  end
  local Num = #sColor
  i = 0
  for j = 1, Num do
    local R = math.floor(sColor[j] % 256)
    local G = math.floor(sColor[j] % 65536 / 256)
    local B = math.floor(sColor[j] / 65536)
    local Two = {}
    for QX = 0, nWidth[j] - 1 do
      local One = {}
      if width < QX + nLeft[j] then
        break
      end
      for QY = 0, nHeight[j] - 1 do
        if height < QY + nTop[j] then
          break
        end
        if ImgData[QX + nLeft[j]][QY + nTop[j]][3] == R and ImgData[QX + nLeft[j]][QY + nTop[j]][2] == G and ImgData[QX + nLeft[j]][QY + nTop[j]][1] == B then
          table.insert(One, "1")
        else
          table.insert(One, "0")
        end
      end
      table.insert(Two, table.concat(One, ""))
    end
    if sWidth == "0" and sHeight == "0" then
    else
      local q, iLfet, iRight, iTop, iBottom
      local FW = {}
      _, q = string.find(sWidth, "-")
      if q == nil then
        q = 0
      end
      if 0 < q then
        FW = Split(sWidth, "-")
        iLfet = tonumber(FW[1])
        iRight = tonumber(FW[2])
      else
        iLfet = 1
        iRight = tonumber(sWidth)
      end
      _, q = string.find(sHeight, "-")
      if q == nil then
        q = 0
      end
      if 0 < q then
        FW = Split(sHeight, "-")
        iTop = tonumber(FW[1])
        iBottom = tonumber(FW[2])
      else
        iTop = 1
        iBottom = tonumber(sHeight)
      end
      if not (iLfet <= nWidth[j] and iRight >= nWidth[j] and iTop <= nHeight[j]) or not (iBottom >= nHeight[j]) then
        goto lbl_342
      else
        local fLeft = nLeft[j]
        local fTop = nTop[j]
        local fWidth = nWidth[j]
        local fHeight = nHeight[j]
        local sLattice = table.concat(Two, "")
        IncData[i + 1] = {
          fLeft,
          fTop,
          fWidth,
          fHeight,
          sLattice
        }
        i = i + 1
      end
    end
    ::lbl_342::
  end
  if 0 < i then
    local n = #IncData
    H_TableSort(1, n)
    local InitialRange = IncData[1][2] + IncData[1][4]
    local m = 1
    for k = 1, n - 1 do
      if InitialRange <= IncData[k + 1][2] then
        InitialRange = IncData[k + 1][2] + IncData[k + 1][4]
        W_TableSort(m, k)
        m = k + 1
      elseif InitialRange < IncData[k + 1][2] + IncData[k + 1][4] then
        InitialRange = IncData[k + 1][2] + IncData[k + 1][4]
      end
    end
    if n > m then
      W_TableSort(m, n)
    end
  end
  return i
end

TURING["切割_范围投影切割"] = function(Row, Column, sWidth, sHeight)
  return TURING.Incise_ScopeAisle(Row, Column, sWidth, sHeight)
end

function TURING.Incise_ScopeAisle(Row, Column, sWidth, sHeight)
  local width = #ImgData - 1
  if width <= 0 then
    return 0
  end
  local height = #ImgData[1] - 1
  if height <= 0 then
    return 0
  end
  local w, h, LuXian, Zw, Zh, x, y, Qx, Qy, Zx, Zy, ix, iy
  if Row == nil or Row < 1 then
    Row = 1
  end
  if Column == nil or Column < 1 then
    Column = 1
  end
  if sWidth == nil then
    sWidth = "0"
  end
  if sHeight == nil then
    sHeight = "0"
  end
  IncData = {}
  local i = 0
  for w = 0, width do
    h = 0
    while height >= h do
      if ImgData[w + 1][h + 1][4] ~= nil and 1 <= ImgData[w + 1][h + 1][4] then
        h = h + ImgData[w + 1][h + 1][4]
      elseif ImgData[w + 1][h + 1][1] == Color_B and ImgData[w + 1][h + 1][2] == Color_G and ImgData[w + 1][h + 1][3] == Color_R then
        Qx = w
        Qy = h
        Zx = Qx + 1
        Zy = Qy + 1
        while true do
          LuXian = true
          for x = 0, Column - 1 do
            for y = 0, Row - 1 do
              if width < Zx + x or height < Zy + y then
                break
              end
              if Zx > width - x then
                Zx = width - x
              end
              if Zy > height - y then
                Zy = height - y
              end
              if ImgData[Zx + x + 1][Zy + y + 1][1] == Color_B and ImgData[Zx + x + 1][Zy + y + 1][2] == Color_G and ImgData[Zx + x + 1][Zy + y + 1][3] == Color_R then
                Zx = Zx + 1
                Zy = Zy + 1
                LuXian = false
              end
            end
          end
          if LuXian ~= true then
          else
            if LuXian == true then
              for y = 0, Row - 1 do
                for ix = Qx + 1, Zx + Column - 1 do
                  if ix > width or Qy - y - 1 < 0 then
                    break
                  end
                  if ImgData[ix + 1][Qy - y - 1 + 1][1] == Color_B and ImgData[ix + 1][Qy - y - 1 + 1][2] == Color_G and ImgData[ix + 1][Qy - y - 1 + 1][3] == Color_R then
                    Qy = Qy - 1
                    LuXian = false
                    break
                  end
                end
                if LuXian == false then
                  break
                end
              end
            end
            if LuXian == true then
              for x = 0, Column - 1 do
                for iy = Zy - 1, Qy, -1 do
                  if iy < 0 or iy > height or width < Zx + x then
                    break
                  end
                  if ImgData[Zx + x + 1][iy + 1][1] == Color_B and ImgData[Zx + x + 1][iy + 1][2] == Color_G and ImgData[Zx + x + 1][iy + 1][3] == Color_R then
                    Zx = Zx + 1
                    LuXian = false
                    break
                  end
                end
                if LuXian == false then
                  break
                end
              end
            end
            if LuXian == true then
              for y = 0, Row - 1 do
                for ix = Zx - 1, Qx, -1 do
                  if ix < 0 or ix > width or height < Zy + y then
                    break
                  end
                  if ImgData[ix + 1][Zy + y + 1][1] == Color_B and ImgData[ix + 1][Zy + y + 1][2] == Color_G and ImgData[ix + 1][Zy + y + 1][3] == Color_R then
                    Zy = Zy + 1
                    LuXian = false
                    break
                  end
                end
                if LuXian == false then
                  break
                end
              end
            end
            if LuXian == true then
              break
            end
          end
        end
        Zw = Zx - Qx - 1
        Zh = Zy - Qy - 1
        local Two = {}
        for x = 0, Zw do
          local One = {}
          for y = 0, Zh do
            if ImgData[Qx + x + 1][Qy + y + 1][1] == Color_B and ImgData[Qx + x + 1][Qy + y + 1][2] == Color_G and ImgData[Qx + x + 1][Qy + y + 1][3] == Color_R then
              ImgData[Qx + x + 1][Qy + y + 1] = {
                0,
                0,
                0
              }
              table.insert(One, "1")
            else
              table.insert(One, "0")
            end
            ImgData[Qx + x + 1][Qy + y + 1][4] = Zh - y
          end
          table.insert(Two, table.concat(One, ""))
        end
        if sWidth == "0" and sHeight == "0" then
        else
          local q, iLfet, iRight, iTop, iBottom
          local FW = {}
          _, q = string.find(sWidth, "-")
          if q == nil then
            q = 0
          end
          if 0 < q then
            FW = Split(sWidth, "-")
            iLfet = tonumber(FW[1])
            iRight = tonumber(FW[2])
          else
            iLfet = 1
            iRight = tonumber(sWidth)
          end
          _, q = string.find(sHeight, "-")
          if q == nil then
            q = 0
          end
          if 0 < q then
            FW = Split(sHeight, "-")
            iTop = tonumber(FW[1])
            iBottom = tonumber(FW[2])
          else
            iTop = 1
            iBottom = tonumber(sHeight)
          end
          if not (iLfet <= Zw + 1 and iRight >= Zw + 1 and iTop <= Zh + 1) or not (iBottom >= Zh + 1) then
            goto lbl_498
          else
            local fLeft = Qx
            local fTop = Qy
            local fWidth = Zw + 1
            local fHeight = Zh + 1
            local sLattice = table.concat(Two, "")
            IncData[i + 1] = {
              fLeft,
              fTop,
              fWidth,
              fHeight,
              sLattice
            }
            i = i + 1
          end
        end
      end
      ::lbl_498::
      h = h + 1
    end
  end
  if 0 < i then
    local n = #IncData
    H_TableSort(1, n)
    local InitialRange = IncData[1][2] + IncData[1][4]
    local m = 1
    for k = 1, n - 1 do
      if InitialRange <= IncData[k + 1][2] then
        InitialRange = IncData[k + 1][2] + IncData[k + 1][4]
        W_TableSort(m, k)
        m = k + 1
      elseif InitialRange < IncData[k + 1][2] + IncData[k + 1][4] then
        InitialRange = IncData[k + 1][2] + IncData[k + 1][4]
      end
    end
    if n > m then
      W_TableSort(m, n)
    end
  end
  return i
end

function TURING.Incise_ScopeAisle_back(Row, Column, sWidth, sHeight)
  local width = #ImgData - 1
  if width <= 0 then
    return 0
  end
  local height = #ImgData[1] - 1
  if height <= 0 then
    return 0
  end
  local w, h, LuXian, Zw, Zh, x, y, Qx, Qy, Zx, Zy, ix, iy
  if Row == nil or Row < 1 then
    Row = 1
  end
  if Column == nil or Column < 1 then
    Column = 1
  end
  if sWidth == nil then
    sWidth = "0"
  end
  if sHeight == nil then
    sHeight = "0"
  end
  IncData = {}
  local i = 0
  for w = 0, width do
    h = 0
    while height >= h do
      if ImgData[w + 1][h + 1][4] ~= nil and 1 <= ImgData[w + 1][h + 1][4] then
        h = h + ImgData[w + 1][h + 1][4]
      elseif ImgData[w + 1][h + 1][1] == Color_B and ImgData[w + 1][h + 1][2] == Color_G and ImgData[w + 1][h + 1][3] == Color_R then
        Qx = w
        Qy = h
        Zx = Qx + 1
        Zy = Qy + 1
        while true do
          LuXian = true
          for x = 0, Column - 1 do
            for y = 0, Row - 1 do
              if width < Zx + x or height < Zy + y then
                break
              end
              if Zx > width - x then
                Zx = width - x
              end
              if Zy > height - y then
                Zy = height - y
              end
              if ImgData[Zx + x + 1][Zy + y + 1][1] == Color_B and ImgData[Zx + x + 1][Zy + y + 1][2] == Color_G and ImgData[Zx + x + 1][Zy + y + 1][3] == Color_R then
                Zx = Zx + 1
                Zy = Zy + 1
                LuXian = false
              end
            end
          end
          if LuXian ~= true then
          else
            if LuXian == true then
              for y = 0, Row - 1 do
                for ix = Qx + 1, Zx + Column - 1 do
                  if ix > width or Qy - y - 1 < 0 then
                    break
                  end
                  if ImgData[ix + 1][Qy - y - 1 + 1][1] == Color_B and ImgData[ix + 1][Qy - y - 1 + 1][2] == Color_G and ImgData[ix + 1][Qy - y - 1 + 1][3] == Color_R then
                    Qy = Qy - 1
                    LuXian = false
                    break
                  end
                end
                if LuXian == false then
                  break
                end
              end
            end
            if LuXian == true then
              for x = 0, Column - 1 do
                for iy = Zy - 1, Qy, -1 do
                  if iy < 0 or iy > height or width < Zx + x then
                    break
                  end
                  if ImgData[Zx + x + 1][iy + 1][1] == Color_B and ImgData[Zx + x + 1][iy + 1][2] == Color_G and ImgData[Zx + x + 1][iy + 1][3] == Color_R then
                    Zx = Zx + 1
                    LuXian = false
                    break
                  end
                end
                if LuXian == false then
                  break
                end
              end
            end
            if LuXian == true then
              for y = 0, Row - 1 do
                for ix = Zx - 1, Qx, -1 do
                  if ix < 0 or ix > width or height < Zy + y then
                    break
                  end
                  if ImgData[ix + 1][Zy + y + 1][1] == Color_B and ImgData[ix + 1][Zy + y + 1][2] == Color_G and ImgData[ix + 1][Zy + y + 1][3] == Color_R then
                    Zy = Zy + 1
                    LuXian = false
                    break
                  end
                end
                if LuXian == false then
                  break
                end
              end
            end
            if LuXian == true then
              break
            end
          end
        end
        Zw = Zx - Qx - 1
        Zh = Zy - Qy - 1
        local Two = {}
        for x = 0, Zw do
          local One = {}
          for y = 0, Zh do
            if ImgData[Qx + x + 1][Qy + y + 1][1] == Color_B and ImgData[Qx + x + 1][Qy + y + 1][2] == Color_G and ImgData[Qx + x + 1][Qy + y + 1][3] == Color_R then
              ImgData[Qx + x + 1][Qy + y + 1] = {
                0,
                0,
                0
              }
              table.insert(One, "1")
            else
              table.insert(One, "0")
            end
            ImgData[Qx + x + 1][Qy + y + 1][4] = Zh - y
          end
          table.insert(Two, table.concat(One, ""))
        end
        if sWidth == "0" and sHeight == "0" then
        else
          local q, iLfet, iRight, iTop, iBottom
          local FW = {}
          _, q = string.find(sWidth, "-")
          if q == nil then
            q = 0
          end
          if 0 < q then
            FW = Split(sWidth, "-")
            iLfet = tonumber(FW[1])
            iRight = tonumber(FW[2])
          else
            iLfet = 1
            iRight = tonumber(sWidth)
          end
          _, q = string.find(sHeight, "-")
          if q == nil then
            q = 0
          end
          if 0 < q then
            FW = Split(sHeight, "-")
            iTop = tonumber(FW[1])
            iBottom = tonumber(FW[2])
          else
            iTop = 1
            iBottom = tonumber(sHeight)
          end
          if not (iLfet <= Zw + 1 and iRight >= Zw + 1 and iTop <= Zh + 1) or not (iBottom >= Zh + 1) then
            goto lbl_498
          else
            local fLeft = Qx
            local fTop = Qy
            local fWidth = Zw + 1
            local fHeight = Zh + 1
            local sLattice = table.concat(Two, "")
            IncData[i + 1] = {
              fLeft,
              fTop,
              fWidth,
              fHeight,
              sLattice
            }
            i = i + 1
          end
        end
      end
      ::lbl_498::
      h = h + 1
    end
  end
  if 0 < i then
    local n = #IncData
    H_TableSort(1, n)
    local InitialRange = IncData[1][2] + IncData[1][4]
    local m = 1
    for k = 1, n - 1 do
      if InitialRange <= IncData[k + 1][2] then
        InitialRange = IncData[k + 1][2] + IncData[k + 1][4]
        W_TableSort(m, k)
        m = k + 1
      elseif InitialRange < IncData[k + 1][2] + IncData[k + 1][4] then
        InitialRange = IncData[k + 1][2] + IncData[k + 1][4]
      end
    end
    if n > m then
      W_TableSort(m, n)
    end
  end
  return i
end

function TURING.Incise_ScopeAisle2(Row, Column, sWidth, sHeight)
  if not RandomNow(AgeLimit, StartIn) then
    if token == "" or sAuth_code == "" then
      return 0
    else
      local TMP_Countdown = 600000 + rndNum
      if TMP_Countdown <= os.time() - Countdown then
        local flag = TURING_LICENSE(sAuth_code, sAdd_code)
        Countdown = os.time()
        if flag ~= 1 then
          sAuth_code = ""
          return 0
        end
      end
    end
  end
  local width = #ImgData - 1
  local height = #ImgData[1] and #ImgData[1] - 1 or 0
  if width <= 0 or height <= 0 then
    return 0
  end
  Row = Row or 1
  Column = Column or 1
  sWidth = sWidth or "0"
  sHeight = sHeight or "0"
  IncData = {}
  local i = 0
  for w = 0, width do
    for h = 0, height do
      if ImgData[w + 1] and ImgData[w + 1][h + 1] then
        if ImgData[w + 1][h + 1][4] and 1 <= ImgData[w + 1][h + 1][4] then
          h = h + ImgData[w + 1][h + 1][4] - 1
        elseif ImgData[w + 1][h + 1][1] == Color_B and ImgData[w + 1][h + 1][2] == Color_G and ImgData[w + 1][h + 1][3] == Color_R then
          local Qx, Qy = w, h
          local Zx, Zy = w + 1, h + 1
          local LuXian = false
          repeat
            LuXian = true
            for x = 0, Column - 1 do
              for y = 0, Row - 1 do
                if width < Zx + x or height < Zy + y then
                  break
                end
                if ImgData[Zx + x + 1] and ImgData[Zx + x + 1][Zy + y + 1] and ImgData[Zx + x + 1][Zy + y + 1][1] == Color_B and ImgData[Zx + x + 1][Zy + y + 1][2] == Color_G and ImgData[Zx + x + 1][Zy + y + 1][3] == Color_R then
                  Zx = Zx + 1
                  Zy = Zy + 1
                  LuXian = false
                end
              end
            end
          until LuXian
          local Zw = Zx - Qx - 1
          local Zh = Zy - Qy - 1
          local widthValid, heightValid = true, true
          if sWidth ~= "0" then
            local minW, maxW = sWidth:match("(%d+)-(%d+)")
            if minW then
              widthValid = tonumber(minW) <= Zw + 1 and tonumber(maxW) >= Zw + 1
            else
              widthValid = tonumber(sWidth) == Zw + 1
            end
          end
          if sHeight ~= "0" then
            local minH, maxH = sHeight:match("(%d+)-(%d+)")
            if minH then
              heightValid = tonumber(minH) <= Zh + 1 and tonumber(maxH) >= Zh + 1
            else
              heightValid = tonumber(sHeight) == Zh + 1
            end
          end
          if widthValid and heightValid then
            local Two = {}
            for x = 0, Zw do
              local One = {}
              for y = 0, Zh do
                if ImgData[Qx + x + 1][Qy + y + 1][1] == Color_B and ImgData[Qx + x + 1][Qy + y + 1][2] == Color_G and ImgData[Qx + x + 1][Qy + y + 1][3] == Color_R then
                  ImgData[Qx + x + 1][Qy + y + 1] = {
                    0,
                    0,
                    0
                  }
                  table.insert(One, "1")
                else
                  table.insert(One, "0")
                end
                ImgData[Qx + x + 1][Qy + y + 1][4] = Zh - y
              end
              table.insert(Two, table.concat(One, ""))
            end
            IncData[i + 1] = {
              Qx,
              Qy,
              Zw + 1,
              Zh + 1,
              table.concat(Two, "")
            }
            i = i + 1
          end
        end
      end
    end
  end
  print("分割完", IncData)
  if 0 < i then
  end
  return i
end

function TURING.Incise_ScopeAisle3(Row, Column, sWidth, sHeight)
  if RandomNow(AgeLimit, StartIn) == false then
    if token == "" then
      return 0
    elseif sAuth_code == "" then
      return 0
    else
      local TMP_Countdown = 600000 + rndNum
      if TMP_Countdown <= os.time() - Countdown then
        local flag = TURING_LICENSE(sAuth_code, sAdd_code)
        Countdown = os.time()
        if flag ~= 1 then
          sAuth_code = ""
          return 0
        end
      end
    end
  end
  local width = #ImgData - 1
  if width <= 0 then
    return 0
  end
  local height = #ImgData[1] - 1
  if height <= 0 then
    return 0
  end
  Row = Row or 1
  Column = Column or 1
  sWidth = sWidth or "0"
  sHeight = sHeight or "0"
  IncData = {}
  local i = 0
  local directions = {
    {-1, 0},
    {1, 0},
    {0, -1},
    {0, 1},
    {-1, -1},
    {-1, 1},
    {1, -1},
    {1, 1}
  }
  local visited = {}
  for x = 0, width do
    visited[x] = {}
  end
  for w = 0, width do
    for h = 0, height do
      if not visited[w][h] and ImgData[w + 1][h + 1][1] == Color_B and ImgData[w + 1][h + 1][2] == Color_G and ImgData[w + 1][h + 1][3] == Color_R then
        local minX, maxX = w, w
        local minY, maxY = h, h
        local queue = {
          {w, h}
        }
        visited[w][h] = true
        while 0 < #queue do
          local current = table.remove(queue, 1)
          local x, y = current[1], current[2]
          minX = math.min(minX, x)
          maxX = math.max(maxX, x)
          minY = math.min(minY, y)
          maxY = math.max(maxY, y)
          for _, dir in ipairs(directions) do
            local nx, ny = x + dir[1], y + dir[2]
            if 0 <= nx and width >= nx and 0 <= ny and height >= ny and not visited[nx][ny] and ImgData[nx + 1][ny + 1][1] == Color_B and ImgData[nx + 1][ny + 1][2] == Color_G and ImgData[nx + 1][ny + 1][3] == Color_R then
              visited[nx][ny] = true
              table.insert(queue, {nx, ny})
            end
          end
        end
        local regionWidth = maxX - minX + 1
        local regionHeight = maxY - minY + 1
        local widthValid, heightValid = true, true
        if sWidth ~= "0" then
          local minW, maxW = sWidth:match("(%d+)-(%d+)")
          if minW then
            widthValid = regionWidth >= tonumber(minW) and regionWidth <= tonumber(maxW)
          else
            widthValid = tonumber(sWidth) == regionWidth
          end
        end
        if sHeight ~= "0" then
          local minH, maxH = sHeight:match("(%d+)-(%d+)")
          if minH then
            heightValid = regionHeight >= tonumber(minH) and regionHeight <= tonumber(maxH)
          else
            heightValid = tonumber(sHeight) == regionHeight
          end
        end
        if widthValid and heightValid then
          local regionData = {}
          for x = minX, maxX do
            local rowData = {}
            for y = minY, maxY do
              if ImgData[x + 1][y + 1][1] == Color_B and ImgData[x + 1][y + 1][2] == Color_G and ImgData[x + 1][y + 1][3] == Color_R then
                table.insert(rowData, "1")
                ImgData[x + 1][y + 1] = {
                  0,
                  0,
                  0
                }
              else
                table.insert(rowData, "0")
              end
            end
            table.insert(regionData, table.concat(rowData))
          end
          IncData[i + 1] = {
            minX,
            minY,
            regionWidth,
            regionHeight,
            table.concat(regionData, "")
          }
          i = i + 1
        end
      end
    end
  end
  print(IncData)
  if 0 < i then
    local n = #IncData
    H_TableSort(1, n)
    local InitialRange = IncData[1][2] + IncData[1][4]
    local m = 1
    for k = 1, n - 1 do
      if InitialRange <= IncData[k + 1][2] then
        InitialRange = IncData[k + 1][2] + IncData[k + 1][4]
        W_TableSort(m, k)
        m = k + 1
      elseif InitialRange < IncData[k + 1][2] + IncData[k + 1][4] then
        InitialRange = IncData[k + 1][2] + IncData[k + 1][4]
      end
    end
    if n > m then
      W_TableSort(m, n)
    end
  end
  return i
end

function TURING.Incise_ScopeAisle4(Row, Column, sWidth, sHeight)
  if not RandomNow(AgeLimit, StartIn) then
    if token == "" or sAuth_code == "" then
      return 0
    else
      local TMP_Countdown = 600000 + rndNum
      if TMP_Countdown <= os.time() - Countdown then
        local flag = TURING_LICENSE(sAuth_code, sAdd_code)
        Countdown = os.time()
        if flag ~= 1 then
          sAuth_code = ""
          return 0
        end
      end
    end
  end
  local width = #ImgData - 1
  local height = #ImgData[1] and #ImgData[1] - 1 or 0
  if width <= 0 or height <= 0 then
    return 0
  end
  Row = Row or 1
  Column = Column or 1
  sWidth = sWidth or "0"
  sHeight = sHeight or "0"
  IncData = {}
  local i = 0
  local directions = {
    {-1, 0},
    {1, 0},
    {0, -1},
    {0, 1},
    {-1, -1},
    {-1, 1},
    {1, -1},
    {1, 1}
  }
  local visited = {}
  for x = 0, width do
    visited[x] = {}
  end
  for w = 0, width do
    for h = 0, height do
      if not visited[w][h] and ImgData[w + 1][h + 1][1] == Color_B and ImgData[w + 1][h + 1][2] == Color_G and ImgData[w + 1][h + 1][3] == Color_R then
        local minX, maxX = w, w
        local minY, maxY = h, h
        local queue = {
          {w, h}
        }
        visited[w][h] = true
        while 0 < #queue do
          local current = table.remove(queue, 1)
          local x, y = current[1], current[2]
          minX = math.min(minX, x)
          maxX = math.max(maxX, x)
          minY = math.min(minY, y)
          maxY = math.max(maxY, y)
          for _, dir in ipairs(directions) do
            local nx, ny = x + dir[1], y + dir[2]
            if 0 <= nx and width >= nx and 0 <= ny and height >= ny and not visited[nx][ny] and ImgData[nx + 1][ny + 1][1] == Color_B and ImgData[nx + 1][ny + 1][2] == Color_G and ImgData[nx + 1][ny + 1][3] == Color_R then
              visited[nx][ny] = true
              table.insert(queue, {nx, ny})
            end
          end
        end
        local regionWidth = maxX - minX + 1
        local regionHeight = maxY - minY + 1
        local widthValid, heightValid = true, true
        if sWidth ~= "0" then
          local minW, maxW = sWidth:match("(%d+)-(%d+)")
          if minW then
            widthValid = regionWidth >= tonumber(minW) and regionWidth <= tonumber(maxW)
          else
            widthValid = tonumber(sWidth) == regionWidth
          end
        end
        if sHeight ~= "0" then
          local minH, maxH = sHeight:match("(%d+)-(%d+)")
          if minH then
            heightValid = regionHeight >= tonumber(minH) and regionHeight <= tonumber(maxH)
          else
            heightValid = tonumber(sHeight) == regionHeight
          end
        end
        if widthValid and heightValid then
          local regionData = {}
          for x = minX, maxX do
            local rowData = {}
            for y = minY, maxY do
              if ImgData[x + 1][y + 1][1] == Color_B and ImgData[x + 1][y + 1][2] == Color_G and ImgData[x + 1][y + 1][3] == Color_R then
                table.insert(rowData, "1")
                ImgData[x + 1][y + 1] = {
                  0,
                  0,
                  0
                }
              else
                table.insert(rowData, "0")
              end
            end
            table.insert(regionData, table.concat(rowData))
          end
          IncData[i + 1] = {
            minX,
            minY,
            regionWidth,
            regionHeight,
            table.concat(regionData, "")
          }
          i = i + 1
        end
      end
    end
  end
  if 0 < i then
    local n = #IncData
    H_TableSort(1, n)
    local InitialRange = IncData[1][2] + IncData[1][4]
    local m = 1
    for k = 1, n - 1 do
      if InitialRange <= IncData[k + 1][2] then
        InitialRange = IncData[k + 1][2] + IncData[k + 1][4]
        W_TableSort(m, k)
        m = k + 1
      elseif InitialRange < IncData[k + 1][2] + IncData[k + 1][4] then
        InitialRange = IncData[k + 1][2] + IncData[k + 1][4]
      end
    end
    if n > m then
      W_TableSort(m, n)
    end
  end
  return i
end

local function Other_pointData(iWidth, iHeight, sLattice)
  local W, H, n
  local TempData = {}
  n = 0
  for W = 1, iWidth do
    TempData[W] = {}
    for H = 1, iHeight do
      if string.sub(sLattice, n + H, n + H) == "1" then
        TempData[W][H] = {
          255,
          255,
          255
        }
      else
        TempData[W][H] = {
          0,
          0,
          0
        }
      end
    end
    n = n + iHeight
  end
  return TempData
end

TURING["切割_像素数据预览"] = function(index)
  TURING.Incise_Preview(index)
end

function TURING.Incise_Preview(index)
  local W, H
  if #IncData <= 0 then
    return
  end
  if index == nil or index < 1 then
    index = 1
  end
  local width = IncData[index][3]
  local height = IncData[index][4]
  local sLattice = IncData[index][5]
  if width <= 0 or height <= 0 then
    return
  end
  local TempData = Other_pointData(width, height, sLattice)
  local imgPath = getSdPath() .. "/temp.bmp"
  SaveImageData(TempData, imgPath)
  local id = createHUD()
  showHUD(id, "", 0, "", imgPath, 1, 0, 0, 0, 0)
  sleep(5000)
  hideHUD(id)
end

TURING["切割_获取切割字符数据"] = function()
  return TURING.Incise_GetCharData()
end

function TURING.Incise_GetCharData()
  return IncData
end

local function isFind(iText, iList)
  local nT = string.len(iText)
  local nL = string.len(iList)
  local i = 1
  for L = 1, nL do
    for T = i, nT do
      if string.byte(iList, L) == string.byte(iText, T) then
        i = T + 1
        break
      end
    end
  end
  if nL < i then
    return true
  else
    return false
  end
end

local function TryRemoveUtf8BOM(ret)
  if string.byte(ret, 1) == 239 and string.byte(ret, 2) == 187 and string.byte(ret, 3) == 191 then
    ret = string.char(string.byte(ret, 4, string.len(ret)))
  end
  return ret
end

local function SpecialCharacters(Characters)
  local B = {}
  for i = 1, string.len(Characters) do
    local ret = string.sub(Characters, i, i)
    if ret == "^" then
      table.insert(B, "%^")
    elseif ret == "$" then
      table.insert(B, "%$")
    elseif ret == "(" then
      table.insert(B, "%(")
    elseif ret == ")" then
      table.insert(B, "%)")
    elseif ret == "%" then
      table.insert(B, "%%")
    elseif ret == "." then
      table.insert(B, "%.")
    elseif ret == "[" then
      table.insert(B, "%[")
    elseif ret == "]" then
      table.insert(B, "%]")
    elseif ret == "*" then
      table.insert(B, "%*")
    elseif ret == "+" then
      table.insert(B, "%+")
    elseif ret == "-" then
      table.insert(B, "%-")
    elseif ret == "?" then
      table.insert(B, "%?")
    else
      table.insert(B, ret)
    end
  end
  return table.concat(B, "")
end

TURING["字库_加载识别字库"] = function(LibPath, iText)
  return TURING.Lib_Load(LibPath, iText)
end

function TURING.Lib_Load(LibPath, iText)
  local Num, k, i, n, listLib, size
  local file = io.open(LibPath, "r")
  if file ~= nil then
    if iText == nil then
      iText = ""
    end
    LibData = {}
    local data = file:read("*all")
    listLib = Split(TryRemoveUtf8BOM(data), "\r\n")
    Num = #listLib
    n = 1
    for i = 1, Num do
      if listLib[i] ~= "" then
        local list = Split(listLib[i], "|")
        if string.find(iText, SpecialCharacters(list[1])) ~= nil or iText == "" then
          if 3 < #list then
            local One = {}
            for k = 1, #list - 2 do
              table.insert(One, list[k])
            end
            list[1] = table.concat(One, "|")
            size = Split(list[#list - 1], ",")
          else
            size = Split(list[2], ",")
          end
          LibData[n] = {
            list[1],
            size[1],
            size[2],
            list[#list]
          }
          n = n + 1
        end
      end
    end
    file:close()
  else
    return 0
  end
  return n - 1
end

TURING["字库_加载识别字库_扩展"] = function(listLib, iText)
  return TURING.Lib_LoadEx(listLib, iText)
end

function TURING.Lib_LoadEx(listLib, iText)
  local Num, k, i, n, size
  Num = #listLib
  if Num <= 0 then
    return 0
  end
  if iText == nil then
    iText = ""
  end
  LibData = {}
  n = 1
  for i = 1, Num do
    if listLib[i] ~= "" then
      local list = Split(listLib[i], "|")
      if string.find(iText, SpecialCharacters(list[1])) ~= nil or iText == "" then
        if 3 < #list then
          local One = {}
          for k = 1, #list - 2 do
            table.insert(One, list[k])
          end
          list[1] = table.concat(One, "|")
          size = Split(list[#list - 1], ",")
        else
          size = Split(list[2], ",")
        end
        LibData[n] = {
          list[1],
          size[1],
          size[2],
          list[#list]
        }
        n = n + 1
      end
    end
  end
  return n - 1
end

TURING["字库_加载十进制识别字库"] = function(listLib, iText)
  local Num, k, i, n, lib, size
  Num = #listLib
  if Num <= 0 then
    return 0
  end
  if iText == nil then
    iText = ""
  end
  LibData = {}
  n = 1
  for i = 1, Num do
    if listLib[i] ~= "" then
      local list = Split(listLib[i], "|")
      if string.find(iText, SpecialCharacters(list[1])) ~= nil or iText == "" then
        if 3 < #list then
          local One = {}
          for k = 1, #list - 2 do
            table.insert(One, list[k])
          end
          list[1] = table.concat(One, "|")
          size = Split(list[#list - 1], ",")
        else
          size = Split(list[2], ",")
        end
        lib = list[#list]
        LibData[n] = {
          list[1],
          size[1],
          size[2],
          lib
        }
        n = n + 1
      end
    end
  end
  return n - 1
end
TURING["字库_加载六十四进制识别字库"] = function(listLib, iText)
  return TURING.Lib_LoadBase64(listLib, iText)
end

function TURING.Lib_LoadBase64(listLib, iText)
  local Num, k, i, n, lib, size
  Num = #listLib
  if Num <= 0 then
    return 0
  end
  if iText == nil then
    iText = ""
  end
  LibData = {}
  n = 1
  for i = 1, Num do
    if listLib[i] ~= "" then
      local list = Split(listLib[i], "|")
      if string.find(iText, SpecialCharacters(list[1])) ~= nil or iText == "" then
        if 3 < #list then
          local One = {}
          for k = 1, #list - 2 do
            table.insert(One, list[k])
          end
          list[1] = table.concat(One, "|")
          size = Split(list[#list - 1], ",")
        else
          size = Split(list[2], ",")
        end
        lib = TURING.Math_Base64toBIN(list[#list])
        lib = string.sub(lib, string.len(lib) - size[1] * size[2] + 1, string.len(lib))
        LibData[n] = {
          list[1],
          size[1],
          size[2],
          lib
        }
        n = n + 1
      end
    end
  end
  return n - 1
end

TURING["字库_加载十六进制识别字库_扩展"] = function(listLib, iText)
  return TURING.Lib_LoadExS(listLib, iText)
end

function TURING.Lib_LoadExS(listLib, iText)
  local Num, k, i, n, lib, size
  Num = #listLib
  if Num <= 0 then
    return 0
  end
  if iText == nil then
    iText = ""
  end
  LibData = {}
  n = 1
  for i = 1, Num do
    if listLib[i] ~= "" then
      local list = Split(listLib[i], "|")
      if string.find(iText, SpecialCharacters(list[1])) ~= nil or iText == "" then
        if 3 < #list then
          local One = {}
          for k = 1, #list - 2 do
            table.insert(One, list[k])
          end
          list[1] = table.concat(One, "|")
          size = Split(list[#list - 1], ",")
        else
          size = Split(list[2], ",")
        end
        lib = TURING.Math_HEXtoBIN(list[#list])
        lib = string.sub(lib, string.len(lib) - size[1] * size[2] + 1, string.len(lib))
        LibData[n] = {
          list[1],
          size[1],
          size[2],
          lib
        }
        n = n + 1
      end
    end
  end
  return n - 1
end

TURING["字库_加载十六进制识别字库_单个"] = function(LibText)
  return TURING.Lib_LoadExC(LibText)
end

function TURING.Lib_LoadExC(LibText)
  local k, lib, size
  if LibText == nil or LibText == "" then
    return 0
  end
  LibData = {}
  local list = Split(LibText, "|")
  if 3 < #list then
    local One = {}
    for k = 1, #list - 2 do
      table.insert(One, list[k])
    end
    list[1] = table.concat(One, "|")
    size = Split(list[#list - 1], ",")
  else
    size = Split(list[2], ",")
  end
  lib = TURING.Math_HEXtoBIN(list[#list])
  lib = string.sub(lib, string.len(lib) - size[1] * size[2] + 1, string.len(lib))
  LibData[1] = {
    list[1],
    size[1],
    size[2],
    lib
  }
  return 1
end

TURING["字库_二进制转十六进制"] = function(listLib)
  return TURING.Lib_BINtoHEX(listLib)
end

function TURING.Lib_BINtoHEX(listLib)
  local Num, k, i, n
  Num = #listLib
  if Num <= 0 then
    return 0
  end
  local libs = {}
  n = 1
  for i = 1, Num do
    if listLib[i] ~= "" then
      local list = Split(listLib[i], "|")
      if 3 < #list then
        local One = {}
        for k = 1, #list - 2 do
          table.insert(One, list[k])
        end
        list[1] = table.concat(One, "|")
        list[2] = list[#list - 1]
      end
      libs[n] = list[1] .. "|" .. list[2] .. "|" .. TURING.Math_BINtoHEX(list[#list])
      n = n + 1
    end
  end
  return libs
end

TURING["字库_字符切割数据"] = function(Num)
  return TURING.Lib_OneCharData(Num)
end

function TURING.Lib_OneCharData(Num)
  local n, k
  n = #IncData
  if n <= 0 then
    return
  end
  if Num == nil or Num < 1 then
    Num = 1
  end
  k = Num
  if k < 1 then
    k = 1
  end
  if n < k then
    k = n
  end
  local IncW = IncData[k][3]
  local IncH = IncData[k][4]
  return IncW .. "," .. IncH .. "|" .. IncData[k][5]
end

local function Bytes(FileName)
  local size = 0
  pcall(function()
    local f = io.open(FileName, "r")
    if f == nil then
      return nil
    end
    size = f:seek("end")
    f:close()
  end)
  return size
end

TURING["字库_存储识别字库"] = function(Text, Lattice, FileName)
  return TURING.Lib_Save(Text, Lattice, FileName)
end

function TURING.Lib_Save(Text, Lattice, FileName)
  pcall(function()
    local f = io.open(FileName, "a")
    if f == nil then
      return false
    end
    local LibText
    LibText = fileSize(FileName)
    if 0 < LibText then
      LibText = "\r\n" .. Text .. "|" .. Lattice
    else
      LibText = Text .. "|" .. Lattice
    end
    local arg = {LibText}
    for i, v in ipairs(arg) do
      f:write(tostring(v))
    end
    f:close()
  end)
  return true
end

TURING["识别"] = function(Similar, Model, Interval)
  return TURING.OCR(Similar, Model, Interval)
end

function TURING.OCR(Similar, Model, Interval)
  local results, IncNum, LibNum, k, i, w, h
  local JieGuo = {}
  local ZuoBiao = {}
  local a = 1
  local kk = 1
  if Similar == nil then
    Similar = 0
  end
  if Model == nil then
    Model = 0
  end
  if Interval == nil then
    Interval = 0
  end
  local ChrData = {}
  local ZhanBi = {}
  IncNum = #IncData
  print("IncData数据" .. IncNum)
  if IncNum <= 0 then
    return
  end
  if RandomNow(AgeLimit, StartIn) == false then
    if token == "" then
      return 0
    elseif sAuth_code == "" then
      return 0
    else
      local TMP_Countdown = 600000 + rndNum
      if TMP_Countdown <= os.time() - Countdown then
        local flag = TURING_LICENSE(sAuth_code, sAdd_code)
        Countdown = os.time()
        if flag ~= 1 then
          sAuth_code = ""
          return 0
        end
      end
    end
  end
  for k = 1, IncNum do
    local OcrData = {}
    local ii = 1
    local nMax = 1
    local ZBI = 0
    local IncW = IncData[k][3]
    local IncH = IncData[k][4]
    LibNum = #LibData
    if LibNum <= 0 then
      return
    end
    for i = 1, LibNum do
      if LibData[i][4] ~= "" then
        local LibW = LibData[i][2]
        local LibH = LibData[i][3]
        if not (math.abs(LibW - IncW) > (LibW + IncW) / 2) and not (math.abs(LibH - IncH) > (LibH + IncH) / 2) then
          local IncZ = 1
          local LibZ = 1
          local m = 1
          local Num = 0
          for w = 1, LibW do
            if w > IncW then
              break
            end
            for h = 1, LibH do
              if h > IncH then
                break
              end
              if string.sub(IncData[k][5], IncZ, IncZ) == string.sub(LibData[i][4], LibZ, LibZ) then
                Num = Num + 1
              end
              IncZ = IncZ + 1
              LibZ = LibZ + 1
            end
            IncZ = w * IncH + 1
            LibZ = w * LibH + 1
          end
          local bfBI = NewRound(Num / (LibW * LibH) * 100)
          local yxBI = NewRound(Num / (IncW * IncH) * 100)
          OcrData[ii] = {
            LibData[i][1],
            IncData[k][1],
            IncData[k][2],
            IncW,
            IncH,
            Num,
            bfBI,
            yxBI
          }
          if 100 <= OcrData[ii][7] and 100 <= OcrData[ii][8] then
            nMax = ii
            break
          end
          if ZBI < OcrData[ii][6] + OcrData[ii][7] + OcrData[ii][8] then
            nMax = ii
            ZBI = OcrData[ii][6] + OcrData[ii][7] + OcrData[ii][8]
          end
          ii = ii + 1
        end
      end
    end
    LibNum = nMax
    local BaiFenShu
    if 0 < #OcrData and Similar <= OcrData[LibNum][7] and Similar <= OcrData[LibNum][8] then
      if OcrData[LibNum][7] > OcrData[LibNum][8] then
        BaiFenShu = OcrData[LibNum][8]
      else
        BaiFenShu = OcrData[LibNum][7]
      end
      if 2 <= Model and 0 <= Interval then
        ChrData[kk] = {
          OcrData[LibNum][1],
          OcrData[LibNum][2],
          OcrData[LibNum][3],
          OcrData[LibNum][4],
          OcrData[LibNum][5],
          BaiFenShu
        }
        if 1 < kk then
          if Interval >= math.abs(ChrData[kk][2] - (ChrData[kk - 1][2] + ChrData[kk - 1][4])) then
            JieGuo[a] = JieGuo[a] .. ChrData[kk][1]
          else
            ZuoBiao[a] = JieGuo[a] .. "," .. ZuoBiao[a]
            if Model == 3 then
              ZhanBi[a] = ZuoBiao[a] .. "," .. ZhanBi[a]
            end
            a = a + 1
            JieGuo[a] = ChrData[kk][1]
            ZuoBiao[a] = ChrData[kk][2] .. "," .. ChrData[kk][3]
            if Model == 3 then
              ZhanBi[a] = ChrData[kk][6]
            end
          end
        else
          JieGuo[a] = ChrData[kk][1]
          ZuoBiao[a] = ChrData[kk][2] .. "," .. ChrData[kk][3]
          if Model == 3 then
            ZhanBi[a] = ChrData[kk][6]
          end
        end
        kk = kk + 1
      else
        JieGuo[a] = OcrData[LibNum][1]
        if Model == 1 then
          ZuoBiao[a] = OcrData[LibNum][2] .. "," .. OcrData[LibNum][3]
        elseif 2 <= Model then
          ZuoBiao[a] = JieGuo[a] .. "," .. OcrData[LibNum][2] .. "," .. OcrData[LibNum][3]
          if Model == 3 then
            ZhanBi[a] = ZuoBiao[a] .. "," .. BaiFenShu
          end
        end
        a = a + 1
      end
    end
    if k == IncNum and 2 <= Model and 0 <= Interval then
      if ZuoBiao == nil then
        return
      end
      if ZuoBiao[a] == nil then
        return
      end
      if JieGuo[a] == nil then
        return
      end
      ZuoBiao[a] = JieGuo[a] .. "," .. ZuoBiao[a]
      if Model == 3 then
        ZhanBi[a] = ZuoBiao[a] .. "," .. ZhanBi[a]
      end
    end
  end
  if 1 < a or 1 < kk then
    if Model == 0 then
      return table.concat(JieGuo, "")
    elseif Model == 1 then
      return table.concat(JieGuo, "") .. "|" .. table.concat(ZuoBiao, "|")
    elseif Model == 2 then
      return table.concat(ZuoBiao, "|")
    elseif Model == 3 then
      return table.concat(ZhanBi, "|")
    else
      return table.concat(JieGuo, "|")
    end
  else
    return
  end
end

TURING["图灵识别"] = function(Model)
  return TURING.OCR_TURING(Model)
end

function TURING.OCR_TURING(Model)
  local LaiYuan = Model[1]
  local LvJing = Model[2]
  local QieGe = Model[3]
  local ZiKu = Model[4]
  local ShiBie = Model[5]
  if RandomNow(AgeLimit, StartIn) == false then
    if token == "" then
      return 0
    elseif sAuth_code == "" then
      return 0
    else
      local TMP_Countdown = 600000 + rndNum
      if TMP_Countdown <= os.time() - Countdown then
        local flag = TURING_LICENSE(sAuth_code, sAdd_code)
        Countdown = os.time()
        if flag ~= 1 then
          sAuth_code = ""
          return 0
        end
      end
    end
  end
  if LaiYuan["屏幕范围"] ~= nil then
    TURING.Pixel_FromScreen(LaiYuan["屏幕范围"][1], LaiYuan["屏幕范围"][2], LaiYuan["屏幕范围"][3], LaiYuan["屏幕范围"][4])
  elseif LaiYuan["加载图片"] ~= nil then
    TURING.Pixel_FromPicture(LaiYuan["加载图片"][1])
  else
    return
  end
  for i = 1, #LvJing do
    if LvJing[i]["二值化"] ~= nil then
      TURING.Filter_Binaryzation(LvJing[i]["二值化"][1])
    elseif LvJing[i]["灰度"] ~= nil then
      TURING.Filter_Gray(LvJing[i]["灰度"][1])
    elseif LvJing[i]["色调分离"] ~= nil then
      TURING.Filter_Posterization(LvJing[i]["色调分离"][1])
    elseif LvJing[i]["通道图层"] ~= nil then
      TURING.Filter_ChannelLayer(LvJing[i]["通道图层"][1])
    elseif LvJing[i]["清除杂点"] ~= nil then
      TURING.Filter_Despeckle(LvJing[i]["清除杂点"][1])
    elseif LvJing[i]["获取轮廓"] ~= nil then
      TURING.Filter_Outline(LvJing[i]["获取轮廓"][1], LvJing[i]["获取轮廓"][2], LvJing[i]["获取轮廓"][3], LvJing[i]["获取轮廓"][4])
    elseif LvJing[i]["提取色块"] ~= nil then
      TURING.Filter_ExtractBlock(LvJing[i]["提取色块"][1], LvJing[i]["提取色块"][2], LvJing[i]["提取色块"][3])
    elseif LvJing[i]["颠倒颜色"] ~= nil then
      TURING.Filter_InverseColor(LvJing[i]["颠倒颜色"][1])
    elseif LvJing[i]["膨胀腐蚀"] ~= nil then
      TURING.Filter_DilationErosion(LvJing[i]["膨胀腐蚀"][1])
    elseif LvJing[i]["等比放大"] ~= nil then
      TURING.Filter_ZoomIn(LvJing[i]["等比放大"][1], LvJing[i]["等比放大"][2])
    elseif LvJing[i]["等比缩小"] ~= nil then
      TURING.Filter_ZoomOut(LvJing[i]["等比缩小"][1], LvJing[i]["等比缩小"][2])
    elseif LvJing[i]["缩放归一化"] ~= nil then
      TURING.Filter_ZoomOne(LvJing[i]["缩放归一化"][1], LvJing[i]["缩放归一化"][2])
    elseif LvJing[i]["缩放自适应"] ~= nil then
      TURING.Filter_ZoomAdaptive(LvJing[i]["缩放自适应"][1], LvJing[i]["缩放自适应"][2])
    elseif LvJing[i]["固定旋转"] ~= nil then
      TURING.Filter_Rotate(LvJing[i]["固定旋转"][1])
    elseif LvJing[i]["延伸裁剪"] ~= nil then
      TURING.Filter_Tailor(LvJing[i]["延伸裁剪"][1], LvJing[i]["延伸裁剪"][2], LvJing[i]["延伸裁剪"][3], LvJing[i]["延伸裁剪"][4])
    elseif LvJing[i]["颜色选留"] ~= nil then
      TURING.Filter_ColorChoose(LvJing[i]["颜色选留"][1])
    elseif LvJing[i]["固定柔化"] ~= nil then
      TURING.Filter_Softness(LvJing[i]["固定柔化"][1])
    elseif LvJing[i]["电影底片"] ~= nil then
      TURING.Filter_Negative()
    elseif LvJing[i]["固定镜像"] ~= nil then
      TURING.Filter_Mirror(LvJing[i]["固定镜像"][1])
    else
      return
    end
  end
  if QieGe["固定位置"] ~= nil then
    TURING.Incise_FixedLocation(QieGe["固定位置"][1], QieGe["固定位置"][2], QieGe["固定位置"][3], QieGe["固定位置"][4], QieGe["固定位置"][5], QieGe["固定位置"][6], QieGe["固定位置"][7], QieGe["固定位置"][8])
  elseif QieGe["随机方位"] ~= nil then
    TURING.Incise_RandomOrientation(QieGe["随机方位"][1], QieGe["随机方位"][2], QieGe["随机方位"][3])
  elseif QieGe["连通区域"] ~= nil then
    TURING.Incise_ConnectedArea(QieGe["连通区域"][1], QieGe["连通区域"][2], QieGe["连通区域"][3])
  elseif QieGe["范围投影"] ~= nil then
    TURING.Incise_ScopeAisle(QieGe["范围投影"][1], QieGe["范围投影"][2], QieGe["范围投影"][3], QieGe["范围投影"][4])
  else
    return
  end
  if ZiKu["字库文件"] ~= nil then
    TURING.Lib_Load(ZiKu["字库文件"][1], ZiKu["字库文件"][2])
  elseif ZiKu["字库数组"] ~= nil then
    TURING.Lib_LoadEx(ZiKu["字库数组"][1], ZiKu["字库数组"][2])
  elseif ZiKu["六十四进制字库数组"] ~= nil then
    TURING.Lib_LoadBase64(ZiKu["六十四进制字库数组"][1], ZiKu["六十四进制字库数组"][2])
  elseif ZiKu["十六进制字库数组"] ~= nil then
    TURING.Lib_LoadExS(ZiKu["十六进制字库数组"][1], ZiKu["十六进制字库数组"][2])
  elseif ZiKu["十六进制字库文本"] ~= nil then
    TURING.Lib_LoadExC(ZiKu["十六进制字库文本"][1])
  else
    return
  end
  if ShiBie ~= nil then
    return TURING.OCR(ShiBie[1], ShiBie[2], ShiBie[3])
  else
    return
  end
end

TURING["识别_返回绝对坐标"] = function(JieGuo)
  return TURING.OCR_AbsCoords(JieGuo)
end

function TURING.OCR_AbsCoords(JieGuo)
  local iLeft, iTop, iRight, iBottom
  iLeft = R_Left
  iTop = R_Top
  iRight = R_Right
  iBottom = R_Bottom
  R_Left = 0
  R_Top = 0
  R_Right = 0
  R_Bottom = 0
  if JieGuo == null then
    return
  end
  if RandomNow(AgeLimit, StartIn) == false then
    if token == "" then
      return 0
    elseif sAuth_code == "" then
      return 0
    else
      local TMP_Countdown = 600000 + rndNum
      if TMP_Countdown <= os.time() - Countdown then
        local flag = TURING_LICENSE(sAuth_code, sAdd_code)
        Countdown = os.time()
        if flag ~= 1 then
          sAuth_code = ""
          return 0
        end
      end
    end
  end
  local Ret = {}
  local List = Split(JieGuo, "|")
  if 1 < #List then
    Ret = Split(List[2], ",")
  elseif #List == 1 then
    Ret = Split(List[1], ",")
  end
  local Results = {}
  if 1 < #Ret then
    local Num = #List
    if #Ret == 2 then
      for i = 1, Num do
        if List[i + 1] == null then
          break
        end
        Results = Split(List[i + 1], ",")
        if R_Model == 1 then
          List[i + 1] = {
            iLeft + (iRight - iLeft) - Results[2],
            iTop + Results[1]
          }
        else
          List[i + 1] = {
            iLeft + Results[1],
            iTop + Results[2]
          }
        end
      end
    elseif #Ret == 3 then
      for i = 1, Num do
        if List[i] == null then
          break
        end
        Results = Split(List[i], ",")
        if R_Model == 1 then
          List[i] = {
            Results[1],
            iLeft + (iRight - iLeft) - Results[3],
            iTop + Results[2]
          }
        else
          List[i] = {
            Results[1],
            iLeft + Results[2],
            iTop + Results[3]
          }
        end
      end
    elseif #Ret == 4 then
      for i = 1, Num do
        if List[i] == null then
          break
        end
        Results = Split(List[i], ",")
        if R_Model == 1 then
          List[i] = {
            Results[1],
            iLeft + (iRight - iLeft) - Results[3],
            iTop + Results[2],
            Results[4]
          }
        else
          List[i] = {
            Results[1],
            iLeft + Results[2],
            iTop + Results[3],
            Results[4]
          }
        end
      end
    end
  end
  R_Model = 0
  Results = List
  return Results
end

TURING["识别返回绝对坐标"] = function(JieGuo)
  return TURING.OCR_AbsCoordinates(JieGuo)
end

function TURING.OCR_AbsCoordinates(JieGuo)
  local iLeft, iTop, iRight, iBottom
  iLeft = R_Left
  iTop = R_Top
  iRight = R_Right
  iBottom = R_Bottom
  R_Left = 0
  R_Top = 0
  R_Right = 0
  R_Bottom = 0
  if JieGuo == nil then
    return
  end
  local Ret = {}
  local List = Split(JieGuo, "|")
  if 1 < #List then
    Ret = Split(List[2], ",")
  elseif #List == 1 then
    Ret = Split(List[1], ",")
  end
  local Results = {}
  if 1 < #Ret then
    local Num = #List
    if #Ret == 2 then
      for i = 1, Num do
        if List[i + 1] == nil then
          break
        end
        Results = Split(List[i + 1], ",")
        if R_Model == 1 then
          List[i + 1] = iLeft + (iRight - iLeft) - Results[2] .. "," .. iTop + Results[1]
        else
          List[i + 1] = iLeft + Results[1] .. "," .. iTop + Results[2]
        end
      end
    elseif #Ret == 3 then
      for i = 1, Num do
        if List[i] == nil then
          break
        end
        Results = Split(List[i], ",")
        if R_Model == 1 then
          List[i] = Results[1] .. "," .. iLeft + (iRight - iLeft) - Results[3] .. "," .. iTop + Results[2]
        else
          List[i] = Results[1] .. "," .. iLeft + Results[2] .. "," .. iTop + Results[3]
        end
      end
    elseif #Ret == 4 then
      for i = 1, Num do
        if List[i] == nil then
          break
        end
        Results = Split(List[i], ",")
        if R_Model == 1 then
          List[i] = Results[1] .. "," .. iLeft + (iRight - iLeft) - Results[3] .. "," .. iTop + Results[2] .. "," .. Results[4]
        else
          List[i] = Results[1] .. "," .. iLeft + Results[2] .. "," .. iTop + Results[3] .. "," .. Results[4]
        end
      end
    end
  end
  R_Model = 0
  Results = List
  return Results
end

TURING["绘图_图像数据备份"] = function(index)
  TURING.Draw_Backups(index)
end

function TURING.Draw_Backups(index)
  local W, H
  local width = #ImgData
  if width <= 0 then
    return
  end
  local height = #ImgData[1]
  if height <= 0 then
    return
  end
  if index == 1 then
    BakData1 = {}
    for W = 1, width do
      BakData1[W] = {}
      for H = 1, height do
        BakData1[W][H] = {
          ImgData[W][H][1],
          ImgData[W][H][2],
          ImgData[W][H][3]
        }
      end
    end
  elseif index == 2 then
    BakData2 = {}
    for W = 1, width do
      BakData2[W] = {}
      for H = 1, height do
        BakData2[W][H] = {
          ImgData[W][H][1],
          ImgData[W][H][2],
          ImgData[W][H][3]
        }
      end
    end
  elseif index == 3 then
    BakData3 = {}
    for W = 1, width do
      BakData3[W] = {}
      for H = 1, height do
        BakData3[W][H] = {
          ImgData[W][H][1],
          ImgData[W][H][2],
          ImgData[W][H][3]
        }
      end
    end
  elseif index == 4 then
    BakData4 = {}
    for W = 1, width do
      BakData4[W] = {}
      for H = 1, height do
        BakData4[W][H] = {
          ImgData[W][H][1],
          ImgData[W][H][2],
          ImgData[W][H][3]
        }
      end
    end
  elseif index == 5 then
    BakData5 = {}
    for W = 1, width do
      BakData5[W] = {}
      for H = 1, height do
        BakData5[W][H] = {
          ImgData[W][H][1],
          ImgData[W][H][2],
          ImgData[W][H][3]
        }
      end
    end
  elseif index == 6 then
    BakData6 = {}
    for W = 1, width do
      BakData6[W] = {}
      for H = 1, height do
        BakData6[W][H] = {
          ImgData[W][H][1],
          ImgData[W][H][2],
          ImgData[W][H][3]
        }
      end
    end
  elseif index == 7 then
    BakData7 = {}
    for W = 1, width do
      BakData7[W] = {}
      for H = 1, height do
        BakData7[W][H] = {
          ImgData[W][H][1],
          ImgData[W][H][2],
          ImgData[W][H][3]
        }
      end
    end
  elseif index == 8 then
    BakData8 = {}
    for W = 1, width do
      BakData8[W] = {}
      for H = 1, height do
        BakData8[W][H] = {
          ImgData[W][H][1],
          ImgData[W][H][2],
          ImgData[W][H][3]
        }
      end
    end
  elseif index == 9 then
    BakData9 = {}
    for W = 1, width do
      BakData9[W] = {}
      for H = 1, height do
        BakData9[W][H] = {
          ImgData[W][H][1],
          ImgData[W][H][2],
          ImgData[W][H][3]
        }
      end
    end
  else
    BakData0 = {}
    for W = 1, width do
      BakData0[W] = {}
      for H = 1, height do
        BakData0[W][H] = {
          ImgData[W][H][1],
          ImgData[W][H][2],
          ImgData[W][H][3]
        }
      end
    end
  end
end

TURING["绘图_图像数据恢复"] = function(index)
  TURING.Draw_Recover(index)
end

function TURING.Draw_Recover(index)
  local W, H, width, height
  if index == 1 then
    width = #BakData1
    if width <= 0 then
      return
    end
    height = #BakData1[1]
    if height <= 0 then
      return
    end
    ImgData = {}
    for W = 1, width do
      ImgData[W] = {}
      for H = 1, height do
        ImgData[W][H] = {
          BakData1[W][H][1],
          BakData1[W][H][2],
          BakData1[W][H][3]
        }
      end
    end
  elseif index == 2 then
    width = #BakData2
    height = #BakData2[1]
    if width <= 0 or height <= 0 then
      return
    end
    ImgData = {}
    for W = 1, width do
      ImgData[W] = {}
      for H = 1, height do
        ImgData[W][H] = {
          BakData2[W][H][1],
          BakData2[W][H][2],
          BakData2[W][H][3]
        }
      end
    end
  elseif index == 3 then
    width = #BakData3
    height = #BakData3[1]
    if width <= 0 or height <= 0 then
      return
    end
    ImgData = {}
    for W = 1, width do
      ImgData[W] = {}
      for H = 1, height do
        ImgData[W][H] = {
          BakData3[W][H][1],
          BakData3[W][H][2],
          BakData3[W][H][3]
        }
      end
    end
  elseif index == 4 then
    width = #BakData4
    height = #BakData4[1]
    if width <= 0 or height <= 0 then
      return
    end
    ImgData = {}
    for W = 1, width do
      ImgData[W] = {}
      for H = 1, height do
        ImgData[W][H] = {
          BakData4[W][H][1],
          BakData4[W][H][2],
          BakData4[W][H][3]
        }
      end
    end
  elseif index == 5 then
    width = #BakData5
    height = #BakData5[1]
    if width <= 0 or height <= 0 then
      return
    end
    ImgData = {}
    for W = 1, width do
      ImgData[W] = {}
      for H = 1, height do
        ImgData[W][H] = {
          BakData5[W][H][1],
          BakData5[W][H][2],
          BakData5[W][H][3]
        }
      end
    end
  elseif index == 6 then
    width = #BakData6
    height = #BakData6[1]
    if width <= 0 or height <= 0 then
      return
    end
    ImgData = {}
    for W = 1, width do
      ImgData[W] = {}
      for H = 1, height do
        ImgData[W][H] = {
          BakData6[W][H][1],
          BakData6[W][H][2],
          BakData6[W][H][3]
        }
      end
    end
  elseif index == 7 then
    width = #BakData7
    height = #BakData7[1]
    if width <= 0 or height <= 0 then
      return
    end
    ImgData = {}
    for W = 1, width do
      ImgData[W] = {}
      for H = 1, height do
        ImgData[W][H] = {
          BakData7[W][H][1],
          BakData7[W][H][2],
          BakData7[W][H][3]
        }
      end
    end
  elseif index == 8 then
    width = #BakData8
    height = #BakData8[1]
    if width <= 0 or height <= 0 then
      return
    end
    ImgData = {}
    for W = 1, width do
      ImgData[W] = {}
      for H = 1, height do
        ImgData[W][H] = {
          BakData8[W][H][1],
          BakData8[W][H][2],
          BakData8[W][H][3]
        }
      end
    end
  elseif index == 9 then
    width = #BakData9
    height = #BakData9[1]
    if width <= 0 or height <= 0 then
      return
    end
    ImgData = {}
    for W = 1, width do
      ImgData[W] = {}
      for H = 1, height do
        ImgData[W][H] = {
          BakData9[W][H][1],
          BakData9[W][H][2],
          BakData9[W][H][3]
        }
      end
    end
  else
    width = #BakData0
    height = #BakData0[1]
    if width <= 0 or height <= 0 then
      return
    end
    ImgData = {}
    for W = 1, width do
      ImgData[W] = {}
      for H = 1, height do
        ImgData[W][H] = {
          BakData0[W][H][1],
          BakData0[W][H][2],
          BakData0[W][H][3]
        }
      end
    end
  end
end

TURING["图色_两图片是否相似"] = function(imagePath1, imagePath2, Similar, colourCast)
  return TURING.DiffImage(imagePath1, imagePath2, Similar, colourCast)
end

function TURING.DiffImage(imagePath1, imagePath2, Similar, colourCast)
  local tpData1 = TURING.Pixel_FromPicture(imagePath1)
  local PW1 = #tpData1
  local PH1 = #tpData1[1]
  local tpData2 = TURING.Pixel_FromPicture(imagePath2)
  local PW2 = #tpData2
  local PH2 = #tpData2[1]
  local W, H, Num
  W = PW1
  if PW1 > PW2 then
    W = PW2
  end
  H = PH1
  if PH1 > PH2 then
    H = PH2
  end
  Num = W * H
  local NotSimilar = 1.0 - Similar
  local dR = tonumber(string.sub(colourCast, 5, 6), 16)
  local dG = tonumber(string.sub(colourCast, 3, 4), 16)
  local dB = tonumber(string.sub(colourCast, 1, 2), 16)
  local Yes = 0
  local No = 0
  for x = 1, W do
    for y = 1, H do
      if dB >= math.abs(tpData1[x][y][1] - tpData2[x][y][1]) and dG >= math.abs(tpData1[x][y][2] - tpData2[x][y][2]) and dR >= math.abs(tpData1[x][y][3] - tpData2[x][y][3]) then
        Yes = Yes + 1
      else
        No = No + 1
      end
      if Similar < Yes / Num then
        return true
      end
      if NotSimilar < No / Num then
        return false
      end
    end
  end
end

local function num_to_hex_str(value)
  return string.format("%02X", value)
end

TURING["图色_获取指定点颜色"] = function(x, y, model)
  return TURING.GetPixelColor(x, y, model)
end

function TURING.GetPixelColor(x, y, model)
  if x == nil then
    x = 0
  end
  if y == nil then
    y = 0
  end
  if model == nil then
    model = 0
  end
  local hex
  if model == 2 then
    local width = #ImgData
    if width <= 0 then
      return ""
    end
    local height = #ImgData[1]
    if height <= 0 then
      return ""
    end
    if 0 <= x and x < width and 0 <= y and y < height then
      hex = num_to_hex_str(ImgData[x + 1][y + 1][1])
      hex = hex .. num_to_hex_str(ImgData[x + 1][y + 1][2])
      hex = hex .. num_to_hex_str(ImgData[x + 1][y + 1][3])
      return hex
    else
      return ""
    end
  else
    local r, g, b = colorToRGB(getPixelColor(x, y))
    hex = num_to_hex_str(b)
    hex = hex .. num_to_hex_str(g)
    hex = hex .. num_to_hex_str(r)
    return hex
  end
end

TURING["图色_获取圆边点颜色"] = function(x0, y0, Radius, Degree)
  return TURING.GetRoundPointColor(x0, y0, Radius, Degree)
end

function TURING.GetRoundPointColor(x0, y0, Radius, Degree)
  local width = #ImgData
  if width <= 0 then
    return ""
  end
  local height = #ImgData[1]
  if height <= 0 then
    return ""
  end
  if Radius == nil then
    Radius = 0
  end
  if Degree == nil then
    Degree = 0
  end
  local x2 = NewRound(x0 + 1 - Radius * math.sin(math.rad(Degree)))
  local y2 = NewRound(y0 + 1 - Radius * math.cos(math.rad(Degree)))
  if 0 <= x2 and width >= x2 and 0 <= y2 and height >= y2 then
    local hex = num_to_hex_str(ImgData[x2][y2][1])
    hex = hex .. num_to_hex_str(ImgData[x2][y2][2])
    hex = hex .. num_to_hex_str(ImgData[x2][y2][3])
    return {
      hex,
      x2 - 1,
      y2 - 1
    }
  else
    return ""
  end
end

TURING["二进制转十六进制"] = function(BinStr)
  return TURING.Math_BINtoHEX(BinStr)
end

function TURING.Math_BINtoHEX(BinStr)
  local i, ret, str
  local n = string.len(BinStr) % 4
  if n ~= 0 then
    str = string.rep("0", 4 - n) .. BinStr
  else
    str = BinStr
  end
  local One = {}
  for i = 1, string.len(str), 4 do
    ret = string.sub(str, i, i + 3)
    if ret == "0000" then
      table.insert(One, "0")
    elseif ret == "0001" then
      table.insert(One, "1")
    elseif ret == "0010" then
      table.insert(One, "2")
    elseif ret == "0011" then
      table.insert(One, "3")
    elseif ret == "0100" then
      table.insert(One, "4")
    elseif ret == "0101" then
      table.insert(One, "5")
    elseif ret == "0110" then
      table.insert(One, "6")
    elseif ret == "0111" then
      table.insert(One, "7")
    elseif ret == "1000" then
      table.insert(One, "8")
    elseif ret == "1001" then
      table.insert(One, "9")
    elseif ret == "1010" then
      table.insert(One, "A")
    elseif ret == "1011" then
      table.insert(One, "B")
    elseif ret == "1100" then
      table.insert(One, "C")
    elseif ret == "1101" then
      table.insert(One, "D")
    elseif ret == "1110" then
      table.insert(One, "E")
    elseif ret == "1111" then
      table.insert(One, "F")
    end
  end
  return table.concat(One, "")
end

TURING["十六进制转二进制"] = function(HexStr)
  return TURING.Math_HEXtoBIN(HexStr)
end

function TURING.Math_HEXtoBIN(HexStr)
  local i, ret, str
  str = string.upper(HexStr)
  local B = {}
  for i = 1, string.len(str) do
    ret = string.sub(str, i, i)
    if ret == "0" then
      table.insert(B, "0000")
    elseif ret == "1" then
      table.insert(B, "0001")
    elseif ret == "2" then
      table.insert(B, "0010")
    elseif ret == "3" then
      table.insert(B, "0011")
    elseif ret == "4" then
      table.insert(B, "0100")
    elseif ret == "5" then
      table.insert(B, "0101")
    elseif ret == "6" then
      table.insert(B, "0110")
    elseif ret == "7" then
      table.insert(B, "0111")
    elseif ret == "8" then
      table.insert(B, "1000")
    elseif ret == "9" then
      table.insert(B, "1001")
    elseif ret == "A" then
      table.insert(B, "1010")
    elseif ret == "B" then
      table.insert(B, "1011")
    elseif ret == "C" then
      table.insert(B, "1100")
    elseif ret == "D" then
      table.insert(B, "1101")
    elseif ret == "E" then
      table.insert(B, "1110")
    elseif ret == "F" then
      table.insert(B, "1111")
    end
  end
  return table.concat(B, "")
end

TURING["二进制转六十四进制"] = function(Base64Str)
  return TURING.Math_BINtoBASE64(Base64Str)
end

function TURING.Math_BINtoBASE64(BinStr)
  local i, ret, str
  local n = string.len(BinStr) % 6
  if n ~= 0 then
    str = string.rep("0", 6 - n) .. BinStr
  else
    str = BinStr
  end
  local One = {}
  for i = 1, string.len(str), 6 do
    ret = string.sub(str, i, i + 5)
    if ret == "000000" then
      table.insert(One, "0")
    elseif ret == "000001" then
      table.insert(One, "1")
    elseif ret == "000010" then
      table.insert(One, "2")
    elseif ret == "000011" then
      table.insert(One, "3")
    elseif ret == "000100" then
      table.insert(One, "4")
    elseif ret == "000101" then
      table.insert(One, "5")
    elseif ret == "000110" then
      table.insert(One, "6")
    elseif ret == "000111" then
      table.insert(One, "7")
    elseif ret == "001000" then
      table.insert(One, "8")
    elseif ret == "001001" then
      table.insert(One, "9")
    elseif ret == "001010" then
      table.insert(One, "A")
    elseif ret == "001011" then
      table.insert(One, "B")
    elseif ret == "001100" then
      table.insert(One, "C")
    elseif ret == "001101" then
      table.insert(One, "D")
    elseif ret == "001110" then
      table.insert(One, "E")
    elseif ret == "001111" then
      table.insert(One, "F")
    elseif ret == "010000" then
      table.insert(One, "G")
    elseif ret == "010001" then
      table.insert(One, "H")
    elseif ret == "010010" then
      table.insert(One, "I")
    elseif ret == "010011" then
      table.insert(One, "J")
    elseif ret == "010100" then
      table.insert(One, "K")
    elseif ret == "010101" then
      table.insert(One, "L")
    elseif ret == "010110" then
      table.insert(One, "M")
    elseif ret == "010111" then
      table.insert(One, "N")
    elseif ret == "011000" then
      table.insert(One, "O")
    elseif ret == "011001" then
      table.insert(One, "P")
    elseif ret == "011010" then
      table.insert(One, "Q")
    elseif ret == "011011" then
      table.insert(One, "R")
    elseif ret == "011100" then
      table.insert(One, "S")
    elseif ret == "011101" then
      table.insert(One, "T")
    elseif ret == "011110" then
      table.insert(One, "U")
    elseif ret == "011111" then
      table.insert(One, "V")
    elseif ret == "100000" then
      table.insert(One, "W")
    elseif ret == "100001" then
      table.insert(One, "X")
    elseif ret == "100010" then
      table.insert(One, "Y")
    elseif ret == "100011" then
      table.insert(One, "Z")
    elseif ret == "100100" then
      table.insert(One, "a")
    elseif ret == "100101" then
      table.insert(One, "b")
    elseif ret == "100110" then
      table.insert(One, "c")
    elseif ret == "100111" then
      table.insert(One, "d")
    elseif ret == "101000" then
      table.insert(One, "e")
    elseif ret == "101001" then
      table.insert(One, "f")
    elseif ret == "101010" then
      table.insert(One, "g")
    elseif ret == "101011" then
      table.insert(One, "h")
    elseif ret == "101100" then
      table.insert(One, "i")
    elseif ret == "101101" then
      table.insert(One, "j")
    elseif ret == "101110" then
      table.insert(One, "k")
    elseif ret == "101111" then
      table.insert(One, "l")
    elseif ret == "110000" then
      table.insert(One, "m")
    elseif ret == "110001" then
      table.insert(One, "n")
    elseif ret == "110010" then
      table.insert(One, "o")
    elseif ret == "110011" then
      table.insert(One, "p")
    elseif ret == "110100" then
      table.insert(One, "q")
    elseif ret == "110101" then
      table.insert(One, "r")
    elseif ret == "110110" then
      table.insert(One, "s")
    elseif ret == "110111" then
      table.insert(One, "t")
    elseif ret == "111000" then
      table.insert(One, "u")
    elseif ret == "111001" then
      table.insert(One, "v")
    elseif ret == "111010" then
      table.insert(One, "w")
    elseif ret == "111011" then
      table.insert(One, "x")
    elseif ret == "111100" then
      table.insert(One, "y")
    elseif ret == "111101" then
      table.insert(One, "z")
    elseif ret == "111110" then
      table.insert(One, "#")
    elseif ret == "111111" then
      table.insert(One, "&")
    end
  end
  return table.concat(One, "")
end

TURING["六十四进制转二进制"] = function(Base64Str)
  return TURING.Math_Base64toBIN(Base64Str)
end

function TURING.Math_Base64toBIN(Base64Str)
  local i, ret
  local B = {}
  for i = 1, string.len(Base64Str) do
    ret = string.sub(Base64Str, i, i)
    if ret == "0" then
      table.insert(B, "000000")
    elseif ret == "1" then
      table.insert(B, "000001")
    elseif ret == "2" then
      table.insert(B, "000010")
    elseif ret == "3" then
      table.insert(B, "000011")
    elseif ret == "4" then
      table.insert(B, "000100")
    elseif ret == "5" then
      table.insert(B, "000101")
    elseif ret == "6" then
      table.insert(B, "000110")
    elseif ret == "7" then
      table.insert(B, "000111")
    elseif ret == "8" then
      table.insert(B, "001000")
    elseif ret == "9" then
      table.insert(B, "001001")
    elseif ret == "A" then
      table.insert(B, "001010")
    elseif ret == "B" then
      table.insert(B, "001011")
    elseif ret == "C" then
      table.insert(B, "001100")
    elseif ret == "D" then
      table.insert(B, "001101")
    elseif ret == "E" then
      table.insert(B, "001110")
    elseif ret == "F" then
      table.insert(B, "001111")
    elseif ret == "G" then
      table.insert(B, "010000")
    elseif ret == "H" then
      table.insert(B, "010001")
    elseif ret == "I" then
      table.insert(B, "010010")
    elseif ret == "J" then
      table.insert(B, "010011")
    elseif ret == "K" then
      table.insert(B, "010100")
    elseif ret == "L" then
      table.insert(B, "010101")
    elseif ret == "M" then
      table.insert(B, "010110")
    elseif ret == "N" then
      table.insert(B, "010111")
    elseif ret == "O" then
      table.insert(B, "011000")
    elseif ret == "P" then
      table.insert(B, "011001")
    elseif ret == "Q" then
      table.insert(B, "011010")
    elseif ret == "R" then
      table.insert(B, "011011")
    elseif ret == "S" then
      table.insert(B, "011100")
    elseif ret == "T" then
      table.insert(B, "011101")
    elseif ret == "U" then
      table.insert(B, "011110")
    elseif ret == "V" then
      table.insert(B, "011111")
    elseif ret == "W" then
      table.insert(B, "100000")
    elseif ret == "X" then
      table.insert(B, "100001")
    elseif ret == "Y" then
      table.insert(B, "100010")
    elseif ret == "Z" then
      table.insert(B, "100011")
    elseif ret == "a" then
      table.insert(B, "100100")
    elseif ret == "b" then
      table.insert(B, "100101")
    elseif ret == "c" then
      table.insert(B, "100110")
    elseif ret == "d" then
      table.insert(B, "100111")
    elseif ret == "e" then
      table.insert(B, "101000")
    elseif ret == "f" then
      table.insert(B, "101001")
    elseif ret == "g" then
      table.insert(B, "101010")
    elseif ret == "h" then
      table.insert(B, "101011")
    elseif ret == "i" then
      table.insert(B, "101100")
    elseif ret == "j" then
      table.insert(B, "101101")
    elseif ret == "k" then
      table.insert(B, "101110")
    elseif ret == "l" then
      table.insert(B, "101111")
    elseif ret == "m" then
      table.insert(B, "110000")
    elseif ret == "n" then
      table.insert(B, "110001")
    elseif ret == "o" then
      table.insert(B, "110010")
    elseif ret == "p" then
      table.insert(B, "110011")
    elseif ret == "q" then
      table.insert(B, "110100")
    elseif ret == "r" then
      table.insert(B, "110101")
    elseif ret == "s" then
      table.insert(B, "110110")
    elseif ret == "t" then
      table.insert(B, "110111")
    elseif ret == "u" then
      table.insert(B, "111000")
    elseif ret == "v" then
      table.insert(B, "111001")
    elseif ret == "w" then
      table.insert(B, "111010")
    elseif ret == "x" then
      table.insert(B, "111011")
    elseif ret == "y" then
      table.insert(B, "111100")
    elseif ret == "z" then
      table.insert(B, "111101")
    elseif ret == "#" then
      table.insert(B, "111110")
    elseif ret == "&" then
      table.insert(B, "111111")
    end
  end
  return table.concat(B, "")
end

TURING["时间戳转换日期"] = function(t)
  return TURING.Time_StampToDate(t)
end

function TURING.Time_StampToDate(t)
  t = tostring(t or os.time())
  if #t == 10 then
    return os.date("%Y-%m-%d %H:%M:%S", t)
  elseif #t == 13 then
    return os.date("%Y-%m-%d %H:%M:%S", t / 1000) .. "." .. t:sub(-3, -1) .. "000"
  end
end

TURING["日期转换时间戳"] = function(d)
  return TURING.Time_DateToStamp(d)
end

function TURING.Time_DateToStamp(d)
  local dt = {}
  local i = 0
  for t in d:gmatch("%d+") do
    i = i + 1
    dt[i] = t
  end
  if dt[7] then
    millisecond = "." .. dt[7]
  else
    millisecond = ""
  end
  return os.time({
    year = dt[1],
    month = dt[2],
    day = dt[3],
    hour = dt[4],
    minute = dt[5],
    second = dt[6]
  }) .. millisecond
end

local function LinearInterpolation(p1, p2, t)
  local x = p1[1] + (p2[1] - p1[1]) * t
  local y = p1[2] + (p2[2] - p1[2]) * t
  return {x, y}
end

local function BezierCurveFormula(p, t)
  p = {
    table.unpack(p)
  }
  while #p ~= 1 do
    for i = 1, #p - 1 do
      p[i] = LinearInterpolation(p[i], p[i + 1], t)
    end
    table.remove(p)
  end
  return p[1]
end

TURING["贝塞尔曲线轨迹"] = function(point, step)
  return TURING.BezierCurve(point, step)
end

function TURING.BezierCurve(point, step)
  local n = 1
  local tracks = {}
  local temp = {}
  if RandomNow(AgeLimit, StartIn) == false then
    if token == "" then
      return 0
    elseif sAuth_code == "" then
      return 0
    else
      local TMP_Countdown = 600000 + rndNum
      if TMP_Countdown <= os.time() - Countdown then
        local flag = TURING_LICENSE(sAuth_code, sAdd_code)
        Countdown = os.time()
        if flag ~= 1 then
          sAuth_code = ""
          return 0
        end
      end
    end
  end
  tracks[n] = {
    point[1][1],
    point[1][2]
  }
  for i = 1, math.floor(1 / step) - 1 do
    temp = BezierCurveFormula(point, i * step)
    n = n + 1
    tracks[n] = {
      math.floor(temp[1]),
      math.floor(temp[2])
    }
  end
  n = n + 1
  tracks[n] = {
    point[#point][1],
    point[#point][2]
  }
  return tracks
end

TURING["算法_角度计算"] = function(aX, aY, bX, bY)
  return TURING.AngleCalc(aX, aY, bX, bY)
end

function TURING.AngleCalc(aX, aY, bX, bY)
  local PI, X, Y, ret
  PI = 3.14159265358979
  X = bX - aX
  Y = aY - bY
  if X == 0 and 0 < Y then
    ret = 0
  elseif 0 < X and Y == 0 then
    ret = 90
  elseif X == 0 and Y < 0 then
    ret = 180
  elseif X < 0 and Y == 0 then
    ret = 270
  elseif 0 < X and 0 < Y then
    ret = math.atan(X / Y) * 180 / PI
  elseif 0 < X and Y < 0 then
    ret = math.atan(math.abs(Y) / X) * 180 / PI + 90
  elseif X < 0 and Y < 0 then
    ret = math.atan(math.abs(X) / math.abs(Y)) * 180 / PI + 180
  elseif X < 0 and 0 < Y then
    ret = math.atan(math.abs(Y) / math.abs(X)) * 180 / PI + 270
  end
  return ret
end

TURING["算法_坐标系旋转"] = function(qx, qy, sWidth, sHeight, degree)
  return TURING.xyRotate(qx, qy, sWidth, sHeight, degree)
end

function TURING.xyRotate(qx, qy, sWidth, sHeight, degree)
  local x, y
  if sWidth == nil then
    sWidth = 0
  end
  if sHeight == nil then
    sHeight = 0
  end
  if degree == nil then
    degree = 270
  end
  if degree == 90 then
    if qy < sHeight then
      x = sHeight - (qy + 1)
    else
      x = 0
    end
    y = qx
  elseif degree == 180 then
    if qx < sWidth then
      x = sWidth - (qx + 1)
    else
      x = 0
    end
    if qy < sHeight then
      y = sHeight - (qy + 1)
    else
      y = 0
    end
  elseif degree == 270 or degree == -90 then
    x = qy
    if qx < sWidth then
      y = sWidth - (qx + 1)
    else
      y = 0
    end
  else
    x = qx
    y = qy
  end
  return {x, y}
end

function TURING_LICENSE()
  return 1
end

function RandomNow()
  return true
end

function TONGJI()
end

local function isTargetColor(pixel)
  return pixel[1] == Color_B and pixel[2] == Color_G and pixel[3] == Color_R
end

local function parseRange(rangeStr)
  if rangeStr == "0" then
    return 0, math.huge
  end
  local parts = {}
  for part in string.gmatch(rangeStr, "%d+") do
    table.insert(parts, tonumber(part))
  end
  if #parts == 1 then
    return parts[1], parts[1]
  else
    return parts[1], parts[2]
  end
end

local function findConnectedRegion(startX, startY, Row, Column, maxX, maxY)
  local left, top = startX, startY
  local right, bottom = startX, startY
  local changed
  repeat
    changed = false
    local newRight = right
    local newBottom = bottom
    while true do
      local canExpand = true
      for dx = 0, math.min(Column - 1, maxX - newRight) do
        for dy = 0, math.min(Row - 1, maxY - newBottom) do
          if not isTargetColor(ImgData[newRight + dx + 1][newBottom + dy + 1]) then
            canExpand = false
            break
          end
        end
        if not canExpand then
          break
        end
      end
      if canExpand then
        newRight = newRight + 1
        newBottom = newBottom + 1
        changed = true
      else
        break
      end
    end
    local newLeft = left
    local newTop = top
    while true do
      local canExpand = true
      for dx = 0, math.min(Column - 1, newLeft) do
        for dy = 0, math.min(Row - 1, newTop) do
          if not isTargetColor(ImgData[newLeft - dx + 1][newTop - dy + 1]) then
            canExpand = false
            break
          end
        end
        if not canExpand then
          break
        end
      end
      if canExpand and 0 < newLeft and 0 < newTop then
        newLeft = newLeft - 1
        newTop = newTop - 1
        changed = true
      else
        break
      end
    end
    left, top, right, bottom = newLeft, newTop, newRight, newBottom
  until not changed
  local binaryMap = {}
  for x = left, right do
    local rowStr = {}
    for y = top, bottom do
      table.insert(rowStr, isTargetColor(ImgData[x + 1][y + 1]) and "1" or "0")
    end
    binaryMap[x - left + 1] = table.concat(rowStr)
  end
  return {
    left = left,
    top = top,
    right = right,
    bottom = bottom,
    width = right - left + 1,
    height = bottom - top + 1,
    binaryMap = table.concat(binaryMap)
  }
end

local function isSizeInRange(width, height, minW, maxW, minH, maxH)
  return minW <= width and width <= maxW and minH <= height and height <= maxH
end

local function markRegionAsProcessed(region)
  for x = region.left, region.right do
    for y = region.top, region.bottom do
      ImgData[x + 1][y + 1][4] = region.bottom - y + 1
    end
  end
end

local function sortRegions(regions, count)
  table.sort(regions, function(a, b)
    return a[2] < b[2] or a[2] == b[2] and a[1] < b[1]
  end)
  local start = 1
  for i = 2, count do
    if regions[i][2] > regions[i - 1][2] + regions[i - 1][4] then
      if start < i - 1 then
        table.sort(regions, function(a, b)
          return a[1] < b[1]
        end, start, i - 1)
      end
      start = i
    end
  end
  if count > start then
    table.sort(regions, function(a, b)
      return a[1] < b[1]
    end, start, count)
  end
end

TURING["切割_范围投影切割"] = function(Row, Column, sWidth, sHeight)
  return TURING.Incise_ScopeAisle(Row, Column, sWidth, sHeight)
end

function TURING.Incise_ScopeAisle(Row, Column, sWidth, sHeight)
  local width = #ImgData - 1
  local height = #ImgData[1] - 1
  if width <= 0 or height <= 0 then
    return 0
  end
  Row = Row and math.max(Row, 1) or 1
  Column = Column and math.max(Column, 1) or 1
  sWidth = sWidth or "0"
  sHeight = sHeight or "0"
  IncData = {}
  local cnt = 0
  local visited = {}
  for x = 1, width + 1 do
    visited[x] = {}
  end
  local dirs = {
    {-1, -1},
    {-1, 0},
    {-1, 1},
    {0, -1},
    {0, 1},
    {1, -1},
    {1, 0},
    {1, 1}
  }
  local queue = {}
  for x = 0, width do
    for y = 0, height do
      if not visited[x + 1][y + 1] and ImgData[x + 1][y + 1][1] == Color_B and ImgData[x + 1][y + 1][2] == Color_G and ImgData[x + 1][y + 1][3] == Color_R then
        local minX, maxX = x, x
        local minY, maxY = y, y
        local qi = 0
        queue[qi + 1] = {x, y}
        qi = qi + 1
        visited[x + 1][y + 1] = true
        while 0 < qi do
          local cx, cy = queue[qi][1], queue[qi][2]
          qi = qi - 1
          minX = math.min(minX, cx)
          maxX = math.max(maxX, cx)
          minY = math.min(minY, cy)
          maxY = math.max(maxY, cy)
          for _, d in ipairs(dirs) do
            local nx, ny = cx + d[1], cy + d[2]
            if 0 <= nx and width >= nx and 0 <= ny and height >= ny and not visited[nx + 1][ny + 1] and ImgData[nx + 1][ny + 1][1] == Color_B and ImgData[nx + 1][ny + 1][2] == Color_G and ImgData[nx + 1][ny + 1][3] == Color_R then
              visited[nx + 1][ny + 1] = true
              qi = qi + 1
              queue[qi] = {nx, ny}
            end
          end
        end
        local w = maxX - minX + 1
        local h = maxY - minY + 1
        local lattice = {}
        for rx = minX, maxX do
          local line = {}
          for ry = minY, maxY do
            if ImgData[rx + 1][ry + 1][1] == Color_B and ImgData[rx + 1][ry + 1][2] == Color_G and ImgData[rx + 1][ry + 1][3] == Color_R then
              table.insert(line, "1")
              ImgData[rx + 1][ry + 1] = {
                0,
                0,
                0
              }
            else
              table.insert(line, "0")
            end
          end
          table.insert(lattice, table.concat(line, ""))
        end
        local sLattice = table.concat(lattice, "")
        local pass = true
        if sWidth ~= "0" or sHeight ~= "0" then
          local wMin, wMax = 1, 9999
          local hMin, hMax = 1, 9999
          if sWidth:find("-") then
            local t = Split(sWidth, "-")
            wMin, wMax = tonumber(t[1]), tonumber(t[2])
          else
            wMax = tonumber(sWidth) or 9999
          end
          if sHeight:find("-") then
            local t = Split(sHeight, "-")
            hMin, hMax = tonumber(t[1]), tonumber(t[2])
          else
            hMax = tonumber(sHeight) or 9999
          end
          pass = w >= wMin and w <= wMax and h >= hMin and h <= hMax
        end
        if pass then
          cnt = cnt + 1
          IncData[cnt] = {
            minX,
            minY,
            w,
            h,
            sLattice
          }
        end
      end
    end
  end
  if 0 < cnt then
    local n = #IncData
    H_TableSort(1, n)
    local bottom = IncData[1][2] + IncData[1][4]
    local start = 1
    for k = 1, n - 1 do
      local nxtTop = IncData[k + 1][2]
      local nxtBot = nxtTop + IncData[k + 1][4]
      if bottom <= nxtTop then
        W_TableSort(start, k)
        start = k + 1
        bottom = nxtBot
      elseif nxtBot > bottom then
        bottom = nxtBot
      end
    end
    if n >= start then
      W_TableSort(start, n)
    end
  end
  return cnt
end

function TURING.Incise_ScopeAisle(Row, Column, sWidth, sHeight)
  local width = #ImgData - 1
  local height = #ImgData[1] - 1
  if width <= 0 or height <= 0 then
    return 0
  end
  Row = Row and math.max(Row, 0) or 0
  Column = Column and math.max(Column, 0) or 0
  sWidth = sWidth or "0"
  sHeight = sHeight or "0"
  IncData = {}
  local cnt = 0
  local visited = {}
  for x = 1, width + 1 do
    visited[x] = {}
  end
  local dirs = {}
  for dx = -1 - Column, 1 + Column do
    for dy = -1 - Row, 1 + Row do
      if dx ~= 0 or dy ~= 0 then
        table.insert(dirs, {dx, dy})
      end
    end
  end
  local queue = {}
  for x = 0, width do
    for y = 0, height do
      if not visited[x + 1][y + 1] and ImgData[x + 1][y + 1][1] == Color_B and ImgData[x + 1][y + 1][2] == Color_G and ImgData[x + 1][y + 1][3] == Color_R then
        local minX, maxX = x, x
        local minY, maxY = y, y
        local qi = 0
        queue[qi + 1] = {x, y}
        qi = qi + 1
        visited[x + 1][y + 1] = true
        while 0 < qi do
          local cx, cy = queue[qi][1], queue[qi][2]
          qi = qi - 1
          minX = math.min(minX, cx)
          maxX = math.max(maxX, cx)
          minY = math.min(minY, cy)
          maxY = math.max(maxY, cy)
          for _, d in ipairs(dirs) do
            local nx, ny = cx + d[1], cy + d[2]
            if 0 <= nx and width >= nx and 0 <= ny and height >= ny and not visited[nx + 1][ny + 1] and ImgData[nx + 1][ny + 1][1] == Color_B and ImgData[nx + 1][ny + 1][2] == Color_G and ImgData[nx + 1][ny + 1][3] == Color_R then
              visited[nx + 1][ny + 1] = true
              qi = qi + 1
              queue[qi] = {nx, ny}
            end
          end
        end
        local w = maxX - minX + 1
        local h = maxY - minY + 1
        local lattice = {}
        for rx = minX, maxX do
          local line = {}
          for ry = minY, maxY do
            if ImgData[rx + 1][ry + 1][1] == Color_B and ImgData[rx + 1][ry + 1][2] == Color_G and ImgData[rx + 1][ry + 1][3] == Color_R then
              table.insert(line, "1")
              ImgData[rx + 1][ry + 1] = {
                0,
                0,
                0
              }
            else
              table.insert(line, "0")
            end
          end
          table.insert(lattice, table.concat(line, ""))
        end
        local sLattice = table.concat(lattice, "")
        local pass = true
        if sWidth ~= "0" or sHeight ~= "0" then
          local wMin, wMax = 1, 9999
          local hMin, hMax = 1, 9999
          if sWidth:find("-") then
            local t = Split(sWidth, "-")
            wMin, wMax = tonumber(t[1]), tonumber(t[2])
          else
            wMax = tonumber(sWidth) or 9999
          end
          if sHeight:find("-") then
            local t = Split(sHeight, "-")
            hMin, hMax = tonumber(t[1]), tonumber(t[2])
          else
            hMax = tonumber(sHeight) or 9999
          end
          pass = w >= wMin and w <= wMax and h >= hMin and h <= hMax
        end
        if pass then
          cnt = cnt + 1
          IncData[cnt] = {
            minX,
            minY,
            w,
            h,
            sLattice
          }
        end
      end
    end
  end
  if 0 < cnt then
    local n = #IncData
    H_TableSort(1, n)
    local bottom = IncData[1][2] + IncData[1][4]
    local start = 1
    for k = 1, n - 1 do
      local nxtTop = IncData[k + 1][2]
      local nxtBot = nxtTop + IncData[k + 1][4]
      if bottom <= nxtTop then
        W_TableSort(start, k)
        start = k + 1
        bottom = nxtBot
      elseif nxtBot > bottom then
        bottom = nxtBot
      end
    end
    if n >= start then
      W_TableSort(start, n)
    end
  end
  return cnt
end

function TURING.Incise_ScopeAisle(Row, Column, sWidth, sHeight)
  local width = #ImgData - 1
  local height = #ImgData[1] - 1
  if width <= 0 or height <= 0 then
    return 0
  end
  Row = Row and math.max(Row, 0) or 0
  Column = Column and math.max(Column, 0) or 0
  sWidth = sWidth or "0"
  sHeight = sHeight or "0"
  IncData = {}
  local cnt = 0
  local visited = {}
  for x = 1, width + 1 do
    visited[x] = {}
  end
  local dirs = {}
  for dx = -1 - Column, 1 + Column do
    for dy = -1 - Row, 1 + Row do
      if dx ~= 0 or dy ~= 0 then
        table.insert(dirs, {dx, dy})
      end
    end
  end
  local queue = {}
  for x = 0, width do
    for y = 0, height do
      if not visited[x + 1][y + 1] and ImgData[x + 1][y + 1][1] == Color_B and ImgData[x + 1][y + 1][2] == Color_G and ImgData[x + 1][y + 1][3] == Color_R then
        local minX, maxX = x, x
        local minY, maxY = y, y
        local qi = 0
        queue[qi + 1] = {x, y}
        qi = qi + 1
        visited[x + 1][y + 1] = true
        while 0 < qi do
          local cx, cy = queue[qi][1], queue[qi][2]
          qi = qi - 1
          minX = math.min(minX, cx)
          maxX = math.max(maxX, cx)
          minY = math.min(minY, cy)
          maxY = math.max(maxY, cy)
          for _, d in ipairs(dirs) do
            local nx, ny = cx + d[1], cy + d[2]
            if 0 <= nx and width >= nx and 0 <= ny and height >= ny and not visited[nx + 1][ny + 1] and ImgData[nx + 1][ny + 1][1] == Color_B and ImgData[nx + 1][ny + 1][2] == Color_G and ImgData[nx + 1][ny + 1][3] == Color_R then
              visited[nx + 1][ny + 1] = true
              qi = qi + 1
              queue[qi] = {nx, ny}
            end
          end
        end
        local w = maxX - minX + 1
        local h = maxY - minY + 1
        local lattice = {}
        for rx = minX, maxX do
          local line = {}
          for ry = minY, maxY do
            if ImgData[rx + 1][ry + 1][1] == Color_B and ImgData[rx + 1][ry + 1][2] == Color_G and ImgData[rx + 1][ry + 1][3] == Color_R then
              table.insert(line, "1")
              ImgData[rx + 1][ry + 1] = {
                0,
                0,
                0
              }
            else
              table.insert(line, "0")
            end
          end
          table.insert(lattice, table.concat(line, ""))
        end
        local sLattice = table.concat(lattice, "")
        local pass = true
        if sWidth ~= "0" or sHeight ~= "0" then
          local wMin, wMax = 1, 9999
          local hMin, hMax = 1, 9999
          if sWidth:find("-") then
            local t = Split(sWidth, "-")
            wMin, wMax = tonumber(t[1]), tonumber(t[2])
          else
            wMax = tonumber(sWidth) or 9999
          end
          if sHeight:find("-") then
            local t = Split(sHeight, "-")
            hMin, hMax = tonumber(t[1]), tonumber(t[2])
          else
            hMax = tonumber(sHeight) or 9999
          end
          if w > wMax then
            local numParts = math.ceil(w / wMax)
            local partWidth = math.floor(w / numParts)
            while true do
              if w > wMax then
                local tmp_lattice = {}
                for i = 1, wMax do
                  tmp_lattice[i] = table.remove(lattice, 1)
                end
                local tmp_sLattice = table.concat(tmp_lattice, "")
                w = #tmp_lattice
                cnt = cnt + 1
                IncData[cnt] = {
                  minX,
                  minY,
                  wMax,
                  h,
                  tmp_sLattice
                }
                w = #lattice
              else
                local tmp_sLattice = table.concat(lattice, "")
                cnt = cnt + 1
                IncData[cnt] = {
                  minX,
                  minY,
                  #lattice,
                  h,
                  tmp_sLattice
                }
                break
              end
            end
            pass = false
          end
        end
        if pass then
          cnt = cnt + 1
          IncData[cnt] = {
            minX,
            minY,
            w,
            h,
            sLattice
          }
        end
      end
    end
  end
  if 0 < cnt then
    local n = #IncData
    H_TableSort(1, n)
    local bottom = IncData[1][2] + IncData[1][4]
    local start = 1
    for k = 1, n - 1 do
      local nxtTop = IncData[k + 1][2]
      local nxtBot = nxtTop + IncData[k + 1][4]
      if bottom <= nxtTop then
        W_TableSort(start, k)
        start = k + 1
        bottom = nxtBot
      elseif nxtBot > bottom then
        bottom = nxtBot
      end
    end
    if n >= start then
      W_TableSort(start, n)
    end
  end
  return cnt
end

function TURING.OCR(Similar, Model, Interval, Step, Debug)
  Similar = Similar or 0
  Model = Model or 0
  Interval = Interval or 0
  Step = Step or 1
  Debug = Debug or false
  local IncNum = #IncData
  if IncNum <= 0 or #LibData <= 0 then
    return
  end
  local J, Z, B = {}, {}, {}
  local a, k = 1, 1
  local prevRight = 0
  
  local function slidingMatch(incStr, incW, incH, libStr, libW, libH)
    local maxMatch = 0
    local maxDX, maxDY = 0, 0
    for dx = 0, incW - libW, Step do
      for dy = 0, incH - libH, Step do
        local match = 0
        local remain = libW * libH - match
        for lx = 0, libW - 1 do
          for ly = 0, libH - 1 do
            local incPos = (dy + ly) * incW + (dx + lx) + 1
            local libPos = ly * libW + lx + 1
            if incStr:sub(incPos, incPos) == libStr:sub(libPos, libPos) then
              match = match + 1
            end
            remain = remain - 1
            if maxMatch >= match + remain then
              break
            end
          end
          if maxMatch >= match + remain then
            break
          end
        end
        if maxMatch < match then
          maxMatch, maxDX, maxDY = match, dx, dy
        end
      end
    end
    if Debug then
      print(string.format("  [滑动] 模板(%dx%d) → 块(%dx%d)  步长=%d  最佳匹配=%d (dx=%d,dy=%d)", libW, libH, incW, incH, Step, maxMatch, maxDX, maxDY))
      print(incStr)
      print(libStr)
    end
    return maxMatch
  end
  
  for i = 1, IncNum do
    local x, y, w, h, s = IncData[i][1], IncData[i][2], IncData[i][3], IncData[i][4], IncData[i][5]
    local bestChar, bestSim = nil, -1
    for j = 1, #LibData do
      local lib = LibData[j]
      if lib[4] == "" then
      else
      end
      local lw, lh, ls = tonumber(lib[2]), tonumber(lib[3]), lib[4]
      if math.abs(lw - w) > (lw + w) / 2 or math.abs(lh - h) > (lh + h) / 2 then
      elseif w < lw or h < lh then
      else
        local maxMatch = slidingMatch(s, w, h, ls, lw, lh)
        local sim1 = math.ceil(maxMatch / (lw * lh) * 100)
        local sim2 = math.ceil(maxMatch / (w * h) * 100)
        local sim = math.min(sim1, sim2)
        local score = maxMatch + sim1 + sim2
        if Debug then
          print(string.format("  [比对] 字符=%s  sim1=%.2f%%  sim2=%.2f%%  sim=%.2f%%  score=%.1f", lib[1], sim1, sim2, sim, score))
        end
        if 100 <= sim1 and 100 <= sim2 then
          bestChar, bestSim = lib[1], sim
          break
        end
        if sim > bestSim then
          bestChar, bestSim = lib[1], sim
        end
      end
    end
    if bestChar and not (Similar > bestSim) then
      if 2 <= Model and 0 <= Interval then
        local currLeft = x
        if 1 < k and Interval > currLeft - prevRight then
          J[a] = (J[a] or "") .. bestChar
        else
          if 1 < k then
            Z[a] = (J[a] or "") .. "," .. (Z[a] or "")
            if Model == 3 then
              B[a] = Z[a] .. "," .. (B[a] or "")
            end
            a = a + 1
          end
          J[a] = bestChar
          Z[a] = x .. "," .. y
          if Model == 3 then
            B[a] = bestSim
          end
        end
        prevRight = x + w
      else
        J[a] = (J[a] or "") .. bestChar
        if Model == 1 then
          Z[a] = x .. "," .. y
        elseif 2 <= Model then
          Z[a] = bestChar .. "," .. x .. "," .. y
          if Model == 3 then
            B[a] = Z[a] .. "," .. bestSim
          end
        end
        a = a + 1
      end
      k = k + 1
    end
  end
  if 2 <= Model and 0 <= Interval and 1 <= a and 1 < k then
    Z[a] = (J[a] or "") .. "," .. (Z[a] or "")
    if Model == 3 then
      B[a] = Z[a] .. "," .. (B[a] or "")
    end
  end
  if a == 1 and k == 1 then
    return ""
  end
  return Model == 0 and table.concat(J, "") or Model == 1 and table.concat(J, "") .. "|" .. table.concat(Z, "|") or Model == 2 and table.concat(Z, "|") or Model == 3 and table.concat(B, "|") or table.concat(J, "|")
end

return TURING
