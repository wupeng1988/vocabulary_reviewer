#!/bin/bash

# Word Review Pro 一键启停脚本
# 用法: ./dev.sh start | stop | status

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
BACKEND_DIR="$PROJECT_DIR/review-backend"
FRONTEND_DIR="$PROJECT_DIR/review-frontend"
BACKEND_PORT=8080
FRONTEND_PORT=5173
PID_DIR="$PROJECT_DIR/.pids"

mkdir -p "$PID_DIR"

start_backend() {
    if lsof -ti:$BACKEND_PORT >/dev/null 2>&1; then
        echo "⚠️  后端已在运行 (端口 $BACKEND_PORT)"
        return 1
    fi
    echo "🚀 启动后端 (Spring Boot)..."
    cd "$BACKEND_DIR"
    nohup mvn spring-boot:run > "$PID_DIR/backend.log" 2>&1 &
    echo $! > "$PID_DIR/backend.pid"
    echo "   PID: $(cat "$PID_DIR/backend.pid")，日志: $PID_DIR/backend.log"

    # 等待后端就绪
    echo "   等待后端启动..."
    for i in $(seq 1 30); do
        if curl -s http://localhost:$BACKEND_PORT/api/review/config >/dev/null 2>&1; then
            echo "✅ 后端启动成功 (http://localhost:$BACKEND_PORT)"
            return 0
        fi
        sleep 2
    done
    echo "❌ 后端启动超时，请检查日志: $PID_DIR/backend.log"
    return 1
}

start_frontend() {
    if lsof -ti:$FRONTEND_PORT >/dev/null 2>&1; then
        echo "⚠️  前端已在运行 (端口 $FRONTEND_PORT)"
        return 1
    fi
    echo "🚀 启动前端 (Vue + Vite)..."
    cd "$FRONTEND_DIR"
    nohup npx vite --host > "$PID_DIR/frontend.log" 2>&1 &
    echo $! > "$PID_DIR/frontend.pid"
    echo "   PID: $(cat "$PID_DIR/frontend.pid")，日志: $PID_DIR/frontend.log"

    # 等待前端就绪
    echo "   等待前端启动..."
    for i in $(seq 1 15); do
        if curl -s http://localhost:$FRONTEND_PORT >/dev/null 2>&1; then
            echo "✅ 前端启动成功 (http://localhost:$FRONTEND_PORT)"
            return 0
        fi
        sleep 1
    done
    echo "❌ 前端启动超时，请检查日志: $PID_DIR/frontend.log"
    return 1
}

stop_backend() {
    local pid_file="$PID_DIR/backend.pid"
    if [ -f "$pid_file" ] && kill -0 "$(cat "$pid_file")" 2>/dev/null; then
        echo "🛑 停止后端 (PID: $(cat "$pid_file"))..."
        kill "$(cat "$pid_file")" 2>/dev/null
        # 同时杀掉占用端口的进程（确保彻底）
        lsof -ti:$BACKEND_PORT | xargs kill 2>/dev/null
        rm -f "$pid_file"
        echo "✅ 后端已停止"
    elif lsof -ti:$BACKEND_PORT >/dev/null 2>&1; then
        echo "🛑 停止后端 (端口 $BACKEND_PORT 上的进程)..."
        lsof -ti:$BACKEND_PORT | xargs kill 2>/dev/null
        echo "✅ 后端已停止"
    else
        echo "ℹ️  后端未在运行"
    fi
}

stop_frontend() {
    local pid_file="$PID_DIR/frontend.pid"
    if [ -f "$pid_file" ] && kill -0 "$(cat "$pid_file")" 2>/dev/null; then
        echo "🛑 停止前端 (PID: $(cat "$pid_file"))..."
        kill "$(cat "$pid_file")" 2>/dev/null
        lsof -ti:$FRONTEND_PORT | xargs kill 2>/dev/null
        rm -f "$pid_file"
        echo "✅ 前端已停止"
    elif lsof -ti:$FRONTEND_PORT >/dev/null 2>&1; then
        echo "🛑 停止前端 (端口 $FRONTEND_PORT 上的进程)..."
        lsof -ti:$FRONTEND_PORT | xargs kill 2>/dev/null
        echo "✅ 前端已停止"
    else
        echo "ℹ️  前端未在运行"
    fi
}

show_status() {
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "  Word Review Pro 运行状态"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    if lsof -ti:$BACKEND_PORT >/dev/null 2>&1; then
        echo "  后端: ✅ 运行中 (http://localhost:$BACKEND_PORT)"
    else
        echo "  后端: ❌ 未运行"
    fi
    if lsof -ti:$FRONTEND_PORT >/dev/null 2>&1; then
        echo "  前端: ✅ 运行中 (http://localhost:$FRONTEND_PORT)"
    else
        echo "  前端: ❌ 未运行"
    fi
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
}

case "$1" in
    start)
        echo "━━━ 启动 Word Review Pro ━━━"
        start_backend
        start_frontend
        echo ""
        show_status
        echo ""
        echo "🌐 访问地址: http://localhost:$FRONTEND_PORT"
        ;;
    stop)
        echo "━━━ 停止 Word Review Pro ━━━"
        stop_frontend
        stop_backend
        echo ""
        show_status
        ;;
    status)
        show_status
        ;;
    *)
        echo "用法: $0 {start|stop|status}"
        echo ""
        echo "  start  - 一键启动前后端"
        echo "  stop   - 一键停止前后端"
        echo "  status - 查看运行状态"
        exit 1
        ;;
esac
