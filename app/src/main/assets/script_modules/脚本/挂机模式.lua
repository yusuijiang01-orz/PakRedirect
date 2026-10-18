local L0_1, L1_1

local function 采集检测点颜色和(检测点)
  keepCapture()
  local ok, colornumber = pcall(function()
    local sum = 0
    for i = 1, #检测点 do
      sum = sum + getPixelColor(检测点[i][1], 检测点[i][2], 1)
    end
    return sum
  end)
  releaseCapture()
  if not ok then
    error(colornumber, 0)
  end
  return colornumber
end

function L0_1()
  if _ENV["上次检测卡死"] == nil or tickCount() - _ENV["上次检测卡死"] > 5000 then
    if _ENV["没有变化次数"] == nil then
      _ENV["没有变化次数"] = 0
    end
    local 检测点 = {}
    local a, b, c, d = 387, 60, 1218, 465
    for x = a, c, 10 do
      for y = b, d, 10 do
        table.insert(检测点, {x, y})
      end
    end
    local colornumber = 采集检测点颜色和(检测点)
    print(colornumber)
    if _ENV["上次检测点色值"] == colornumber then
      _ENV["没有变化次数"] = _ENV["没有变化次数"] + 1
      if _ENV["没有变化次数"] >= 10 then
        toast("长时间没变化")
        sleep(1000)
        _ENV["没有变化次数"] = 0
        return true
      end
    else
      toast("没有卡死")
      _ENV["没有变化次数"] = 0
    end
    _ENV["上次检测卡死"] = tickCount()
    _ENV["上次检测点色值"] = colornumber
  else
  end
end

_ENV["检测卡死"] = L0_1

function L0_1()
  if _ENV["检测寻路完成_寻路没有变化次数"] == nil then
    _ENV["检测寻路完成_寻路没有变化次数"] = 0
  end
  local 检测点 = {}
  local a, b, c, d = 1132, 168, 1196, 189
  for x = a, c, 10 do
    for y = b, d, 10 do
      table.insert(检测点, {x, y})
    end
  end
  local colornumber = 采集检测点颜色和(检测点)
  if _ENV["检测寻路完成_上次检测寻路点色值"] == colornumber then
    _ENV["检测寻路完成_寻路没有变化次数"] = _ENV["检测寻路完成_寻路没有变化次数"] + 1
    if _ENV["检测寻路完成_寻路没有变化次数"] >= 3 then
      toast("寻路完成")
      sleep(1000)
      _ENV["检测寻路完成_寻路没有变化次数"] = 0
      return true
    end
  else
    _ENV["检测寻路完成_寻路没有变化次数"] = 0
  end
  _ENV["检测寻路完成_上次检测寻路点色值"] = colornumber
end

_ENV["检测寻路完成"] = L0_1

function L0_1()
  if _ENV["检测挂机长时间不移动_上次检测寻路"] == nil or tickCount() - _ENV["检测挂机长时间不移动_上次检测寻路"] > 1000 then
    if _ENV["检测挂机长时间不移动_寻路没有变化次数"] == nil then
      _ENV["检测挂机长时间不移动_寻路没有变化次数"] = 0
    end
    local 检测点 = {}
    local a, b, c, d = 1124, 166, 1201, 193
    local a, b, c, d = 1131, 169, 1196, 190
    for x = a, c, 10 do
      for y = b, d, 10 do
        table.insert(检测点, {x, y})
      end
    end
    local colornumber = 采集检测点颜色和(检测点)
    print(colornumber)
    if _ENV["检测挂机长时间不移动_上次检测寻路点色值"] == colornumber then
      _ENV["检测挂机长时间不移动_寻路没有变化次数"] = _ENV["检测挂机长时间不移动_寻路没有变化次数"] + 1
      if _ENV["检测挂机长时间不移动_寻路没有变化次数"] >= tonumberEx(_ENV["界面数据"]["挂机不移动秒"]) then
        toast("挂机长时间不移动")
        sleep(1000)
        _ENV["检测挂机长时间不移动_寻路没有变化次数"] = 0
        return true
      end
    else
      _ENV["检测挂机长时间不移动_寻路没有变化次数"] = 0
    end
    _ENV["检测挂机长时间不移动_上次检测寻路"] = tickCount()
    _ENV["检测挂机长时间不移动_上次检测寻路点色值"] = colornumber
  else
  end
end

_ENV["检测挂机长时间不移动"] = L0_1

function L0_1()
  local 返回值 = false
  local tt1 = tickCount()
  local t1 = _ENV["取上次点击时间"]()
  if _ENV["找到"]("@@登录横竖不对", tz["登录横竖不对"]) then
    keyPress("home")
    sleep(1000)
  end
  if _ENV["检测卡死"]() then
    stopApp(_ENV["游戏包名"])
    sleep(4000)
    返回值 = true
  end
  if _ENV["找到"](tz["提示掉线"]) or _ENV["找到"](tz["提示下载出现问题"]) or _ENV["找到"](tz["提示服务器维护中"]) or _ENV["找到"](tz["提示账号使用中"]) then
    toast("提示掉线 下载出现问题 服务器维护中 提示账号使用中 重启游戏")
    stopApp(_ENV["游戏包名"])
    sleep(4000)
    返回值 = true
  end
  if not appIsRunning(_ENV["游戏包名"]) then
    _ENV["当前任务2"] = "游戏没有开启 开一次vpn"
    _ENV["开启vpn函数"]()
    sleep(1000)
    toast("启动游戏")
    _ENV["需要去挂机点一次"] = true
    runApp(_ENV["游戏包名"])
    sleep(4000)
    返回值 = true
  end
  if frontAppName() ~= _ENV["游戏包名"] then
    _ENV["当前任务2"] = "游戏没有开启 开一次vpn"
    _ENV["开启vpn函数"]()
    sleep(1000)
    toast("启动游戏")
    _ENV["需要去挂机点一次"] = true
    runApp(_ENV["游戏包名"])
    sleep(4000)
    返回值 = true
  end
  if _ENV["找到"](tz["负重满了提示"]) then
    toast("负重满了提示")
  end
  if _ENV["找到"](tz["组队_附近玩家页面"]) or _ENV["找到"](tz["组队_附近队伍页面"]) then
    _ENV["点击"]("@@附近玩家页面", 1193, 130, 1000)
  end
  _ENV["找到点击"]("@@行为设置页面关闭按钮", tz["行为设置页面关闭按钮"])
  _ENV["找到点击"]("@@不更新", tz["新版本更新按钮"], {
    735,
    448,
    1000
  })
  _ENV["找到点击"]("@@拍卖行页面", tz["拍卖行页面"], {
    1200,
    127,
    1000
  })
  _ENV["找到点击"]("@@死亡有时候弹出", tz["死亡有时候弹出"])
  if _ENV["找到"]("@@提示或拒绝", tz["弹出提示或拒绝"]) then
    sleep(500)
    _ENV["找到点击"]("@@是否加入其他人队伍弹出", tz["是否加入其他人队伍弹出"], {
      755,
      411,
      1000
    })
  end
  print("通用处理用时" .. tickCount() - tt1)
  if t1 < _ENV["取上次点击时间"]() then
    返回值 = true
  end
  return 返回值
end

_ENV["通用处理"] = L0_1

function L0_1()
  if _ENV["找到"](tz["对话页面"]) then
    if _ENV["找到"](tz["对话进入商店按钮"]) then
      _ENV["点击"]("@@关闭对话 不去商店", 1233, 421, 1000)
    else
      _ENV["点击"]("@@关闭对话", 892, 652, 1000)
    end
  end
  if _ENV["找到"](tz["商店买货确定页面"]) then
    _ENV["点击"]("@@关闭商店买货确定页面", 914, 198, 1000)
  end
  if _ENV["找到"](tz["商店卖货确定页面"]) then
    _ENV["点击"]("@@关闭商店卖货确定页面", 714, 497, 1000)
  end
  if _ENV["找到"](tz["商店选中装备属性页面"]) or _ENV["找到"](tz["商店选中非装备属性页面"]) then
    _ENV["点击"]("@@关闭商店选中装备属性页面", 1188, 626, 1000)
  end
  if _ENV["找到"](tz["签到页面"]) then
    _ENV["点击"]("@@关闭签到页面", 1198, 119, 1000)
  end
  if _ENV["找到"](tz["背包页面"]) then
    _ENV["点击"]("@@关闭背包", 1244, 104, 1000)
  end
  if _ENV["找到"](tz["商店页面"]) then
    _ENV["点击"]("@@关闭商店", 1244, 104, 1000)
  end
  if _ENV["找到"](tz["大地图页面"]) then
    _ENV["点击"]("@@关闭大地图", 1239, 40, 1000)
  end
  if _ENV["找到"](tz["确定输入内容"]) then
    _ENV["点击"]("@@关闭输入内容", 85, 190, 1000)
  end
  if _ENV["找到"](tz["自动设置页面"]) then
    _ENV["点击"]("@@自动设置页面 关闭", 1234, 54, 2000)
  end
end

_ENV["通用关闭"] = L0_1

function L0_1(目标区)
  for i = 1, 2 do
    if 目标区 == "1" or 目标区 == "2" or 目标区 == "3" or 目标区 == "4" then
      _ENV["点击"](165, 201, 1000)
      _ENV["点击"](540, 230, 1000)
    end
    if 目标区 == "5" or 目标区 == "6" then
      _ENV["点击"](165, 201, 1000)
      _ENV["点击"](984, 237, 1000)
    end
    if 目标区 == "7" then
      _ENV["点击"](165, 201, 1000)
      _ENV["点击"](537, 323, 1000)
    end
    if 目标区 == "8" then
      _ENV["点击"](165, 201, 1000)
      _ENV["点击"](999, 327, 1000)
    end
    if 目标区 == "9" then
      _ENV["点击"](165, 201, 1000)
      _ENV["点击"](542, 414, 1000)
    end
  end
  _ENV["点击"]("@@确定", 755, 662, 2000)
end

_ENV["登录_选区"] = L0_1

