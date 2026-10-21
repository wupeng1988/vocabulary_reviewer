#!/usr/bin/env bash
# ============================================================
#  Word Review Pro 一键发布脚本
#
#  用法:
#    ./release.sh              # 打包含内置 JRE 的完整包（推荐，目标平台=本机）
#    ./release.sh --no-jre     # 只打包 jar + 启动脚本（体积小，要求对方已装 Java）
#    ./release.sh --no-zip     # 只生成目录，不压缩
#    ./release.sh --no-audio   # 不内置发音缓存（包小 ~50MB，但对方首次运行要联网生成）
#    ./release.sh --no-seed    # 不内置种子词库（对方得自己导 Excel/JSON 才有词库）
#    ./release.sh --platform windows-x86_64
#                              # 交叉打 Windows 包（可在 macOS/Linux 上执行）。
#                              # 自动下载一份 Windows 版 JDK 到 release/.jdk-cache/，
#                              # 用本机 jlink 指向它的 jmods 生成 Windows 运行时；
#                              # jar 与前端是平台无关的，只有 jre/ 换成 Windows 的。
#
#  产物: release/WordReviewPro-<版本>-<系统>-<架构>.zip
#
#  支持的目标平台:
#    macos-arm64 / macos-x86_64 / linux-x86_64  （本机打包）
#    windows-x86_64                             （可从 macOS/Linux 交叉打）
# ============================================================
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
BACKEND_DIR="$ROOT_DIR/review-backend"
FRONTEND_DIR="$ROOT_DIR/review-frontend"
PACKAGING_DIR="$ROOT_DIR/packaging"
RELEASE_DIR="$ROOT_DIR/release"

usage() {
    cat <<'USAGE'
Word Review Pro 一键发布脚本

用法:
  ./release.sh                    打包含内置 JRE 的完整包（目标平台 = 本机）
  ./release.sh --no-jre           只打包 jar + 启动脚本（体积小，对方需自装 Java 11+）
  ./release.sh --no-zip           只生成目录，不压缩
  ./release.sh --no-audio         不内置发音缓存（包小约 50MB）
  ./release.sh --no-seed          不内置种子词库（对方得自己导 Excel/JSON 才有词库）
  ./release.sh --platform windows-x86_64
                                  交叉打 Windows 包，可在 macOS / Linux 上执行

--platform 支持的取值:
  macos-arm64 / macos-x86_64      本机是对应系统时才打得出来
  linux-x86_64 / linux-aarch64    同上
  windows-x86_64                  可从 macOS / Linux 交叉打（其他平台同理不行）

交叉打 Windows 包的原理:
  jar 和前端是平台无关的，只有 jre/ 需要换。脚本会下载一份 Windows 版 JDK 到
  release/.jdk-cache/（仅首次，约 180MB，之后复用），再用本机 jlink 的
  --module-path 指向它的 jmods。jlink 只是把 jmods 打包、不校验宿主平台，
  所以 macOS 上的 jlink + Windows 的 jmods 能直接产出 java.exe。

产物: release/WordReviewPro-<版本>-<系统>-<架构>.zip
USAGE
}

WITH_JRE=1
WITH_AUDIO=1
WITH_SEED=1
MAKE_ZIP=1
PLATFORM_OPT="auto"     # auto = 跟随本机；也可显式指定 windows-x86_64 等
while [ $# -gt 0 ]; do
    case "$1" in
        --no-jre) WITH_JRE=0; shift ;;
        --no-audio) WITH_AUDIO=0; shift ;;
        --no-seed) WITH_SEED=0; shift ;;
        --no-zip) MAKE_ZIP=0; shift ;;
        --platform)
            # shift 2 在只剩一个参数时会被 set -e 干掉，先判断再移
            if [ $# -lt 2 ]; then echo "❌ --platform 缺少取值"; exit 1; fi
            PLATFORM_OPT="$2"; shift 2 ;;
        --platform=*) PLATFORM_OPT="${1#*=}"; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "未知参数: $1"; echo "试试 ./release.sh --help"; exit 1 ;;
    esac
