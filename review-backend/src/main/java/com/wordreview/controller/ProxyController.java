package com.wordreview.controller;

import com.wordreview.entity.ProxyConfig;
import com.wordreview.service.ProxyConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 网络代理配置接口。
 *
 * 只影响后端出站的微软 Edge TTS 语音合成请求（WSS）。
 * 配置存放在独立的 proxy_config 表，与词库数据隔离：
 * JSON 备份导出不含它，导入也不会覆盖它。
 */
@RestController
@RequestMapping("/api/proxy")
@RequiredArgsConstructor
public class ProxyController {

    private final ProxyConfigService proxyConfigService;

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig() {
        ProxyConfig config = proxyConfigService.get();
        Map<String, Object> result = new HashMap<>();
        result.put("enabled", Boolean.TRUE.equals(config.getEnabled()));
        result.put("host", config.getHost());
        result.put("port", config.getPort());
        result.put("configured", proxyConfigService.isConfigured());
        return ResponseEntity.ok(result);
    }

    @PutMapping("/config")
    public ResponseEntity<Map<String, Object>> saveConfig(@RequestBody ProxyConfig config) {
        Map<String, Object> result = new HashMap<>();
        try {
            if (config == null) {
                result.put("success", false);
                result.put("message", "请求内容为空");
                return ResponseEntity.badRequest().body(result);
            }
            boolean enabled = Boolean.TRUE.equals(config.getEnabled());
            boolean hasHost = config.getHost() != null && !config.getHost().trim().isEmpty();
            if (enabled && !hasHost) {
                result.put("success", false);
                result.put("message", "启用代理时必须填写代理服务器地址");
                return ResponseEntity.badRequest().body(result);
            }
            if (enabled && (config.getPort() == null || config.getPort() <= 0 || config.getPort() > 65535)) {
                result.put("success", false);
                result.put("message", "启用代理时必须填写 1-65535 之间的端口号");
                return ResponseEntity.badRequest().body(result);
            }
            ProxyConfig saved = proxyConfigService.save(config);
            result.put("success", true);
            result.put("message", saved.getEnabled()
                    ? "保存成功，代理已对 TTS 语音合成生效（无需重启）"
                    : "保存成功，已改为直连");
            result.put("enabled", saved.getEnabled());
            result.put("configured", proxyConfigService.isConfigured());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "保存失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    /**
     * 连通性测试。body 里可带页面上尚未保存的表单值；
     * host 为空时回落到已保存的配置，避免「填了没保存就测不了」。
     */
    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> test(@RequestBody(required = false) ProxyConfig request) {
        Map<String, Object> result = new HashMap<>();
        try {
            ProxyConfig config = request == null ? new ProxyConfig() : request;
            if (config.getHost() == null || config.getHost().trim().isEmpty()) {
                ProxyConfig saved = proxyConfigService.get();
                config.setHost(saved.getHost());
                config.setPort(saved.getPort());
            }
            if (config.getHost() == null || config.getHost().trim().isEmpty()
                    || config.getPort() == null || config.getPort() <= 0 || config.getPort() > 65535) {
                result.put("success", false);
                result.put("message", "请先填写代理服务器地址和端口，或改点「测试直连」");
                return ResponseEntity.badRequest().body(result);
            }
            return ResponseEntity.ok(proxyConfigService.test(config, true));
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "测试失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    /** 直连测试：绕开代理看本机能否直连 TTS 地址，用于判断「到底需不需要代理」 */
    @PostMapping("/test-direct")
    public ResponseEntity<Map<String, Object>> testDirect() {
        try {
            return ResponseEntity.ok(proxyConfigService.test(new ProxyConfig(), false));
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "测试失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }
}
