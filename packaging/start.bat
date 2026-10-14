@echo off
REM ============================================================
REM  Word Review Pro 启动脚本（Windows）
REM  双击本文件即可启动。程序在后台运行，关掉本窗口不会停止它 —— 要停止请运行 stop.bat
REM
REM  [注意] 本文件在仓库里是 UTF-8 + LF，release.sh 打包时会转成 GBK + CRLF 再放进
REM     发布包。原因：cmd.exe 是按系统代码页（中文 Windows 为 936/GBK）读批处理
REM     的，直接交付 UTF-8 文件会被按 GBK 错切行 —— 表现为 REM 整行丢失、
REM     后面的破折号被当成命令、中文全部变乱码（"没有找到" 变成 "娌℃湁鎵惧埌"）。
REM     LF 换行同样会让多行 if (...) 块解析错乱。所以：改完别直接拿这个文件去 Windows
REM     上跑，走 ./release.sh --platform windows-x86_64 打包。
REM
REM  [踩过的坑 1] 千万不要用 `for /f %%i in ('powershell ... Start-Process -PassThru ...')`
REM     去捕获 PID。cmd 的 for /f 会给这条命令接一根管道，PowerShell 又会把这根管道
REM     （连同它继承到的其它可继承句柄）原样传给 java，于是 java 一直攥着管道写端不放，
REM     for /f 永远等不到 EOF —— 表现就是卡在启动横幅那儿不动，进程号 / 访问地址 /
REM     浏览器全都不出现。正确做法：让 PowerShell 把 PID 写进文件，cmd 再 set /p 读。
REM
REM  [踩过的坑 2] 就绪探测别用 Invoke-WebRequest。它每次都要冷启一个 PowerShell（约 1~2 秒），
REM     而且会走系统代理设置，在挂着代理的机器上对 localhost 也可能绕远路或超时。
REM     直接用 TcpClient 探端口，快且不吃代理。
REM ============================================================
setlocal enabledelayedexpansion
REM 先记下进来的代码页。chcp 改的是**整个控制台窗口**的代码页，不是本进程的：
REM 双击时是新控制台，关掉就没了无所谓；但若在已经开着的 cmd / PowerShell 会话里
REM 跑本脚本，不恢复就会把那个会话的代码页永久改掉（之后贴 emoji / 生僻字变方块）。
for /f "tokens=2 delims=:" %%a in ('chcp') do set "OLDCHCP=%%a"
for /f "tokens=* delims= " %%a in ("%OLDCHCP%") do set "OLDCHCP=%%a"
REM 强制控制台代码页 = GBK，和交付编码保持一致（即便系统勾了 UTF-8 beta 选项也稳）
chcp 936 >nul
cd /d "%~dp0"

set "JAR=WordReviewPro.jar"
set "JAVA_BIN="
set "APP_PID="
set "OUT_LOG=app-out.log"
set "ERR_LOG=app-err.log"

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
    call :RESTORE_CHCP
    pause
    exit /b 1
)

if not exist "%JAR%" (
    echo [X] 没有找到 %JAR%，请确认解压后的文件完整。
    call :RESTORE_CHCP
    pause
    exit /b 1
)

REM ---------- 3. 端口占用检查（和 start.sh 保持一致，只提醒不拦截） ----------
netstat -ano | findstr /c:":%PORT% " | findstr /i LISTENING >nul 2>nul
if not errorlevel 1 (
    echo [!] 端口 %PORT% 已被占用，程序可能已经在运行了（也可能是别的软件占的）。
    echo     - 先在浏览器打开 %URL% 试试，能打开就说明程序本来就在跑，不用再启一次
    echo     - 确认是别的软件占的，就把本目录 application.properties 里的端口换掉
    echo.
)

echo ========================================
echo    Word Review Pro 启动中......
echo ========================================
echo    首次启动会扫描并补齐缺失的发音，那一步在后台跑，
echo    Java: %JAVA_BIN%
echo    端口: %PORT%   (想换端口改 application.properties)
echo    日志: %OUT_LOG% / %ERR_LOG%
echo.

REM ---------- 4. 后台启动（隐藏窗口），PID 写进文件 ----------
REM 见上面 [踩过的坑 1]：这里绝对不能用 for /f 去接 PowerShell 的输出
if exist "%~dp0app.pid" del "%~dp0app.pid" >nul 2>nul
powershell -NoProfile -ExecutionPolicy Bypass -Command "$p = Start-Process -WindowStyle Hidden -FilePath '%JAVA_BIN%' -ArgumentList '-Xms128m','-Xmx512m','-Dfile.encoding=UTF-8','-jar','%JAR%','--server.port=%PORT%' -WorkingDirectory '%~dp0' -RedirectStandardOutput '%~dp0%OUT_LOG%' -RedirectStandardError '%~dp0%ERR_LOG%' -PassThru; [IO.File]::WriteAllText('%~dp0app.pid', [string]$p.Id)"

if not exist "%~dp0app.pid" (
    echo [X] 启动失败：没能把程序拉起来。请把本窗口截图发给发送程序的人。
    call :RESTORE_CHCP
    pause
    exit /b 1
)
set /p APP_PID=<"%~dp0app.pid"
if not defined APP_PID (
    echo [X] 启动失败：进程号文件是空的。请把本窗口截图发给发送程序的人。
    call :RESTORE_CHCP
    pause
    exit /b 1
)
echo    进程 PID: %APP_PID%

REM ---------- 5. 轮询等待就绪 ----------
set /a TRIES=0
:WAIT_LOOP
set /a TRIES+=1
if %TRIES% GEQ 90 goto TIMEOUT