done
[ -n "$PLATFORM_OPT" ] || { echo "❌ --platform 缺少取值"; exit 1; }

# ---------- 0. 版本与平台 ----------
# 取本工程自己的 version（紧跟 artifactId），不能取到 parent 的 Spring Boot 版本
VERSION="$(awk '/<artifactId>word-review-backend<\/artifactId>/{found=1} found && /<version>/{gsub(/.*<version>|<\/version>.*/,""); print; exit}' "$BACKEND_DIR/pom.xml")"
VERSION="${VERSION//[[:space:]]/}"
[ -n "$VERSION" ] || VERSION="1.0.0"

# 本机平台（Git Bash 下 uname 返回 MINGW64_NT-10.0 之类，归到 windows）
case "$(uname -s)" in
    Darwin)                HOST_OS="macos" ;;
    Linux)                 HOST_OS="linux" ;;
    MINGW*|MSYS*|CYGWIN*)  HOST_OS="windows" ;;
    *)                     HOST_OS="unknown" ;;
esac
case "$(uname -m)" in
    arm64|aarch64) HOST_ARCH="arm64" ;;
    x86_64|amd64)  HOST_ARCH="x86_64" ;;
    *)             HOST_ARCH="$(uname -m)" ;;
esac
HOST_PLATFORM="${HOST_OS}-${HOST_ARCH}"

if [ "$PLATFORM_OPT" = "auto" ]; then
    PLATFORM="$HOST_PLATFORM"
else
    PLATFORM="$PLATFORM_OPT"
fi

case "$PLATFORM" in
    macos-arm64|macos-x86_64|linux-x86_64|linux-aarch64|windows-x86_64) ;;
    *) echo "❌ 不支持的目标平台: $PLATFORM"
       echo "   目前支持: macos-arm64 / macos-x86_64 / linux-x86_64 / linux-aarch64 / windows-x86_64"
       exit 1 ;;
esac

# 目标平台不是本机 = 交叉打包：jar 一样能构建，但 jre 必须换成目标平台的运行时
IS_CROSS=0
if [ "$PLATFORM" != "$HOST_PLATFORM" ]; then
    IS_CROSS=1
fi

PKG_NAME="WordReviewPro-${VERSION}-${PLATFORM}"
STAGE_DIR="$RELEASE_DIR/$PKG_NAME"

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  Word Review Pro 打包  v${VERSION}  (${PLATFORM})"
if [ "$IS_CROSS" = "1" ]; then
    echo "  ⚠️  交叉打包：本机是 ${HOST_PLATFORM}，产物给 ${PLATFORM} 用"
fi
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
cp "$PACKAGING_DIR/stop.sh"   "$STAGE_DIR/stop.sh"
chmod +x "$STAGE_DIR/start.sh" "$STAGE_DIR/stop.sh"

# .bat 必须以 GBK + CRLF 交付。cmd.exe 按系统代码页（中文 Windows = 936/GBK）
# 读批处理，交付 UTF-8 会被按 GBK 错切行：REM 整行丢失、破折号被当命令、中文变乱码；
# LF 换行还会让多行 if (...) 块解析错乱。仓库里保持 UTF-8 + LF 方便编辑，这里转码。
for BAT_FILE in start.bat stop.bat; do
    python3 - "$PACKAGING_DIR/$BAT_FILE" "$STAGE_DIR/$BAT_FILE" <<'PYEOF'
import sys
src, dst = sys.argv[1], sys.argv[2]
text = open(src, encoding='utf-8').read()
try:
    text.encode('gbk')
except UnicodeEncodeError as e:
    print('❌ %s 里有 GBK 编不了的字符: %r（请改用 GBK 能表示的汉字或全角符号）'
          % (src, e.object))
    sys.exit(1)
