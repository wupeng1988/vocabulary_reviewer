# Word Review Pro 一键启停脚本 (PowerShell)
# 用法: .\dev.ps1 start | stop | status

$ErrorActionPreference = "SilentlyContinue"

$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$BackendDir = Join-Path $ProjectDir "review-backend"
$FrontendDir = Join-Path $ProjectDir "review-frontend"
$BackendPort = 8080
$FrontendPort = 5173
$PidDir = Join-Path $ProjectDir ".pids"

if (-not (Test-Path $PidDir)) { New-Item -ItemType Directory -Path $PidDir | Out-Null }

function Test-PortListening($port) {
    $conn = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    return $null -ne $conn
}

function Get-PortPid($port) {
    $conn = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    if ($conn) { return $conn[0].OwningProcess }
    return $null
}

function Start-Backend {
    if (Test-PortListening $BackendPort) {
        Write-Host "⚠️  后端已在运行 (端口 $BackendPort)" -ForegroundColor Yellow
        return
    }
    Write-Host "🚀 启动后端 (Spring Boot)..." -ForegroundColor Cyan
    Push-Location $BackendDir
    $proc = Start-Process -FilePath "mvn" -ArgumentList "spring-boot:run" `
        -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $PidDir "backend.log") `
        -RedirectStandardError (Join-Path $PidDir "backend-err.log")
    $proc.Id | Out-File (Join-Path $PidDir "backend.pid") -Encoding utf8
    Write-Host "   PID: $($proc.Id)，日志: $PidDir\backend.log"
    Pop-Location

    Write-Host "   等待后端启动..." -NoNewline
    $ready = $false
    for ($i = 1; $i -le 30; $i++) {
        Start-Sleep -Seconds 2
        Write-Host "." -NoNewline
        try {
            $r = Invoke-WebRequest -Uri "http://localhost:$BackendPort/api/review/config" -UseBasicParsing -TimeoutSec 2
            if ($r.StatusCode -eq 200) { $ready = $true; break }
        } catch {}
    }
    Write-Host ""
    if ($ready) {
        Write-Host "✅ 后端启动成功 (http://localhost:$BackendPort)" -ForegroundColor Green
    } else {
        Write-Host "❌ 后端启动超时，请检查日志: $PidDir\backend.log" -ForegroundColor Red
    }
}

function Start-Frontend {
    if (Test-PortListening $FrontendPort) {
        Write-Host "⚠️  前端已在运行 (端口 $FrontendPort)" -ForegroundColor Yellow
        return
    }
    Write-Host "🚀 启动前端 (Vue + Vite)..." -ForegroundColor Cyan
    Push-Location $FrontendDir
    $proc = Start-Process -FilePath "npx" -ArgumentList "vite","--host" `
        -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $PidDir "frontend.log") `
        -RedirectStandardError (Join-Path $PidDir "frontend-err.log")
    $proc.Id | Out-File (Join-Path $PidDir "frontend.pid") -Encoding utf8
    Write-Host "   PID: $($proc.Id)，日志: $PidDir\frontend.log"
    Pop-Location

    Write-Host "   等待前端启动..." -NoNewline
    $ready = $false
    for ($i = 1; $i -le 15; $i++) {
        Start-Sleep -Seconds 1
        Write-Host "." -NoNewline
        try {
            $r = Invoke-WebRequest -Uri "http://localhost:$FrontendPort" -UseBasicParsing -TimeoutSec 2
            if ($r.StatusCode -eq 200) { $ready = $true; break }
        } catch {}
    }
    Write-Host ""
    if ($ready) {
        Write-Host "✅ 前端启动成功 (http://localhost:$FrontendPort)" -ForegroundColor Green
    } else {
        Write-Host "❌ 前端启动超时，请检查日志: $PidDir\frontend.log" -ForegroundColor Red
    }
}

function Stop-Backend {
    $pid = Get-PortPid $BackendPort
    if ($pid) {
        Write-Host "🛑 停止后端 (PID: $pid)..." -ForegroundColor Yellow
        Stop-Process -Id $pid -Force -ErrorAction SilentlyContinue
        # 同时杀掉子进程树
        Get-CimInstance Win32_Process -Filter "ParentProcessId=$pid" | ForEach-Object {
            Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
        }
        Write-Host "✅ 后端已停止" -ForegroundColor Green
    } else {
        Write-Host "ℹ️  后端未在运行" -ForegroundColor Gray
    }
}

function Stop-Frontend {
    $pid = Get-PortPid $FrontendPort
    if ($pid) {
        Write-Host "🛑 停止前端 (PID: $pid)..." -ForegroundColor Yellow
        Stop-Process -Id $pid -Force -ErrorAction SilentlyContinue
        Get-CimInstance Win32_Process -Filter "ParentProcessId=$pid" | ForEach-Object {
            Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
        }
        Write-Host "✅ 前端已停止" -ForegroundColor Green
    } else {
        Write-Host "ℹ️  前端未在运行" -ForegroundColor Gray
    }
}

function Show-Status {
    Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor White
    Write-Host "  Word Review Pro 运行状态" -ForegroundColor White
    Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor White
    if (Test-PortListening $BackendPort) {
        Write-Host "  后端: ✅ 运行中 (http://localhost:$BackendPort)" -ForegroundColor Green
    } else {
        Write-Host "  后端: ❌ 未运行" -ForegroundColor Red
    }
    if (Test-PortListening $FrontendPort) {
        Write-Host "  前端: ✅ 运行中 (http://localhost:$FrontendPort)" -ForegroundColor Green
    } else {
        Write-Host "  前端: ❌ 未运行" -ForegroundColor Red
    }
    Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor White
}

# 主入口
switch ($args[0]) {
    "start" {
        Write-Host "━━━ 启动 Word Review Pro ━━━" -ForegroundColor Cyan
        Start-Backend
        Start-Frontend
        Write-Host ""
        Show-Status
        Write-Host ""
        Write-Host "🌐 访问地址: http://localhost:$FrontendPort" -ForegroundColor Cyan
    }
    "stop" {
        Write-Host "━━━ 停止 Word Review Pro ━━━" -ForegroundColor Cyan
        Stop-Frontend
        Stop-Backend
        Write-Host ""
        Show-Status
    }
    "status" {
        Show-Status
    }
    default {
        Write-Host "用法: .\dev.ps1 {start|stop|status}" -ForegroundColor Yellow
        Write-Host ""
        Write-Host "  start  - 一键启动前后端"
        Write-Host "  stop   - 一键停止前后端"
        Write-Host "  status - 查看运行状态"
        exit 1
    }
}
