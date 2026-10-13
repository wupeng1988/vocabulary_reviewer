package com.wordreview.service;

import com.wordreview.entity.ProxyConfig;
import com.wordreview.repository.ProxyConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 网络代理配置（单例读写 + 按配置构造 HttpClient）。
 *
 * 当前唯一消费方是 {@link EdgeTtsService}（微软 Edge TTS 的 WSS 请求）。
 * 与词库数据完全隔离：不参与 JSON 备份的导出与导入。
 *
 * 代理能力边界（java.net.http.HttpClient 的限制）：
 * - 只支持 HTTP 代理（CONNECT 隧道），SOCKS 会被 JDK 静默忽略，因此不提供该选项
 * - 只支持免认证代理：CONNECT 请求走 Exchange 而非 MultiExchange，认证过滤器压根不参与，
 *   带账号密码的代理一律收到 407（已在测试中验证），所以配置里不设账号密码字段
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProxyConfigService {

    /** 探测地址：与 TTS 同域，能通就说明合成请求也出得去 */
    private static final String TEST_URL = "https://speech.platform.bing.com/";

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(10);

    /** 「测试直连」用：明确不走任何代理（连系统代理属性也忽略） */
    private static final ProxySelector DIRECT_SELECTOR = new ProxySelector() {
        @Override
        public List<Proxy> select(URI uri) {
            return Collections.singletonList(Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            // 直连失败由调用方的异常处理统一汇报
        }
    };

    private final ProxyConfigRepository repository;

    /**
     * 唯一一个出站 HttpClient。
     *
     * 只在两个时机重建：首次取用、保存配置时。批量生成几百个单词全程复用同一个实例
     * （HttpClient 自带连接池，重建反而会丢掉连接和线程池），合成路径上是一次 volatile 读，
     * 既不查库也不做字符串比较。
     */
    private final Object clientLock = new Object();
    private volatile HttpClient httpClient;

    /** 读取配置；未初始化过则返回带默认值的新对象（不落库） */
    public ProxyConfig get() {
        return repository.findById(ProxyConfig.SINGLETON_ID).orElseGet(ProxyConfig::new);
    }

    /** 保存配置（固定写入单例行），保存后对后续请求立即生效 */
    @Transactional
    public ProxyConfig save(ProxyConfig config) {
        config.setId(ProxyConfig.SINGLETON_ID);
        config.setEnabled(Boolean.TRUE.equals(config.getEnabled()));
        config.setHost(trimToNull(config.getHost(), null));
        if (config.getPort() != null && config.getPort() <= 0) {
            config.setPort(null);
        }
        ProxyConfig saved = repository.save(config);
        // 保存即重建并热替换：下一次合成请求直接用新客户端，不用等缓存失效。
        // 与 getHttpClient() 的懒初始化共用同一把锁，避免「首次取用」与「首次保存」并发时旧配置反超
        HttpClient client = buildClient(saved, usesProxy(saved));
        synchronized (clientLock) {
            httpClient = client;
        }
        log.info("代理配置已更新: enabled={}, address={}, HttpClient 已重建",
                saved.getEnabled(), describe(saved));
        return saved;
    }

    /** 是否已填好可用的代理地址（与开关无关） */
    public boolean isConfigured() {
        return hasAddress(get());
    }

    /** 取共享的出站 HttpClient：进程内只此一个，批量生成时逐词复用 */
    public HttpClient getHttpClient() {
        HttpClient client = httpClient;
        if (client == null) {
            synchronized (clientLock) {
                client = httpClient;
                if (client == null) {
                    ProxyConfig config = get();
                    client = buildClient(config, usesProxy(config));
                    httpClient = client;
                    log.info("HttpClient 已初始化: {}",
                            usesProxy(config) ? describe(config) : "直连");
                }
            }
        }
        return client;
    }

    /**
     * 按配置构造 HttpClient。
     *
     * @param config   代理配置
     * @param useProxy true 走配置里的代理；false 强制直连（忽略系统代理属性，用于「测试直连」）
     */
    public HttpClient buildClient(ProxyConfig config, boolean useProxy) {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15));
        if (useProxy && hasAddress(config)) {
            builder.proxy(new SingleProxySelector(config));
        } else if (!useProxy) {
            builder.proxy(DIRECT_SELECTOR);
        } else {
            // 未启用时沿用 JDK 默认行为（读 http.proxyHost 等系统属性）
            builder.proxy(ProxySelector.getDefault());
        }
        return builder.build();
    }

    /**
     * 连通性测试：按给定配置发一次请求。
     * 收到任何 HTTP 状态码都算「链路通了」，407 除外（代理认证失败）。
     *
     * @param config   待测配置（通常是页面上尚未保存的表单值）
     * @param useProxy true 测代理，false 测直连
     */
    public Map<String, Object> test(ProxyConfig config, boolean useProxy) {
        boolean throughProxy = useProxy && hasAddress(config);
        HttpClient client = buildClient(config, throughProxy);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TEST_URL))
                .timeout(TEST_TIMEOUT)
                .header("User-Agent", "Mozilla/5.0 WordReviewPro")
                .GET()
                .build();

        long start = System.currentTimeMillis();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            long cost = System.currentTimeMillis() - start;
            int status = response.statusCode();

            Map<String, Object> result = new HashMap<>();
            result.put("latencyMs", cost);
            result.put("statusCode", status);
            result.put("viaProxy", throughProxy);
            if (status == 407) {
                result.put("success", false);
                result.put("message", "代理要求认证（HTTP 407）：本程序走 JDK 的 HTTP 客户端，"
                        + "CONNECT 隧道不支持账号密码，请改用免认证的本地代理");
            } else {
                result.put("success", true);
                result.put("message", String.format("连接成功（HTTP %d，耗时 %dms）%s",
                        status, cost, throughProxy ? "，请求已通过代理" : "，直连可达"));
            }
            return result;
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("latencyMs", cost);
            result.put("viaProxy", throughProxy);
            result.put("message", String.format("连接失败（%dms）：%s%s", cost, rootMessage(e),
                    throughProxy ? "，请检查代理地址和端口，或用「测试直连」对比" : "，说明本机无法直连该地址"));
            log.info("连通性测试失败: viaProxy={}, {}", throughProxy, rootMessage(e));
            return result;
        }
    }

    /** 该配置是否应当走代理：开关打开且地址完整 */
    private boolean usesProxy(ProxyConfig config) {
        return Boolean.TRUE.equals(config.getEnabled()) && hasAddress(config);
    }

    private boolean hasAddress(ProxyConfig config) {
        return config != null && notBlank(config.getHost())
                && config.getPort() != null && config.getPort() > 0 && config.getPort() <= 65535;
    }

    private static String describe(ProxyConfig c) {
        return notBlank(c.getHost())
                ? c.getHost().trim() + ":" + (c.getPort() == null ? "?" : c.getPort())
                : "(未填地址)";
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String msg = t.getMessage();
        if (msg == null || msg.trim().isEmpty()) {
            msg = t.getClass().getSimpleName();
        }
        return msg;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static String trimToNull(String s, String fallback) {
        if (s == null) return fallback;
        String t = s.trim();
        return t.isEmpty() ? fallback : t;
    }

    /** 只返回配置里的那一个代理，不理会系统代理属性 */
    private static class SingleProxySelector extends ProxySelector {
        private final Proxy proxy;

        SingleProxySelector(ProxyConfig config) {
            this.proxy = new Proxy(Proxy.Type.HTTP,
                    new InetSocketAddress(config.getHost().trim(), config.getPort()));
        }

        @Override
        public List<Proxy> select(URI uri) {
            return Collections.singletonList(proxy);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            log.warn("代理连接失败: {} -> {}", uri, sa);
        }
    }
}
