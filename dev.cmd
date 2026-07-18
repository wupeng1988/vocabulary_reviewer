@echo off
chcp 65001 >nul 2>&1
setlocal enabledelayedexpansion

:: Word Review Pro 一键启停脚本 (CMD)
:: 用法: dev.cmd start | stop | status

set "PROJECT_DIR=%~dp0"
:: 去掉末尾反斜杠
if "%PROJECT_DIR:~-1%"=="\" set "PROJECT_DIR=%PROJECT_DIR:~0,-1%"
set "BACKEND_DIR=%PROJECT_DIR%\review-backend"
set "FRONTEND_DIR=%PROJECT_DIR%\review-frontend"
set "BACKEND_PORT=8080"
set "FRONTEND_PORT=5173"
set "PID_DIR=%PROJECT_DIR%\.pids"

if not exist "%PID_DIR%" mkdir "%PID_DIR%"

if "%1"=="" goto :usage
if /i "%1"=="start" goto :do_start
if /i "%1"=="stop" goto :do_stop
if /i "%1"=="status" goto :do_status
goto :usage

:: ==================== 启动 ====================
:do_start
echo ━━━ 启动 Word Review Pro ━━━
call :start_backend
call :start_frontend
echo.
call :show_status
echo.
echo 🌐 访问地址: http://localhost:%FRONTEND_PORT%
goto :eof

:: ==================== 停止 ====================
:do_stop
echo ━━━ 停止 Word Review Pro ━━━
call :stop_frontend
call :stop_backend
echo.
call :show_status
goto :eof

:: ==================== 状态 ====================
:do_status
call :show_status
goto :eof

:: ==================== 启动后端 ====================
:start_backend
netstat -ano | findstr ":%BACKEND_PORT% " | findstr "LISTENING" >nul 2>&1
if %errorlevel%==0 (
    echo ⚠️  后端已在运行 (端口 %BACKEND_PORT%)
    goto :eof
)
echo 🚀 启动后端 (Spring Boot)...
cd /d "%BACKEND_DIR%"
start /b mvn spring-boot:run > "%PID_DIR%\backend.log" 2>&1
:: 记录 PID（通过端口查找）
echo 等待后端启动...
set "BACKEND_READY=0"
for /l %%i in (1,1,30) do (
    if !BACKEND_READY!==0 (
        timeout /t 2 /nobreak >nul 2>&1
        curl -s http://localhost:%BACKEND_PORT%/api/review/config >nul 2>&1
        if !errorlevel!==0 (
            set "BACKEND_READY=1"
            echo ✅ 后端启动成功 (http://localhost:%BACKEND_PORT%)
        )
    )
)
if %BACKEND_READY%==0 (
    echo ❌ 后端启动超时，请检查日志: %PID_DIR%\backend.log
)
cd /d "%PROJECT_DIR%"
goto :eof

:: ==================== 启动前端 ====================
:start_frontend
netstat -ano | findstr ":%FRONTEND_PORT% " | findstr "LISTENING" >nul 2>&1
if %errorlevel%==0 (
    echo ⚠️  前端已在运行 (端口 %FRONTEND_PORT%)
    goto :eof
)
echo 🚀 启动前端 (Vue + Vite)...
cd /d "%FRONTEND_DIR%"
start /b npx vite --host > "%PID_DIR%\frontend.log" 2>&1
echo 等待前端启动...
set "FRONTEND_READY=0"
for /l %%i in (1,1,15) do (
    if !FRONTEND_READY!==0 (
        timeout /t 1 /nobreak >nul 2>&1
        curl -s http://localhost:%FRONTEND_PORT% >nul 2>&1
        if !errorlevel!==0 (
            set "FRONTEND_READY=1"
            echo ✅ 前端启动成功 (http://localhost:%FRONTEND_PORT%)
        )
    )
)
if %FRONTEND_READY%==0 (
    echo ❌ 前端启动超时，请检查日志: %PID_DIR%\frontend.log
)
cd /d "%PROJECT_DIR%"
goto :eof

:: ==================== 停止后端 ====================
:stop_backend
set "FOUND_PID="
for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":%BACKEND_PORT% " ^| findstr "LISTENING"') do (
    set "FOUND_PID=%%p"
)
if defined FOUND_PID (
    echo 🛑 停止后端 (PID: %FOUND_PID%)...
    taskkill /pid %FOUND_PID% /t /f >nul 2>&1
    echo ✅ 后端已停止
) else (
    echo ℹ️  后端未在运行
)
goto :eof

:: ==================== 停止前端 ====================
:stop_frontend
set "FOUND_PID="
for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":%FRONTEND_PORT% " ^| findstr "LISTENING"') do (
    set "FOUND_PID=%%p"
)
if defined FOUND_PID (
    echo 🛑 停止前端 (PID: %FOUND_PID%)...
    taskkill /pid %FOUND_PID% /t /f >nul 2>&1
    echo ✅ 前端已停止
) else (
    echo ℹ️  前端未在运行
)
goto :eof

:: ==================== 显示状态 ====================
:show_status
echo ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
echo   Word Review Pro 运行状态
echo ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
netstat -ano | findstr ":%BACKEND_PORT% " | findstr "LISTENING" >nul 2>&1
if %errorlevel%==0 (
    echo   后端: ✅ 运行中 (http://localhost:%BACKEND_PORT%)
) else (
    echo   后端: ❌ 未运行
)
netstat -ano | findstr ":%FRONTEND_PORT% " | findstr "LISTENING" >nul 2>&1
if %errorlevel%==0 (
    echo   前端: ✅ 运行中 (http://localhost:%FRONTEND_PORT%)
) else (
    echo   前端: ❌ 未运行
)
echo ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
goto :eof

:: ==================== 用法 ====================
:usage
echo 用法: %~nx0 {start^|stop^|status}
echo.
echo   start  - 一键启动前后端
echo   stop   - 一键停止前后端
echo   status - 查看运行状态
exit /b 1
