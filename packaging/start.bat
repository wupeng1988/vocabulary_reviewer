@echo off
REM ============================================================
REM  Word Review Pro 启动脚本（Windows）
REM  双击本文件即可启动；关闭本窗口即自动停止程序
REM ============================================================
setlocal enabledelayedexpansion
chcp 65001 >nul
cd /d "%~dp0"

set "JAR=WordReviewPro.jar"
set "LOG=app.log"
set "JAVA_BIN="
set "APP_PID="
REM ---------- 1. 读取端口配置（默认 8080，可用 application.properties 修改） ----------
set "PORT=8080"
if exist "%~dp0application.properties" (
    for /f "usebackq tokens=1,* delims== " %%a in (`findstr /b /r /c:"server[.]port" "%~dp0application.properties"`) do set "PORT=%%b"
)
REM 校验：PORT 必须是纯数字，否则回退 8080（for /f 在全是数字时不会进循环体）
for /f "delims=0123456789" %%x in ("%PORT%") do set "PORT=8080"
if not defined PORT set "PORT=8080"
set "URL=http://localhost:%PORT%"

REM ---------- 2. 找 Java：优先用包里自带的 JRE ----------
if exist "%~dp0jre\bin\java.exe" set "JAVA_BIN=%~dp0jre\bin\java.exe"
if not defined JAVA_BIN if exist "%~dp0jdk\bin\java.exe" set "JAVA_BIN=%~dp0jdk\bin\java.exe"
if not defined JAVA_BIN (
    where java >nul 2>nul
    if not errorlevel 1 set "JAVA_BIN=java"
)

if not defined JAVA_BIN (
    echo [X] 没有找到 Java 运行环境。
    echo.
    echo     本程序是 Java 应用，需要 Java 11 及以上版本。
    echo     推荐安装免费的 Temurin: https://adoptium.net/temurin/releases/
    echo     安装完成后请重新双击本文件。
    echo.
    pause
    exit /b 1
)

if not exist "%JAR%" (
    echo [X] 没有找到 %JAR%，请确认解压后的文件完整。
    pause
    exit /b 1
)

echo ========================================
echo    Word Review Pro 启动中......
echo ========================================
echo    Java: %JAVA_BIN%
echo    端口: %PORT%   (想换端口改 application.properties)
echo    日志: %LOG%
echo.

REM ---------- 3. 后台启动（隐藏窗口），记录 PID ----------
for /f "delims=" %%i in ('powershell -NoProfile -ExecutionPolicy Bypass -Command "(Start-Process -WindowStyle Hidden -FilePath '%JAVA_BIN%' -ArgumentList '-Xms128m','-Xmx512m','-Dfile.encoding=UTF-8','-jar','%JAR%','--server.port=%PORT%' -WorkingDirectory '%~dp0' -RedirectStandardOutput '%~dp0app-out.log' -RedirectStandardError '%~dp0app-err.log' -PassThru).Id"') do set "APP_PID=%%i"

if not defined APP_PID (
    echo [X] 启动失败，请把本窗口截图发给发送程序的人。
    pause
    exit /b 1
)
echo    进程 PID: %APP_PID%

REM ---------- 4. 轮询等待就绪（只用 PowerShell，避免依赖 curl） ----------
set /a TRIES=0
:WAIT_LOOP
set /a TRIES+=1
if %TRIES% GEQ 60 goto TIMEOUT
ping -n 2 127.0.0.1 >nul
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ok=$false; try { $r=Invoke-WebRequest -Uri '%URL%/api/review/config' -UseBasicParsing -TimeoutSec 2; if ($r.StatusCode -eq 200) { $ok=$true } } catch { }; if ($ok) { exit 0 } else { exit 1 }" >nul 2>nul
if not errorlevel 1 goto READY
goto WAIT_LOOP

:READY
echo.
echo [OK] 启动成功！
echo      浏览器即将打开 %URL%
echo      关闭本窗口即可停止程序
echo.
start "" "%URL%"

REM ---------- 5. 本窗口关闭 / Ctrl+C 时清理进程 ----------
echo 按任意键停止程序并退出...
pause >nul
taskkill /PID %APP_PID% /F >nul 2>nul
echo 已停止。
exit /b 0

:TIMEOUT
echo.
echo [X] 启动超时（60 秒未就绪），最近日志：
echo ----------------------------------
if exist "%~dp0app-err.log" powershell -NoProfile -Command "Get-Content -Tail 30 -Encoding UTF8 '%~dp0app-err.log'"
if exist "%~dp0app-out.log" powershell -NoProfile -Command "Get-Content -Tail 30 -Encoding UTF8 '%~dp0app-out.log'"
echo ----------------------------------
powershell -NoProfile -Command "if ((Test-Path '%~dp0app-err.log') -and (Select-String -Path '%~dp0app-err.log' -Pattern 'already in use','The file is locked' -Quiet)) { Write-Host ''; Write-Host '提示: 数据库被占用了，多半是程序已经开着（可能重复双击了）。'; Write-Host '      请先关掉已有的 Word Review Pro 窗口，或直接访问 %URL%' }"
taskkill /PID %APP_PID% /F >nul 2>nul
pause
exit /b 1