open(dst, 'w', encoding='gbk', newline='\r\n').write(text)
print('   ✅ %s 已转为 GBK + CRLF' % dst.rsplit('/', 1)[-1])
PYEOF
done
cp "$PACKAGING_DIR/README.txt" "$STAGE_DIR/"
cp "$PACKAGING_DIR/README.md" "$STAGE_DIR/"
cp "$PACKAGING_DIR/application.properties" "$STAGE_DIR/"
chmod +x "$STAGE_DIR/start.sh"

# 发音缓存一并打包。不带的话对方首次运行要联网把几千个词的发音全生成一遍，
# 等很久；音频按单词名存 mp3，是纯数据，不含任何用户配置或词库内容。
if [ "$WITH_AUDIO" = "1" ]; then
    if [ -d "$BACKEND_DIR/audio" ]; then
        rm -rf "$STAGE_DIR/audio"
        cp -R "$BACKEND_DIR/audio" "$STAGE_DIR/audio"
        # macOS 的 Finder 垃圾文件，别混给 Windows 用户
        find "$STAGE_DIR/audio" -name '.DS_Store' -delete
        # 少数文件名里用了 U+2011 这类 Unicode 连字符（非 ASCII），Windows 解压会变乱码，
        # 归一化成普通连字符 -；对不上的那几个词首运行会自动重新生成，无影响。
        python3 - "$STAGE_DIR/audio" <<'PYAUDIO'
import os, sys, unicodedata
d = sys.argv[1]
fixed = []
for name in os.listdir(d):
    if name.isascii():
        continue
    new = ''.join('-' if unicodedata.category(c) == 'Pd' else c for c in name)
    if new != name:
        os.rename(os.path.join(d, name), os.path.join(d, new))
        fixed.append(f'{name} -> {new}')
for line in fixed:
    print(f'   ↪ 已归一化文件名: {line}')
PYAUDIO
        AUDIO_COUNT="$(find "$STAGE_DIR/audio" -name '*.mp3' | wc -l | tr -d ' ')"
        echo "   ✅ 发音缓存: ${AUDIO_COUNT} 个 mp3 / $(du -sh "$STAGE_DIR/audio" | cut -f1)"
    else
        echo "⚠️  没找到 $BACKEND_DIR/audio，本包不含发音缓存"
    fi
else
    echo "   （跳过发音缓存，--no-audio）"
fi

# 词库种子一并打包。数据库本身不在包里（运行期在用户家目录的 ~/worddb.mv.db），
# 所以对方首次启动词库是空的，得自己导入一次。
# 这里放一份清掉复习记录的种子文件，让对方在「数据管理」里导入即可拿到完整词库。
#
# 数据源优先取本机正在运行的实例（最新的词库），取不到就退回仓库里的备份文件。
if [ "$WITH_SEED" = "1" ]; then
    SEED_SRC=""
    # 从运行中的实例导出（含复习记录，make-seed.py 会清掉）
    for PORT_TRY in 8080 8081 8082; do
        if curl -s --max-time 5 -o "$RELEASE_DIR/.seed-raw.json" \
                "http://localhost:${PORT_TRY}/api/words/export" 2>/dev/null; then
            # 校验拿到的是不是真的 JSON 备份（不能只看开头几个字节）
            if python3 -c "
import json, sys
try:
    d = json.load(open('$RELEASE_DIR/.seed-raw.json', encoding='utf-8'))
    sys.exit(0 if isinstance(d, dict) and 'words' in d else 1)
except Exception:
    sys.exit(1)
