local L0_1, L1_1, L2_1, L3_1, L4_1, L5_1, L6_1, L7_1, L8_1
_ENV["当前软件版本"] = 72
_ENV["游戏包名"] = "com.tepaylink.tamgioiphantranhmobile"
L0_1 = setControlBarPosNew
L1_1 = 0
L2_1 = 1
L0_1(L1_1, L2_1)
L0_1 = _G
L1_1 = _G
L2_1 = _G
L3_1 = _G
L4_1 = _G
L5_1 = ""
L6_1 = 0
L7_1 = 0
L8_1 = ""
L4_1["当前任务2"] = ""
L3_1["当前任务1"] = L8_1
L2_1["当前血量"] = L7_1
L1_1["当前等级"] = L6_1
L0_1["当前页面"] = L5_1
_ENV["没有变化次数"] = 0
_ENV["检测挂机长时间不移动_寻路没有变化次数"] = 0
_ENV["当前背包格子"] = 0
_ENV["当前背包重量"] = ""
_ENV["当前元宝"] = ""

function L0_1()
  local hud = createHUD()
  while true do
    pcall(function()
      showHUD(hud, string.format("%s %s-%s 卡%d 格%s.%s.%s.%s %s/%s 宝%s 版%d", _ENV["界面数据"]["手机编号"], _ENV["当前任务1"], _ENV["当前任务2"], _ENV["检测挂机长时间不移动_寻路没有变化次数"], tostring(_ENV["小红数量"]), tostring(_ENV["大红数量"]), tostring(_ENV["小蓝数量"]), tostring(_ENV["大蓝数量"]), tostring(_ENV["当前背包格子"]), tostring(_ENV["当前背包重量"]), tostring(_ENV["当前元宝"]), _ENV["当前软件版本"]), 8, "0xffff0000", "0xffffffff", 0, 0, 1020, -1, 35)
    end)
    sleep(500)
  end
end

_ENV["显示线程"] = L0_1

function L0_1()
  if getPackageName() == "com.fsb" then
    local 本地版本 = _ENV["当前软件版本"]
    local 远程版本 = httpGet("http://119.29.253.58/lr/com.fsb/ver.php")
    local 远程md5 = httpGet("http://119.29.253.58/?action=getmd5&app=com.fsb")
    print("本地版本", 本地版本)
    print("远程md5", 远程md5)
    if 远程md5 ~= "" and 本地版本 ~= tonumber(远程版本) then
      function progress(pos)
        toast("下载进度:" .. pos, 0, 0, 30)
      end
      
      toast("开始热更新")
      sleep(1000)
      downloadFile("http://119.29.253.58/lr/com.fsb/update.lrj", "/sdcard/update.lrj")
      local 本地md5 = fileMD5("/sdcard/update.lrj")
      if 本地md5 == 远程md5 then
        toast("热更新完毕")
        sleep(2000)
        installLrPkg("/sdcard/update.lrj")
        restartScript()
      end
      toast("热更新失败")
      sleep(2000)
    end
  end
  toast("无需热更新")
  sleep(2000)
end

_ENV["热更新"] = L0_1
L0_1 = _ENV["热更新"]
L0_1()
L0_1 = beginThread
L1_1 = _ENV["显示线程"]
L0_1(L1_1)
L0_1 = require
L1_1 = "TURING_VIP"
L0_1 = L0_1(L1_1)
TURING = L0_1
L0_1 = require
L1_1 = "tomocr"
L0_1(L1_1)
L0_1 = require
L1_1 = "w"
L0_1 = L0_1(L1_1)
w = L0_1
L0_1 = require
L1_1 = "中控"
L0_1(L1_1)
L0_1 = require
L1_1 = "通用数据"
L0_1(L1_1)
L0_1 = require
L1_1 = "挂机模式"
L0_1(L1_1)
L0_1 = hideToast
L0_1()
L0_1 = sleep
L1_1 = 500
L0_1(L1_1)
L0_1 = splitStr
L1_1 = "崇城野外,sùng thành\n北海,bắc hải\n燕山,Yến sơn\n西昆仑,tây côn lôn\n昆仑山,chân núi côn lôn\n首阳山,dương sơn\n游魂关,du hồn\n苗疆,miêu cương\n巨鹿,cự lộc\n潼关,đồng quan\n孟津,mạnh tân\n城塘关,trần đường\n荒漠,hoang mạc\n荒漠2,thổ thành\n荒漠3,phong thần\n荒漠4,lục châu\n荒漠5,sa mạc chết\n海1,thủy vực\n海2,long cung\n海3,hải c\n海4,Long Vực\n海5,Long Uyên\n火1,Hiên Viên tầng 1\n火2,Hiên Viên tầng 2\n火3,Hiên Viên tầng 3\n火4,Hiên Viên tầng 4\n火5,Hiên Viên tầng 5\n冰1,Ngọc Tuyền\n冰2,Tuyết Cốc\n冰3,Đại Phong\n冰4,Đại Trạch\n冰5,Băng Xuyên\n捆仙宫1,Khổn Tiên tầng 1\n捆仙宫2,Khổn Tiên tầng 2\n捆仙宫3,Khổn Tiên tầng 3\n捆仙宫4,Khổn Tiên tầng 4\n捆仙宫5,Khổn Tiên tầng 5\n碧游宫1,Bích Du tầng 1"
L2_1 = "\n"
L0_1 = L0_1(L1_1, L2_1)
_ENV["中越大地图数组"] = L0_1

