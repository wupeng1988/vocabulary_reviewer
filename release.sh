#!/usr/bin/env bash
# ============================================================
#  Word Review Pro 一键发布脚本
#
#  用法:
#    ./release.sh              # 打包含内置 JRE 的完整包（推荐）
#    ./release.sh --no-jre     # 只打包 jar + 启动脚本（体积小，要求对方已装 Java）
#    ./release.sh --no-zip     # 只生成目录，不压缩
#
#  产物: release/WordReviewPro-<版本>-<系统>-<架构>.zip
# ============================================================
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
BACKEND_DIR="$ROOT_DIR/review-backend"
FRONTEND_DIR="$ROOT_DIR/review-frontend"
PACKAGING_DIR="$ROOT_DIR/packaging"
RELEASE_DIR="$ROOT_DIR/release"

WITH_JRE=1
MAKE_ZIP=1
for arg in "$@"; do
    case "$arg" in
        --no-jre) WITH_JRE=0 ;;
        --no-zip) MAKE_ZIP=0 ;;
        -h|--help) grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "未知参数: $arg"; exit 1 ;;
    esac
done

# ---------- 0. 版本与平台 ----------
# 取本工程自己的 version（紧跟 artifactId），不能取到 parent 的 Spring Boot 版本
VERSION="$(awk '/<artifactId>word-review-backend<\/artifactId>/{found=1} found && /<version>/{gsub(/.*<version>|<\/version>.*/,""); print; exit}' "$BACKEND_DIR/pom.xml")"
VERSION="${VERSION//[[:space:]]/}"
[ -n "$VERSION" ] || VERSION="1.0.0"

case "$(uname -s)" in
    Darwin) OS="macos" ;;
    Linux)  OS="linux" ;;
    *)      OS="unknown" ;;
esac
case "$(uname -m)" in
    arm64|aarch64) ARCH="arm64" ;;
    x86_64|amd64)  ARCH="x86_64" ;;
    *)             ARCH="$(uname -m)" ;;
esac
PLATFORM="${OS}-${ARCH}"
PKG_NAME="WordReviewPro-${VERSION}-${PLATFORM}"
STAGE_DIR="$RELEASE_DIR/$PKG_NAME"

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  Word Review Pro 打包  v${VERSION}  (${PLATFORM})"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# ---------- 1. 环境检查 ----------
need() {
    command -v "$1" >/dev/null 2>&1 || { echo "❌ 缺少 $1，请先安装"; exit 1; }
}
need mvn
need java
if [ ! -d "$FRONTEND_DIR/node_modules" ]; then
    need npm
    echo "▶ 安装前端依赖..."
    (cd "$FRONTEND_DIR" && npm install --no-audit --no-fund)
fi

# ---------- 2. 构建前端 ----------
echo ""
echo "▶ [1/4] 构建前端..."
rm -rf "$FRONTEND_DIR/dist"
(cd "$FRONTEND_DIR" && npm run build)
[ -f "$FRONTEND_DIR/dist/index.html" ] || { echo "❌ 前端构建失败：dist/index.html 不存在"; exit 1; }
echo "   ✅ $(du -sh "$FRONTEND_DIR/dist" | cut -f1)  → $(find "$FRONTEND_DIR/dist" -type f | wc -l | tr -d ' ') 个文件"

# ---------- 3. 构建后端 jar（-Prelease 把前端产物并入 jar） ----------
echo ""
echo "▶ [2/4] 构建后端 jar..."
(cd "$BACKEND_DIR" && mvn -q -Prelease -DskipTests clean package)
JAR_SRC="$BACKEND_DIR/target/WordReviewPro.jar"
[ -f "$JAR_SRC" ] || { echo "❌ 构建失败：$JAR_SRC 不存在"; exit 1; }
echo "   ✅ $(du -sh "$JAR_SRC" | cut -f1)"

# 校验 jar 里确实带上了前端
# 先取整份列表再 grep：pipefail 下 grep -q 会因 SIGPIPE 误判失败
JAR_LISTING="$(unzip -l "$JAR_SRC")"
if ! printf '%s\n' "$JAR_LISTING" | grep -q "BOOT-INF/classes/static/index.html"; then
    echo "❌ jar 中缺少前端页面，请检查 -Prelease profile"
    exit 1
fi
if ! printf '%s\n' "$JAR_LISTING" | grep -q "BOOT-INF/classes/static/assets/index-.*\.js"; then
    echo "❌ jar 中缺少前端 JS 资源"
    exit 1
fi

