#!/usr/bin/env bash
# ============================================================
#  Word Review Pro 停止脚本（macOS / Linux）
#  ./stop.sh 即可停止正在后台运行的程序。
#
#  start.sh 把程序放到后台再等你按键，直接关窗口程序不会停，
#  所以单独提供这个脚本。
# ============================================================
set -uo pipefail

APP_HOME="$(cd "$(dirname "$0")" && pwd)"
cd "$APP_HOME"

# ---------- 1. 读端口配置（和 start.sh 保持一致） ----------
PORT=8080
if [ -f "$APP_HOME/application.properties" ]; then
    P="$(sed -n 's/^server\.port[[:space:]]*=[[:space:]]*//p' "$APP_HOME/application.properties" | head -1 | tr -d '[:space:]')"
    case "$P" in
        ''|*[!0-9]*) ;;
        *) PORT="$P" ;;
    esac
fi

STOPPED=""

# ---------- 2. 优先按 start.sh 记下的 PID 停 ----------
if [ -f "$APP_HOME/app.pid" ]; then
    PID="$(cat "$APP_HOME/app.pid" 2>/dev/null | tr -d '[:space:]')"
    if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then
        if kill "$PID" 2>/dev/null; then
            echo "✅ 已停止 Word Review Pro（PID $PID）"
            STOPPED=1
        fi
    fi
    rm -f "$APP_HOME/app.pid"
fi

# ---------- 3. 没有 PID 文件就按端口找 ----------
if [ -z "$STOPPED" ]; then
    PID="$(lsof -ti tcp:"$PORT" -sTCP:LISTEN 2>/dev/null | head -1)"
    if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then
        if kill "$PID" 2>/dev/null; then
            echo "✅ 已停止 Word Review Pro（端口 $PORT 被释放，PID $PID）"
            STOPPED=1
        fi
    fi
fi

echo ""
if [ -n "$STOPPED" ]; then
    echo "   词库和复习记录存在用户目录的 worddb.mv.db，停止程序不会丢数据。"
    echo "   下次要用，重新执行 ./start.sh 即可。"
else
    echo "   没有找到正在运行的 Word Review Pro，可能本来就没开。"
fi
echo ""