function L0_1(a, b, c, d)
  local x = -1
  local y = -1
  x, y = findMultiColor(a, b, c, d, "8c2829-111111", "3|-7|a53031-111111|4|-12|842829-111111|1|-16|ad3031-111111|-1|-22|842421-111111|-4|-17|c67173-111111|-8|-12|bd494a-111111|-9|-10|b53431-111111|-7|-4|842429-111111|-6|-2|8c2829-111111|-2|-3|942829-111111|-1|-13|a52c31-111111|-1|-20|8c2829-111111|-5|-10|f7d7d6-111111|-5|-12|e7b2b5-111111|-3|-28|ad6531-111111|-2|-28|ad6531-111111|0|-27|b56d31-111111|-1|-29|9c5929-111111|-2|-23|7b2421-111111|-2|-19|9c2c29-111111|0|-13|a52c31-111111", 0, 0.9)
  if x ~= -1 and y ~= -1 then
    return "小红"
  end
  local x = -1
  local y = -1
  x, y = findMultiColor(a, b, c, d, "8c2829-111111", "-1|-1|942d29-111111|-5|-4|ad3d3a-111111|-1|-14|b5494a-111111|0|-17|942829-111111|4|-11|a42d29-111111|7|-4|a43131-111111|3|-6|ad3131-111111|-2|-7|efcece-111111|-2|-8|e6b2b5-111111|-1|-24|845529-111111|1|-25|a45d31-111111|4|-24|845129-111111|4|-23|ad6d31-111111|1|-22|ad8142-111111|5|-11|ad3131-111111|7|-6|a43131-111111|2|2|9c2d29-111111|-1|-3|a4393a-111111|2|-9|ad3131-111111|66|63|291410-111111", 0, 0.9)
  if x ~= -1 and y ~= -1 then
    return "小红"
  end
  local x = -1
  local y = -1
  x, y = findMultiColor(a, b, c, d, "943431-111111", "-5|-1|942829-111111|-9|-5|942829-111111|-12|-10|b54142-111111|-10|-18|a52c31-111111|0|-20|7b2421-111111|3|-18|842021-111111|4|-14|9c3031-111111|2|-7|b5595a-111111|-4|-9|ce797b-111111|-7|-13|dea2a5-111111|-9|-16|d69a9c-111111|-4|-25|b58e42-111111|-6|-27|b58e4a-111111|-9|-25|a57931-111111|-7|-23|9c6d31-111111|-2|-24|9c6529-111111|-3|-27|9c6529-111111|-2|-17|943439-111111|-6|-8|c67173-111111|-5|-1|942829-111111|-8|-1|942829-111111|-3|-1|8c2829-111111|1|-3|ad5552-111111", 0, 0.9)
  if x ~= -1 and y ~= -1 then
    return "大红"
  end
  local x = -1
  local y = -1
  x, y = findMultiColor(a, b, c, d, "3151a5-111111", "-2|5|3155a5-111111|-3|7|29458c-111111|-6|8|213c73-111111|-9|2|294994-111111|-11|-1|395dad-111111|-10|-4|849ad6-111111|-7|-9|8c9ece-111111|-5|-11|3151a5-111111|-8|-4|d6dbef-111111|-9|1|294d9c-111111|-5|5|294d9c-111111|-2|-2|294d9c-111111|-3|-10|3155a5-111111|-4|-15|214184-111111|-5|-17|213c7b-111111|-6|-20|b57939-111111|-7|-20|b5824a-111111|-5|-21|ad6531-111111|-5|-23|211c18-111111|-5|-21|ad6531-111111|-4|-20|b56931-111111|-5|-21|ad6531-111111|-6|-22|a55d31-111111|-6|-23|211c18-111111", 0, 0.9)
  if x ~= -1 and y ~= -1 then
    return "小蓝"
  end
  local x = -1
  local y = -1
  x, y = findMultiColor(a, b, c, d, "3155a4-111111", "-5|6|294d9c-111111|-7|6|294d9c-111111|-12|2|294994-111111|-13|-2|4265b5-111111|-11|-5|738ed6-111111|-10|-3|cedbef-111111|-8|-9|849ace-111111|-8|-15|214184-111111|-5|-13|29498c-111111|-5|-13|29498c-111111|-8|-21|ad6931-111111|-6|-21|ad6531-111111|-5|-21|ad6931-111111", 0, 0.9)
  if x ~= -1 and y ~= -1 then
    return "小蓝"
  end
  local x = -1
  local y = -1
  x, y = findMultiColor(a, b, c, d, "ad7d39-111111", "-1|-1|634921-111111|2|-2|b58e4a-111111|6|-1|9c6929-111111|7|0|9c7131-111111|4|1|ad8639-111111|2|0|ceb263-111111|1|1|ad8639-111111|7|2|945d29-111111|9|5|213c7b-111111|12|10|29458c-111111|12|14|31519c-111111|11|18|526dad-111111|10|22|5269ad-111111|7|26|21346b-111111|3|27|21346b-111111|0|25|294184-111111|-3|20|213c84-111111|-5|17|395db5-111111|-5|13|294d9c-111111|-4|8|3959a5-111111|-2|8|5271b5-111111|-1|11|e7eff7-111111|2|16|94a6d6-111111|5|14|6382c6-111111|3|9|637dbd-111111|0|9|9caad6-111111|0|12|cedbef-111111|0|15|94aad6-111111|4|18|4261a5-111111|9|11|315194-111111", 0, 0.9)
  if x ~= -1 and y ~= -1 then
    return "大蓝"
  end
  return ""
end

_ENV["检测背包_识别红蓝数量_识别红蓝"] = L0_1

function L0_1()
  hideToast()
  sleep(500)
  local 药品信息 = {}
  for yy = 149, 575, 71 do
    for xx = 670, 1035, 71 do
      if 1024 < xx then
      else
        local 物品类型 = _ENV["检测背包_识别红蓝数量_识别红蓝"](xx, yy, xx + 68, yy + 72)
        if 物品类型 == "" then
        else
          药品信息[物品类型] = tomocrtextall(xx - 20 + 808 - 792, yy + 189 - 149, xx + 68 + 840 - 880, yy + 72 + 214 - 221)
        end
      end
    end
  end
  return 药品信息
end

_ENV["检测背包_识别红蓝数量"] = L0_1

function L0_1(出售种类)
  hideToast()
  sleep(500)
  local 药品信息 = {}
  for yy = 149, 575, 71 do
    for xx = 670, 1035, 71 do
      if 1024 < xx then
      else
        local 物品类型 = _ENV["检测背包_识别红蓝数量_识别红蓝"](xx, yy, xx + 68, yy + 72)
        if 物品类型:find("红") and 出售种类:find("红") or 物品类型:find("蓝") and 出售种类:find("蓝") then
          _ENV["点击"]("@@点击" .. 出售种类, xx + 15, yy + 15, 1000)
          _ENV["点击"]("@@点击出售" .. 出售种类, 933, 350, 1000)
          _ENV["点击"]("@@点击确认" .. 出售种类, 544, 496, 1000)
        end
      end
    end
  end
  return 药品信息
end

_ENV["去商店_卖货_出售红蓝"] = L0_1

function L0_1(要做的处理, 当前处理的药数量)
  local 购买数量 = 0
  if 要做的处理 == "买蓝" then
    购买数量 = tonumberEx(_ENV["界面数据"]["购买蓝的数量"])
    if 购买数量 < 1 then
      toast("蓝药购买数量小于1，已跳过")
      return false
    end
    _ENV["点击"]("@@点蓝", 394, 139, 1000)
    _ENV["点击"]("@@购买", 927, 351, 1000)
  end
  if 要做的处理 == "买红" then
    购买数量 = tonumberEx(_ENV["界面数据"]["购买红的数量"])
    if 购买数量 < 1 then
      toast("红药购买数量小于1，已跳过")
      return false
    end
    _ENV["点击"]("@@点红", 142, 139, 1000)
    _ENV["点击"]("@@购买", 927, 351, 1000)
  end
  if _ENV["找到"](tz["商店买货确定页面"]) then
    for i = 1, tonumber(购买数量) - 1 do
      _ENV["点击"]("@@购买+1", 773, 356, 500)
    end
    _ENV["点击"]("@@确定购买数量", 545, 495, 1000)
  end
end

_ENV["去商店_卖货_购买红蓝"] = L0_1

function L0_1()
  local index, x, y = findColor(797, 90, 1013, 130, "ffff00-111111|ffff00-111111|ffff00-111111|f7f700-111111", 0, 0.9)
  if 0 < x then
    return "黄色"
  end
  local index, x, y = findColor(797, 90, 1013, 130, "00ff00-111111|00e700-111111|00ff00-111111|00f700-111111|00c600-111111|00ff00-111111|00ff00-111111|00d200-111111|00ff00-111111", 0, 0.9)
  if 0 < x then
    return "绿色"
  end
  local index, x, y = findColor(797, 90, 1013, 130, "efa23a-111111|efa231-111111|e69e3a-111111|e69e3a-111111|e69a31-111111|e69a31-111111|f7aa3a-111111|c58629-111111|c58629-111111", 0, 0.9)
  if 0 < x then
    return "橙色"
  end
  return "白色"
end

_ENV["去卖货_卖货_识别装备品质"] = L0_1

function L0_1(装备品质, 出售装备品质上限)
  local 上限 = tonumberEx(出售装备品质上限)
  if 装备品质 == "白色" then
    return 上限 >= 1
  end
  if 装备品质 == "绿色" then
    return 上限 >= 2
  end
  return false
end

_ENV["去卖货_卖货_品质是否允许出售"] = L0_1

function L0_1()
  return _ENV["去卖货_卖货_识别装备品质"]() ~= "白色"
end

_ENV["去卖货_卖货_判断是否稀有装备"] = L0_1

function L0_1()
  local x, y = findMultiColor(682, 225, 1047, 663, "e7e700-111111", "57|-1|e7e300-111111|37|12|cecb00-111111|40|4|efef00-111111|40|8|e7e300-111111|31|0|e7eb00-111111|20|9|bdbe00-111111|23|8|f7f300-111111|13|-1|ffff00-111111|14|1|ffff00-111111|-30|-1|dedf00-111111|-31|0|e7e700-111111|-27|12|cecb00-111111|-26|0|eff300-111111|-27|8|e7e700-111111|-47|-1|dee300-111111|-44|7|ffff00-111111|9|7|efef00-111111|-53|5|bdbe00-111111|-57|-3|f7f300-111111", 0, 0.9)
  if x ~= -1 and y ~= -1 then
    return true
  end
  return false
end

_ENV["去卖货_卖货_判断是否装备"] = L0_1

function L0_1()
  keepCapture()
  local 蓝条数量 = 0
  local a, b, c, d = 688, 240, 1005, 658
  for i = 1, 10 do
    local index, x, y = findColor(a, b, c, d, "6361ff-222222|6361ff-222222|6361ff-222222|3a3994-222222", 0, 0.9)
    print(index, x, y)
    if 0 < x then
      b = y + 26
      print(x, y)
      蓝条数量 = 蓝条数量 + 1
    else
      break
    end
  end
  releaseCapture()
  return 蓝条数量
