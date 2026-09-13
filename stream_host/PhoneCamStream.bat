@echo off
cd /d "%~dp0"
if exist "Start.bat" (
  call "Start.bat"
) else (
  echo Start.bat missing. Use the full PhoneCamStream folder.
  pause
)