REM 进程已经没了就别傻等，直接去看日志
tasklist /FI "PID eq %APP_PID%" 2>nul | findstr /I "%APP_PID%" >nul
if errorlevel 1 goto DIED

REM 日志里出现致命错误就立刻收工，不等满超时（HITERR 必须每轮重建，否则会捡到上一轮的 errorlevel）
set "HITERR="
if exist "%~dp0%ERR_LOG%" (
    findstr /c:"APPLICATION FAILED TO START" /c:"The file is locked" /c:"already in use" /c:"Unrecognized option" /c:"no main manifest attribute" "%~dp0%ERR_LOG%" >nul 2>nul
    if not errorlevel 1 set "HITERR=1"
)
REM Spring Boot 的启动失败横幅走的是 stdout，两个日志都得查
if exist "%~dp0%OUT_LOG%" (
    findstr /c:"APPLICATION FAILED TO START" /c:"The file is locked" /c:"already in use" /c:"no main manifest attribute" "%~dp0%OUT_LOG%" >nul 2>nul
    if not errorlevel 1 set "HITERR=1"
)
if defined HITERR goto FAILED

REM 打点表示还在等（否则窗口像卡死了，用户会直接 Ctrl+C）
<nul set /p "=."
ping -n 2 127.0.0.1 >nul
powershell -NoProfile -ExecutionPolicy Bypass -Command "$c = New-Object Net.Sockets.TcpClient; try { $c.Connect('127.0.0.1', %PORT%); $c.Close(); exit 0 } catch { exit 1 }" >nul 2>nul
if not errorlevel 1 goto READY
goto WAIT_LOOP

:READY
echo.
echo.
echo [OK] 启动成功！
echo      浏览器即将打开 %URL%
echo.
echo      注意：程序是后台运行的，**直接点窗口的 X 关掉它，程序不会停**。
echo      要停止请双击本目录的 stop.bat（或留在本窗口按任意键）。
echo.
start "" "%URL%"

REM ---------- 6. 本窗口关闭 / Ctrl+C 时清理进程 ----------
echo 按任意键停止程序并退出...
pause >nul
taskkill /PID %APP_PID% /F >nul 2>nul
if exist "%~dp0app.pid" del "%~dp0app.pid" >nul 2>nul
echo 已停止。
call :RESTORE_CHCP
exit /b 0

:DIED
echo.
echo.
echo [X] 程序启动后立刻退出了。最近日志：
call :SHOW_LOG
call :SHOW_HINTS
taskkill /PID %APP_PID% /F >nul 2>nul
if exist "%~dp0app.pid" del "%~dp0app.pid" >nul 2>nul
call :RESTORE_CHCP
pause
exit /b 1

:FAILED
echo.
echo.
echo [X] 启动失败，最近日志：
call :SHOW_LOG
call :SHOW_HINTS
taskkill /PID %APP_PID% /F >nul 2>nul
if exist "%~dp0app.pid" del "%~dp0app.pid" >nul 2>nul
call :RESTORE_CHCP
pause
exit /b 1

:TIMEOUT
echo.
echo.
echo [X] 启动超时（90 秒端口仍未就绪），最近日志：
call :SHOW_LOG
call :SHOW_HINTS
taskkill /PID %APP_PID% /F >nul 2>nul
if exist "%~dp0app.pid" del "%~dp0app.pid" >nul 2>nul
call :RESTORE_CHCP
pause
exit /b 1

REM ---------- 子过程：把两个日志的尾部打出来 ----------
:SHOW_LOG
echo ----------------------------------
if exist "%~dp0%ERR_LOG%" powershell -NoProfile -Command "Get-Content -Tail 30 -Encoding UTF8 '%~dp0%ERR_LOG%'"
if exist "%~dp0%OUT_LOG%" powershell -NoProfile -Command "Get-Content -Tail 30 -Encoding UTF8 '%~dp0%OUT_LOG%'"
if not exist "%~dp0%ERR_LOG%" if not exist "%~dp0%OUT_LOG%" echo     （还没有日志文件，程序多半是压根没被拉起来）
echo ----------------------------------
exit /b 0

REM ---------- 子过程：按日志内容给一句人话提示 ----------
:SHOW_HINTS
set "LOCKED="
set "OLDJAVA="
if exist "%~dp0%ERR_LOG%" (
    findstr /c:"The file is locked" /c:"already in use" "%~dp0%ERR_LOG%" >nul 2>nul
    if not errorlevel 1 set "LOCKED=1"
    findstr /c:"Unrecognized option" "%~dp0%ERR_LOG%" >nul 2>nul
    if not errorlevel 1 set "OLDJAVA=1"
)
if exist "%~dp0%OUT_LOG%" (
    findstr /c:"The file is locked" /c:"already in use" "%~dp0%OUT_LOG%" >nul 2>nul
    if not errorlevel 1 set "LOCKED=1"
)
if defined LOCKED (
    echo.
    echo [!] 提示: 数据库被占用了，多半是程序已经开着（可能重复双击了）。
    echo     请先双击本目录的 stop.bat，或直接访问 %URL%
)
if defined OLDJAVA (
    echo.
    echo [!] 提示: Java 版本太老（需要 11 及以上）。装新版 Temurin 后再试。
)
exit /b 0
REM ---------- 子过程：恢复进脚本时的代码页 ----------
:RESTORE_CHCP
if defined OLDCHCP chcp %OLDCHCP% >nul 2>nul
exit /b 0
