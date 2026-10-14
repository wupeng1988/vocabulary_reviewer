@echo off
REM ============================================================
REM  Word Review Pro 停止脚本（Windows）
REM  双击即可停止正在后台运行的程序。
REM
REM  为什么需要这个：start.bat 是把程序放到后台再等你按键的，
REM  直接点窗口的 X 只会关掉黑色窗口，程序还在后台跑着。
REM
REM  [注意] 本文件在仓库里是 UTF-8 + LF，release.sh 打包时会转成 GBK + CRLF，
REM     理由同 start.bat：cmd.exe 按系统代码页读批处理，UTF-8 会被错切行。
REM ============================================================
setlocal enabledelayedexpansion
chcp 936 >nul
cd /d "%~dp0"

REM ---------- 1. 读端口配置（和 start.bat 保持一致） ----------
set "PORT=8080"
if exist "%~dp0application.properties" (
    for /f "usebackq tokens=1,* delims== " %%a in (`findstr /b /r /c:"server[.]port" "%~dp0application.properties"`) do set "PORT=%%b"
)
for /f "delims=0123456789" %%x in ("%PORT%") do set "PORT=8080"
if not defined PORT set "PORT=8080"

set "STOPPED="

REM ---------- 2. 优先按 start.bat 记下的 PID 停 ----------
if exist "%~dp0app.pid" (
    set /p APP_PID=<"%~dp0app.pid"
    if defined APP_PID (
        taskkill /PID !APP_PID! /F >nul 2>nul
        if not errorlevel 1 (
            echo [OK] 已停止 Word Review Pro（PID !APP_PID!）
            set "STOPPED=1"
        )
    )
    del "%~dp0app.pid" >nul 2>nul
)

REM ---------- 3. 没有 PID 文件（或进程已经没了）就按端口找 ----------
if not defined STOPPED (
    for /f "tokens=5" %%p in ('netstat -ano ^| findstr /c:":%PORT% " ^| findstr /i LISTENING') do (
        taskkill /PID %%p /F >nul 2>nul
        if not errorlevel 1 (
            echo [OK] 已停止 Word Review Pro（端口 %PORT% 被释放，PID %%p）
            set "STOPPED=1"
        )
    )
)

echo.
if defined STOPPED (
    echo      词库和复习记录存在用户目录的 worddb.mv.db，停止程序不会丢数据。
    echo      下次要用，重新双击 start.bat 即可。
) else (
    echo      没有找到正在运行的 Word Review Pro，可能本来就没开，
    echo      或者已经占用的端口是别的程序（那就别管它）。
)
echo.
pause