end

_ENV["去卖货_卖货_识别蓝条数量"] = L0_1

function L0_1(a, b, c, d)
  local x = -1
  local y = -1
  x, y = findMultiColor(a, b, c, d, "5a5def-111111", "0|4|5a5def-111111|-1|12|5a5def-111111|58|17|4a49b5-111111|37|18|5a5def-111111|70|11|6365ff-111111|70|6|6365ff-111111|65|6|6365ff-111111|65|8|6365ff-111111|66|10|6365ff-111111|66|11|6365ff-111111|66|12|6365ff-111111|67|13|635def-111111|68|13|5a5def-111111|67|4|5a5def-111111|68|4|5a5def-111111|31|12|5a5def-111111|31|9|5a5def-111111|31|6|5a5def-111111|30|4|6361f7-111111|29|4|5255de-111111|27|4|635def-111111|26|4|6365ff-111111|26|6|6365ff-111111|26|9|6365ff-111111|26|12|6365ff-111111", 0, 0.9)
  if x ~= -1 and y ~= -1 then
    return "回蓝"
  end
  local x = -1
  local y = -1
  x, y = findMultiColor(a, b, c, d, "6361f7-111111", "3|5|6365ff-111111|5|12|6361f7-111111|0|12|5a5def-111111|5|1|6365ff-111111|132|11|5251d6-111111|131|2|6361f7-111111|130|16|6361f7-111111|88|11|6361ff-111111|85|1|6b65ff-111111|97|0|5251d6-111111|98|8|6361f7-111111|94|11|6361ff-111111|94|5|6361ff-111111|88|11|6361ff-111111|107|1|6365ff-111111|107|8|6365ff-111111|110|8|6361ff-111111|116|0|6365ff-111111|116|4|6365ff-111111|116|12|6365ff-111111|121|11|5a5def-111111|121|6|5a59e6-111111|119|4|5255de-111111", 0, 0.9)
  if x ~= -1 and y ~= -1 then
    return "武器出招"
  end
  local x = -1
  local y = -1
  x, y = findMultiColor(a, b, c, d, "5a59e7-111111", "-2|6|6361f7-111111|-1|0|5a59e7-111111|-79|7|6365ff-111111|-81|12|6361f7-111111|-81|0|6361ff-111111|-76|0|6361f7-111111|61|14|6365ff-111111|62|11|393c9c-111111|60|6|5255d6-111111|61|3|393894-111111|56|-1|6361f7-111111|55|0|5251ce-111111|43|1|5a59e7-111111|43|4|5a59e7-111111|43|8|5a59e7-111111|43|11|5a59e7-111111|35|4|6365ff-111111|34|8|5a5def-111111|34|12|5a5def-111111|33|15|101021-111111|71|12|6361f7-111111|72|8|4a49b5-111111|72|4|393c94-111111|71|1|4a4dc6-111111", 0, 0.9)
  if x ~= -1 and y ~= -1 then
    return "魔法出招"
  end
  return ""
end

_ENV["去卖货_卖货_识别蓝条数量和属性_识别蓝条属性"] = L0_1

function L0_1(a, b, c, d)
  local function 文字识别库()
    local 识别库 = {}
    
    识别库[1] = "0|7,15|1&uV&t0EW0S03&&p&y"
    识别库[2] = "0|7,15|3&y&&q02W0U06V&m&m"
    识别库[3] = "1|3,15|200u07&&"
    识别库[4] = "1|4,15|G0200&&t&&"
    识别库[5] = "1|4,15|G0600&&&&#"
    识别库[6] = "1|4,15|G0600&&t&&"
    识别库[7] = "2|7,15|002S1#0VWES7Z&mRu3"
    识别库[8] = "2|7,15|3W7u3y0xWSUF3&WPm0"
    识别库[9] = "2|7,15|3W7y3y0&WUSF3&WRu0"
    识别库[10] = "2|7,15|1W2y1#0&WUS7Z&mRu3"
    识别库[11] = "2|7,15|3W7y1#0&WUSFZ&mRu3"
    识别库[12] = "3|7,15|30Su3q06WWSE6&&pny"
    识别库[13] = "3|6,15|O3c0EW0S43nmpxy"
    识别库[14] = "3|7,15|30Su1q03WWUE6VVnWm"
    识别库[15] = "4|7,15|07W3a3WWu67&&&&m0W"
    识别库[16] = "4|7,15|07W3i1mWu67&&&&m0m"
    识别库[17] = "5|6,15|&ZcSEX0S83XWq7y"
    识别库[18] = "5|7,15|7yC&ZqC6X0S82X&m7y"
    识别库[19] = "5|6,15|y1qC6X0C82Xnm5q"
    识别库[20] = "5|7,15|7ySxXq83X0SC6W&m1m"
    识别库[21] = "6|7,15|1&u&&sC6X0SC2v&o7y"
    识别库[22] = "6|7,15|1&uV&sE6X0S83n&p7y"
    识别库[23] = "7|5,15|400W3yFy&m700"
    识别库[24] = "7|6,15|W040EWV&&m&0400"
    识别库[25] = "7|6,15|W040FW&&&m&0400"
    识别库[26] = "8|7,15|3py&vqC2WWUE6VVm0u"
    识别库[27] = "8|7,15|1Xy&&sE6WWSC3&&pny"
    识别库[28] = "9|7,15|1y8&ns66WGS23&&p&y"
    识别库[29] = "4|8,15|0S0FWF470m&&t&&0600W"
    识别库[30] = "6|7,15|1&y&&qC3X0SC2u&m3u"
    识别库[31] = "0|8,15|7&Z&&S0#01G0B&&F&m10"
    识别库[32] = "1|4,15|80300V&&&&"
    识别库[33] = "9|6,15|Vnc66WGS23uvp&y"
    识别库[34] = "9|7,15|3yC&nq23WGU66V&m&m"
    识别库[35] = "5|7,15|7yS&XqC6X0SC2X&m7y"
    识别库[36] = "8|7,15|3ny&&sE3WWUE3&&nny"
    识别库[37] = "8|7,15|3ny&&qE2WWUE2&&nXy"
    识别库[38] = "1|4,15|G0700&&&Ts"
    识别库[39] = "0|7,15|0&yV&x03m0A01V&v&#"
    识别库[40] = "0|7,15|3&y&&q02W0S02&&n&u"
    识别库[41] = "6|7,15|0&mV&tEEX0SC3nxp7y"
    识别库[42] = "1|4,15|G0700&&zLK"
    识别库[43] = "0|7,15|1lu&&q02W0S02d#H&y"
    识别库[44] = "5|6,15|VntE7mWE41mmQ3#"
    识别库[45] = "1|4,15|G0700&&xkw"
    识别库[46] = "8|7,15|3ny&&qC3WWUE2V&m1u"
    识别库[47] = "4|8,15|0E0Fm7Y3WG&&&&&0300G"
    识别库[48] = "0|8,15|F&3&#u0q03W0V&#V&W20"
    识别库[49] = "5|7,15|3#4&n#63mWE41m&u3#"
    识别库[50] = "1|4,15|G0700&&#xk"
    识别库[51] = "6|7,14|0Vu&&iSU41X0SU&3#"
    识别库[52] = "0|7,15|1&u&&s06W0S03&&p&y"
    识别库[53] = "6|7,15|3&y&&q82X0SC6u&m1m"
    识别库[54] = "6|7,15|1&yV&w61mWA61O&u1y"
    识别库[55] = "+|6,7|42F&yG8"
    识别库[56] = "+|6,7|421dyO8"
    识别库[57] = "+|5,7|21dyO8"
    识别库[58] = "+|6,7|42FvmG8"
    识别库[59] = "3|7,15|1WES1w01mGA73V&umO"
    识别库[60] = "3|7,15|30Su3s07WWSE3&&pny"
    识别库[61] = "3|7,15|30Oe2q06WWCA2&&omi"
    识别库[62] = "7|6,15|W0400W3yFu&W700"
    识别库[63] = "2|7,15|1m3U0x0VGFE7nVu9y1"
    识别库[64] = "1|4,15|803W0&&wgg"
    识别库[65] = "9|7,15|1#6Uuw11m8B33F&mVu"
    识别库[66] = "5|7,15|3#Evm#41mWE63G&m0m"
    return 识别库
  end
  
  _ENV["加载结果"] = TURING["字库_加载六十四进制识别字库"](文字识别库())
  TURING["来源_获取屏幕像素"](a, b, c, d)
  TURING["滤镜_彩色_二值化"]("FF6563-202020|DE5552-202020")
  TURING["切割_范围投影切割"](1, 1)
  local 切割数据 = TURING["切割_获取切割字符数据"]()
  local 识别结果 = TURING["识别"](85)
  if 识别结果 ~= nil and 识别结果 ~= "" then
    local arr = splitStr(识别结果, "+")
    if #arr == 2 then
      return tonumberEx(arr[2])
    end
  end
  return 0
end

_ENV["去卖货_卖货_识别蓝条数量和属性_识别蓝条数值"] = L0_1

function L0_1()
  local 装备蓝条属性数组 = {}
  keepCapture()
  local 蓝条数量 = 0
  local a, b, c, d = 688, 240, 1005, 658
  for i = 1, 10 do
    local index, x, y = findColor(a, b, c, d, "6361ff-222222|6361ff-222222|6361ff-222222|3a3994-222222", 0, 0.9)
    if 0 < x then
      b = y + 26
      蓝条数量 = 蓝条数量 + 1
      local aa, bb, cc, dd = 663, y - 8, 1050, y + 26
      local 蓝条属性 = _ENV["去卖货_卖货_识别蓝条数量和属性_识别蓝条属性"](aa, bb, cc, dd)
      local 蓝条数值 = _ENV["去卖货_卖货_识别蓝条数量和属性_识别蓝条数值"](aa, bb, cc, dd)
      table.insert(装备蓝条属性数组, {
        ["蓝条属性"] = 蓝条属性,
        ["蓝条数值"] = 蓝条数值
      })
    else
      break
    end
  end
  releaseCapture()
  if 4 <= #装备蓝条属性数组 then
  end
  return 装备蓝条属性数组
end

_ENV["去卖货_卖货_识别蓝条数量和属性"] = L0_1

function L0_1(要做的处理, 当前处理的药数量)
  if 要做的处理 == "卖蓝" then
    _ENV["去商店_卖货_出售红蓝"](要做的处理)
  end
  if 要做的处理 == "卖红" then
    _ENV["去商店_卖货_出售红蓝"](要做的处理)
  end
  if 要做的处理 == "买蓝" then
    _ENV["去商店_卖货_购买红蓝"](要做的处理, 当前处理的药数量)
  end
  if 要做的处理 == "买红" then
    _ENV["去商店_卖货_购买红蓝"](要做的处理, 当前处理的药数量)
  end