function L0_1()
  _ENV["界面文本编号"] = 0
  
  function getuitextnumber()
    _ENV["界面文本编号"] = _ENV["界面文本编号"] + 1
    return "编号" .. _ENV["界面文本编号"] .. "_" .. _ENV["当前软件版本"]
  end
  
  _ENV["界面完成"] = false
  ui.newLayout("layout1", -1, -1)
  ui.addTabView("layout1", "tabviewId1")
  ui.addTab("tabviewId1", "lab1", "子标签1")
  ui.addTab("tabviewId1", "卖货设置", "卖货设置")
  ui.addTab("tabviewId1", "挂机设置", "挂机设置")
  ui.addTab("tabviewId1", "红蓝设置", "红蓝设置")
  ui.addTab("tabviewId1", "队伍设置", "队伍设置")
  ui.newRow("队伍设置", getuitextnumber())
  ui.addRadioGroup("队伍设置", "是否队长", {
    "队长",
    "队员",
    "不组队"
  }, 0)
  ui.newRow("队伍设置", getuitextnumber())
  ui.addTextView("队伍设置", getuitextnumber(), "队长名")
  ui.addEditText("队伍设置", "队长名", "", 300)
  ui.addTextView("队伍设置", getuitextnumber(), "检测队长名字频率秒")
  ui.addEditText("队伍设置", "检测队长名字频率秒", "180", 100)
  ui.addTextView("队伍设置", getuitextnumber(), "检测是否有队伍频率秒")
  ui.addEditText("队伍设置", "检测是否有队伍频率秒", "180", 100)
  ui.newRow("队伍设置", getuitextnumber())
  ui.addTextView("队伍设置", getuitextnumber(), "队员名")
  ui.addEditText("队伍设置", "队员名", "", 300)
  ui.newRow("红蓝设置", getuitextnumber())
  ui.addTextView("红蓝设置", getuitextnumber(), "红的数量高于")
  ui.addEditText("红蓝设置", "红的数量高于", "10", 100)
  ui.addCheckBox("红蓝设置", "出售全部红", "出售全部红", false)
  ui.newRow("红蓝设置", getuitextnumber())
  ui.addTextView("红蓝设置", getuitextnumber(), "蓝的数量高于")
  ui.addEditText("红蓝设置", "蓝的数量高于", "10", 100)
  ui.addCheckBox("红蓝设置", "出售全部蓝", "出售全部蓝", false)
  ui.newRow("红蓝设置", getuitextnumber())
  ui.addTextView("红蓝设置", getuitextnumber(), "红的数量低于")
  ui.addEditText("红蓝设置", "红的数量低于", "10", 100)
  ui.addCheckBox("红蓝设置", "购买小红", "购买小红", false)
  ui.addEditText("红蓝设置", "购买红的数量", "10", 100)
  ui.newRow("红蓝设置", getuitextnumber())
  ui.addTextView("红蓝设置", getuitextnumber(), "蓝的数量低于")
  ui.addEditText("红蓝设置", "蓝的数量低于", "10", 100)
  ui.addCheckBox("红蓝设置", "购买小蓝", "购买小蓝", false)
  ui.addEditText("红蓝设置", "购买蓝的数量", "10", 100)
  ui.newRow("挂机设置", getuitextnumber())
  ui.addTextView("挂机设置", getuitextnumber(), "挂机点坐标")
  ui.addEditText("挂机设置", "挂机点x", "100", 100)
  ui.addEditText("挂机设置", "挂机点y", "200", 100)
  ui.newRow("挂机设置", getuitextnumber())
  ui.addTextView("挂机设置", getuitextnumber(), "挂机点地图")
  ui.addRadioGroup("挂机设置", "挂机点地图", _ENV["中越大地图数组"], 0)
  ui.newRow("卖货设置", getuitextnumber())
  ui.addTextView("卖货设置", getuitextnumber(), "卖货地图")
  ui.addRadioGroup("卖货设置", "卖货地图", _ENV["中越大地图数组"], 0)
  ui.addTextView("lab1", getuitextnumber(), "版本1." .. _ENV["当前软件版本"])
  ui.newRow("lab1", getuitextnumber())
  ui.addTextView("lab1", getuitextnumber(), "手机编号")
  ui.addEditText("lab1", "手机编号", "", 400)
  ui.newRow("lab1", getuitextnumber())
  ui.addButton("lab1", "上传截图", "上传截图")
  ui.setOnClick("上传截图", "上传截图(true)")
  ui.newRow("lab1", getuitextnumber())
  ui.addTextView("lab1", getuitextnumber(), "挂机")
  ui.addEditText("lab1", "挂机不移动秒", "60", 100)
  ui.addTextView("lab1", getuitextnumber(), "秒不移动 回挂机点")
  ui.newRow("lab1", getuitextnumber())
  ui.addTextView("lab1", getuitextnumber(), "队员")
  ui.addEditText("lab1", "队员找不到队长组队就回家次数", "2", 100)
  ui.addTextView("lab1", getuitextnumber(), "找不到队长组队 回挂机点")
  ui.newRow("lab1", getuitextnumber())
  ui.addTextView("lab1", getuitextnumber(), "背包保留重量")
  ui.addEditText("lab1", "背包保留重量", "3", 100)
  ui.addTextView("lab1", getuitextnumber(), "背包保留格子")
  ui.addEditText("lab1", "背包保留格子", "2", 100)
  ui.newRow("lab1", getuitextnumber())
  ui.addCheckBox("lab1", "检测修理装备", "检测修理装备", true)
  ui.newRow("lab1", getuitextnumber())
  ui.addTextView("lab1", getuitextnumber(), "背包检测间隔秒")
  ui.addEditText("lab1", "背包检测间隔秒", "60", 100)
  ui.newRow("lab1", getuitextnumber())
  ui.addTextView("lab1", getuitextnumber(), "保留蓝条数量")
  ui.addEditText("lab1", "保留蓝条数量", "4", 100)
  ui.newRow("lab1", getuitextnumber())
  ui.addTextView("lab1", getuitextnumber(), "同时1条选中生效")
  ui.addCheckBox("lab1", "回蓝>=", "回蓝>=", true)
  ui.addEditText("lab1", "保留回蓝数值", "10", 50)
  ui.newRow("lab1", getuitextnumber())
  ui.addCheckBox("lab1", "魔法出招>=", "魔法出招>=", true)
  ui.addEditText("lab1", "保留魔法出招数值", "15", 50)
  ui.addCheckBox("lab1", "武器出招>=", "武器出招>=", true)
  ui.addEditText("lab1", "保留武器出招数值", "15", 50)
  ui.newRow("lab1", getuitextnumber())
  ui.addCheckBox("lab1", "开启vpn", "开启vpn", true)
  ui.loadProfile("/sdcard/封神榜配置.txt")
  ui.show("layout1")
  ui.setOnClose("layout1", "onClose()")
  ui.setText("版本号", "版本1." .. _ENV["当前软件版本"])
  
  function onClose()
    _ENV["界面数据"] = ui.getData()
    ui.saveProfile("/sdcard/封神榜配置.txt")
    _ENV["界面完成"] = true
  end
  
  for i = 1, 60 do
    if i == 60 then
      onClose()
      sleep(1000)
      ui.dismiss("layout1")
      sleep(1000)
    end
    if _ENV["界面完成"] then
      break
    end
    sleep(1000)
  end
