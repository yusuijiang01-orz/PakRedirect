@echo off
setlocal
cd /d "%~dp0.."

set "RYLUX_ADB_EXECUTABLE=E:\leidian\LDPlayer14\adb.exe"
if not "%~1"=="" set "RYLUX_ADB_DEVICE=%~1"
if "%RYLUX_ADB_DEVICE%"=="" set "RYLUX_ADB_DEVICE=emulator-5554"

if not exist "%RYLUX_ADB_EXECUTABLE%" (
  echo ADB not found: %RYLUX_ADB_EXECUTABLE%
  echo Edit this BAT and set RYLUX_ADB_EXECUTABLE to your adb.exe path.
  pause
  exit /b 2
)
if not exist "game-agent\.venv\Scripts\python.exe" (
  echo Python environment not found: game-agent\.venv
  echo Create it and install game-agent requirements first.
  pause
  exit /b 2
)

set "PYTHONPATH=%CD%\game-agent\src"
set "RYLUX_TEST_PANEL_PORT=8767"
"game-agent\.venv\Scripts\python.exe" tools\adb_game_test_panel.py
set "RESULT=%ERRORLEVEL%"
if not "%RESULT%"=="0" pause
exit /b %RESULT%
