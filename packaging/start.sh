#!/bin/bash
# ============================================================
#  Word Review Pro 启动脚本（macOS / Linux）
#
#  双击「Start.command」或在终端执行  ./start.sh
#
#  端口可在同目录 application.properties 里修改（server.port=）
# ============================================================
set -e

APP_HOME="$(cd "$(dirname "$0")" && pwd)"
cd "$APP_HOME"

JAR_NAME="WordReviewPro.jar"
LOG_FILE="$APP_HOME/app.log"

# ---------- 1. 读取端口配置（默认 8080，可用 application.properties 修改） ----------
PORT="8080"
if [ -f "$APP_HOME/application.properties" ]; then
    v="$(sed -n 's/^[[:space:]]*server\.port[[:space:]]*=[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$APP_HOME/application.properties" | head -n 1)"
    if [ -n "$v" ]; then
        PORT="$v"
    fi
fi
# 校验：必须是纯数字且在合法范围，否则回退 8080
case "$PORT" in
    ''|*[!0-9]*) PORT="8080" ;;
esac
if [ "$PORT" -lt 1 ] || [ "$PORT" -gt 65535 ] 2>/dev/null; then
    PORT="8080"
fi
URL="http://localhost:${PORT}"

# ---------- 2. 找 Java：优先用包里自带的 JRE ----------
find_java() {
    if [ -x "$APP_HOME/jre/bin/java" ]; then
        echo "$APP_HOME/jre/bin/java"
        return 0
    fi
    if [ -x "$APP_HOME/jdk/bin/java" ]; then
        echo "$APP_HOME/jdk/bin/java"
        return 0
    fi
    if command -v java >/dev/null 2>&1; then
        echo "$(command -v java)"
        return 0
    fi
    return 1
}

if ! JAVA_BIN="$(find_java)"; then
    echo "❌ 没有找到 Java 运行环境。"
    echo ""
    echo "   本程序是 Java 应用，需要 Java 11 及以上版本。"
    echo "   推荐安装免费版：https://adoptium.net/temurin/releases/"
    exit 1
fi

if [ ! -f "$JAR_NAME" ]; then
    echo "❌ 没有找到 ${JAR_NAME}，请确认文件完整。"
    exit 1
fi

# ---------- 3. 端口占用检查 ----------
if command -v lsof >/dev/null 2>&1 && lsof -ti:"$PORT" >/dev/null 2>&1; then
    echo "⚠️  端口 ${PORT} 已被占用，可能程序已经在运行了。"
    echo "   如果确认不是 Word Review Pro，可在 application.properties 里换个端口。"
    echo "   正在继续尝试启动……"
fi

# ---------- 4. 启动 ----------
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  📖 Word Review Pro 启动中……"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  Java: ${JAVA_BIN}"
echo "  端口: ${PORT}   (想换端口改 application.properties)"
echo "  日志: ${LOG_FILE}"
echo ""

# 后台启动，就绪后自动打开浏览器
# 注意：--server.port 必须放在 -jar 之后，否则会被 JVM 当成虚拟机参数拒绝
"$JAVA_BIN" -Xms128m -Xmx512m -Dfile.encoding=UTF-8 \
    -jar "$JAR_NAME" --server.port="$PORT" > "$LOG_FILE" 2>&1 &
APP_PID=$!
echo "$APP_PID" > "$APP_HOME/app.pid"

# ---------- 5. 等待就绪 ----------
READY=0
for i in $(seq 1 60); do
    if ! kill -0 "$APP_PID" 2>/dev/null; then
        echo "❌ 启动失败，最后 30 行日志："
        echo "----------------------------------"
        tail -30 "$LOG_FILE"
        echo "----------------------------------"
        if grep -qi "already in use\|The file is locked" "$LOG_FILE" 2>/dev/null; then
            echo ""
            echo "💡 提示: 数据库被占用了，多半是程序已经开着（可能重复双击了）。"
            echo "   请先关掉已有的 Word Review Pro 窗口，或直接访问 ${URL}"
        fi
        exit 1
    fi
    if curl -s -o /dev/null --max-time 2 "$URL/api/review/config" 2>/dev/null; then
        READY=1
        break
    fi
    sleep 1
done

if [ "$READY" = "1" ]; then
    echo "✅ 启动成功！"
    echo "🌐 浏览器即将打开 ${URL}"
    echo "   关闭本窗口或按 Ctrl+C 即可停止程序"
    echo ""
    # 自动打开浏览器（失败不报错）
    case "$(uname -s)" in
        Darwin) open "$URL" >/dev/null 2>&1 || true ;;
        *)      (xdg-open "$URL" >/dev/null 2>&1 || true) ;;
    esac
    # 前台等待，保持窗口与进程同生命周期
    wait "$APP_PID"
else
    echo "❌ 启动超时（60 秒未就绪），最后 30 行日志："
    echo "----------------------------------"
    tail -30 "$LOG_FILE"
    echo "----------------------------------"
    kill "$APP_PID" 2>/dev/null || true
    exit 1
fi