end

_ENV["去卖货_卖货_执行红蓝处理"] = L0_1

function L0_1()
  if not _ENV["找到"](tz["商店卖货确定页面"]) then
    return true
  end
  if _ENV["界面数据"]["出售装备二次确认"] == "true" then
    toast("请在游戏中确认或取消装备出售")
    for i = 1, 120 do
      sleep(500)
      if not _ENV["找到"](tz["商店卖货确定页面"]) then
        return true
      end
    end
    toast("装备出售确认超时，停止后续卖货")
    _ENV["点击"]("@@关闭商店卖货确定页面", 714, 497, 1000)
    return false
  end
  _ENV["点击"]("@@确定卖掉", 537, 494, 1000)
  return true
end

_ENV["去卖货_处理装备出售确认"] = L0_1

function L0_1()
  local 格子x = 0
  local 格子y = 0
  -- The legacy script used to enter equipment sales unconditionally. Keep the
  -- workbench's protective defaults authoritative: only scan/sell gear when the
  -- user explicitly enables sales and has not enabled the protection switch.
  local 出售装备品质上限 = tonumberEx(_ENV["界面数据"]["出售装备品质上限"])
  local 允许出售装备 = _ENV["界面数据"]["出售装备"] == "true"
    and _ENV["界面数据"]["保护装备不出售"] ~= "true"
    and 出售装备品质上限 >= 1
  local 当前任务3 = 允许出售装备 and "卖装备" or "买卖药"
  _ENV["买了一次蓝"] = false
  _ENV["买了一次红"] = false
  for i = 1, 200 do
    if _ENV["找到"](tz["对话页面"]) then
      _ENV["点击"]("@@关对话", 1229, 422, 1000)
    end
    if _ENV["找到"](tz["商店卖货确定页面"]) then
      if 当前任务3 == "卖装备" then
        if not _ENV["去卖货_处理装备出售确认"]() then return end
      else
        _ENV["点击"]("@@确定卖掉", 537, 494, 1000)
      end
    end
    if _ENV["找到"](tz["商店买货确定页面"]) then
      _ENV["点击"]("@@确定购买", 537, 494, 1000)
    end
    if 当前任务3 == "卖装备" then
      if _ENV["找到"](tz["商店页面"]) then
        print(695 + 格子x * 73, 178 + 格子y * 73)
        if 695 + 格子x * 73 > 1016 then
          格子x = 0
          格子y = 格子y + 1
        end
        if 178 + 格子y * 73 > 640 then
          当前任务3 = "买卖药"
        else
          local xx, yy = 666 + 格子x * 73, 147 + 格子y * 73
          local rtn, x, y = findPic(xx, yy, xx + 60, yy + 60, "空格子0.png", "101010", 0, 0.95)
          if 0 < x then
            toast("跳过格子" .. 1 + 格子x + 格子y * 5)
          else
            _ENV["点击"]("@@点装备" .. 1 + 格子x + 格子y * 5, 695 + 格子x * 73, 178 + 格子y * 73, 800)
          end
          格子x = 格子x + 1
        end
      end
      if _ENV["找到"](tz["商店选中装备属性页面"]) then
        hideToast()
        sleep(200)
        local 装备品质 = _ENV["去卖货_卖货_识别装备品质"]()
        local 是否稀有 = not _ENV["去卖货_卖货_品质是否允许出售"](装备品质, 出售装备品质上限)
        local 装备蓝条属性数组 = _ENV["去卖货_卖货_识别蓝条数量和属性"]()
        local 蓝条数量 = #装备蓝条属性数组
        if 是否稀有 then
          toast("稀有装备 保留")
        elseif 蓝条数量 >= _ENV["保留蓝条数量"] then
          local 是否符合详细属性值要求 = _ENV["界面数据"]["回蓝>="] == "false" and _ENV["界面数据"]["魔法出招>="] == "false" and _ENV["界面数据"]["武器出招>="] == "false"
          if _ENV["界面数据"]["回蓝>="] == "true" then
            for ii = 1, #装备蓝条属性数组 do
              if 装备蓝条属性数组[ii]["蓝条属性"] == "回蓝" and 装备蓝条属性数组[ii]["蓝条数值"] >= tonumber(_ENV["界面数据"]["保留回蓝数值"]) then
                是否符合详细属性值要求 = true
                break
              end
            end
          end
          if _ENV["界面数据"]["魔法出招>="] == "true" then
            for ii = 1, #装备蓝条属性数组 do
              if 装备蓝条属性数组[ii]["蓝条属性"] == "魔法出招" and 装备蓝条属性数组[ii]["蓝条数值"] >= tonumber(_ENV["界面数据"]["保留魔法出招数值"]) then
                是否符合详细属性值要求 = true
                break
              end
            end
          end
          if _ENV["界面数据"]["武器出招>="] == "true" then
            for ii = 1, #装备蓝条属性数组 do
              if 装备蓝条属性数组[ii]["蓝条属性"] == "武器出招" and 装备蓝条属性数组[ii]["蓝条数值"] >= tonumber(_ENV["界面数据"]["保留武器出招数值"]) then
                是否符合详细属性值要求 = true
                break
              end
            end
          end
          local 提示属性 = "蓝条数量" .. #装备蓝条属性数组 .. "\n"
          for iii = 1, #装备蓝条属性数组 do
            提示属性 = 提示属性 .. 装备蓝条属性数组[iii]["蓝条属性"] .. 装备蓝条属性数组[iii]["蓝条数值"] .. "\n"
          end
          if 是否符合详细属性值要求 then
            toast(提示属性 .. " 保留")
          else
            toast(提示属性 .. " 卖掉")
            _ENV["点击"]("@@卖1", 1155, 353, 1000)
            for i = 1, 3 do
              if _ENV["找到"](tz["商店卖货确定页面"]) then
                if not _ENV["去卖货_处理装备出售确认"]() then return end
                break
              end
              sleep(500)
            end
          end
        else
          toast(蓝条数量 .. "/" .. _ENV["保留蓝条数量"] .. " 卖掉")
          _ENV["点击"]("@@卖1", 1155, 353, 1000)
          for i = 1, 3 do
            if _ENV["找到"](tz["商店卖货确定页面"]) then
              if not _ENV["去卖货_处理装备出售确认"]() then return end
              break
            end
            sleep(500)
          end
        end
        _ENV["点击"]("@@关掉选中物品", 1184, 638, 800)
      elseif _ENV["找到"](tz["商店选中非装备属性页面"]) then
        if _ENV["找到"](tz["商店选中特殊物品属性页面"]) then
          _ENV["点击"]("@@出售特殊物品", 935, 354, 1000)
          for i = 1, 3 do
            if _ENV["找到"](tz["商店卖货确定页面"]) then
              _ENV["点击"]("@@确定卖掉", 537, 494, 800)
              break
            end
            sleep(500)
          end
        else
          _ENV["点击"]("@@关掉非装备物品", 1184, 638, 800)
        end
      end
    end
    if 当前任务3 == "买卖药" and _ENV["找到"](tz["商店页面"]) then
      _ENV["点击"]("@@初始化选中", 1164, 315, 1000)
      if _ENV["界面数据"]["购买小红"] == "true" and _ENV["小红数量"] + _ENV["大红数量"] < tonumber(_ENV["界面数据"]["红的数量低于"]) then
        toast(string.format("%d %d %d %d 红太少", _ENV["小红数量"], _ENV["大红数量"], _ENV["小蓝数量"], _ENV["大蓝数量"]))
        sleep(1000)
        _ENV["去卖货_卖货_执行红蓝处理"]("买红")
      end
      if _ENV["界面数据"]["购买小蓝"] == "true" and _ENV["小蓝数量"] + _ENV["大蓝数量"] < tonumber(_ENV["界面数据"]["蓝的数量低于"]) then
        toast(string.format("%d %d %d %d 蓝太少", _ENV["小红数量"], _ENV["大红数量"], _ENV["小蓝数量"], _ENV["大蓝数量"]))
        sleep(1000)
        _ENV["去卖货_卖货_执行红蓝处理"]("买蓝")
      end
      if _ENV["界面数据"]["出售全部红"] == "true" and _ENV["小红数量"] + _ENV["大红数量"] > tonumber(_ENV["界面数据"]["红的数量高于"]) then
        toast(string.format("%d %d %d %d 红太多", _ENV["小红数量"], _ENV["大红数量"], _ENV["小蓝数量"], _ENV["大蓝数量"]))
        sleep(1000)
        _ENV["去卖货_卖货_执行红蓝处理"]("卖红")
      end
      if _ENV["界面数据"]["出售全部蓝"] == "true" and _ENV["小蓝数量"] + _ENV["大蓝数量"] > tonumber(_ENV["界面数据"]["蓝的数量高于"]) then
        toast(string.format("%d %d %d %d 蓝太多", _ENV["小红数量"], _ENV["大红数量"], _ENV["小蓝数量"], _ENV["大蓝数量"]))
        sleep(1000)
        _ENV["去卖货_卖货_执行红蓝处理"]("卖蓝")
      end
      当前任务3 = "修装备"
    end
    if 当前任务3 == "修装备" and _ENV["找到"](tz["商店页面"]) then
      _ENV["点击"]("@@修理1", 136, 615, 2000)
      _ENV["点击"]("@@修理2", 472, 622, 2000)
      break
    end
    sleep(500)
  end
  _ENV["买了一次蓝"] = false
  _ENV["买了一次红"] = false
end

_ENV["去卖货_卖货"] = L0_1

function L0_1()
  for ii = 1, 300 do
    toast("前往目的地" .. ii .. "/300")
    if _ENV["检测寻路完成"]() then
      return true
    end
    sleep(1000)
  end
end

_ENV["寻路_等待完成"] = L0_1

