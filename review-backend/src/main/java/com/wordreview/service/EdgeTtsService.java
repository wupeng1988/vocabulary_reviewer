package com.wordreview.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Edge TTS 服务：Java 原生实现微软 Edge 在线 TTS 的 WebSocket 协议。
 * 协议参考自 Python edge-tts 项目 (communicate.py / drm.py / constants.py)。
 *
 * 核心流程：
 * 1. 生成 Sec-MS-GEC DRM token
 * 2. 建立 WSS 连接（携带 Chrome/Edge UA 等伪装头）
 * 3. 发送 speech.config 配置消息 + ssml 合成请求
 * 4. 接收二进制消息（含 MP3 音频），直到收到 turn.end
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EdgeTtsService {

    // ===== 协议常量（来自 constants.py） =====
    private static final String TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";
    private static final String BASE_URL = "speech.platform.bing.com/consumer/speech/synthesize/readaloud";
    private static final String WSS_URL = "wss://" + BASE_URL + "/edge/v1?TrustedClientToken=" + TRUSTED_CLIENT_TOKEN;
    private static final String CHROMIUM_FULL_VERSION = "143.0.3650.75";
    private static final String CHROMIUM_MAJOR_VERSION = CHROMIUM_FULL_VERSION.split("\\.")[0];
    /** 微软 2026-10 起只认 v2 token，版本前缀也从 1- 换成了 2-，两者必须配套 */
    private static final String SEC_MS_GEC_VERSION = "2-" + CHROMIUM_FULL_VERSION;
    private static final String DEFAULT_VOICE = "en-US-JennyNeural";

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            + " (KHTML, like Gecko) Chrome/" + CHROMIUM_MAJOR_VERSION + ".0.0.0 Safari/537.36"
            + " Edg/" + CHROMIUM_MAJOR_VERSION + ".0.0.0";

    /** 与微软服务器的时钟偏差（毫秒）；握手 403 时用服务器 Date 头校准 */
    private volatile long clockSkewMillis = 0;

    /** 每个进程最多校时一次：校过还不行就说明不是时钟问题，别再白跑一趟 */
    private volatile boolean clockSynced = false;

    /** 网络代理配置：进程内共用一个 HttpClient，配置改了下次请求就走新通道，无需重启 */
    private final ProxyConfigService proxyConfigService;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    /**
     * 生成 Sec-MS-GEC v2 DRM token（算法在 {@link EdgeTtsDrm}）。
     * 微软 2026-10 起只认 v2，格式为 &lt;CS64&gt;-&lt;SHA256&gt;，版本前缀也要用 2-。
     */
    private String generateSecMsGec() {
        return EdgeTtsDrm.generateSecMsGec(serverNowMillis() / 1000.0);
    }

    /** 校准过时钟偏差后的时间：Sec-MS-GEC 是时间令牌，本机时钟偏一点就会 403 */
    private long serverNowMillis() {
        return System.currentTimeMillis() + clockSkewMillis;
    }

    /**
     * 用微软返回的 Date 头校准本机时钟偏差（对应 drm.py handle_client_response_error）。
     * 只在握手 403 时才会调用，成功后同一个进程内一直复用。
     */
    private void calibrateClockSkew() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://" + BASE_URL + "/"))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();
            HttpResponse<Void> response = proxyConfigService.getHttpClient()
                    .send(request, HttpResponse.BodyHandlers.discarding());
            String date = response.headers().firstValue("Date").orElse(null);
            if (date == null) {
                log.warn("Edge TTS 校时失败：响应里没有 Date 头");
                return;
            }
            Instant serverTime = DateTimeFormatter.RFC_1123_DATE_TIME
                    .withLocale(Locale.US)
                    .parse(date.trim(), Instant::from);
            long skew = serverTime.toEpochMilli() - System.currentTimeMillis();
            clockSkewMillis = skew;
            log.warn("Edge TTS 服务器与本机时钟相差 {} ms，已校准（服务器时间: {}）", skew, date.trim());
        } catch (Exception e) {
            log.warn("Edge TTS 校时失败: {}", e.toString());
        }
    }

    /** 异常链里找 403：握手被拒时 JDK 抛 WebSocketHandshakeException，状态码在更深层的 cause 里 */
    private static boolean looksLikeForbidden(Throwable e) {
        Throwable t = e;
        for (int i = 0; i < 10 && t != null; i++) {
            String msg = t.getMessage();
            if (msg != null && msg.contains("403")) {
                return true;
            }
            t = t.getCause() == t ? null : t.getCause();
        }
        return false;
    }

    /** 取异常链最深处的 message，日志里更有诊断价值 */
    private static String rootCauseMessage(Throwable e) {
        Throwable t = e;
        for (int i = 0; i < 10 && t.getCause() != null && t.getCause() != t; i++) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    /**
     * 生成随机 MUID（来自 drm.py generate_muid）
     */
    private String generateMuid() {
        return UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }

    /**
     * 转换语音名称格式（来自 data_classes.py TTSConfig）
     * en-US-EmmaMultilingualNeural → Microsoft Server Speech Text to Speech Voice (en-US, EmmaMultilingualNeural)
     */
    private String convertVoiceName(String voice) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("^([a-z]{2,})-([A-Z]{2,})-(.+Neural)$");
        java.util.regex.Matcher m = p.matcher(voice);
        if (m.matches()) {
            String lang = m.group(1);
            String region = m.group(2);
            String name = m.group(3);
            int dashIdx = name.indexOf('-');
            if (dashIdx != -1) {
                region = region + "-" + name.substring(0, dashIdx);
                name = name.substring(dashIdx + 1);
            }
            return "Microsoft Server Speech Text to Speech Voice (" + lang + "-" + region + ", " + name + ")";
        }
        return voice;
    }

    /**
     * 构造 SSML（来自 communicate.py mkssml）
     */
    private String buildSsml(String voiceName, String text) {
        // 转义 XML 特殊字符
        String escaped = text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>"
                + "<voice name='" + voiceName + "'>"
                + "<prosody pitch='+0Hz' rate='+0%' volume='+0%'>"
                + escaped
                + "</prosody>"
                + "</voice>"
                + "</speak>";
    }

    private String jsDate() {
        // 模拟 Python date_to_string 的 JavaScript 风格日期
        return java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                .withZone(java.time.ZoneOffset.UTC)
                .format(Instant.now());
    }

    /**
     * 同步生成音频文件。
     *
     * @param text       要合成语音的文本（一般为单词）
     * @param outputPath 输出 MP3 文件路径
     */
    public void generateAudio(String text, Path outputPath) throws Exception {
        Files.createDirectories(outputPath.getParent());
        byte[] audio = synthesize(text, DEFAULT_VOICE);
        Files.write(outputPath, audio);
        log.info("Edge TTS 生成音频成功: {} -> {} ({} bytes)", text, outputPath, audio.length);
    }

    /**
     * 异步生成音频文件。
     */
    public CompletableFuture<Void> generateAudioAsync(String text, Path outputPath) {
        return CompletableFuture.runAsync(() -> {
            try {
                generateAudio(text, outputPath);
            } catch (Exception e) {
                log.error("Edge TTS 异步生成音频失败: {}", text, e);
                throw new RuntimeException(e);
            }
        }, executor);
    }

    /**
     * 核心合成方法：建立 WebSocket 连接，发送请求，收集音频数据。
     *
     * 握手被 403 拒绝时，多半是本机时钟和微软服务器对不齐（Sec-MS-GEC 是 5 分钟粒度的时间令牌），
     * 此时用服务器的 Date 头校准偏差后重试一次，对应 edge-tts 里 on 403 → handle_client_response_error 的逻辑。
     */
    private byte[] synthesize(String text, String voice) throws Exception {
        try {
            return doSynthesize(text, voice);
        } catch (Exception e) {
            if (!looksLikeForbidden(e) || clockSynced) {
                throw e;
            }
            clockSynced = true;
            log.warn("Edge TTS 握手被拒(403)，尝试校准服务器时钟后重试一次: {}", rootCauseMessage(e));
            calibrateClockSkew();
            return doSynthesize(text, voice);
        }
    }

    private byte[] doSynthesize(String text, String voice) throws Exception {
        String secMsGec = generateSecMsGec();
        String connectionId = UUID.randomUUID().toString().replace("-", "");
        String fullUrl = WSS_URL + "&ConnectionId=" + connectionId
                + "&Sec-MS-GEC=" + secMsGec
                + "&Sec-MS-GEC-Version=" + SEC_MS_GEC_VERSION;

        String voiceName = convertVoiceName(voice);
        String ssml = buildSsml(voiceName, text);

        // 构造发送的消息（参考 communicate.py）
        String configMessage = "X-Timestamp:" + jsDate() + "\r\n"
                + "Content-Type:application/json; charset=utf-8\r\n"
                + "Path:speech.config\r\n"
                + "\r\n"
                + "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":"
                + "{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"true\"},"
                + "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}";

        String ssmlMessage = "X-RequestId:" + connectionId + "\r\n"
                + "Content-Type:application/ssml+xml\r\n"
                + "X-Timestamp:" + jsDate() + "Z\r\n"
                + "Path:ssml\r\n"
                + "\r\n"
                + ssml;

        // WebSocket 监听器：收集音频数据，turn.end 时释放 latch
        SynthesisListener listener = new SynthesisListener();
        WebSocket ws = proxyConfigService.getHttpClient().newWebSocketBuilder()
                .header("User-Agent", USER_AGENT)
                .header("Pragma", "no-cache")
                .header("Cache-Control", "no-cache")
                .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                .header("Cookie", "muid=" + generateMuid() + ";")
                .buildAsync(URI.create(fullUrl), listener)
                .get(20, TimeUnit.SECONDS);

        try {
            // 发送配置消息
            ws.sendText(configMessage, true).get(10, TimeUnit.SECONDS);
            // 发送 SSML 请求
            ws.sendText(ssmlMessage, true).get(10, TimeUnit.SECONDS);

            // 等待合成完成（turn.end）或超时
            if (!listener.latch.await(30, TimeUnit.SECONDS)) {
                throw new TimeoutException("Edge TTS 合成超时");
            }

            if (listener.error != null) {
                throw new RuntimeException("Edge TTS 合成失败: " + listener.error);
            }
            if (listener.audioBytes.size() == 0) {
                throw new RuntimeException("Edge TTS 未返回音频数据");
            }

            return listener.audioBytes.toByteArray();
        } finally {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        }
    }

    /**
     * WebSocket 监听器：解析服务端返回的文本和二进制消息，收集 MP3 音频数据。
     */
    private static class SynthesisListener implements WebSocket.Listener {
        private final ByteArrayOutputStream audioBytes = new ByteArrayOutputStream();
        private final CountDownLatch latch = new CountDownLatch(1);
        private String error;
        // 用于拼接分片二进制消息（WebSocket 可能将一个大二进制帧拆成多次 onBinary 回调）
        private final ByteArrayOutputStream pendingBinary = new ByteArrayOutputStream();
        private boolean inBinary = false;

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(Long.MAX_VALUE);
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            String text = data.toString();
            // 文本消息：检查 Path
            if (text.contains("Path:turn.end")) {
                latch.countDown();
            } else if (text.contains("Path:error")) {
                error = text;
                latch.countDown();
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            // 收集二进制分片
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            pendingBinary.write(bytes, 0, bytes.length);

            if (last) {
                // 完整二进制消息到达，解析
                byte[] full = pendingBinary.toByteArray();
                pendingBinary.reset();
                parseBinaryMessage(full);
            }
            webSocket.request(1);
            return null;
        }

        /**
         * 解析二进制消息：前 2 字节 = header length（含自身 2 字节），其后为 headers，剩余为音频数据。
         * Python 中：header_length = int.from_bytes(data[:2], "big")
         *           headers 在 data[:header_length] 中（含 2 字节长度字段）
         *           data 在 data[header_length + 2:] 中
         */
        private void parseBinaryMessage(byte[] data) {
            if (data.length < 2) {
                return;
            }
            int headerLength = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
            if (headerLength > data.length) {
                return;
            }
            // 解析 headers：data[2 : headerLength]，跳过前 2 字节的长度字段
            int headersStart = 2;
            int headersLen = headerLength - 2;
            if (headersLen <= 0) {
                return;
            }
            String headersStr = new String(data, headersStart, headersLen, StandardCharsets.UTF_8);
            Map<String, String> headers = new HashMap<>();
            for (String line : headersStr.split("\r\n")) {
                int idx = line.indexOf(':');
                if (idx > 0) {
                    String key = line.substring(0, idx).trim();
                    String value = line.substring(idx + 1).trim();
                    headers.put(key, value);
                }
            }
            String path = headers.get("Path");
            if ("audio".equals(path)) {
                // 数据从 headerLength + 2 开始（跳过 header 部分和 \r\n 分隔符）
                int dataStart = headerLength + 2;
                if (dataStart < data.length) {
                    audioBytes.write(data, dataStart, data.length - dataStart);
                }
            }
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            this.error = error.getMessage();
            latch.countDown();
        }
    }
}