" 2>/dev/null; then
                SEED_SRC="$RELEASE_DIR/.seed-raw.json"
                echo "   ↪ 词库数据源: 运行中的实例 (localhost:${PORT_TRY})"
                break
            fi
        fi
    done
    # 回退到仓库里的备份文件
    if [ -z "$SEED_SRC" ]; then
        for F in "$ROOT_DIR"/data/*.json; do
            if [ -f "$F" ]; then
                SEED_SRC="$F"
                echo "   ↪ 词库数据源: 仓库备份 ($(basename "$F"))"
                break
            fi
        done
    fi

    if [ -z "$SEED_SRC" ]; then
        echo "⚠️  找不到词库数据源（运行中的实例和 data/*.json 都没有），本包不含种子词库"
    else
        python3 "$ROOT_DIR/tools/make-seed.py" "$SEED_SRC" "$STAGE_DIR/seed-words.json"
        SEED_SIZE="$(du -h "$STAGE_DIR/seed-words.json" | cut -f1)"
        echo "   ✅ 种子文件: seed-words.json (${SEED_SIZE}，含词组结构，不含复习记录)"
    fi
    rm -f "$RELEASE_DIR/.seed-raw.json"
else
    echo "   （跳过种子词库，--no-seed）"
fi

# macOS 双击入口（Windows 包里放 .command 没有意义，别混进去）
case "$PLATFORM" in
    macos-*)
        cp "$PACKAGING_DIR/start.sh" "$STAGE_DIR/Start.command"
        chmod +x "$STAGE_DIR/Start.command"
        ;;
esac

# ---------- 5. 内置 JRE（jlink 裁剪版） ----------
# 交叉打包时 jlink 仍用本机的（保证命令行一致），但 --module-path 指向目标平台的
# jmods。jlink 只是把 jmods 里的类和平台原生库打包，不校验宿主平台，
# 所以 macOS 上的 jlink + Windows 的 jmods 能直接产出 java.exe。
prepare_target_jmods() {
    # 本机打包：jlink 默认就读自己 JDK 的 jmods，不用指定
    JMODS_PATH=""
    [ "$IS_CROSS" = "1" ] || return 0

    if [ "$PLATFORM" != "windows-x86_64" ]; then
        echo "❌ 交叉打包目前只支持 windows-x86_64"
        exit 1
    fi

    local cache_dir="$RELEASE_DIR/.jdk-cache/windows-x64"
    local jdk_home
    jdk_home="$(ls -d "$cache_dir"/jdk-*/ 2>/dev/null | head -1 || true)"
    if [ -n "$jdk_home" ] && [ -d "$jdk_home/jmods" ]; then
        JMODS_PATH="$jdk_home/jmods"
        echo "   ✅ 复用已缓存的 Windows JDK: $(basename "$jdk_home")"
        return 0
    fi

    # 取本机 JDK 主版本号，下同版本的 Windows JDK（jmods 格式要和本机 jlink 配套）
    local major
    # 只取第一个引号里的数字。BSD sed 对 (\.[0-9]+)? 这种写法不认，别用
    major="$(java -version 2>&1 | head -1 | sed -nE 's/.*"([0-9]+).*/\1/p')"
    if [ "$major" = "1" ]; then
        major="$(java -version 2>&1 | sed -nE 's/.*version "1\.([0-9]+).*/\1/p' | head -1)"
    fi
    [ -n "$major" ] || { echo "❌ 读不出本机 JDK 版本号"; exit 1; }
    if [ "$major" -lt 11 ]; then
        echo "❌ 本机 JDK 是 ${major}，项目需要 Java 11+"
        exit 1
    fi

    local url="https://api.adoptium.net/v3/binary/latest/${major}/ga/windows/x64/jdk/hotspot/normal/eclipse"
    local zipfile="$RELEASE_DIR/.jdk-cache/windows-x64-jdk.zip"
    mkdir -p "$cache_dir"
    echo "   ⬇️  下载 Windows JDK ${major}（Temurin x64，仅首次，约 180MB）..."
    echo "      $url"
    # -sS：进度条只占一行且不进日志，出错时仍打印原因
    if ! curl -fsSL --retry 3 --connect-timeout 20 -o "$zipfile" "$url"; then
        rm -f "$zipfile"
        echo "❌ 下载 Windows JDK 失败（网络不通？可先 export HTTPS_PROXY=... 再重试）"
        echo "   或改用 ./release.sh --no-jre 出一个不内置 JRE 的包"
        exit 1
    fi
    echo "      ✅ 下载完成 $(du -h "$zipfile" | cut -f1)，解压中..."
    rm -rf "$cache_dir"
    mkdir -p "$cache_dir"
    if ! unzip -q -o "$zipfile" -d "$cache_dir"; then
        echo "❌ 解压 Windows JDK 失败：$zipfile"
        exit 1
    fi
    rm -f "$zipfile"
    jdk_home="$(ls -d "$cache_dir"/jdk-*/ 2>/dev/null | head -1 || true)"
    if [ -z "$jdk_home" ] || [ ! -d "$jdk_home/jmods" ]; then
        echo "❌ 下载下来的 Windows JDK 里没找到 jmods/"
        exit 1
    fi
    JMODS_PATH="$jdk_home/jmods"
    echo "   ✅ Windows JDK 就绪: $(basename "$jdk_home")"
}

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
        prepare_target_jmods

        # --compress 的取值 JDK 21 起换掉了：21+ 用 zip-6 这类写法，21 以下用数字
        JLINK_MAJOR="$(jlink --version 2>/dev/null | head -1 | cut -d. -f1)"
        if [ "${JLINK_MAJOR:-17}" -ge 21 ]; then
            COMPRESS_OPT="--compress=zip-6"
        else
            COMPRESS_OPT="--compress=2"
        fi

        rm -rf "$STAGE_DIR/jre"
        "$JLINK_BIN" \
            ${JMODS_PATH:+"--module-path" "$JMODS_PATH"} \
            --add-modules "$MODULES" \
            --strip-debug --no-man-pages --no-header-files "$COMPRESS_OPT" \
            --output "$STAGE_DIR/jre" > /dev/null

        # 自检：本机包直接跑一下；交叉包是目标平台的二进制，本机跑不了，
        # 改成校验关键文件存在，且 release 文件里声明的 OS 对得上
        if [ "$IS_CROSS" = "1" ]; then
            if [ ! -f "$STAGE_DIR/jre/bin/java.exe" ] \
               || [ ! -f "$STAGE_DIR/jre/bin/javaw.exe" ] \
               || [ ! -f "$STAGE_DIR/jre/bin/server/jvm.dll" ]; then
                echo "❌ 内置 JRE 自检失败：缺少 Windows 可执行文件"
                exit 1
            fi
            # jlink 生成的 release 只有 JAVA_VERSION 和 MODULES，没有 OS_NAME，
            # 不能靠它判断平台；改成校验 java.exe 的 PE 文件头（MZ）。
            if [ "$(head -c 2 "$STAGE_DIR/jre/bin/java.exe" 2>/dev/null)" != "MZ" ]; then
                echo "❌ 内置 JRE 自检失败：bin/java.exe 不是 Windows PE 可执行文件"
                exit 1
            fi
            if [ ! -f "$STAGE_DIR/jre/lib/modules" ]; then
                echo "❌ 内置 JRE 自检失败：缺少 lib/modules（模块映像）"
                exit 1
            fi
            echo "   ✅ Windows 运行时自检通过（本机执行不了，已校验 PE 文件头与模块映像）"
        else
            if ! "$STAGE_DIR/jre/bin/java" -version >/dev/null 2>&1; then
                echo "❌ 内置 JRE 自检失败"
                exit 1
            fi
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
echo "   目标平台: ${PLATFORM}"
if [ "$IS_CROSS" = "1" ]; then
    echo "   ⚠️  交叉产物：分发前请在 ${PLATFORM} 真机上双击 start.bat 验证一次"
fi
