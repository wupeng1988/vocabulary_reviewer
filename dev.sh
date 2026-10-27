#!/bin/bash

# Word Review Pro 一键启停脚本
# 用法: ./dev.sh {start|stop|restart|status} [后端端口] [前端端口]
#
# 端口参数：
#   第一个参数 = 后端端口（Spring Boot），第二个参数 = 前端端口（Vite）。
#   两者都可省略；省略时沿用上次启动用的端口（首次默认 8080 / 5173）。
#   例：
#     ./dev.sh start             # 后端 8080，前端 5173
#     ./dev.sh start 9090        # 后端 9090，前端沿用上次
#     ./dev.sh start 9090 5190   # 后端 9090，前端 5190
#
# 前端的 /api、/audio、/h2-console 代理会自动指向本次指定的后端端口。

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
BACKEND_DIR="$PROJECT_DIR/review-backend"
FRONTEND_DIR="$PROJECT_DIR/review-frontend"
DEFAULT_BACKEND_PORT=8080
DEFAULT_FRONTEND_PORT=5173
PID_DIR="$PROJECT_DIR/.pids"
PORTS_FILE="$PID_DIR/ports"

BACKEND_PORT=""
FRONTEND_PORT=""

mkdir -p "$PID_DIR"

# ==================== 端口解析 ====================

is_valid_port() {
    case "$1" in
        ''|*[!0-9]*) return 1 ;;
    esac
    [ "$1" -ge 1 ] && [ "$1" -le 65535 ]
}

# 读取上次启动用的端口
load_saved_ports() {
    [ -f "$PORTS_FILE" ] || return 0
    local b f
    b=$(grep -o '^BACKEND_PORT=[0-9]*' "$PORTS_FILE" 2>/dev/null | head -1 | cut -d= -f2)
    f=$(grep -o '^FRONTEND_PORT=[0-9]*' "$PORTS_FILE" 2>/dev/null | head -1 | cut -d= -f2)
    [ -n "$b" ] && BACKEND_PORT="$b"
    [ -n "$f" ] && FRONTEND_PORT="$f"
}

# 记录本次使用的端口，供后续 stop / status 使用
save_ports() {
    {
        echo "BACKEND_PORT=$BACKEND_PORT"
        echo "FRONTEND_PORT=$FRONTEND_PORT"
    } > "$PORTS_FILE"
}

# 参数 > 上次记录 > 默认值
resolve_ports() {
    local arg_backend="$1" arg_frontend="$2"

    # 先载入上次用的端口，再让显式参数逐个覆盖
    # （只给后端端口时，前端要沿用上次的，不能掉回默认值）
    load_saved_ports

    if [ -n "$arg_backend" ]; then
        if ! is_valid_port "$arg_backend"; then
            echo "❌ 后端端口无效: '$arg_backend'（应为 1-65535 的数字）"
            exit 1
        fi
        BACKEND_PORT="$arg_backend"
    fi

    if [ -n "$arg_frontend" ]; then
        if ! is_valid_port "$arg_frontend"; then
            echo "❌ 前端端口无效: '$arg_frontend'（应为 1-65535 的数字）"
            exit 1
        fi
        FRONTEND_PORT="$arg_frontend"
    fi

    [ -z "$BACKEND_PORT" ] && BACKEND_PORT=$DEFAULT_BACKEND_PORT
    [ -z "$FRONTEND_PORT" ] && FRONTEND_PORT=$DEFAULT_FRONTEND_PORT

    # 前后端撞同一个端口：前端自动往后顺延
    if [ "$BACKEND_PORT" = "$FRONTEND_PORT" ]; then
        local next=$((FRONTEND_PORT + 1))
        while [ "$next" -le 65535 ] && lsof -ti:$next >/dev/null 2>&1; do
            next=$((next + 1))
        done
        if [ "$next" -le 65535 ]; then
            echo "⚠️  前后端端口相同 ($BACKEND_PORT)，前端自动改用 $next"
            FRONTEND_PORT=$next
        else
            echo "❌ 找不到可用的前端端口（1-65535 已占满）"
            exit 1
        fi
    fi
}

# ==================== 启动 ====================