# ---------- 4. 组装发布目录 ----------
echo ""
echo "▶ [3/4] 组装发布目录..."
rm -rf "$STAGE_DIR"
mkdir -p "$STAGE_DIR"
cp "$JAR_SRC" "$STAGE_DIR/WordReviewPro.jar"
cp "$PACKAGING_DIR/start.sh"  "$STAGE_DIR/start.sh"
cp "$PACKAGING_DIR/start.bat" "$STAGE_DIR/start.bat"
cp "$PACKAGING_DIR/README.txt" "$STAGE_DIR/"
cp "$PACKAGING_DIR/README.md" "$STAGE_DIR/"
cp "$PACKAGING_DIR/application.properties" "$STAGE_DIR/"
chmod +x "$STAGE_DIR/start.sh"

# macOS 双击入口
if [ "$OS" = "macos" ]; then
    cp "$PACKAGING_DIR/start.sh" "$STAGE_DIR/Start.command"
    chmod +x "$STAGE_DIR/Start.command"
fi

# ---------- 5. 内置 JRE（jlink 裁剪版） ----------
if [ "$WITH_JRE" = "1" ]; then
    echo "▶ [4/4] 生成内置 JRE..."
    # Spring Boot + H2 + POI 所需模块；jdk.localedata 保证中文/时区正确
    MODULES="java.base,java.desktop,java.instrument,java.logging,java.management,\
java.naming,java.net.http,java.prefs,java.rmi,java.security.jgss,java.sql,\
java.sql.rowset,java.transaction.xa,java.xml,java.xml.crypto,jdk.charsets,\
jdk.crypto.cryptoki,jdk.crypto.ec,jdk.localedata,jdk.management,jdk.unsupported,jdk.zipfs"
    JLINK_BIN="$(command -v jlink || true)"
    [ -n "$JLINK_BIN" ] || JLINK_BIN="$(dirname "$(readlink -f "$(command -v java)")")/jlink"
    if [ ! -x "$JLINK_BIN" ]; then
        echo "⚠️  找不到 jlink，改为生成不带 JRE 的包"
        WITH_JRE=0
    else
        rm -rf "$STAGE_DIR/jre"
        "$JLINK_BIN" \
            --add-modules "$MODULES" \
            --strip-debug --no-man-pages --no-header-files --compress=2 \
            --output "$STAGE_DIR/jre" > /dev/null
        # 自检：裁剪后的 JRE 能跑起来
        if ! "$STAGE_DIR/jre/bin/java" -version >/dev/null 2>&1; then
            echo "❌ 内置 JRE 自检失败"
            exit 1
        fi
        echo "   ✅ 内置 JRE: $(du -sh "$STAGE_DIR/jre" | cut -f1)"
    fi
else
    echo "▶ [4/4] 跳过内置 JRE（--no-jre）"
fi

# ---------- 6. 压缩 ----------
echo ""
if [ "$MAKE_ZIP" = "1" ]; then
    OUT_ZIP="$RELEASE_DIR/$PKG_NAME.zip"
    rm -f "$OUT_ZIP"
    (cd "$RELEASE_DIR" && zip -qry "$PKG_NAME.zip" "$PKG_NAME")

    # 校验：包内文件名必须全部 ASCII。
    # macOS 的 zip 不设置 UTF-8 文件名标志，中文名在 Windows 资源管理器里会变乱码。
    if command -v python3 >/dev/null 2>&1; then
        NON_ASCII="$(python3 -c "
import zipfile, sys
z = zipfile.ZipFile('$OUT_ZIP')
bad = [i.filename for i in z.infolist() if not i.filename.isascii()]
print(','.join(bad))
")"
        if [ -n "$NON_ASCII" ]; then
            echo "❌ 包内存在非 ASCII 文件名（Windows 解压会乱码）: $NON_ASCII"
            echo "   请把 packaging/ 下的文件改成英文名，中文写进文档内容里。"
            exit 1
        fi
        echo "   ✅ 文件名全为 ASCII，Windows 解压不会乱码"
    fi

    echo "🎉 打包完成: $OUT_ZIP  ($(du -sh "$OUT_ZIP" | cut -f1))"
else
    echo "🎉 打包完成: $STAGE_DIR  ($(du -sh "$STAGE_DIR" | cut -f1))"
fi

if [ "$WITH_JRE" = "0" ]; then
    echo "   ⚠️  未内置 JRE，对方电脑需自行安装 Java 11+"
fi

# ---------- 7. 清理开发环境里的发布产物 ----------
# target/classes/static 是 -Prelease 拷进去的前端页面，留着会让
# 之后不带 profile 的 mvn spring-boot:run 也吐出这份旧前端。
# 打包已完成，这里清掉，保证 dev.sh 行为和打包前一致。
echo ""
echo "▶ 清理开发环境构建产物（target/）..."
(cd "$BACKEND_DIR" && mvn -q clean)
echo "   ✅ 已清理，日常开发照旧使用 ./dev.sh"
echo "   目标平台: ${PLATFORM}（如需给其他系统用，请在该系统上重新执行 ./release.sh）"
