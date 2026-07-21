@echo off
chcp 65001 >nul 2>&1
setlocal enabledelayedexpansion

:: Word Review Pro 一键启停脚本 (CMD)
:: 用法: dev.cmd start | stop | status

set "PROJECT_DIR=%~dp0"
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

:do_start
echo ━━━ 启动 Word Review Pro ━━━
call :start_backend
call :start_frontend
echo.
call :show_status
echo.
echo 🌐 访问地址: http://localhost:%FRONTEND_PORT%
goto :eof

:do_stop
echo ━━━ 停止 Word Review Pro ━━━
call :stop_frontend
call :stop_backend
echo.
call :show_status
goto :eof

:do_status
call :show_status
goto :eof

:: ==================== 启动后端 ====================
:start_backend
echo 🚀 启动后端 (Spring Boot) 在新窗口中...
cd /d "%BACKEND_DIR%"
start "Backend" cmd /k mvn spring-boot:run
echo ✅ 后端启动命令已发出（请观察新窗口）
cd /d "%PROJECT_DIR%"
goto :eof

:: ==================== 启动前端 ====================
:start_frontend
echo 🚀 启动前端 (Vue + Vite) 在新窗口中...
cd /d "%FRONTEND_DIR%"
start "Frontend" cmd /k npx vite --host
echo ✅ 前端启动命令已发出（请观察新窗口）
cd /d "%PROJECT_DIR%"
goto :eof

:: ==================== 停止后端 ====================
:stop_backend
call :is_port_in_use %BACKEND_PORT% backend_pid
if "!backend_pid!"=="" goto :backend_not_running
echo 🛑 停止后端 (PID: !backend_pid!)...
taskkill /pid !backend_pid! /t /f >nul 2>&1
echo ✅ 后端已停止
goto :eof
:backend_not_running
echo ℹ️  后端未在运行
goto :eof

:: ==================== 停止前端 ====================
:stop_frontend
call :is_port_in_use %FRONTEND_PORT% frontend_pid
if "!frontend_pid!"=="" goto :frontend_not_running
echo 🛑 停止前端 (PID: !frontend_pid!)...
taskkill /pid !frontend_pid! /t /f >nul 2>&1
echo ✅ 前端已停止
goto :eof
:frontend_not_running
echo ℹ️  前端未在运行
goto :eof

:: ==================== 显示状态 ====================
:show_status
echo ---------------------------
echo   Word Review Pro 运行状态
echo ---------------------------
call :is_port_in_use %BACKEND_PORT% backend_pid
if not "!backend_pid!"=="" (
    echo   后端: ✅ 运行中 (http://localhost:%BACKEND_PORT%)
) else (
    echo   后端: ❌ 未运行
)
call :is_port_in_use %FRONTEND_PORT% frontend_pid
if not "!frontend_pid!"=="" (
    echo   前端: ✅ 运行中 (http://localhost:%FRONTEND_PORT%)
) else (
    echo   前端: ❌ 未运行
)
echo ---------------------------
goto :eof

:: ==================== 通用端口检测函数（无管道） ====================
:is_port_in_use
set "port=%~1"
set "pid_var=%~2"
set "%pid_var%="
set "tmpfile=%TEMP%\netstat_%port%.txt"
netstat -ano > "%tmpfile%"
findstr /r ":%port% " "%tmpfile%" | findstr "LISTENING" > "%TEMP%\pid_%port%.txt"
for /f "tokens=5" %%p in ('type "%TEMP%\pid_%port%.txt"') do set "%pid_var%=%%p"
del "%tmpfile%" "%TEMP%\pid_%port%.txt" 2>nul
goto :eof

:: ==================== 用法 ====================
:usage
echo 用法: %~nx0 {start^|stop^|status}
echo.
echo   start  - 一键启动前后端
echo   stop   - 一键停止前后端
echo   status - 查看运行状态
exit /b 1