function L0_1(地图名)
  if _ENV["找到"](tz["大地图页面"]) then
    _ENV["点击"]("@@大地图选择", 253, 38, 1000)
  end
  if _ENV["找到"](tz["大地图页面"]) then
    _ENV["点击"]("@@大地图选择", 253, 38, 1000)
  end
  if _ENV["找到"](tz["大地图页面"]) then
    _ENV["点击"]("@@大地图选择", 361, 95, 1500)
  end
  if _ENV["找到"](tz["大地图页面"]) then
    _ENV["点击"]("@@大地图输入框", 283, 96, 1500)
    imeLib.lock()
    imeLib.setText(地图名)
    imeLib.unlock()
    sleep(1000)
    _ENV["点击"]("@@XONG", 98, 197, 2000)
  end
  if _ENV["找到"](tz["大地图页面"]) then
    if 地图名 == "phong thần" or 地图名 == "sùng thành" then
      _ENV["点击"]("@@选择地图2", 80, 195, 1500)
    else
      _ENV["点击"]("@@选择地图1", 66, 155, 1500)
    end
  end
  if _ENV["找到"](tz["大地图页面"]) then
    _ENV["点击"]("@@npc选择", 82, 38, 1500)
  end
  if _ENV["找到"](tz["大地图页面"]) then
    _ENV["点击"]("@@npc选择", 82, 38, 1500)
  end
end

_ENV["大地图_输入大地图"] = L0_1

function L0_1()
  _ENV["当前任务2"] = "去卖货"
  for i = 1, 100 do
    _ENV["通用处理"]()
    _ENV["登录_判断一轮"]()
    if _ENV["找到"](tz["对话页面"]) then
      if _ENV["找到"](tz["对话进入商店按钮"]) then
        _ENV["点击"]("@@关闭对话 不去商店", 1233, 421, 1000)
      else
        _ENV["点击"]("@@关闭对话", 892, 652, 1000)
      end
    end
    if _ENV["找到"](tz["商店卖货确定页面"]) then
      _ENV["点击"]("@@关闭商店卖货确定页面", 714, 497, 1000)
    end
    if _ENV["找到"](tz["商店选中装备属性页面"]) or _ENV["找到"](tz["商店选中非装备属性页面"]) then
      _ENV["点击"]("@@关闭商店选中装备属性页面", 1188, 626, 1000)
    end
    if _ENV["找到"](tz["签到页面"]) then
      _ENV["点击"]("@@关闭签到页面", 1198, 119, 1000)
    end
    if _ENV["找到"](tz["背包页面"]) then
      _ENV["点击"]("@@关闭背包", 1244, 104, 1000)
    end
    if _ENV["找到"](tz["商店页面"]) then
      _ENV["点击"]("@@关闭商店", 1244, 104, 1000)
    end
    if _ENV["找到"](tz["确定输入内容"]) then
      _ENV["点击"]("@@关闭输入内容", 85, 190, 1000)
    end
    if _ENV["在主页面"]() then
      if _ENV["找到点击"]("@@关掉自动挂机", tz["自动挂机按钮_已开启"]) then
      elseif _ENV["找到"](tz["自动挂机按钮_未开启"]) then
        _ENV["点击"]("@@打开大地图", 1159, 110, 1000)
      end
    elseif _ENV["找到"](tz["大地图页面"]) then
      _ENV["大地图_输入大地图"](_ENV["卖货地图"])
      _ENV["点击"]("@@前往卖货npc", 38, 160, 1000)
      _ENV["寻路_等待完成"]()
      if _ENV["卖货地图"]:find("Đại Phong") then
        touchDown(1, 205, 590)
        touchMoveEx(1, 137, 594, 300)
        sleep(100)
        touchUp(1)
      end
      _ENV["当前任务2"] = "去卖货 卖货开始"
      for i = 1, 10 do
        _ENV["找到点击"]("@@对话进入商店按钮", tz["对话进入商店按钮"], {
          530,
          624,
          1000
        })
        _ENV["找到点击"]("@@对话按钮", tz["对话按钮"])
        if _ENV["找到"](tz["商店页面"]) then
          _ENV["去卖货_卖货"]()
          break
        end
        sleep(1000)
      end
      break
    end
    sleep(500)
  end
end

_ENV["去卖货"] = L0_1

function L0_1()
  _ENV["当前任务2"] = "去挂机点" .. _ENV["挂机点x"] .. "," .. _ENV["挂机点y"]
  while true do
    _ENV["通用处理"]()
    _ENV["登录_判断一轮"]()
    if _ENV["找到"](tz["对话页面"]) then
      if _ENV["找到"](tz["对话进入商店按钮"]) then
        _ENV["点击"]("@@关闭对话 不去商店", 1233, 421, 1000)
      else
        _ENV["点击"]("@@关闭对话", 892, 652, 1000)
      end
    end
    if _ENV["找到"](tz["商店卖货确定页面"]) then
      _ENV["点击"]("@@关闭商店卖货确定页面", 714, 497, 1000)
    end
    if _ENV["找到"](tz["商店选中装备属性页面"]) or _ENV["找到"](tz["商店选中非装备属性页面"]) then
      _ENV["点击"]("@@关闭商店选中装备属性页面", 1188, 626, 1000)
    end
    if _ENV["找到"](tz["签到页面"]) then
      _ENV["点击"]("@@关闭签到页面", 1198, 119, 1000)
    end
    if _ENV["找到"](tz["背包页面"]) then
      _ENV["点击"]("@@关闭背包", 1244, 104, 1000)
    end
    if _ENV["找到"](tz["商店页面"]) then
      _ENV["点击"]("@@关闭商店", 1244, 104, 1000)
    end
    if _ENV["找到"](tz["确定输入内容"]) then
      _ENV["点击"]("@@关闭输入内容", 85, 190, 1000)
    end
    if _ENV["在主页面"]() then
      if _ENV["找到点击"](tz["自动挂机按钮_已开启"]) then
      elseif _ENV["找到"](tz["自动挂机按钮_未开启"]) then
        _ENV["点击"]("@@寻路 关闭自动挂机", 1159, 110, 1000)
      end
    elseif _ENV["找到"](tz["大地图页面"]) then
      _ENV["大地图_输入大地图"](_ENV["挂机点地图"])
      if _ENV["找到"](tz["大地图页面"]) then
        _ENV["点击"]("@@输入x", 664, 655, 1000)
        for i = 1, 10 do
          keyPress("del")
          sleep(80)
        end
        imeLib.lock()
        imeLib.setText(_ENV["挂机点x"])
        print(_ENV["挂机点x"])
        sleep(1000)
        _ENV["点击"]("@@xong", 116, 227, 1000)
      end
      if _ENV["找到"](tz["大地图页面"]) then
        _ENV["点击"]("@@输入y", 774, 657, 1000)
        for i = 1, 10 do
          keyPress("del")
          sleep(80)
        end
        imeLib.lock()
        imeLib.setText(_ENV["挂机点y"])
        print(_ENV["挂机点y"])
        sleep(1000)
        _ENV["点击"]("@@xong", 116, 227, 1000)
      end
      imeLib.unlock()
      if _ENV["找到"](tz["大地图页面"]) then
        _ENV["点击"]("@@去目的地", 857, 651, 1000)
        toast("等待寻路完成")
        if _ENV["寻路_等待完成"]() then
          _ENV["点击"](809, 673, 2000)
          if _ENV["找到"](tz["自动设置页面"]) then
            _ENV["点击"](1173, 516, 2000)
            _ENV["点击"](808, 189, 2000)
          end
          return true
        else
          toast("寻路超时，暂未到达挂机点")
          return false
        end
      end
    end
    sleep(500)
  end
end

_ENV["去挂机点"] = L0_1

function L0_1()
  if _ENV["去挂机点"]() then
    _ENV["需要去挂机点一次"] = false
    _ENV["队员找不到队长组队次数"] = 0
    return true
  end
  _ENV["需要去挂机点一次"] = true
  _ENV["当前任务2"] = "回挂机点失败，等待重试"
  toast("回挂机点失败，稍后重试")
  return false
end

_ENV["执行返回挂机点"] = L0_1

function L0_1()
  _ENV["找到点击"]("@@选区失败页面_关闭按钮", tz["选区失败页面_关闭按钮"])
  if _ENV["找到"](tz["选区页面"]) then
    _ENV["点击"]("@@选区确定", 749, 664, 1000)
  end
  if _ENV["找到"](tz["账号密码页面"]) then
    _ENV["点击"]("@@确定账号密码", 660, 525, 1000)
  end
  if _ENV["找到"](tz["选角色页面"]) then
    _ENV["点击"]("@@确定角色", 1090, 551, 1000)
  end
end

_ENV["登录_判断一轮"] = L0_1

function L0_1()
  local 空格子 = 0
  for yy = 149, 575, 71 do
    for xx = 670, 1035, 71 do
      if 1024 < xx then
      else
        local rtn, x, y = findPic(xx, yy, xx + 68, yy + 72, "空格子0.png", "101010", 0, 0.95)
        if 0 < x then
          print(rtn, x, y)
          空格子 = 空格子 + 1
        end
      end
    end
  end
  return 空格子
end

_ENV["检测背包_识别空格子"] = L0_1

function L0_1()
  local 红数量 = 0
  local 蓝数量 = 0
  for i = 1, 10 do
    local 药品信息 = _ENV["检测背包_识别红蓝数量"]()
    红数量 = tonumberEx(药品信息["大红"]) + tonumberEx(药品信息["小红"])
    蓝数量 = tonumberEx(药品信息["大蓝"]) + tonumberEx(药品信息["小蓝"])
    toast(string.format("红%d 蓝%d 识别" .. i .. "/10次", 红数量, 蓝数量))
    sleep(1000)
    if tonumberEx(药品信息["大红"]) == 0 or tonumberEx(药品信息["小红"]) == 0 or tonumberEx(药品信息["大蓝"]) == 0 or tonumberEx(药品信息["小蓝"]) == 0 then
    else
      break
    end
  end
  print(_ENV["界面数据"]["购买小红"], _ENV["界面数据"]["购买小红"] == "true")
  print(_ENV["界面数据"]["红的数量低于"])
  print(_ENV["界面数据"]["购买小蓝"], _ENV["界面数据"]["购买小蓝"] == "true")
  print(_ENV["界面数据"]["蓝的数量低于"])
  if _ENV["界面数据"]["出售全部红"] == "true" and 红数量 > tonumber(_ENV["界面数据"]["红的数量高于"]) then
    toast("红太多" .. 红数量 .. ">" .. _ENV["界面数据"]["红的数量高于"] .. " 要去卖红")
    sleep(1000)
    return "卖红", 红数量
  end
  if _ENV["界面数据"]["出售全部蓝"] == "true" and 蓝数量 > tonumber(_ENV["界面数据"]["蓝的数量高于"]) then
    toast("蓝太多" .. 蓝数量 .. ">" .. _ENV["界面数据"]["蓝的数量高于"] .. " 要去卖蓝")
    sleep(1000)
    return "卖蓝", 蓝数量
  end
  if _ENV["界面数据"]["购买小红"] == "true" and _ENV["买了一次红"] == false and 红数量 < tonumber(_ENV["界面数据"]["红的数量低于"]) then
    toast("红太少" .. 红数量 .. "<" .. _ENV["界面数据"]["红的数量低于"] .. " 要去买红")
    sleep(1000)
    return "买红", 红数量
  end
  if _ENV["界面数据"]["购买小蓝"] == "true" and _ENV["买了一次蓝"] == false and 蓝数量 < tonumber(_ENV["界面数据"]["蓝的数量低于"]) then
    toast("蓝太少" .. 蓝数量 .. "<" .. _ENV["界面数据"]["蓝的数量低于"] .. " 要去买蓝")
    sleep(1000)
    return "买蓝", 蓝数量
  end
  return ""