end

_ENV["显示ui界面"] = L0_1

function L0_1()
  _ENV["界面文本编号"] = 0
  
  function getuitextnumber()
    _ENV["界面文本编号"] = _ENV["界面文本编号"] + 1
    return "编号" .. _ENV["界面文本编号"] .. "_" .. _ENV["当前软件版本"]
  end
  
  _ENV["界面完成"] = false
  ui.newLayout("layout1", -1, -1)
  ui.addTabView("layout1", "tabviewId1")
  ui.addTab("tabviewId1", "lab1", "子标签1")
  ui.addTextView("lab1", getuitextnumber(), "版本1." .. _ENV["当前软件版本"])
  ui.newRow("lab1", getuitextnumber())
  ui.addButton("lab1", "上传截图", "上传截图")
  ui.setOnClick("上传截图", "上传截图(false)")
  ui.loadProfile("/sdcard/封神榜配置.txt")
  ui.show("layout1")
  ui.setOnClose("layout1", "onClose()")
  ui.setText("版本号", "版本1." .. _ENV["当前软件版本"])
  
  function onClose()
    toast("窗口关闭了", 0, 0, 30)
    _ENV["界面数据"] = ui.getData()
    ui.saveProfile("/sdcard/封神榜配置.txt")
    _ENV["界面完成"] = true
  end
  
  for i = 1, 20 do
    if i == 20 then
      onClose()
      sleep(1000)
      ui.dismiss("layout1")
      sleep(1000)
    end
    if _ENV["界面完成"] then
      break
    end
    sleep(1000)
  end