start_backend() {
    if lsof -ti:$BACKEND_PORT >/dev/null 2>&1; then
        echo "⚠️  后端端口 $BACKEND_PORT 已被占用（可能已在运行）"
        return 1
    fi
    echo "🚀 启动后端 (Spring Boot, 端口 $BACKEND_PORT)..."
    cd "$BACKEND_DIR"
    nohup mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=$BACKEND_PORT > "$PID_DIR/backend.log" 2>&1 &
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
        echo "⚠️  前端端口 $FRONTEND_PORT 已被占用（可能已在运行）"
        return 1
    fi
    echo "🚀 启动前端 (Vue + Vite, 端口 $FRONTEND_PORT)..."
    cd "$FRONTEND_DIR"
    # 端口通过环境变量传给 vite.config.js：前端端口 + 代理指向的后端端口
    BACKEND_PORT="$BACKEND_PORT" FRONTEND_PORT="$FRONTEND_PORT" \
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

# ==================== 停止 ====================

# 等待端口释放（Spring Boot 是 maven fork 出来的子 JVM，
# kill 后还要走完优雅停机才真正让出端口，这里等它一下）
wait_port_free() {
    local port="$1" i
    for i in $(seq 1 15); do
        lsof -ti:$port >/dev/null 2>&1 || return 0
        sleep 1
    done
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
        wait_port_free $BACKEND_PORT
        echo "✅ 后端已停止"
    elif lsof -ti:$BACKEND_PORT >/dev/null 2>&1; then
        echo "🛑 停止后端 (端口 $BACKEND_PORT 上的进程)..."
        lsof -ti:$BACKEND_PORT | xargs kill 2>/dev/null
        wait_port_free $BACKEND_PORT
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
        wait_port_free $FRONTEND_PORT
        echo "✅ 前端已停止"
    elif lsof -ti:$FRONTEND_PORT >/dev/null 2>&1; then
        echo "🛑 停止前端 (端口 $FRONTEND_PORT 上的进程)..."
        lsof -ti:$FRONTEND_PORT | xargs kill 2>/dev/null
        wait_port_free $FRONTEND_PORT
        echo "✅ 前端已停止"
    else
        echo "ℹ️  前端未在运行"
    fi
}

# ==================== 状态 ====================

show_status() {
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "  Word Review Pro 运行状态"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    if lsof -ti:$BACKEND_PORT >/dev/null 2>&1; then
        echo "  后端: ✅ 运行中 (http://localhost:$BACKEND_PORT)"
    else
        echo "  后端: ❌ 未运行 (端口 $BACKEND_PORT)"
    fi
    if lsof -ti:$FRONTEND_PORT >/dev/null 2>&1; then
        echo "  前端: ✅ 运行中 (http://localhost:$FRONTEND_PORT)"
    else
        echo "  前端: ❌ 未运行 (端口 $FRONTEND_PORT)"
    fi
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
}

# ==================== 入口 ====================

CMD="${1:-}"
if [ $# -gt 0 ]; then shift; fi
ARG_BACKEND="${1:-}"
ARG_FRONTEND="${2:-}"

case "$CMD" in
    start|stop|restart|status)
        resolve_ports "$ARG_BACKEND" "$ARG_FRONTEND"
        ;;
esac

case "$CMD" in
    start)
        echo "━━━ 启动 Word Review Pro ━━━"
        save_ports
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
    restart)
        echo "━━━ 重启 Word Review Pro ━━━"
        stop_frontend
        stop_backend
        echo ""
        sleep 2
        save_ports
        start_backend
        start_frontend
        echo ""
        show_status
        echo ""
        echo "🌐 访问地址: http://localhost:$FRONTEND_PORT"
        ;;
    status)
        show_status
        ;;
    *)
        echo "用法: $0 {start|stop|restart|status} [后端端口] [前端端口]"
        echo ""
        echo "  start   - 一键启动前后端"
        echo "  stop    - 一键停止前后端"
        echo "  restart - 一键重启前后端（先停后启）"
        echo "  status  - 查看运行状态"
        echo ""
        echo "端口参数（均可省略，省略时沿用上次启动的端口，默认 8080 / 5173）："
        echo "  第一个参数 = 后端端口（Spring Boot）"
        echo "  第二个参数 = 前端端口（Vite）"
        echo ""
        echo "例:"
        echo "  $0 start             # 后端 8080，前端 5173"
        echo "  $0 start 9090        # 后端 9090，前端沿用上次"
        echo "  $0 start 9090 5190   # 后端 9090，前端 5190"
        exit 1
        ;;
esac
