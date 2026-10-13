package com.wordreview.controller;

import com.wordreview.entity.AiConfig;
import com.wordreview.service.AiConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * AI 服务配置接口。
 *
 * 仅服务于「批量AI解析添加」，配置存放在独立的 ai_config 表，
 * 与词库数据隔离：JSON 备份导出不含它，导入也不会覆盖它。
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiConfigService aiConfigService;

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig() {
        AiConfig config = aiConfigService.get();
        Map<String, Object> result = new HashMap<>();
        result.put("provider", config.getProvider());
        result.put("apiKey", config.getApiKey());
        result.put("baseUrl", config.getBaseUrl());
        result.put("model", config.getModel());
        result.put("configured", aiConfigService.isConfigured());
        return ResponseEntity.ok(result);
    }

    @PutMapping("/config")
    public ResponseEntity<Map<String, Object>> saveConfig(@RequestBody AiConfig config) {
        Map<String, Object> result = new HashMap<>();
        try {
            if (config == null) {
                result.put("success", false);
                result.put("message", "请求内容为空");
                return ResponseEntity.badRequest().body(result);
            }
            // 服务商留空时保持默认值，保证清空后前端下拉框仍有选项
            config.setProvider(notBlank(config.getProvider()) ? config.getProvider().trim() : "deepseek");

            // 三项要么都填、要么都清空：混着填一律拦下，避免存出半套配置
            boolean hasApiKey = notBlank(config.getApiKey());
            boolean hasBaseUrl = notBlank(config.getBaseUrl());
            boolean hasModel = notBlank(config.getModel());
            if (hasApiKey != hasBaseUrl || hasBaseUrl != hasModel) {
                result.put("success", false);
                result.put("message", "API Key、接口地址、模型三项要么都填，要么都清空");
                return ResponseEntity.badRequest().body(result);
            }
            if (!hasApiKey) {
                config.setApiKey(null);
                config.setBaseUrl(null);
                config.setModel(null);
            }

            AiConfig saved = aiConfigService.save(config);
            boolean configured = aiConfigService.isConfigured();
            result.put("success", true);
            result.put("message", configured ? "保存成功" : "AI 配置已清空，「批量AI解析添加」将不可用，重新填写即可恢复");
            result.put("configured", configured);
            result.put("provider", saved.getProvider());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "保存失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