end

_ENV["检测背包_判断是否去卖或者买药"] = L0_1

function L0_1(a, b, c, d)
  local function 文字识别库()
    local 识别库 = {}
    
    识别库[1] = "1|4,14|100m0F&&&&"
    识别库[2] = "7|5,15|200m0U1&&#7u0"
    识别库[3] = "6|7,15|0FmV&p67mmQ63SVm1y"
    识别库[4] = "5|6,15|&ns67mWU63m&m1u"
    识别库[5] = "8|7,15|00GV&pF3mmQ73V&mmy"
    识别库[6] = "6|6,15|F&ZlEmmQ63S&n3y"
    识别库[7] = "9|7,15|0O0Vnp37m8Q33V&m&u"
    识别库[8] = "6|7,15|0FWV&p67mmQ63SVn1y"
    识别库[9] = "5|7,15|008&ns63mWU67G&m0m"
    识别库[10] = "0|6,15|F&pWEm0Q03V&n&y"
    识别库[11] = "5|7,15|0C8&ns43mWU67G&m0m"
    识别库[12] = "8|6,14|&ViyU63Xm&&umy"
    识别库[13] = "2|7,15|002U1x0VmEQ7ZVmPu0"
    识别库[14] = "3|7,15|0W8S1p07mGQ73V&muu"
    识别库[15] = "4|7,15|00m0#0yGV37&&V&u0O"
    识别库[16] = "2|6,15|U1x0VmFQ7ZVmPu0"
    识别库[17] = "3|7,14|0W8u3i0U23Xm&&vuy"
    识别库[18] = "3|6,15|C1ZWEG0U23Pux&#"
    识别库[19] = "6|6,15|F&p76mmU63S&n1y"
    识别库[20] = "8|6,15|VlpF7mmQ73V&mmy"
    识别库[21] = "4|8,15|0603m3o3uO&&p&&0300G"
    识别库[22] = "7|5,15|602mF#&y&m300"
    return 识别库
  end
  
  TURING["来源_获取屏幕像素"](a, b, c, d)
  TURING["滤镜_彩色_二值化"]("00DBDE-202020|00FBF7-202020|00CBC6-202020")
  TURING["切割_范围投影切割"](0, 0, "8")
  TURING["字库_加载六十四进制识别字库"](文字识别库())
  local 识别结果 = TURING["识别"](80)
  if 识别结果 ~= nil and 识别结果 ~= "" then
    return 识别结果
  end
  return ""
end

_ENV["检测背包_主页面识别红蓝数量_识别子函数"] = L0_1

function L0_1()
  local 检测数量数组 = {}
  检测数量数组[1] = _ENV["检测背包_主页面识别红蓝数量_识别子函数"](940, 299, 995, 321)
  检测数量数组[2] = _ENV["检测背包_主页面识别红蓝数量_识别子函数"](1023, 299, 1078, 321)
  检测数量数组[3] = _ENV["检测背包_主页面识别红蓝数量_识别子函数"](1100, 299, 1159, 321)
  检测数量数组[4] = _ENV["检测背包_主页面识别红蓝数量_识别子函数"](1180, 299, 1238, 321)
  for i = 1, #检测数量数组 do
    print("第" .. i .. "个数量", 检测数量数组[i])
  end
  local 数量识别有效 = true
  for i = 1, #检测数量数组 do
    if 检测数量数组[i] == nil or not tostring(检测数量数组[i]):match("%d") then
      数量识别有效 = false
      break
    end
  end
  _ENV["小红数量"] = tonumberEx(检测数量数组[1])
  _ENV["大红数量"] = tonumberEx(检测数量数组[2])
  _ENV["小蓝数量"] = tonumberEx(检测数量数组[3])
  _ENV["大蓝数量"] = tonumberEx(检测数量数组[4])
  _ENV["药品数量识别有效"] = 数量识别有效
  return 数量识别有效
end

_ENV["检测背包_主页面识别红蓝数量"] = L0_1

function L0_1(识别文本)
  识别文本 = tostring(识别文本 or "")
  识别文本 = string.gsub(识别文本, "%s+", "")
  识别文本 = string.gsub(识别文本, "／", "/")
  local 当前重量文本, 总重量文本 = string.match(识别文本, "^(%d+)%/(%d+)$")
  if 当前重量文本 == nil then
    return nil, nil
  end
  local 当前重量 = tonumber(当前重量文本)
  local 总重量 = tonumber(总重量文本)
  if 总重量 <= 0 or 当前重量 > 总重量 then
    return nil, nil
  end
  return 当前重量, 总重量
end

_ENV["检测背包_解析重量"] = L0_1

function L0_1()
  if _ENV["检测修理装备"] and _ENV["找到"](tz["提示装备需要修理"]) then
    toast("装备需要修理")
    sleep(1000)
    return false
  end
  if _ENV["上次检测背包"] == nil or tickCount() - _ENV["上次检测背包"] > 1000 * _ENV["背包检测间隔秒"] then
    if _ENV["在主页面"]() then
      if not _ENV["检测背包_主页面识别红蓝数量"]() then
        toast("药品数量识别失败，本轮跳过补给交易")
        return true
      end
      print("检测背包" .. "完成")
      if _ENV["界面数据"]["购买小红"] == "true" then
        print(_ENV["小红数量"], _ENV["大红数量"], _ENV["界面数据"]["红的数量低于"])
        if _ENV["小红数量"] + _ENV["大红数量"] < tonumber(_ENV["界面数据"]["红的数量低于"]) then
          toast(string.format("%d %d %d %d 红太少", _ENV["小红数量"], _ENV["大红数量"], _ENV["小蓝数量"], _ENV["大蓝数量"]))
          sleep(1000)
          return false
        end
      end
      if _ENV["界面数据"]["购买小蓝"] == "true" and _ENV["小蓝数量"] + _ENV["大蓝数量"] < tonumber(_ENV["界面数据"]["蓝的数量低于"]) then
        toast(string.format("%d %d %d %d 蓝太少", _ENV["小红数量"], _ENV["大红数量"], _ENV["小蓝数量"], _ENV["大蓝数量"]))
        sleep(1000)
        return false
      end
      if _ENV["界面数据"]["出售全部红"] == "true" and _ENV["小红数量"] + _ENV["大红数量"] > tonumber(_ENV["界面数据"]["红的数量高于"]) then
        toast(string.format("%d %d %d %d 红太多", _ENV["小红数量"], _ENV["大红数量"], _ENV["小蓝数量"], _ENV["大蓝数量"]))
        sleep(1000)
        return false
      end
      if _ENV["界面数据"]["出售全部蓝"] == "true" and _ENV["小蓝数量"] + _ENV["大蓝数量"] > tonumber(_ENV["界面数据"]["蓝的数量高于"]) then
        toast(string.format("%d %d %d %d 蓝太多", _ENV["小红数量"], _ENV["大红数量"], _ENV["小蓝数量"], _ENV["大蓝数量"]))
        sleep(1000)
        return false
      end
      _ENV["点击"]("@@打开背包", 902, 663, 1000)
    end
    if _ENV["找到"](tz["背包页面"]) then
      _ENV["上次检测背包"] = tickCount()
      local 是否要去商店 = false
      local 当前空格子 = _ENV["检测背包_识别空格子"]()
      _ENV["当前背包格子"] = 当前空格子
      local 识别结果 = tomocrtextall(1057, 346, 1187, 373)
      print(识别结果)
      local 重量, 总重量 = _ENV["检测背包_解析重量"](识别结果)
      if 重量 ~= nil and 总重量 ~= nil then
        _ENV["当前背包重量"] = 重量 .. "/" .. 总重量
        toast(string.format("%d/%d", 重量, 总重量))
        if 总重量 - 重量 <= _ENV["背包保留重量"] then
          是否要去商店 = true
        end
      end
      if 当前空格子 <= _ENV["背包保留格子"] then
        toast("空格子不足" .. 当前空格子 .. "/" .. _ENV["背包保留格子"])
        sleep(1000)
        是否要去商店 = true
      end
      if _ENV["上次检测元宝"] == nil then
        _ENV["上次检测元宝"] = -999999
      end
      if tickCount() - _ENV["上次检测元宝"] > 300000 then
        toast("去检测元宝")
        sleep(1000)
        _ENV["当前元宝"] = tomocrtextall(1054, 271, 1198, 305)
        _ENV["修改数据库"]("元宝", _ENV["当前元宝"])
        _ENV["上次检测元宝"] = tickCount()
      end
      return not 是否要去商店
    end
  end
  return true
end

_ENV["检测背包"] = L0_1

function L0_1()
  if _ENV["检测修理装备"] and _ENV["找到"](tz["提示装备需要修理"]) then
    toast("装备需要修理")
    sleep(1000)
    return false
  end
  if _ENV["上次检测背包"] == nil or tickCount() - _ENV["上次检测背包"] > 1000 * _ENV["背包检测间隔秒"] then
    if _ENV["在主页面"]() then
      _ENV["点击"]("@@打开背包", 902, 663, 1000)
    end
    if _ENV["找到"](tz["背包页面"]) then
      _ENV["上次检测背包"] = tickCount()
      local 是否要去商店 = false
      是否要去商店 = _ENV["检测背包_判断是否去卖或者买药"]() ~= ""
      if 是否要去商店 == false then
        local 当前空格子 = _ENV["检测背包_识别空格子"]()
        _ENV["当前背包格子"] = 当前空格子
        local 识别结果 = tomocrtextall(1057, 346, 1187, 373)
        print(识别结果)
        local 识别结果数组 = splitStr(识别结果, "/")
        if #识别结果数组 == 2 then
          local 重量 = tonumberEx(识别结果数组[1])
          local 总重量 = tonumberEx(识别结果数组[2])
          _ENV["当前背包重量"] = 重量 .. "/" .. 总重量
          toast(string.format("%d/%d", 重量, 总重量))
          if 总重量 - 重量 <= _ENV["背包保留重量"] then
            是否要去商店 = true
          end
        end
        if 当前空格子 <= _ENV["背包保留格子"] then
          toast("空格子不足" .. 当前空格子 .. "/" .. _ENV["背包保留格子"])
          sleep(1000)
          是否要去商店 = true
        end
      end
      return not 是否要去商店
    end
  end
  return true
