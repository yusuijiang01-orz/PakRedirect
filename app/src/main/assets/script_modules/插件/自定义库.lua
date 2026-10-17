local L0_1, L1_1

function L0_1(filePath)
  local file = io.open(filePath, "rb")
  if not file then
    return nil, "无法打开文件"
  end
  local currentPosition = file:seek("end")
  file:close()
  return currentPosition
end

getFileSize = L0_1