end

_ENV["显示截图ui界面"] = L0_1
L0_1 = _ENV["显示ui界面"]
if _ENV["RYLUX_WORKBENCH_CONFIG"] then
  _ENV["界面数据"] = ui.getData()
else
  L0_1()
end
L0_1 = print
L1_1 = _ENV["界面数据"]
L0_1(L1_1)
L0_1 = splitStr
L1_1 = _ENV["界面数据"]
L1_1 = L1_1["队长名"]
L2_1 = "\n"
L0_1 = L0_1(L1_1, L2_1)
_ENV["队长名称数组"] = L0_1
L0_1 = splitStr
L1_1 = _ENV["界面数据"]
L1_1 = L1_1["队员名"]
L2_1 = "\n"
L0_1 = L0_1(L1_1, L2_1)
_ENV["队友名称数组"] = L0_1
L0_1 = _ENV["界面数据"]
L0_1 = L0_1["是否队长"]
L0_1 = L0_1 == "0"
_ENV["是队长"] = L0_1
L0_1 = print
L1_1 = "开始执行"
L0_1(L1_1)
_ENV["区"] = "9"
_ENV["账号"] = "Qq112211"
_ENV["密码"] = "Qq112211."
L0_1 = _ENV["界面数据"]
L0_1 = L0_1["开启vpn"]
L0_1 = L0_1 == "true"
_ENV["开启vpn"] = L0_1
L0_1 = _ENV["界面数据"]
L0_1 = L0_1["检测修理装备"]
L0_1 = L0_1 == "true"
_ENV["检测修理装备"] = L0_1
L0_1 = tonumber
L1_1 = _ENV["界面数据"]
L1_1 = L1_1["背包保留重量"]
L0_1 = L0_1(L1_1)
_ENV["背包保留重量"] = L0_1
L0_1 = tonumber
L1_1 = _ENV["界面数据"]
L1_1 = L1_1["背包保留格子"]
L0_1 = L0_1(L1_1)
_ENV["背包保留格子"] = L0_1
L0_1 = tonumber
L1_1 = _ENV["界面数据"]
L1_1 = L1_1["背包检测间隔秒"]
L0_1 = L0_1(L1_1)
_ENV["背包检测间隔秒"] = L0_1
L0_1 = _ENV["界面数据"]
L0_1 = L0_1["挂机点x"]
_ENV["挂机点x"] = L0_1
L0_1 = _ENV["界面数据"]
L0_1 = L0_1["挂机点y"]
_ENV["挂机点y"] = L0_1
L0_1 = _ENV["中越大地图数组"]
L1_1 = tonumber
L2_1 = _ENV["界面数据"]
L2_1 = L2_1["卖货地图"]
L1_1 = L1_1(L2_1)
L1_1 = L1_1 + 1
L0_1 = L0_1[L1_1]
_ENV["卖货地图"] = L0_1
L0_1 = splitStr
L1_1 = _ENV["卖货地图"]
L2_1 = ","
L0_1 = L0_1(L1_1, L2_1)
_ENV["卖货地图"] = L0_1
L0_1 = _ENV["卖货地图"]
L0_1 = L0_1[2]
_ENV["卖货地图"] = L0_1
L0_1 = _ENV["中越大地图数组"]
L1_1 = tonumber
L2_1 = _ENV["界面数据"]
L2_1 = L2_1["挂机点地图"]
L1_1 = L1_1(L2_1)
L1_1 = L1_1 + 1
L0_1 = L0_1[L1_1]
_ENV["挂机点地图"] = L0_1
L0_1 = splitStr
L1_1 = _ENV["挂机点地图"]
L2_1 = ","
L0_1 = L0_1(L1_1, L2_1)
_ENV["挂机点地图"] = L0_1
L0_1 = _ENV["挂机点地图"]
L0_1 = L0_1[2]
_ENV["挂机点地图"] = L0_1
L0_1 = tonumber
L1_1 = _ENV["界面数据"]
L1_1 = L1_1["保留蓝条数量"]
L0_1 = L0_1(L1_1)
_ENV["保留蓝条数量"] = L0_1
L0_1 = sleep
L1_1 = 1000
L0_1(L1_1)
L0_1 = print
L1_1 = "开机秒数"
L2_1 = _ENV["取开机秒数"]
L2_1, L3_1, L4_1, L5_1, L6_1, L7_1, L8_1 = L2_1()
L0_1(L1_1, L2_1, L3_1, L4_1, L5_1, L6_1, L7_1, L8_1)
L0_1 = print
L1_1 = _ENV["界面数据"]
L0_1(L1_1)
_ENV["需要去挂机点一次"] = false
_ENV["买了一次红"] = false
_ENV["买了一次蓝"] = false
L0_1 = _ENV["监控命令"]
L0_1()