end

_ENV["检测背包_备份"] = L0_1

function L0_1()
  local ret = cmpColorEx("287|394|808080-111111,191|485|ffffff-111111,329|393|808080-111111,200|327|808080-111111,344|291|808080-111111,300|310|808080-111111,292|328|808080-111111,218|289|808080-111111,362|444|ffffff-111111,321|320|808080-111111,313|399|808080-111111,226|306|808080-111111,207|518|ffffff-111111,303|508|ffffff-111111,304|521|ffffff-111111,323|454|ffffff-111111,361|328|808080-111111,365|508|ffffff-111111,186|425|f2f2f2-111111,297|500|ffffff-111111", 0.8)
  if ret == 1 then
    return true
  end
  local x, y = findMultiColor(55, 226, 672, 624, "ebebeb-111111", "112|14|808080-111111|301|23|808080-111111|7|145|808080-111111|131|143|808080-111111|303|152|808080-111111|-2|208|ffffff-111111|102|210|ffffff-111111|183|210|ffffff-111111|22|275|000000-111111|17|108|ffffff-111111|18|89|ffffff-111111", 0, 0.9)
  if x ~= -1 and y ~= -1 then
    return true
  end
  return false
end

_ENV["页面_vpn已关"] = L0_1

function L0_1()
  local x, y = findMultiColor(10, 172, 223, 416, "1e4376-111111", "157|5|1e4376-111111|349|25|1e4376-111111|395|28|1e4376-111111|-128|136|1e4376-111111|-128|113|1e4376-111111|-125|-37|fafafa-111111|-58|-37|fafafa-111111|-27|-37|fafafa-111111", 0, 0.9)
  if x ~= -1 and y ~= -1 then
    return true
  end
  local ret = cmpColorEx("410|273|1e4376-111111,495|263|1e4376-111111,553|269|1e4376-111111,557|331|1e4376-111111,559|493|ffffff-111111,541|535|ffffff-111111,525|689|ffffff-111111", 0.8)
  if ret == 1 then
    return true
  end
  x, y = findMultiColor(55, 226, 672, 624, "1e4376-111111", "101|0|1e4376-111111|303|5|1e4376-111111|9|113|1e4376-111111|152|129|1e4376-111111|2|78|ffffff-111111|14|70|486690-111111|-19|193|ffffff-111111|61|191|ffffff-111111|7|243|000000-111111|-11|243|000000-111111", 0, 0.9)
  if x ~= -1 and y ~= -1 then
    return true
  end
  return false
end

_ENV["页面_vpn已开"] = L0_1

function L0_1()
  local vpn包名 = "com.github.metacubex.clash.meta"
  local vpn包名 = "com.github.kr328.clash"
  local 确定开启 = {
    "596|912|008577-111111,599|912|008577-111111,601|912|008577-111111,596|916|008577-111111,596|921|008577-111111,600|918|0d8b7e-111111,164|837|ffffff-111111,180|803|ffffff-111111,171|817|060606-111111,179|819|010101-111111,177|826|505050-111111,176|803|ffffff-111111,149|803|ffffff-111111,216|805|757575-111111,221|805|757575-111111",
    0.95
  }
  local 次数 = 0
  while _ENV["开启vpn"] do
    次数 = 次数 + 1
    toast("去开启vpn")
    runApp(vpn包名)
    for i = 1, 10 do
      sleep(1000)
      if _ENV["页面_vpn已关"]() then
        break
      end
      if _ENV["页面_vpn已开"]() then
        tap(378, 297)
        sleep(1000)
      end
      _ENV["找到点击"]("@@确定开启", 确定开启)
    end
    for i = 1, 10 do
      sleep(1000)
      _ENV["找到点击"]("@@确定开启", 确定开启)
      if _ENV["页面_vpn已关"]() then
        tap(378, 297)
        sleep(1000)
      end
      if _ENV["页面_vpn已开"]() then
        return true
      end
    end
    if 2 <= 次数 then
      toast("vpn白屏 重启手机")
      exec("reboot")
    end
    stopApp(vpn包名)
    sleep(1000)
  end
end

_ENV["开启vpn函数"] = L0_1

function L0_1(a, b, c, d)
  local function 文字识别库()
    local 识别库 = {}
    
    识别库[1] = "0|10,19|0Vy1&&n&&yu0EG01u00t01p&&u&&u150"
    识别库[2] = "1|5,19|6003003&&x&&#&&&"
    识别库[3] = "1|5,18|O00O00y00&&&&&&"
    识别库[4] = "1|5,19|6003003&&v&&&&&&"
    识别库[5] = "1|5,19|6003003u03&&#&&&"
    识别库[6] = "2|9,19|0O0Cy0U#0&O0&u1yq3uR&uD&m6VW3"
    识别库[7] = "2|9,19|0u0Ty0##0&O1&u3us7uR&mD&m6FW2"
    识别库[8] = "2|9,19|0u0Sy0U#0&O1&u1yq3uR&uD&m6VW3"
    识别库[9] = "3|9,19|0m3Xu1yy0#O03u41q70x&&v&&yV7u"
    识别库[10] = "3|9,19|1m3Xu1yy0#O03e61#70x&&v&&yF7u"
    识别库[11] = "3|9,18|C0uy0#y0#m07WG7mu7&&#&&#F7u"
    识别库[12] = "4|10,19|00y01#07&0FXWU0mV&&d&&x&&y03001W"
    识别库[13] = "5|9,19|0011&nz&u#OO3yC1s60&3rvX&y0Fu"
    识别库[14] = "5|9,19|1V1X&nz&u#OO3y80s60&3&vX&y0Fu"
    识别库[15] = "5|9,18|&uu&u#&uUmm7mW3mm7m&#m&#0Fu"
    识别库[16] = "5|9,19|3&ZZ&nz&uUuO3i80#60x3&xW&u0Fu"
    识别库[17] = "6|9,19|0V&W&&u&&#OC3u40s30xn&vu&yC7u"
    识别库[18] = "6|9,18|7&mV&y&&#mO6WG3WG3uUUyV#SFy"
    识别库[19] = "7|8,19|1001W00m07u0&yF&t&y3&W0u00"
    识别库[20] = "8|9,19|0uVX&Vy&&#OS3u40s70x&&u&Vy63u"
    识别库[21] = "9|9,19|0&XX&uy&yEu63e11s1Wx&&u&&uF&u"
    识别库[22] = "9|9,18|FuOVyU&yUmC7W47mC7&&#V&#F&u"
    识别库[23] = "a|7,13|0u#y&uPyOMSR&y&&"
    识别库[24] = "a|8,13|0Xzv#uxumiGt&v&#F#"
    识别库[25] = "b|8,19|3&&&&&&V&&WO0WC0O7&y3&y0&y"
    识别库[26] = "c|7,13|0&y&&O1y0U0VmyuS"
    识别库[27] = "d|8,19|01&u1&&0yNWO0mC0J&&&&&#&&&"
    识别库[28] = "e|8,13|1&v&#&tGWyGVvvyy60"
    识别库[29] = "e|8,13|1&v&#&tumyG&zvyy60"
    识别库[30] = "f|6,17|200E03&&&&&&uYH00"
    识别库[31] = "f|6,18|1003W0V&&V&&&rKH00"
    识别库[32] = "g|9,17|1yxl&&&zZWJ7XcF&EV#DlmVG08"
    识别库[33] = "h|8,19|1&&#&&&&&&W8008007003&#1&&"
    识别库[34] = "h|8,19|3&&#&&&V&&0O00C007&u3&#0&#"
    识别库[35] = "i|3,18|H&#n&&n&&"
    识别库[36] = "i|3,18|n&&n&&H&#"
    识别库[37] = "j|4,22|0003H4H&7&&qV&#"
    识别库[38] = "j|4,21|000SV&&Z&&qV&y"
    识别库[39] = "k|9,19|3&&z&&&&&&03W03m07&07&u3W#107"
    识别库[40] = "k|9,19|3&&&&&#&&#03W03u07&07du30#102"
    识别库[41] = "l|3,19|2ggh&&#&&&"
    识别库[42] = "m|13,13|1&&&&y0C0603&z&&V&O0C0703&&&&"
    识别库[43] = "m|12,19|V&&&&&&&03&W03&W03#01&WF#3&W3&W3&&#&&&"
    识别库[44] = "n|8,13|3&&&&&&O0C07&x&#&#"
    识别库[45] = "o|8,13|1&v&#m3u0y0&&v&y7m"
    识别库[46] = "p|8,17|F&&&&&&&&W23067hyF&mF&0"
    识别库[47] = "q|8,17|7&WV&W&&XW33047&&&&&&&&"
    识别库[48] = "r|6,13|&&&&&&x0301W0"
    识别库[49] = "s|7,13|0yC&7VnuSUFzZyGS"
    识别库[50] = "t|7,17|100701&&x&&z&NmW1X02"
    识别库[51] = "u|8,13|3&z&&&&W0W0N&x&&&&"
    识别库[52] = "u|8,13|3&z&&&&W0W0N&x&&&#"
    识别库[53] = "v|7,13|1y0&#7&m7v&&&ny0"
    识别库[54] = "w|11,13|V0F&m&y3&&u&0Vy0FuF&&z#0"
    识别库[55] = "x|8,13|005mV&&7#3&3&xmV03"
    识别库[56] = "y|8,17|C00Vm3&yE1&u3&n&yFy0S00"
    识别库[57] = "z|6,13|WFmVy&&#Fu7W3"
    return 识别库
  end
  
  TURING["来源_获取屏幕像素"](a, b, c, d)
  TURING["滤镜_彩色_二值化"]("127-255")
  TURING["切割_范围投影切割"](5, 1)
  TURING["字库_加载六十四进制识别字库"](文字识别库())
  local 识别结果 = TURING["识别"](88)
  if 识别结果 ~= nil and 识别结果 ~= "" then
    return 识别结果
  end
  return ""
end

_ENV["组队_识别名字"] = L0_1

function L0_1(aa, bb, cc, dd)
  local 所有队伍 = {}
  local ret = findMultiColorAll(aa, bb, cc, dd, "73418c-111111", "7|1|733584-111111|8|2|73357b-111111|11|5|7b3963-111111|14|5|7b3963-111111|6|8|84394a-111111|2|9|8c3d4a-111111|1|10|8c3d42-111111", 0, 0.9)
  if ret ~= nil then
    for i = 1, #ret do
      local a, b, c, d = 24 + ret[i].x, ret[i].y, 229 + ret[i].x, 34 + ret[i].y
      table.insert(所有队伍, {
        x = a + 76,
        y = b + 27,
        words = _ENV["组队_识别名字"](a, b, c, d)
      })
    end
  end
  return 所有队伍
end

_ENV["组队_识别所有队伍名字"] = L0_1

function L0_1()
  return _ENV["组队_识别名字"](147, 212, 327, 263)
end

_ENV["组队_识别队长名称2"] = L0_1

function L0_1()
  local x, y = findMultiColor(105, 187, 165, 285, "7b4173-111111", "-7|1|7b3863-111111|-9|-4|6b348c-111111|-13|1|7b3863-111111|-13|3|843852-111111|-9|2|84385a-111111|-6|3|843852-111111|-18|14|943c18-111111|-18|-3|6b387b-111111|-18|6|843c39-111111", 0, 0.9)
  if x ~= -1 and y ~= -1 then
    print("有队长")
    local 队长名字 = tomocrtextall(147, 212, 327, 263)
    print("队长名字", 队长名字)
    return 队长名字
  end
  return ""
end

_ENV["组队_识别队长名称"] = L0_1

function L0_1()
  if _ENV["界面数据"]["自动战斗"] ~= "true" then
    return false
  end
  return _ENV["找到点击"]("@@自动挂机按钮_未开启", tz["自动挂机按钮_未开启"])
end

_ENV["执行自动战斗"] = L0_1

function L0_1(识别名称1, 识别名称2, 期望队长名称)
  期望队长名称 = tostring(期望队长名称 or "")
  期望队长名称 = string.gsub(期望队长名称, "^%s*(.-)%s*$", "%1")
  if 期望队长名称 == "" then
    return "未配置"
  end
  local 有效识别结果 = false
  for _, 名称 in ipairs({识别名称1 or "", 识别名称2 or ""}) do
    名称 = tostring(名称)
    名称 = string.gsub(名称, "^%s*(.-)%s*$", "%1")
    if 名称 ~= "" then
      有效识别结果 = true
      if 0.95 <= w.stringSimilarity.levenshteinNormalized(名称, 期望队长名称) then
        return "匹配"
      end
    end
  end
  if not 有效识别结果 then
    return "无法识别"
  end
  return "不匹配"
end

_ENV["组队_判断队长名称"] = L0_1

function L0_1()
  _ENV["当前任务1"] = "挂机模式"
  _ENV["当前任务2"] = ""
  while true do
    _ENV["当前任务2"] = "挂机"
    print(_ENV["当前任务1"], _ENV["当前任务2"])
    _ENV["通用处理"]()
    _ENV["登录_判断一轮"]()
    if _ENV["检测挂机长时间不移动"]() then
      _ENV["需要去挂机点一次"] = true
    end
    if _ENV["在主页面"]() then
      print("在主页面")
      if _ENV["界面数据"]["是否队长"] == "0" then
        if _ENV["找到"](tz["是否允许队伍申请弹出"]) then
          local rtn, x, y = _ENV["找到"](tz["是否允许队伍申请弹出"])
          local tb = tomocrtexttable(411, 269, x - 5, 304)
          for i = 1, #tb do
            print(tb[i].words)
            local 是否允许进入队伍 = false
            for ii = 1, #_ENV["队友名称数组"] do
              local sim = w.stringSimilarity.levenshteinNormalized(tb[i].words, _ENV["队友名称数组"][ii])
              print(sim, tb[i].words, _ENV["队友名称数组"][ii])
              if 0.8 <= sim then
                是否允许进入队伍 = true
                break
              end
            end
            if 是否允许进入队伍 then
              _ENV["点击"]("@@允许进入队伍", 542, 407, 1000)
              break
            end
          end
          if _ENV["找到"](tz["是否允许队伍申请弹出"]) and _ENV["是否允许进入队伍"] == false then
            _ENV["点击"]("@@不允许进入队伍", 764, 412, 1000)
          end
        elseif _ENV["找到点击"]("@@有人申请", tz["有人申请组队提示按钮"]) then
        elseif _ENV["没有创建队伍"]() then
          _ENV["点击"]("@@点队伍按钮", 1114, 556, 2000)
          if _ENV["找到"](tz["组队_附近玩家页面"]) then
            _ENV["点击"]("@@点创建队伍按钮", 909, 638, 1500)
            _ENV["点击"]("@@关闭队伍", 1203, 130, 1000)
          end
        end
      end
      if _ENV["界面数据"]["是否队长"] == "1" then
        if _ENV["上次检测队长名字"] == nil then
          _ENV["上次检测队长名字"] = tickCount()
        end
        if tickCount() - _ENV["上次检测队长名字"] > 1000 * tonumberEx(_ENV["界面数据"]["检测队长名字频率秒"]) then
          toast("去检测队长名字是否正确")
          sleep(1000)
          if _ENV["在主页面"]() then
            if _ENV["没有创建队伍"]() then
              toast("没有创建队伍 不用检测队长名字")
              sleep(1000)
            else
              _ENV["点击"]("@@打开队伍", 1118, 556, 2000)
            end
          end
          if _ENV["找到"](tz["组队_附近玩家页面"]) or _ENV["找到"](tz["组队_附近队伍页面"]) then
            local 队长名称 = _ENV["组队_识别队长名称"]()
            local 队长名称2 = ""
            local 判断结果 = _ENV["组队_判断队长名称"](队长名称, 队长名称2, _ENV["队长名称数组"][1])
            if 判断结果 == "不匹配" then
              队长名称2 = _ENV["组队_识别队长名称2"]()
              判断结果 = _ENV["组队_判断队长名称"](队长名称, 队长名称2, _ENV["队长名称数组"][1])
            end
            if 判断结果 == "匹配" then
              toast(string.format("[%s]==[%s] ", 队长名称, _ENV["队长名称数组"][1]))
              sleep(1000)
              _ENV["上次检测队长名字"] = tickCount()
            elseif 判断结果 == "不匹配" then
              toast(string.format("[%s]!=[%s]", 队长名称, _ENV["队长名称数组"][1]))
              sleep(1000)
              _ENV["点击"]("@@解散队伍", 477, 630, 1000)
              _ENV["点击"]("@@解散队伍", 477, 630, 1000)
              _ENV["需要去挂机点一次"] = true
            else
              toast(判断结果 == "未配置" and "未填写队长名，跳过队长校验" or "未识别到队长名，跳过解散操作")
              _ENV["上次检测队长名字"] = tickCount()
            end
          end
          if _ENV["找到"](tz["组队_附近玩家页面"]) or _ENV["找到"](tz["组队_附近队伍页面"]) then
            _ENV["点击"]("@@关闭队伍页面", 1201, 126, 1500)
          end
        end
        if _ENV["上次检测是否有队伍"] == nil then
          _ENV["上次检测是否有队伍"] = tickCount()
        end
        if tickCount() - _ENV["上次检测是否有队伍"] > 1000 * tonumberEx(_ENV["界面数据"]["检测是否有队伍频率秒"]) then
          if _ENV["在主页面"]() and _ENV["没有创建队伍"]() then
            _ENV["点击"]("@@点队伍按钮", 1114, 556, 2000)
          end
          if _ENV["找到"](tz["组队_附近玩家页面"]) then
            _ENV["点击"]("@@去附近队伍页面", 1141, 323, 1500)
          end
          if _ENV["找到"](tz["组队_附近队伍页面"]) then
            local 找到队长 = false
            local tb = _ENV["组队_识别所有队伍名字"](576, 186, 626, 525)
            for i = 1, #tb do
              print(tb[i].words)
              for ii = 1, #_ENV["队长名称数组"] do
                local sim = w.stringSimilarity.levenshteinNormalized(tb[i].words, _ENV["队长名称数组"][ii])
                print(sim, tb[i].words, _ENV["队长名称数组"][ii], tb[i])
                if 0.99 <= sim then
                  找到队长 = true
                  toast(string.format("[%s]==[%s] %s", tb[i].words, _ENV["队长名称数组"][ii], tostring(sim)))
                  sleep(1000)
                  _ENV["点击"]("@@点击队伍", math.floor(tb[i].x), math.floor(tb[i].y), 2000)
                  if not _ENV["找到点击"]("@@加入队伍按钮", tz["加入队伍按钮"]) then
                    break
                  end
                  for i = 1, 10 do
                    sleep(1000)
                    toast("等待队长同意" .. i .. "/10")
                    if _ENV["找到"](tz["组队_左侧发现旗帜"]) then
                      goto lbl_461
                    end
                  end
                  break
                else
                  toast(string.format("[%s]!=[%s] %s", tb[i].words, _ENV["队长名称数组"][ii], tostring(sim)))
                  sleep(1000)
                end
              end
              ::lbl_461::
              if _ENV["在主页面"]() then
                break
              end
            end
            if 找到队长 == false then
              if _ENV["队员找不到队长组队次数"] == nil then
                _ENV["队员找不到队长组队次数"] = 0
              end
              _ENV["队员找不到队长组队次数"] = _ENV["队员找不到队长组队次数"] + 1
              toast("没有找到队长" .. _ENV["队员找不到队长组队次数"] .. "次/" .. _ENV["界面数据"]["队员找不到队长组队就回家次数"])
              sleep(1000)
              if _ENV["队员找不到队长组队次数"] >= tonumberEx(_ENV["界面数据"]["队员找不到队长组队就回家次数"]) then
                _ENV["队员找不到队长组队次数"] = 0
                _ENV["需要去挂机点一次"] = true
              end
            else
              _ENV["队员找不到队长组队次数"] = 0
            end
            if _ENV["找到"](tz["组队_附近队伍页面"]) then
              _ENV["点击"]("@@关闭队伍 应该没有组队成功", 1203, 130, 1000)
            end
          end
        end
      end
      if _ENV["需要去挂机点一次"] then
        if not _ENV["执行返回挂机点"]() then sleep(3000) end
      else
        _ENV["执行自动战斗"]()
      end
    end
    _ENV["通用关闭"]()
    if _ENV["在主页面"]() and _ENV["检测背包"]() == false then
      toast("前往卖货")
      _ENV["去卖货"]()
      _ENV["执行返回挂机点"]()
    end
    sleep(1000)
  end
end

_ENV["挂机模式"] = L0_1
